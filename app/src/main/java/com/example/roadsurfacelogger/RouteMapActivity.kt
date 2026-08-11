package com.example.roadsurfacelogger

import android.app.Activity
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import java.io.File
import java.util.Locale
import kotlin.math.ceil

class RouteMapActivity : Activity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_LIVE = "live"
        private const val MAX_DISPLAY_POINTS = 10_000
    }

    private data class MarkerPoint(val label: String, val position: LatLng)
    private data class RouteSnapshot(
        val points: List<LatLng>,
        val markers: List<MarkerPoint>,
        val distanceMeters: Double
    )

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var fitButton: Button
    private var googleMap: GoogleMap? = null
    private var sessionDir: File? = null
    private var live = false
    private var latestSnapshot: RouteSnapshot? = null
    private var hasInitialCamera = false

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            loadRouteAsync()
            if (live) handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_map)

        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.mapStatusText)
        fitButton = findViewById(R.id.fitRouteButton)

        sessionDir = intent.getStringExtra(EXTRA_SESSION_PATH)?.let(::File)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        fitButton.setOnClickListener { latestSnapshot?.let { fitRoute(it.points) } }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        handler.post(refreshTask)
    }

    private fun loadRouteAsync() {
        val dir = sessionDir
        if (dir == null || !dir.isDirectory) {
            statusText.text = "Session folder not found"
            return
        }

        Thread {
            val snapshot = runCatching { readRouteSnapshot(dir) }.getOrElse {
                runOnUiThread { statusText.text = "Could not read route: ${it.message}" }
                return@Thread
            }
            runOnUiThread { renderSnapshot(snapshot) }
        }.start()
    }

    private fun readRouteSnapshot(dir: File): RouteSnapshot {
        val gpsFile = File(dir, "gps.csv")
        if (!gpsFile.isFile) return RouteSnapshot(emptyList(), emptyList(), 0.0)

        val allPoints = ArrayList<LatLng>()
        var distanceMeters = 0.0
        var previous: LatLng? = null

        gpsFile.useLines { lines ->
            lines.drop(1).forEach { line ->
                val columns = line.split(',')
                if (columns.size < 6) return@forEach
                val lat = columns[4].toDoubleOrNull() ?: return@forEach
                val lon = columns[5].toDoubleOrNull() ?: return@forEach
                if (!lat.isFinite() || !lon.isFinite()) return@forEach
                val point = LatLng(lat, lon)
                previous?.let { prev ->
                    val result = FloatArray(1)
                    Location.distanceBetween(prev.latitude, prev.longitude, point.latitude, point.longitude, result)
                    if (result[0].isFinite()) distanceMeters += result[0]
                }
                previous = point
                allPoints += point
            }
        }

        val displayPoints = if (allPoints.size <= MAX_DISPLAY_POINTS) {
            allPoints
        } else {
            val stride = ceil(allPoints.size.toDouble() / MAX_DISPLAY_POINTS).toInt().coerceAtLeast(1)
            allPoints.filterIndexed { index, _ -> index % stride == 0 || index == allPoints.lastIndex }
        }

        val markerPoints = readMarkers(File(dir, "markers.csv"))
        return RouteSnapshot(displayPoints, markerPoints, distanceMeters)
    }

    private fun readMarkers(file: File): List<MarkerPoint> {
        if (!file.isFile) return emptyList()
        val result = ArrayList<MarkerPoint>()
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                val columns = line.split(',')
                if (columns.size < 6) return@forEach
                val label = columns[3].ifBlank { "event" }
                val lat = columns[4].toDoubleOrNull() ?: return@forEach
                val lon = columns[5].toDoubleOrNull() ?: return@forEach
                result += MarkerPoint(label, LatLng(lat, lon))
            }
        }
        return result
    }

    private fun renderSnapshot(snapshot: RouteSnapshot) {
        latestSnapshot = snapshot
        val map = googleMap ?: return
        map.clear()

        if (snapshot.points.isEmpty()) {
            statusText.text = if (live) "Waiting for GNSS route points…" else "No GPS points in this session"
            return
        }

        map.addPolyline(
            PolylineOptions()
                .addAll(snapshot.points)
                .width(8f)
                .color(Color.rgb(25, 118, 210))
                .geodesic(false)
        )
        map.addMarker(MarkerOptions().position(snapshot.points.first()).title("Start"))
        if (snapshot.points.size > 1) {
            map.addMarker(MarkerOptions().position(snapshot.points.last()).title(if (live) "Latest" else "Finish"))
        }
        snapshot.markers.forEach { marker ->
            map.addMarker(MarkerOptions().position(marker.position).title(marker.label.replace('_', ' ')))
        }

        statusText.text = String.format(
            Locale.US,
            "%s • %,d route points • %.2f km • %d event markers",
            if (live) "LIVE ROUTE" else "RECORDED ROUTE",
            snapshot.points.size,
            snapshot.distanceMeters / 1000.0,
            snapshot.markers.size
        )

        if (!hasInitialCamera) {
            hasInitialCamera = true
            fitRoute(snapshot.points)
        } else if (live) {
            map.animateCamera(CameraUpdateFactory.newLatLng(snapshot.points.last()))
        }
    }

    private fun fitRoute(points: List<LatLng>) {
        val map = googleMap ?: return
        if (points.isEmpty()) return
        if (points.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 17f))
            return
        }

        val builder = LatLngBounds.Builder()
        points.forEach(builder::include)
        val bounds = builder.build()
        mapView.post {
            runCatching {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        mapView.onDestroy()
        super.onDestroy()
    }
}
