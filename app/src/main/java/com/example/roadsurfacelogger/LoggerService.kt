package com.example.roadsurfacelogger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoggerService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "com.example.roadsurfacelogger.START"
        const val ACTION_STOP = "com.example.roadsurfacelogger.STOP"
        const val ACTION_MARK = "com.example.roadsurfacelogger.MARK"

        const val PREFS = "logger_state"
        const val KEY_RECORDING = "recording"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_LAST_SESSION_PATH = "last_session_path"
        const val KEY_IMU_COUNT = "imu_count"
        const val KEY_GPS_COUNT = "gps_count"
        const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_GPS_ACCURACY = "gps_accuracy"
        const val KEY_GPS_PROVIDER = "gps_provider"

        private const val CHANNEL_ID = "road_logger_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SENSOR_PERIOD_US = 10_000 // target ~100 Hz
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private var imuWriter: BufferedWriter? = null
    private var gpsWriter: BufferedWriter? = null
    private var markerWriter: BufferedWriter? = null

    @Volatile
    private var recording = false

    private var sessionId = ""
    private var sessionDir: File? = null
    private var sessionStartWallMs = 0L
    private var sessionStartElapsedNs = 0L
    private var imuCount = 0L
    private var gpsCount = 0L
    private var imuSinceFlush = 0
    private var gpsSinceFlush = 0
    private var lastMetricsPublishMs = 0L
    private var latestLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        workerThread = HandlerThread("RoadLoggerWorker")
        workerThread.start()
        worker = Handler(workerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!recording) {
                    startAsForeground()
                    worker.post { beginRecording() }
                }
            }

            ACTION_MARK -> if (recording) {
                worker.post { writeMarker() }
            }

            ACTION_STOP -> if (recording) {
                worker.post { finishRecording() }
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("Preparing data logger…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginRecording() {
        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        sessionId = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(sessionStartWallMs))

        val base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val root = File(base, "RoadSurfaceLogger")
        sessionDir = File(root, "session_$sessionId")
        require(sessionDir!!.mkdirs() || sessionDir!!.isDirectory) {
            "Could not create session directory"
        }

        imuWriter = BufferedWriter(FileWriter(File(sessionDir, "imu.csv"))).apply {
            write("session_id,sensor_timestamp_ns,wall_time_ms,sensor_type,x,y,z,accuracy\n")
            flush()
        }
        gpsWriter = BufferedWriter(FileWriter(File(sessionDir, "gps.csv"))).apply {
            write("session_id,elapsed_realtime_ns,wall_time_ms,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg\n")
            flush()
        }
        markerWriter = BufferedWriter(FileWriter(File(sessionDir, "markers.csv"))).apply {
            write("session_id,elapsed_realtime_ns,wall_time_ms,label,latitude,longitude\n")
            flush()
        }

        writeMetadata()
        imuCount = 0
        gpsCount = 0
        recording = true

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_RECORDING, true)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_LAST_SESSION_PATH, sessionDir!!.absolutePath)
            .putLong(KEY_IMU_COUNT, 0)
            .putLong(KEY_GPS_COUNT, 0)
            .putLong(KEY_START_ELAPSED_MS, SystemClock.elapsedRealtime())
            .apply()

        registerSensors()
        registerLocation()
        publishMetrics(force = true)
        updateNotification("Recording • $sessionId")
    }

    private fun registerSensors() {
        val sensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY
        )

        sensorTypes.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US, 0, worker)
            }
        }
    }

    private fun registerLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                200L,
                0f,
                this,
                workerThread.looper
            )
        } catch (_: IllegalArgumentException) {
            // GPS provider is not available on this device. IMU logging continues.
        } catch (_: SecurityException) {
            // Permission changed while recording. IMU logging continues.
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.values.size < 3) return

        val writer = imuWriter ?: return
        val wallTimeMs = sessionStartWallMs + ((event.timestamp - sessionStartElapsedNs) / 1_000_000L)
        val sensorName = sensorTypeName(event.sensor.type)
        writer.write(
            String.format(
                Locale.US,
                "%s,%d,%d,%s,%.9f,%.9f,%.9f,%d\n",
                sessionId,
                event.timestamp,
                wallTimeMs,
                sensorName,
                event.values[0],
                event.values[1],
                event.values[2],
                event.accuracy
            )
        )
        imuCount++
        imuSinceFlush++
        if (imuSinceFlush >= 200) {
            writer.flush()
            imuSinceFlush = 0
        }
        publishMetrics()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!recording) return
        latestLocation = location
        val writer = gpsWriter ?: return

        val verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else Float.NaN
        val speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.NaN
        val bearingAccuracy = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else Float.NaN
        val altitude = if (location.hasAltitude()) location.altitude else Double.NaN
        val speed = if (location.hasSpeed()) location.speed else Float.NaN
        val bearing = if (location.hasBearing()) location.bearing else Float.NaN

        writer.write(
            String.format(
                Locale.US,
                "%s,%d,%d,%s,%.9f,%.9f,%.3f,%.4f,%.3f,%.3f,%.3f,%.4f,%.3f\n",
                sessionId,
                location.elapsedRealtimeNanos,
                location.time,
                location.provider ?: "unknown",
                location.latitude,
                location.longitude,
                altitude,
                speed,
                bearing,
                location.accuracy,
                verticalAccuracy,
                speedAccuracy,
                bearingAccuracy
            )
        )
        gpsCount++
        gpsSinceFlush++
        if (gpsSinceFlush >= 10) {
            writer.flush()
            gpsSinceFlush = 0
        }
        publishMetrics(force = true)
    }

    private fun writeMarker() {
        if (!recording) return
        val loc = latestLocation
        markerWriter?.apply {
            write(
                String.format(
                    Locale.US,
                    "%s,%d,%d,manual_marker,%s,%s\n",
                    sessionId,
                    SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis(),
                    loc?.latitude?.let { String.format(Locale.US, "%.9f", it) } ?: "",
                    loc?.longitude?.let { String.format(Locale.US, "%.9f", it) } ?: ""
                )
            )
            flush()
        }
    }

    private fun publishMetrics(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMetricsPublishMs < 500) return
        lastMetricsPublishMs = now

        val edit = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_IMU_COUNT, imuCount)
            .putLong(KEY_GPS_COUNT, gpsCount)

        latestLocation?.let { loc ->
            edit.putString(KEY_LAT, String.format(Locale.US, "%.7f", loc.latitude))
                .putString(KEY_LON, String.format(Locale.US, "%.7f", loc.longitude))
                .putFloat(KEY_GPS_ACCURACY, loc.accuracy)
                .putString(KEY_GPS_PROVIDER, loc.provider ?: "unknown")
        }
        edit.apply()
    }

    private fun finishRecording() {
        if (!recording) return
        recording = false

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }

        imuWriter?.runCatching { flush(); close() }
        gpsWriter?.runCatching { flush(); close() }
        markerWriter?.runCatching { flush(); close() }
        imuWriter = null
        gpsWriter = null
        markerWriter = null

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECORDING, false)
            .putLong(KEY_IMU_COUNT, imuCount)
            .putLong(KEY_GPS_COUNT, gpsCount)
            .apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun writeMetadata() {
        val file = File(sessionDir, "metadata.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("session_id=$sessionId")
            out.appendLine("start_wall_time_ms=$sessionStartWallMs")
            out.appendLine("start_elapsed_realtime_ns=$sessionStartElapsedNs")
            out.appendLine("requested_sensor_period_us=$SENSOR_PERIOD_US")
            out.appendLine("manufacturer=${Build.MANUFACTURER}")
            out.appendLine("brand=${Build.BRAND}")
            out.appendLine("model=${Build.MODEL}")
            out.appendLine("device=${Build.DEVICE}")
            out.appendLine("android_release=${Build.VERSION.RELEASE}")
            out.appendLine("sdk_int=${Build.VERSION.SDK_INT}")
            out.appendLine("imu_coordinate_system=Android device coordinate system")
            out.appendLine("accelerometer_unit=m/s^2")
            out.appendLine("gyroscope_unit=rad/s")
            out.appendLine("gps_speed_unit=m/s")
            out.appendLine("gps_altitude_unit=m")
            out.appendLine()

            listOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY
            ).forEach { type ->
                val sensor = sensorManager.getDefaultSensor(type)
                val prefix = "sensor.${sensorTypeName(type)}"
                if (sensor == null) {
                    out.appendLine("$prefix.available=false")
                } else {
                    out.appendLine("$prefix.available=true")
                    out.appendLine("$prefix.name=${sensor.name}")
                    out.appendLine("$prefix.vendor=${sensor.vendor}")
                    out.appendLine("$prefix.version=${sensor.version}")
                    out.appendLine("$prefix.resolution=${sensor.resolution}")
                    out.appendLine("$prefix.maximum_range=${sensor.maximumRange}")
                    out.appendLine("$prefix.min_delay_us=${sensor.minDelay}")
                    out.appendLine("$prefix.max_delay_us=${sensor.maxDelay}")
                }
            }
        }
    }

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_GRAVITY -> "gravity"
        else -> "sensor_$type"
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Road data recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when IMU and GNSS logging is active"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Road Surface Logger")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (recording) {
            try {
                sensorManager.unregisterListener(this)
                locationManager.removeUpdates(this)
            } catch (_: Exception) {
            }
            imuWriter?.runCatching { flush(); close() }
            gpsWriter?.runCatching { flush(); close() }
            markerWriter?.runCatching { flush(); close() }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RECORDING, false)
                .apply()
            recording = false
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
