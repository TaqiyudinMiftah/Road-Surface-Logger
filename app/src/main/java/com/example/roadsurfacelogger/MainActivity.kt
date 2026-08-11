package com.example.roadsurfacelogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 10
        private const val REQ_EXPORT_ZIP = 11
        private const val FORM_PREFS = "experiment_form"
    }

    private data class ExperimentConfig(
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

    private lateinit var statusText: TextView
    private lateinit var sessionText: TextView
    private lateinit var imuText: TextView
    private lateinit var gpsText: TextView
    private lateinit var durationText: TextView
    private lateinit var rateText: TextView
    private lateinit var speedText: TextView
    private lateinit var storageText: TextView
    private lateinit var accelValueText: TextView
    private lateinit var gyroValueText: TextView
    private lateinit var linearValueText: TextView
    private lateinit var gravityValueText: TextView
    private lateinit var gpsPositionText: TextView
    private lateinit var gpsDetailText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var potholeButton: Button
    private lateinit var roughButton: Button
    private lateinit var speedBumpButton: Button
    private lateinit var otherButton: Button
    private lateinit var exportButton: Button
    private lateinit var historyButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var startAfterPermission = false
    private var pendingConfig: ExperimentConfig? = null
    private var exportSessionPath: String? = null

    private val uiTick = object : Runnable {
        override fun run() {
            updateUiFromState()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        sessionText = findViewById(R.id.sessionText)
        imuText = findViewById(R.id.imuText)
        gpsText = findViewById(R.id.gpsText)
        durationText = findViewById(R.id.durationText)
        rateText = findViewById(R.id.rateText)
        speedText = findViewById(R.id.speedText)
        storageText = findViewById(R.id.storageText)
        accelValueText = findViewById(R.id.accelValueText)
        gyroValueText = findViewById(R.id.gyroValueText)
        linearValueText = findViewById(R.id.linearValueText)
        gravityValueText = findViewById(R.id.gravityValueText)
        gpsPositionText = findViewById(R.id.gpsPositionText)
        gpsDetailText = findViewById(R.id.gpsDetailText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        potholeButton = findViewById(R.id.potholeButton)
        roughButton = findViewById(R.id.roughButton)
        speedBumpButton = findViewById(R.id.speedBumpButton)
        otherButton = findViewById(R.id.otherButton)
        exportButton = findViewById(R.id.exportButton)
        historyButton = findViewById(R.id.historyButton)

        startButton.setOnClickListener { showExperimentDialog() }
        stopButton.setOnClickListener {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
        }
        potholeButton.setOnClickListener { sendMarker("pothole") }
        roughButton.setOnClickListener { sendMarker("rough_road") }
        speedBumpButton.setOnClickListener { sendMarker("speed_bump") }
        otherButton.setOnClickListener { sendMarker("other") }
        exportButton.setOnClickListener { exportLastSession() }
        historyButton.setOnClickListener { showSessionHistory() }

        uiHandler.post(uiTick)
    }

    private fun showExperimentDialog() {
        val prefs = getSharedPreferences(FORM_PREFS, Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val experimentId = addField(container, "Experiment ID", prefs.getString("experiment_id", "") ?: "")
        val vehicle = addField(container, "Vehicle", prefs.getString("vehicle", "") ?: "")
        val mountPosition = addField(container, "Phone mount position", prefs.getString("mount", "dashboard_center") ?: "dashboard_center")
        val phoneOrientation = addField(container, "Phone orientation", prefs.getString("orientation", "landscape") ?: "landscape")
        val route = addField(container, "Route", prefs.getString("route", "") ?: "")
        val weather = addField(container, "Weather / road condition", prefs.getString("weather", "dry") ?: "dry")
        val tirePressure = addField(container, "Tire pressure (optional)", prefs.getString("tire_pressure", "") ?: "")
        val passengers = addField(container, "Passengers", prefs.getString("passengers", "") ?: "", InputType.TYPE_CLASS_NUMBER)
        val targetSpeed = addField(container, "Target speed km/h (optional)", prefs.getString("target_speed", "") ?: "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val notes = addField(container, "Notes", prefs.getString("notes", "") ?: "")

        val samplingLabel = TextView(this).apply {
            text = "Sampling target"
            setPadding(0, dp(10), 0, dp(4))
        }
        container.addView(samplingLabel)
        val samplingChoices = listOf("50 Hz", "100 Hz", "200 Hz", "FASTEST")
        val samplingSpinner = Spinner(this)
        samplingSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, samplingChoices)
        val savedSampling = prefs.getString("sampling", "100 Hz") ?: "100 Hz"
        samplingSpinner.setSelection(samplingChoices.indexOf(savedSampling).coerceAtLeast(0))
        container.addView(samplingSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Experiment metadata")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sampling = samplingSpinner.selectedItem.toString()
                val periodUs = when (sampling) {
                    "50 Hz" -> 20_000
                    "200 Hz" -> 5_000
                    "FASTEST" -> 0
                    else -> 10_000
                }

                val config = ExperimentConfig(
                    experimentId = experimentId.text.toString().trim(),
                    vehicle = vehicle.text.toString().trim(),
                    mountPosition = mountPosition.text.toString().trim(),
                    phoneOrientation = phoneOrientation.text.toString().trim(),
                    route = route.text.toString().trim(),
                    weather = weather.text.toString().trim(),
                    tirePressure = tirePressure.text.toString().trim(),
                    passengers = passengers.text.toString().trim(),
                    targetSpeed = targetSpeed.text.toString().trim(),
                    notes = notes.text.toString().trim(),
                    samplingLabel = sampling,
                    sensorPeriodUs = periodUs
                )

                prefs.edit()
                    .putString("experiment_id", config.experimentId)
                    .putString("vehicle", config.vehicle)
                    .putString("mount", config.mountPosition)
                    .putString("orientation", config.phoneOrientation)
                    .putString("route", config.route)
                    .putString("weather", config.weather)
                    .putString("tire_pressure", config.tirePressure)
                    .putString("passengers", config.passengers)
                    .putString("target_speed", config.targetSpeed)
                    .putString("notes", config.notes)
                    .putString("sampling", config.samplingLabel)
                    .apply()

                dialog.dismiss()
                ensurePermissionsAndStart(config)
            }
        }
        dialog.show()
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        val field = EditText(this).apply {
            hint = label
            setText(value)
            this.inputType = inputType
            setSingleLine(label != "Notes")
            if (label == "Notes") minLines = 2
        }
        parent.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return field
    }

    private fun ensurePermissionsAndStart(config: ExperimentConfig) {
        pendingConfig = config
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionIfUseful()
            startLogger(config)
            return
        }

        startAfterPermission = true
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(permissions.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMISSIONS)
        }
    }

    private fun startLogger(config: ExperimentConfig) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Precise location is required for GNSS logging.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, LoggerService::class.java)
            .setAction(LoggerService.ACTION_START)
            .putExtra(LoggerService.EXTRA_EXPERIMENT_ID, config.experimentId)
            .putExtra(LoggerService.EXTRA_VEHICLE, config.vehicle)
            .putExtra(LoggerService.EXTRA_MOUNT_POSITION, config.mountPosition)
            .putExtra(LoggerService.EXTRA_PHONE_ORIENTATION, config.phoneOrientation)
            .putExtra(LoggerService.EXTRA_ROUTE, config.route)
            .putExtra(LoggerService.EXTRA_WEATHER, config.weather)
            .putExtra(LoggerService.EXTRA_TIRE_PRESSURE, config.tirePressure)
            .putExtra(LoggerService.EXTRA_PASSENGERS, config.passengers)
            .putExtra(LoggerService.EXTRA_TARGET_SPEED, config.targetSpeed)
            .putExtra(LoggerService.EXTRA_NOTES, config.notes)
            .putExtra(LoggerService.EXTRA_SAMPLING_LABEL, config.samplingLabel)
            .putExtra(LoggerService.EXTRA_SENSOR_PERIOD_US, config.sensorPeriodUs)
        startForegroundService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        if (startAfterPermission) {
            startAfterPermission = false
            val config = pendingConfig
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && config != null) {
                startLogger(config)
            } else {
                Toast.makeText(this, "Enable Precise location for road mapping.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendMarker(label: String) {
        startService(
            Intent(this, LoggerService::class.java)
                .setAction(LoggerService.ACTION_MARK)
                .putExtra(LoggerService.EXTRA_MARK_LABEL, label)
        )
        Toast.makeText(this, "Marker: $label", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiFromState() {
        val prefs = getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE)
        val recording = prefs.getBoolean(LoggerService.KEY_RECORDING, false)
        val session = prefs.getString(LoggerService.KEY_SESSION_ID, null)
        val experimentId = prefs.getString(LoggerService.KEY_EXPERIMENT_ID, null)
        val imuCount = prefs.getLong(LoggerService.KEY_IMU_COUNT, 0)
        val gpsCount = prefs.getLong(LoggerService.KEY_GPS_COUNT, 0)
        val startElapsed = prefs.getLong(LoggerService.KEY_START_ELAPSED_MS, 0)
        val lat = prefs.getString(LoggerService.KEY_LAT, null)
        val lon = prefs.getString(LoggerService.KEY_LON, null)
        val accuracy = prefs.getFloat(LoggerService.KEY_GPS_ACCURACY, Float.NaN)
        val provider = prefs.getString(LoggerService.KEY_GPS_PROVIDER, "GPS")
        val speedMps = prefs.getFloat(LoggerService.KEY_SPEED_MPS, Float.NaN)
        val altitudeM = prefs.getFloat(LoggerService.KEY_ALTITUDE_M, Float.NaN)
        val bearingDeg = prefs.getFloat(LoggerService.KEY_BEARING_DEG, Float.NaN)
        val gpsFixElapsedMs = prefs.getLong(LoggerService.KEY_GPS_FIX_ELAPSED_MS, 0L)
        val accelHz = prefs.getFloat(LoggerService.KEY_ACCEL_HZ, Float.NaN)
        val gyroHz = prefs.getFloat(LoggerService.KEY_GYRO_HZ, Float.NaN)
        val sessionBytes = prefs.getLong(LoggerService.KEY_SESSION_BYTES, 0L)

        val accelX = prefs.getFloat(LoggerService.KEY_ACCEL_X, Float.NaN)
        val accelY = prefs.getFloat(LoggerService.KEY_ACCEL_Y, Float.NaN)
        val accelZ = prefs.getFloat(LoggerService.KEY_ACCEL_Z, Float.NaN)
        val accelAccuracy = prefs.getInt(LoggerService.KEY_ACCEL_ACCURACY, -1)
        val gyroX = prefs.getFloat(LoggerService.KEY_GYRO_X, Float.NaN)
        val gyroY = prefs.getFloat(LoggerService.KEY_GYRO_Y, Float.NaN)
        val gyroZ = prefs.getFloat(LoggerService.KEY_GYRO_Z, Float.NaN)
        val gyroAccuracy = prefs.getInt(LoggerService.KEY_GYRO_ACCURACY, -1)
        val linearX = prefs.getFloat(LoggerService.KEY_LINEAR_X, Float.NaN)
        val linearY = prefs.getFloat(LoggerService.KEY_LINEAR_Y, Float.NaN)
        val linearZ = prefs.getFloat(LoggerService.KEY_LINEAR_Z, Float.NaN)
        val gravityX = prefs.getFloat(LoggerService.KEY_GRAVITY_X, Float.NaN)
        val gravityY = prefs.getFloat(LoggerService.KEY_GRAVITY_Y, Float.NaN)
        val gravityZ = prefs.getFloat(LoggerService.KEY_GRAVITY_Z, Float.NaN)

        statusText.text = if (recording) "● RECORDING" else "Idle"
        sessionText.text = buildString {
            append("Session: ${session ?: "-"}")
            if (!experimentId.isNullOrBlank()) append(" • Experiment: $experimentId")
        }
        imuText.text = String.format(Locale.US, "IMU events: %,d", imuCount)
        rateText.text = "Accelerometer: ${formatHz(accelHz)} • Gyroscope: ${formatHz(gyroHz)}"
        gpsText.text = if (lat != null && lon != null) {
            val accText = if (accuracy.isNaN()) "?" else String.format(Locale.US, "%.1f", accuracy)
            "$provider: $lat, $lon • ±${accText} m • $gpsCount fixes"
        } else {
            "GNSS: waiting for fix • $gpsCount fixes"
        }
        speedText.text = if (speedMps.isNaN()) "Speed: -" else String.format(Locale.US, "Speed: %.1f km/h", speedMps * 3.6f)
        storageText.text = String.format(Locale.US, "Session size: %.2f MB", sessionBytes / (1024.0 * 1024.0))

        accelValueText.text = "Accelerometer (m/s²)\n${formatVector(accelX, accelY, accelZ)} • accuracy ${formatSensorAccuracy(accelAccuracy)}"
        gyroValueText.text = "Gyroscope (rad/s)\n${formatVector(gyroX, gyroY, gyroZ)} • accuracy ${formatSensorAccuracy(gyroAccuracy)}"
        linearValueText.text = "Linear acceleration (m/s²)\n${formatVector(linearX, linearY, linearZ)}"
        gravityValueText.text = "Gravity (m/s²)\n${formatVector(gravityX, gravityY, gravityZ)}"

        gpsPositionText.text = if (lat != null && lon != null) {
            "Latitude : $lat\nLongitude: $lon"
        } else {
            "Latitude : -\nLongitude: -"
        }

        val fixAgeMs = if (gpsFixElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - gpsFixElapsedMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        gpsDetailText.text = buildString {
            append("Altitude: ${formatNumber(altitudeM, 1, "m")} • Speed: ${formatSpeed(speedMps)}\n")
            append("Bearing: ${formatNumber(bearingDeg, 1, "°")} • Accuracy: ${formatAccuracy(accuracy)}")
            append(" • Fix age: ${formatAge(fixAgeMs)}")
        }

        val durationMs = if (recording && startElapsed > 0) SystemClock.elapsedRealtime() - startElapsed else 0L
        durationText.text = "Duration: ${formatDuration(durationMs)}"

        startButton.isEnabled = !recording
        stopButton.isEnabled = recording
        listOf(potholeButton, roughButton, speedBumpButton, otherButton).forEach { it.isEnabled = recording }
        exportButton.isEnabled = !recording && !prefs.getString(LoggerService.KEY_LAST_SESSION_PATH, null).isNullOrBlank()
        historyButton.isEnabled = !recording
    }

    private fun formatHz(value: Float): String = if (value.isNaN() || value <= 0f) "-" else String.format(Locale.US, "%.1f Hz", value)

    private fun formatVector(x: Float, y: Float, z: Float): String {
        if (x.isNaN() || y.isNaN() || z.isNaN()) return "X: -   Y: -   Z: -"
        return String.format(Locale.US, "X: %+.4f   Y: %+.4f   Z: %+.4f", x, y, z)
    }

    private fun formatSensorAccuracy(value: Int): String = if (value < 0) "-" else value.toString()

    private fun formatNumber(value: Float, decimals: Int, suffix: String): String {
        if (value.isNaN()) return "-"
        return String.format(Locale.US, "%.${decimals}f %s", value, suffix)
    }

    private fun formatSpeed(speedMps: Float): String {
        if (speedMps.isNaN()) return "-"
        return String.format(Locale.US, "%.1f km/h", speedMps * 3.6f)
    }

    private fun formatAccuracy(accuracy: Float): String {
        if (accuracy.isNaN()) return "-"
        return String.format(Locale.US, "±%.1f m", accuracy)
    }

    private fun formatAge(ageMs: Long): String {
        if (ageMs < 0) return "-"
        return String.format(Locale.US, "%.1f s", ageMs / 1000.0)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun sessionRoot(): File {
        val base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        return File(base, "RoadSurfaceLogger")
    }

    private fun showSessionHistory() {
        val sessions = sessionRoot().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("session_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (sessions.isEmpty()) {
            Toast.makeText(this, "No recorded sessions yet", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = sessions.map { dir ->
            val info = readJson(File(dir, "session_info.json"))
            val experiment = info?.optString("experiment_id")?.takeIf { it.isNotBlank() }
            if (experiment != null) "${dir.name}\n$experiment" else dir.name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Recorded sessions")
            .setItems(labels) { _, which -> showSessionDetail(sessions[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSessionDetail(dir: File) {
        val info = readJson(File(dir, "session_info.json"))
        val report = readJson(File(dir, "quality_report.json"))
        val detail = buildString {
            appendLine(dir.name)
            appendLine()
            if (info != null) {
                appendLine("Experiment: ${info.optString("experiment_id", "-")}")
                appendLine("Vehicle: ${info.optString("vehicle", "-")}")
                appendLine("Route: ${info.optString("route", "-")}")
                appendLine("Mount: ${info.optString("mount_position", "-")}")
                appendLine("Orientation: ${info.optString("phone_orientation", "-")}")
                appendLine("Sampling: ${info.optString("sampling_label", "-")}")
            }
            if (report != null) {
                appendLine()
                appendLine("Quality report")
                appendLine("Duration: ${formatDuration(report.optLong("duration_ms", 0L))}")
                appendLine("Accelerometer: ${formatJsonHz(report, "accelerometer_avg_hz")}")
                appendLine("Gyroscope: ${formatJsonHz(report, "gyroscope_avg_hz")}")
                appendLine("GPS fixes: ${report.optLong("gps_fixes", 0)}")
                appendLine("Average GPS accuracy: ${formatJsonNumber(report, "gps_avg_accuracy_m", "m")}")
                appendLine("Largest GPS gap: ${report.optLong("gps_max_gap_ms", 0)} ms")
                appendLine("Total size: ${String.format(Locale.US, "%.2f MB", report.optLong("total_session_bytes", 0L) / (1024.0 * 1024.0))}")
            } else {
                appendLine()
                appendLine("Quality report is not available (session may be from v0.1 or incomplete).")
            }
        }

        val textView = TextView(this).apply {
            text = detail
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("Session detail")
            .setView(scroll)
            .setPositiveButton("Export ZIP") { _, _ -> exportSession(dir.absolutePath) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (!file.isFile) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()

    private fun formatJsonHz(json: JSONObject, key: String): String {
        if (json.isNull(key)) return "-"
        return String.format(Locale.US, "%.1f Hz", json.optDouble(key, Double.NaN))
    }

    private fun formatJsonNumber(json: JSONObject, key: String, suffix: String): String {
        if (json.isNull(key)) return "-"
        return String.format(Locale.US, "%.1f %s", json.optDouble(key, Double.NaN), suffix)
    }

    private fun exportLastSession() {
        val path = getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE)
            .getString(LoggerService.KEY_LAST_SESSION_PATH, null)
        if (path.isNullOrBlank() || !File(path).isDirectory) {
            Toast.makeText(this, "No completed session found", Toast.LENGTH_SHORT).show()
            return
        }
        exportSession(path)
    }

    private fun exportSession(path: String) {
        exportSessionPath = path
        val sessionName = File(path).name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "$sessionName.zip")
        }
        startActivityForResult(intent, REQ_EXPORT_ZIP)
    }

    @Deprecated("Uses the classic Activity result API to keep this sample dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_ZIP || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        val sourcePath = exportSessionPath ?: return

        Thread {
            val result = runCatching { zipSession(File(sourcePath), destination) }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) "Session exported" else "Export failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun zipSession(sourceDir: File, destination: Uri) {
        contentResolver.openOutputStream(destination)?.use { rawOut ->
            ZipOutputStream(rawOut.buffered()).use { zip ->
                sourceDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    if (!file.isFile) return@forEach
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Could not open export destination")
    }

    @Suppress("UNUSED")
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTick)
        super.onDestroy()
    }
}
