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
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class LoggerService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "com.example.roadsurfacelogger.START"
        const val ACTION_STOP = "com.example.roadsurfacelogger.STOP"
        const val ACTION_MARK = "com.example.roadsurfacelogger.MARK"

        const val EXTRA_MARK_LABEL = "mark_label"
        const val EXTRA_EXPERIMENT_ID = "experiment_id"
        const val EXTRA_VEHICLE = "vehicle"
        const val EXTRA_MOUNT_POSITION = "mount_position"
        const val EXTRA_PHONE_ORIENTATION = "phone_orientation"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_WEATHER = "weather"
        const val EXTRA_TIRE_PRESSURE = "tire_pressure"
        const val EXTRA_PASSENGERS = "passengers"
        const val EXTRA_TARGET_SPEED = "target_speed"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_SAMPLING_LABEL = "sampling_label"
        const val EXTRA_SENSOR_PERIOD_US = "sensor_period_us"

        const val PREFS = "logger_state"
        const val KEY_RECORDING = "recording"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_EXPERIMENT_ID = "experiment_id"
        const val KEY_LAST_SESSION_PATH = "last_session_path"
        const val KEY_IMU_COUNT = "imu_count"
        const val KEY_GPS_COUNT = "gps_count"
        const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_GPS_ACCURACY = "gps_accuracy"
        const val KEY_GPS_PROVIDER = "gps_provider"
        const val KEY_SPEED_MPS = "speed_mps"
        const val KEY_ALTITUDE_M = "altitude_m"
        const val KEY_BEARING_DEG = "bearing_deg"
        const val KEY_GPS_FIX_ELAPSED_MS = "gps_fix_elapsed_ms"
        const val KEY_ACCEL_HZ = "accel_hz"
        const val KEY_GYRO_HZ = "gyro_hz"
        const val KEY_SESSION_BYTES = "session_bytes"
        const val KEY_ACCEL_X = "accel_x"
        const val KEY_ACCEL_Y = "accel_y"
        const val KEY_ACCEL_Z = "accel_z"
        const val KEY_ACCEL_ACCURACY = "accel_accuracy"
        const val KEY_GYRO_X = "gyro_x"
        const val KEY_GYRO_Y = "gyro_y"
        const val KEY_GYRO_Z = "gyro_z"
        const val KEY_GYRO_ACCURACY = "gyro_accuracy"
        const val KEY_LINEAR_X = "linear_x"
        const val KEY_LINEAR_Y = "linear_y"
        const val KEY_LINEAR_Z = "linear_z"
        const val KEY_GRAVITY_X = "gravity_x"
        const val KEY_GRAVITY_Y = "gravity_y"
        const val KEY_GRAVITY_Z = "gravity_z"
        const val KEY_AZIMUTH_DEG = "azimuth_deg"
        const val KEY_PITCH_DEG = "pitch_deg"
        const val KEY_ROLL_DEG = "roll_deg"
        const val KEY_ORIENTATION_COUNT = "orientation_count"

        private const val CHANNEL_ID = "road_logger_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_SENSOR_PERIOD_US = 10_000
    }

    private data class RecordingConfig(
        val experimentId: String,
        val vehicle: String,
        val mountPosition: String,
        val phoneOrientation: String,
        val route: String,
        val weather: String,
        val tirePressure: String,
        val passengers: String,
        val targetSpeed: String,
        val notes: String,
        val samplingLabel: String,
        val sensorPeriodUs: Int
    )

    private data class SensorTiming(
        var count: Long = 0,
        var firstTimestampNs: Long = 0,
        var lastTimestampNs: Long = 0
    ) {
        fun add(timestampNs: Long) {
            if (count == 0L) firstTimestampNs = timestampNs
            lastTimestampNs = timestampNs
            count++
        }

        fun averageHz(): Double? {
            if (count < 2 || lastTimestampNs <= firstTimestampNs) return null
            val seconds = (lastTimestampNs - firstTimestampNs) / 1_000_000_000.0
            return (count - 1) / seconds
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    private var imuWriter: BufferedWriter? = null
    private var gpsWriter: BufferedWriter? = null
    private var markerWriter: BufferedWriter? = null
    private var orientationWriter: BufferedWriter? = null

    @Volatile
    private var recording = false

    private var config = RecordingConfig("", "", "", "", "", "", "", "", "", "", "100 Hz", DEFAULT_SENSOR_PERIOD_US)
    private var sessionId = ""
    private var sessionDir: File? = null
    private var sessionStartWallMs = 0L
    private var sessionStartElapsedNs = 0L
    private var imuCount = 0L
    private var gpsCount = 0L
    private var linearCount = 0L
    private var gravityCount = 0L
    private var orientationCount = 0L
    private var imuSinceFlush = 0
    private var orientationSinceFlush = 0
    private var lastMetricsPublishMs = 0L
    private var latestLocation: Location? = null

    private val latestAccel = FloatArray(3) { Float.NaN }
    private val latestGyro = FloatArray(3) { Float.NaN }
    private val latestLinear = FloatArray(3) { Float.NaN }
    private val latestGravity = FloatArray(3) { Float.NaN }
    private var latestAccelAccuracy = -1
    private var latestGyroAccuracy = -1
    private var latestAzimuthDeg = Float.NaN
    private var latestPitchDeg = Float.NaN
    private var latestRollDeg = Float.NaN

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelTiming = SensorTiming()
    private val gyroTiming = SensorTiming()
    private var gpsAccuracySum = 0.0
    private var gpsAccuracyCount = 0L
    private var lastGpsElapsedNs = 0L
    private var maxGpsGapNs = 0L

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
            ACTION_START -> if (!recording) {
                val requestedConfig = configFromIntent(intent)
                startAsForeground()
                worker.post { beginRecording(requestedConfig) }
            }
            ACTION_MARK -> if (recording) {
                val label = intent.getStringExtra(EXTRA_MARK_LABEL)?.trim().orEmpty().ifBlank { "other" }
                worker.post { writeMarker(label) }
            }
            ACTION_STOP -> if (recording) {
                worker.post { finishRecording() }
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun configFromIntent(intent: Intent): RecordingConfig = RecordingConfig(
        experimentId = intent.getStringExtra(EXTRA_EXPERIMENT_ID).orEmpty(),
        vehicle = intent.getStringExtra(EXTRA_VEHICLE).orEmpty(),
        mountPosition = intent.getStringExtra(EXTRA_MOUNT_POSITION).orEmpty(),
        phoneOrientation = intent.getStringExtra(EXTRA_PHONE_ORIENTATION).orEmpty(),
        route = intent.getStringExtra(EXTRA_ROUTE).orEmpty(),
        weather = intent.getStringExtra(EXTRA_WEATHER).orEmpty(),
        tirePressure = intent.getStringExtra(EXTRA_TIRE_PRESSURE).orEmpty(),
        passengers = intent.getStringExtra(EXTRA_PASSENGERS).orEmpty(),
        targetSpeed = intent.getStringExtra(EXTRA_TARGET_SPEED).orEmpty(),
        notes = intent.getStringExtra(EXTRA_NOTES).orEmpty(),
        samplingLabel = intent.getStringExtra(EXTRA_SAMPLING_LABEL).orEmpty().ifBlank { "100 Hz" },
        sensorPeriodUs = intent.getIntExtra(EXTRA_SENSOR_PERIOD_US, DEFAULT_SENSOR_PERIOD_US).coerceAtLeast(0)
    )

    private fun startAsForeground() {
        val notification = buildNotification("Preparing data logger…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginRecording(requestedConfig: RecordingConfig) {
        config = requestedConfig
        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        sessionId = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(sessionStartWallMs))

        val base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val root = File(base, "RoadSurfaceLogger")
        sessionDir = File(root, "session_$sessionId")
        require(sessionDir!!.mkdirs() || sessionDir!!.isDirectory) { "Could not create session directory" }

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
        orientationWriter = BufferedWriter(FileWriter(File(sessionDir, "orientation.csv"))).apply {
            write("session_id,sensor_timestamp_ns,wall_time_ms,quat_x,quat_y,quat_z,quat_w,azimuth_deg,pitch_deg,roll_deg,heading_accuracy_rad\n")
            flush()
        }

        resetMetrics()
        acquireWakeLock()
        writeSessionInfo()
        writeMetadata()
        recording = true

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECORDING, true)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_EXPERIMENT_ID, config.experimentId)
            .putString(KEY_LAST_SESSION_PATH, sessionDir!!.absolutePath)
            .putLong(KEY_IMU_COUNT, 0)
            .putLong(KEY_GPS_COUNT, 0)
            .putLong(KEY_ORIENTATION_COUNT, 0)
            .putLong(KEY_START_ELAPSED_MS, SystemClock.elapsedRealtime())
            .putFloat(KEY_ACCEL_HZ, Float.NaN)
            .putFloat(KEY_GYRO_HZ, Float.NaN)
            .putFloat(KEY_SPEED_MPS, Float.NaN)
            .putFloat(KEY_ALTITUDE_M, Float.NaN)
            .putFloat(KEY_BEARING_DEG, Float.NaN)
            .putFloat(KEY_AZIMUTH_DEG, Float.NaN)
            .putFloat(KEY_PITCH_DEG, Float.NaN)
            .putFloat(KEY_ROLL_DEG, Float.NaN)
            .putLong(KEY_GPS_FIX_ELAPSED_MS, 0L)
            .putLong(KEY_SESSION_BYTES, 0)
            .remove(KEY_LAT)
            .remove(KEY_LON)
            .apply()

        registerSensors()
        registerLocation()
        publishMetrics(force = true)
        updateNotification("Recording • $sessionId • ${config.samplingLabel}")
    }

    private fun resetMetrics() {
        imuCount = 0
        gpsCount = 0
        linearCount = 0
        gravityCount = 0
        orientationCount = 0
        imuSinceFlush = 0
        orientationSinceFlush = 0
        lastMetricsPublishMs = 0
        latestLocation = null
        resetVector(latestAccel)
        resetVector(latestGyro)
        resetVector(latestLinear)
        resetVector(latestGravity)
        latestAccelAccuracy = -1
        latestGyroAccuracy = -1
        latestAzimuthDeg = Float.NaN
        latestPitchDeg = Float.NaN
        latestRollDeg = Float.NaN
        accelTiming.count = 0
        accelTiming.firstTimestampNs = 0
        accelTiming.lastTimestampNs = 0
        gyroTiming.count = 0
        gyroTiming.firstTimestampNs = 0
        gyroTiming.lastTimestampNs = 0
        gpsAccuracySum = 0.0
        gpsAccuracyCount = 0
        lastGpsElapsedNs = 0L
        maxGpsGapNs = 0L
    }

    private fun resetVector(target: FloatArray) {
        target.indices.forEach { target[it] = Float.NaN }
    }

    private fun copyVector(target: FloatArray, values: FloatArray) {
        if (values.size < 3) return
        target[0] = values[0]
        target[1] = values[1]
        target[2] = values[2]
    }

    private fun registerSensors() {
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ROTATION_VECTOR
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, config.sensorPeriodUs, 0, worker)
            }
        }
    }

    private fun registerLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this, workerThread.looper)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.values.size < 3) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            writeOrientation(event)
            return
        }

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
                event.values[0], event.values[1], event.values[2], event.accuracy
            )
        )

        imuCount++
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelTiming.add(event.timestamp)
                copyVector(latestAccel, event.values)
                latestAccelAccuracy = event.accuracy
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroTiming.add(event.timestamp)
                copyVector(latestGyro, event.values)
                latestGyroAccuracy = event.accuracy
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearCount++
                copyVector(latestLinear, event.values)
            }
            Sensor.TYPE_GRAVITY -> {
                gravityCount++
                copyVector(latestGravity, event.values)
            }
        }

        imuSinceFlush++
        if (imuSinceFlush >= 200) {
            writer.flush()
            imuSinceFlush = 0
        }
        publishMetrics()
    }

    private fun writeOrientation(event: SensorEvent) {
        val writer = orientationWriter ?: return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        latestAzimuthDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
        latestPitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        latestRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        val qx = event.values[0]
        val qy = event.values[1]
        val qz = event.values[2]
        val qw = if (event.values.size > 3) {
            event.values[3]
        } else {
            sqrt((1f - qx * qx - qy * qy - qz * qz).coerceAtLeast(0f))
        }
        val headingAccuracy = if (event.values.size > 4) event.values[4] else Float.NaN
        val wallTimeMs = sessionStartWallMs + ((event.timestamp - sessionStartElapsedNs) / 1_000_000L)

        writer.write(
            String.format(
                Locale.US,
                "%s,%d,%d,%.9f,%.9f,%.9f,%.9f,%.4f,%.4f,%.4f,%.7f\n",
                sessionId,
                event.timestamp,
                wallTimeMs,
                qx, qy, qz, qw,
                latestAzimuthDeg, latestPitchDeg, latestRollDeg,
                headingAccuracy
            )
        )
        orientationCount++
        orientationSinceFlush++
        if (orientationSinceFlush >= 50) {
            writer.flush()
            orientationSinceFlush = 0
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
        writer.flush()

        gpsCount++
        gpsAccuracySum += location.accuracy
        gpsAccuracyCount++
        if (lastGpsElapsedNs > 0L) {
            val gap = location.elapsedRealtimeNanos - lastGpsElapsedNs
            if (gap > maxGpsGapNs) maxGpsGapNs = gap
        }
        lastGpsElapsedNs = location.elapsedRealtimeNanos
        publishMetrics(force = true)
    }

    private fun writeMarker(label: String) {
        if (!recording) return
        val loc = latestLocation
        markerWriter?.apply {
            write(
                String.format(
                    Locale.US,
                    "%s,%d,%d,%s,%s,%s\n",
                    sessionId,
                    SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis(),
                    csvSafe(label),
                    loc?.latitude?.let { String.format(Locale.US, "%.9f", it) } ?: "",
                    loc?.longitude?.let { String.format(Locale.US, "%.9f", it) } ?: ""
                )
            )
            flush()
        }
    }

    private fun csvSafe(value: String): String = value.replace(',', '_').replace('\n', ' ').replace('\r', ' ')

    private fun publishMetrics(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMetricsPublishMs < 200L) return
        lastMetricsPublishMs = now

        val accelHz = accelTiming.averageHz()?.toFloat() ?: Float.NaN
        val gyroHz = gyroTiming.averageHz()?.toFloat() ?: Float.NaN
        val sessionBytes = calculateSessionBytes()

        val edit = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_IMU_COUNT, imuCount)
            .putLong(KEY_GPS_COUNT, gpsCount)
            .putLong(KEY_ORIENTATION_COUNT, orientationCount)
            .putFloat(KEY_ACCEL_HZ, accelHz)
            .putFloat(KEY_GYRO_HZ, gyroHz)
            .putLong(KEY_SESSION_BYTES, sessionBytes)
            .putFloat(KEY_ACCEL_X, latestAccel[0])
            .putFloat(KEY_ACCEL_Y, latestAccel[1])
            .putFloat(KEY_ACCEL_Z, latestAccel[2])
            .putInt(KEY_ACCEL_ACCURACY, latestAccelAccuracy)
            .putFloat(KEY_GYRO_X, latestGyro[0])
            .putFloat(KEY_GYRO_Y, latestGyro[1])
            .putFloat(KEY_GYRO_Z, latestGyro[2])
            .putInt(KEY_GYRO_ACCURACY, latestGyroAccuracy)
            .putFloat(KEY_LINEAR_X, latestLinear[0])
            .putFloat(KEY_LINEAR_Y, latestLinear[1])
            .putFloat(KEY_LINEAR_Z, latestLinear[2])
            .putFloat(KEY_GRAVITY_X, latestGravity[0])
            .putFloat(KEY_GRAVITY_Y, latestGravity[1])
            .putFloat(KEY_GRAVITY_Z, latestGravity[2])
            .putFloat(KEY_AZIMUTH_DEG, latestAzimuthDeg)
            .putFloat(KEY_PITCH_DEG, latestPitchDeg)
            .putFloat(KEY_ROLL_DEG, latestRollDeg)

        latestLocation?.let { loc ->
            edit.putString(KEY_LAT, String.format(Locale.US, "%.7f", loc.latitude))
                .putString(KEY_LON, String.format(Locale.US, "%.7f", loc.longitude))
                .putFloat(KEY_GPS_ACCURACY, loc.accuracy)
                .putString(KEY_GPS_PROVIDER, loc.provider ?: "unknown")
                .putFloat(KEY_SPEED_MPS, if (loc.hasSpeed()) loc.speed else Float.NaN)
                .putFloat(KEY_ALTITUDE_M, if (loc.hasAltitude()) loc.altitude.toFloat() else Float.NaN)
                .putFloat(KEY_BEARING_DEG, if (loc.hasBearing()) loc.bearing else Float.NaN)
                .putLong(KEY_GPS_FIX_ELAPSED_MS, loc.elapsedRealtimeNanos / 1_000_000L)
        }
        edit.apply()
    }

    private fun finishRecording() {
        if (!recording) return
        finalizeRecording(interrupted = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finalizeRecording(interrupted: Boolean) {
        recording = false
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}

        imuWriter?.runCatching { flush(); close() }
        gpsWriter?.runCatching { flush(); close() }
        markerWriter?.runCatching { flush(); close() }
        orientationWriter?.runCatching { flush(); close() }
        imuWriter = null
        gpsWriter = null
        markerWriter = null
        orientationWriter = null

        writeQualityReport(interrupted)
        releaseWakeLock()
        val finalBytes = calculateSessionBytes()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECORDING, false)
            .putLong(KEY_IMU_COUNT, imuCount)
            .putLong(KEY_GPS_COUNT, gpsCount)
            .putLong(KEY_ORIENTATION_COUNT, orientationCount)
            .putFloat(KEY_ACCEL_HZ, accelTiming.averageHz()?.toFloat() ?: Float.NaN)
            .putFloat(KEY_GYRO_HZ, gyroTiming.averageHz()?.toFloat() ?: Float.NaN)
            .putLong(KEY_SESSION_BYTES, finalBytes)
            .apply()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadSurfaceLogger:Recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun writeSessionInfo() {
        val json = JSONObject()
            .put("schema_version", 3)
            .put("session_id", sessionId)
            .put("experiment_id", config.experimentId)
            .put("start_wall_time_ms", sessionStartWallMs)
            .put("vehicle", config.vehicle)
            .put("mount_position", config.mountPosition)
            .put("phone_orientation", config.phoneOrientation)
            .put("route", config.route)
            .put("weather", config.weather)
            .put("tire_pressure", config.tirePressure)
            .put("passengers", config.passengers)
            .put("target_speed_kmh", config.targetSpeed)
            .put("notes", config.notes)
            .put("sampling_label", config.samplingLabel)
            .put("requested_sensor_period_us", config.sensorPeriodUs)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android_release", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
        File(sessionDir, "session_info.json").writeText(json.toString(2))
    }

    private fun writeQualityReport(interrupted: Boolean) {
        val durationMs = ((SystemClock.elapsedRealtimeNanos() - sessionStartElapsedNs) / 1_000_000L).coerceAtLeast(0L)
        val json = JSONObject()
            .put("schema_version", 2)
            .put("session_id", sessionId)
            .put("interrupted", interrupted)
            .put("duration_ms", durationMs)
            .put("completed_wall_time_ms", System.currentTimeMillis())
            .put("requested_sampling_label", config.samplingLabel)
            .put("requested_sensor_period_us", config.sensorPeriodUs)
            .put("imu_events_total", imuCount)
            .put("accelerometer_samples", accelTiming.count)
            .put("gyroscope_samples", gyroTiming.count)
            .put("linear_acceleration_samples", linearCount)
            .put("gravity_samples", gravityCount)
            .put("orientation_samples", orientationCount)
            .put("gps_fixes", gpsCount)
            .put("gps_max_gap_ms", maxGpsGapNs / 1_000_000L)

        putNullableDouble(json, "accelerometer_avg_hz", accelTiming.averageHz())
        putNullableDouble(json, "gyroscope_avg_hz", gyroTiming.averageHz())
        putNullableDouble(json, "gps_avg_accuracy_m", if (gpsAccuracyCount > 0) gpsAccuracySum / gpsAccuracyCount else null)

        val dir = sessionDir
        json.put("imu_csv_bytes", File(dir, "imu.csv").takeIf { it.isFile }?.length() ?: 0L)
        json.put("gps_csv_bytes", File(dir, "gps.csv").takeIf { it.isFile }?.length() ?: 0L)
        json.put("markers_csv_bytes", File(dir, "markers.csv").takeIf { it.isFile }?.length() ?: 0L)
        json.put("orientation_csv_bytes", File(dir, "orientation.csv").takeIf { it.isFile }?.length() ?: 0L)

        val reportFile = File(dir, "quality_report.json")
        reportFile.writeText(json.toString(2))
        json.put("total_session_bytes", calculateSessionBytes())
        reportFile.writeText(json.toString(2))
    }

    private fun putNullableDouble(json: JSONObject, key: String, value: Double?) {
        if (value == null || value.isNaN() || value.isInfinite()) json.put(key, JSONObject.NULL) else json.put(key, value)
    }

    private fun calculateSessionBytes(): Long = sessionDir?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun writeMetadata() {
        val file = File(sessionDir, "metadata.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("session_id=$sessionId")
            out.appendLine("start_wall_time_ms=$sessionStartWallMs")
            out.appendLine("start_elapsed_realtime_ns=$sessionStartElapsedNs")
            out.appendLine("requested_sampling_label=${config.samplingLabel}")
            out.appendLine("requested_sensor_period_us=${config.sensorPeriodUs}")
            out.appendLine("manufacturer=${Build.MANUFACTURER}")
            out.appendLine("brand=${Build.BRAND}")
            out.appendLine("model=${Build.MODEL}")
            out.appendLine("device=${Build.DEVICE}")
            out.appendLine("android_release=${Build.VERSION.RELEASE}")
            out.appendLine("sdk_int=${Build.VERSION.SDK_INT}")
            out.appendLine("imu_coordinate_system=Android device coordinate system")
            out.appendLine("accelerometer_unit=m/s^2")
            out.appendLine("gyroscope_unit=rad/s")
            out.appendLine("orientation_angles_unit=degrees")
            out.appendLine("gps_speed_unit=m/s")
            out.appendLine("gps_altitude_unit=m")
            out.appendLine()

            listOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY,
                Sensor.TYPE_ROTATION_VECTOR
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
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        else -> "sensor_$type"
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Road data recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when IMU and GNSS logging is active"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (recording) finalizeRecording(interrupted = true)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
