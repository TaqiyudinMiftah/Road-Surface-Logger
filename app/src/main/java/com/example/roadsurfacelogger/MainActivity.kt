package com.example.roadsurfacelogger

import android.Manifest
import android.app.Activity
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 10
        private const val REQ_EXPORT_ZIP = 11
    }

    private lateinit var statusText: TextView
    private lateinit var sessionText: TextView
    private lateinit var imuText: TextView
    private lateinit var gpsText: TextView
    private lateinit var durationText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var markButton: Button
    private lateinit var exportButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var startAfterPermission = false
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
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        markButton = findViewById(R.id.markButton)
        exportButton = findViewById(R.id.exportButton)

        startButton.setOnClickListener { ensurePermissionsAndStart() }
        stopButton.setOnClickListener {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
        }
        markButton.setOnClickListener {
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_MARK))
            Toast.makeText(this, "Marker saved", Toast.LENGTH_SHORT).show()
        }
        exportButton.setOnClickListener { exportLastSession() }

        uiHandler.post(uiTick)
    }

    private fun ensurePermissionsAndStart() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionIfUseful()
            startLogger()
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

    private fun startLogger() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Precise location is required for research-quality GNSS logging.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_START)
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
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startLogger()
            } else {
                Toast.makeText(
                    this,
                    "Enable Precise location. Approximate location is not sufficient for road mapping.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateUiFromState() {
        val prefs = getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE)
        val recording = prefs.getBoolean(LoggerService.KEY_RECORDING, false)
        val session = prefs.getString(LoggerService.KEY_SESSION_ID, null)
        val imuCount = prefs.getLong(LoggerService.KEY_IMU_COUNT, 0)
        val gpsCount = prefs.getLong(LoggerService.KEY_GPS_COUNT, 0)
        val startElapsed = prefs.getLong(LoggerService.KEY_START_ELAPSED_MS, 0)
        val lat = prefs.getString(LoggerService.KEY_LAT, null)
        val lon = prefs.getString(LoggerService.KEY_LON, null)
        val accuracy = prefs.getFloat(LoggerService.KEY_GPS_ACCURACY, Float.NaN)
        val provider = prefs.getString(LoggerService.KEY_GPS_PROVIDER, "GPS")

        statusText.text = if (recording) "RECORDING" else "Idle"
        sessionText.text = "Session: ${session ?: "-"}"
        imuText.text = String.format(Locale.US, "IMU events: %,d", imuCount)
        gpsText.text = if (lat != null && lon != null) {
            val accText = if (accuracy.isNaN()) "?" else String.format(Locale.US, "%.1f", accuracy)
            "$provider: $lat, $lon • ±${accText} m • $gpsCount fixes"
        } else {
            "GNSS: waiting for fix • $gpsCount fixes"
        }

        val durationMs = if (recording && startElapsed > 0) {
            SystemClock.elapsedRealtime() - startElapsed
        } else 0L
        durationText.text = "Duration: ${formatDuration(durationMs)}"

        startButton.isEnabled = !recording
        stopButton.isEnabled = recording
        markButton.isEnabled = recording
        exportButton.isEnabled = !recording && !prefs.getString(LoggerService.KEY_LAST_SESSION_PATH, null).isNullOrBlank()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun exportLastSession() {
        val prefs = getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(LoggerService.KEY_LAST_SESSION_PATH, null)
        if (path.isNullOrBlank() || !File(path).isDirectory) {
            Toast.makeText(this, "No completed session found", Toast.LENGTH_SHORT).show()
            return
        }

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
                if (result.isSuccess) {
                    Toast.makeText(this, "Session exported", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        "Export failed: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTick)
        super.onDestroy()
    }
}
