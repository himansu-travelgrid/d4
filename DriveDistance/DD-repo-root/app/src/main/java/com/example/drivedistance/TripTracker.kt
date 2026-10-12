package com.example.drivedistance

import android.location.Location
import org.osmdroid.util.GeoPoint

/** One saved point of the trip: A, B, C, D ... */
class Stop(
    val label: String,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val cumMeters: Double      // distance travelled from A up to this stop
) {
    @Volatile var name: String = ""   // reverse-geocoded place name (filled in later)
}

/**
 * Temporary in-memory store for the current trip.
 * Wiped when a new trip starts.
 */
object TripTracker {
    private val points = mutableListOf<Location>()   // intermediate GPS points (temp)
    private val stops = mutableListOf<Stop>()        // A, B, C, D ...

    @Volatile var distanceMeters = 0.0
    @Volatile var running = false
    @Volatile var startTime = 0L
    @Volatile var endTime = 0L
    @Volatile var toast: String? = null              // one-shot message for the UI
    var listener: (() -> Unit)? = null               // UI refresh callback

    private const val MIN_LEG_METERS = 20.0          // minimum movement before a stop can be marked
    private const val RETURN_RADIUS_M = 150f         // finishing within this of A = "back at A"

    @Synchronized fun reset() {
        points.clear()
        stops.clear()
        distanceMeters = 0.0
        startTime = System.currentTimeMillis()
        endTime = 0L
        toast = null
    }

    /** Returns true if the point was accepted. The very first point becomes stop "A". */
    @Synchronized fun addPoint(loc: Location): Boolean {
        val last = points.lastOrNull()
        if (last != null) {
            val d = last.distanceTo(loc)
            if (d < 5f) return false          // ignore GPS jitter when standing still
            distanceMeters += d
        } else {
            stops.add(Stop(labelFor(0), loc.latitude, loc.longitude, loc.time, 0.0))
        }
        points.add(loc)
        return true
    }

    /**
     * Saves the current position as the next stop (B, C, D ...).
     * If [finish] is true and we are back within 150 m of A, the stop is labelled "A" again (round trip).
     * Returns null if the vehicle has not moved enough since the previous stop.
     */
    @Synchronized fun markStop(finish: Boolean): Stop? {
        val cur = points.lastOrNull() ?: return null
        val prev = stops.lastOrNull() ?: return null
        if (distanceMeters - prev.cumMeters < MIN_LEG_METERS) return null

        var label = labelFor(stops.size)
        if (finish && stops.size > 1) {
            val first = stops.first()
            val res = FloatArray(1)
            Location.distanceBetween(first.lat, first.lon, cur.latitude, cur.longitude, res)
            if (res[0] < RETURN_RADIUS_M) label = first.label
        }
        val s = Stop(label, cur.latitude, cur.longitude, cur.time, distanceMeters)
        stops.add(s)
        return s
    }

    private fun labelFor(index: Int): String =
        if (index < 26) ('A' + index).toString() else "S$index"

    @Synchronized fun pointCount(): Int = points.size

    @Synchronized fun snapshot(): List<GeoPoint> =
        points.map { GeoPoint(it.latitude, it.longitude) }

    @Synchronized fun stopsSnapshot(): List<Stop> = stops.toList()
}
