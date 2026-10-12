package com.example.drivedistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRoute: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPoints: TextView
    private lateinit var tvLegs: TextView
    private lateinit var tvTripTime: TextView
    private lateinit var tvLegCount: TextView
    private lateinit var btnStart: Button
    private lateinit var btnMark: Button
    private lateinit var btnStop: Button
    private lateinit var btnCenter: Button
    private lateinit var btnReset: Button
    private lateinit var btnShare: Button

    private lateinit var map: MapView
    private val routePolylines = mutableListOf<Polyline>()
    private lateinit var meMarker: Marker
    private val stopMarkers = mutableListOf<Marker>()
    private val iconCache = HashMap<String, BitmapDrawable>()
    private var fittedAfterStop = false

    private val GREEN = Color.rgb(46, 125, 50)
    private val RED = Color.rgb(198, 40, 40)
    private val ORANGE = Color.rgb(239, 108, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "DriveDistance/3.0 (Android; https://github.com/himansu-travelgrid/d4) osmdroid"
        cfg.load(this, PreferenceManager.getDefaultSharedPreferences(this))
        val base = File(cacheDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
        // Ensure tile directory exists and is writable
        cfg.osmdroidTileCache?.mkdirs()

        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRoute = findViewById(R.id.tvRoute)
        tvDistance = findViewById(R.id.tvDistance)
        tvPoints = findViewById(R.id.tvPoints)
        tvLegs = findViewById(R.id.tvLegs)
        tvTripTime = findViewById(R.id.tvTripTime)
        tvLegCount = findViewById(R.id.tvLegCount)
        btnStart = findViewById(R.id.btnStart)
        btnMark = findViewById(R.id.btnMark)
        btnStop = findViewById(R.id.btnStop)
        btnCenter = findViewById(R.id.btnCenter)
        btnReset = findViewById(R.id.btnReset)
        btnShare = findViewById(R.id.btnShare)
        map = findViewById(R.id.map)

        setupMap()

        btnStart.setOnClickListener { if (hasPermissions()) startTrip() else requestPermissions() }
        btnMark.setOnClickListener {
            startService(Intent(this, TripService::class.java).setAction(TripService.ACTION_MARK))
        }
        btnStop.setOnClickListener {
            startService(Intent(this, TripService::class.java).setAction(TripService.ACTION_STOP))
        }
        btnCenter.setOnClickListener { centerMapOnLocation() }
        btnReset.setOnClickListener {
            TripTracker.reset()
            refresh()
            map.controller.setZoom(5.0)
            map.controller.setCenter(GeoPoint(20.5937, 78.9629))
        }
        btnShare.setOnClickListener {
            val text = "Trip: " + TripTracker.stopsSnapshot().joinToString(" → ") { it.label } +
                    "\n" + legsText(TripTracker.stopsSnapshot())
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Trip summary")
                .putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, "Share trip summary"))
        }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setUseDataConnection(true)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(20.5937, 78.9629))

        meMarker = Marker(map)
        meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        meMarker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
        meMarker.title = "You are here"
    }

    private fun centerMapOnLocation() {
        val pts = TripTracker.snapshot()
        if (pts.isNotEmpty()) {
            val last = pts.last()
            map.controller.animateTo(last)
            if (map.zoomLevelDouble < 12.0) map.controller.setZoom(17.0)
            return
        }
        map.controller.animateTo(GeoPoint(20.5937, 78.9629))
        map.controller.setZoom(5.0)
    }

    private fun labelIcon(text: String, color: Int): BitmapDrawable {
        val key = "$text#$color"
        iconCache[key]?.let { return it }
        val d = resources.displayMetrics.density
        val size = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = color
        c.drawCircle(size / 2f, size / 2f, size / 2f - 4 * d, p)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = 18 * d
        p.isFakeBoldText = true
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(text, size / 2f, y, p)
        val drawable = BitmapDrawable(resources, bmp)
        iconCache[key] = drawable
        return drawable
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        TripTracker.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        TripTracker.listener = null
        map.onPause()
        super.onPause()
    }

    private fun startTrip() {
        fittedAfterStop = false
        ContextCompat.startForegroundService(
            this, Intent(this, TripService::class.java).setAction(TripService.ACTION_START)
        )
    }

    private fun km(meters: Double) = String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
    private fun nm(s: Stop) = if (s.name.isBlank()) "locating…" else s.name

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatTime(ms: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun legsText(stops: List<Stop>): String {
        val sb = StringBuilder()
        for (i in 1 until stops.size) {
            val a = stops[i - 1]
            val b = stops[i]
            sb.append("${a.label} → ${b.label}:  ${nm(a)} → ${nm(b)}  (${km(b.cumMeters - a.cumMeters)})\n")
        }
        if (TripTracker.running && stops.isNotEmpty()) {
            val last = stops.last()
            sb.append("${last.label} → …:  ${km(TripTracker.distanceMeters - last.cumMeters)} so far\n")
        }
        val mins = if (TripTracker.endTime > 0) (TripTracker.endTime - TripTracker.startTime) / 60000 else null
        sb.append("TOTAL: ${km(TripTracker.distanceMeters)}")
        if (mins != null) sb.append("  •  $mins min")
        return sb.toString()
    }

    private fun refresh() {
        TripTracker.toast?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            TripTracker.toast = null
        }

        val stops = TripTracker.stopsSnapshot()
        val running = TripTracker.running
        val finished = !running && TripTracker.endTime > 0
        val elapsedMs = if (running) {
            System.currentTimeMillis() - TripTracker.startTime
        } else if (finished) {
            TripTracker.endTime - TripTracker.startTime
        } else {
            0L
        }
        val legCount = maxOf(stops.size - 1, 0)

        tvDistance.text = km(TripTracker.distanceMeters)
        tvPoints.text = "GPS points: ${TripTracker.pointCount()}"
        tvTripTime.text = formatDuration(elapsedMs)
        tvLegCount.text = "$legCount"

        btnStart.isEnabled = !running
        btnMark.isEnabled = running && stops.isNotEmpty()
        btnStop.isEnabled = running
        btnCenter.isEnabled = TripTracker.pointCount() > 0 || stops.isNotEmpty()
        btnReset.isEnabled = true
        btnShare.isEnabled = finished && stops.size > 1

        tvStatus.text = when {
            running -> "Driving… tap MARK STOP at each location"
            finished -> {
                val mins = (TripTracker.endTime - TripTracker.startTime) / 60000
                "Trip finished • $mins min • $legCount legs"
            }
            else -> "Ready"
        }
        tvRoute.text = when {
            running && stops.isEmpty() -> "Waiting for GPS fix…"
            running -> stops.joinToString(" → ") { it.label } + " → …"
            finished -> stops.joinToString(" → ") { it.label }
            else -> "—"
        }
        tvLegs.text = if (stops.isEmpty() && !running && !finished)
            "Tap START at point A. Then tap MARK STOP at every location (B, C, D …). " +
                    "Tap FINISH at the end — if you are back at A, the trip is closed as A → B → C → … → A."
        else legsText(stops)

        redrawMap(finished, stops)
    }

    private fun redrawMap(finished: Boolean, stops: List<Stop>) {
        val pts = TripTracker.snapshot()

        routePolylines.forEach { map.overlays.remove(it) }
        routePolylines.clear()

        map.overlays.removeAll(stopMarkers)
        stopMarkers.clear()
        map.overlays.remove(meMarker)

        if (pts.isEmpty()) {
            map.invalidate()
            return
        }

        drawGradientRoute(pts)

        for ((i, s) in stops.withIndex()) {
            val color = when {
                i == 0 -> GREEN
                i == stops.size - 1 && finished -> RED
                else -> ORANGE
            }
            val m = Marker(map)
            m.position = GeoPoint(s.lat, s.lon)
            m.icon = labelIcon(s.label, color)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.title = "${s.label}: ${nm(s)}"
            m.subDescription = formatTime(s.time)
            stopMarkers.add(m)
        }
        map.overlays.addAll(stopMarkers)

        val last = pts.last()
        if (TripTracker.running) {
            meMarker.position = last
            map.overlays.add(meMarker)
            if (map.zoomLevelDouble < 12.0) map.controller.setZoom(17.0)
            if (!map.boundingBox.contains(last.latitude, last.longitude) || pts.size == 1) {
                map.controller.animateTo(last)
            }
        } else if (finished && !fittedAfterStop) {
            fittedAfterStop = true
            if (pts.size > 1) {
                map.post {
                    try {
                        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120)
                    } catch (e: Exception) { /* ignore */ }
                }
            } else {
                map.controller.setCenter(last)
            }
        }
        map.invalidate()
    }

    private fun drawGradientRoute(pts: List<GeoPoint>) {
        if (pts.size < 2) return

        val segmentSize = 10
        for (i in 0 until pts.size - 1 step segmentSize) {
            val endIdx = minOf(i + segmentSize, pts.size - 1)
            val progress = i.toFloat() / pts.size.toFloat()
            val color = interpolateColor(progress)

            val segment = Polyline()
            segment.outlinePaint.color = color
            segment.outlinePaint.strokeWidth = 14f
            segment.setPoints(pts.subList(i, endIdx + 1))
            map.overlays.add(segment)
            routePolylines.add(segment)
        }
    }

    private fun interpolateColor(progress: Float): Int {
        val colors = listOf(
            Color.rgb(25, 118, 210),
            Color.rgb(76, 175, 80),
            Color.rgb(255, 193, 7),
            Color.rgb(255, 152, 0),
            Color.rgb(211, 47, 47)
        )
        val total = colors.size - 1
        val scaled = (progress * total).coerceIn(0f, total.toFloat())
        val index = scaled.toInt()
        val nextIndex = (index + 1).coerceAtMost(total)
        val ratio = scaled - index
        return blendColors(colors[index], colors[nextIndex], ratio)
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)
        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)
        return Color.rgb(
            (r1 + (r2 - r1) * ratio).toInt(),
            (g1 + (g2 - g1) * ratio).toInt(),
            (b1 + (b2 - b1) * ratio).toInt()
        )
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 100 && hasPermissions()) startTrip()
    }
}
