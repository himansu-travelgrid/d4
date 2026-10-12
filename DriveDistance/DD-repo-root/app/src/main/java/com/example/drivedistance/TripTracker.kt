package com.example.drivedistance

import android.location.Location
import org.osmdroid.util.GeoPoint
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

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
    private val plannedStops = mutableListOf<Stop>() // upcoming sales stops for route optimization

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
        plannedStops.clear()
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

    @Synchronized fun addPlannedStop(label: String, lat: Double, lon: Double) {
        plannedStops.add(Stop(label, lat, lon, System.currentTimeMillis(), 0.0))
    }

    @Synchronized fun clearPlannedStops() {
        plannedStops.clear()
    }

    private fun labelFor(index: Int): String =
        if (index < 26) ('A' + index).toString() else "S$index"

    @Synchronized fun pointCount(): Int = points.size

    @Synchronized fun snapshot(): List<GeoPoint> =
        points.map { GeoPoint(it.latitude, it.longitude) }

    @Synchronized fun stopsSnapshot(): List<Stop> = stops.toList()

    @Synchronized fun plannedStopsSnapshot(): List<Stop> = plannedStops.toList()

    /** Haversine distance in meters */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * acos(a.coerceIn(-1.0, 1.0))
        return r * c
    }

    /** Nearest-neighbor route optimization */
    @Synchronized fun optimizeRoute(currentLat: Double, currentLon: Double): List<Stop> {
        val remaining = plannedStops.toMutableList()
        val ordered = mutableListOf<Stop>()
        var curLat = currentLat
        var curLon = currentLon
        var totalMeters = 0.0

        while (remaining.isNotEmpty()) {
            var nearest: Stop? = null
            var nearestDistance = Double.MAX_VALUE
            for (stop in remaining) {
                val d = haversineDistance(curLat, curLon, stop.lat, stop.lon)
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearest = stop
                }
            }
            if (nearest == null) break
            ordered.add(nearest)
            totalMeters += nearestDistance
            remaining.remove(nearest)
            curLat = nearest.lat
            curLon = nearest.lon
        }

        plannedStops.clear()
        plannedStops.addAll(ordered)
        toast = "Route optimized • ${String.format("%.1f", totalMeters / 1000.0)} km"
        return ordered
    }

    @Synchronized fun getNextRecommendedStop(currentLat: Double, currentLon: Double): Stop? {
        if (plannedStops.isEmpty()) return null
        var nearest: Stop? = null
        var nearestDistance = Double.MAX_VALUE
        for (stop in plannedStops) {
            val d = haversineDistance(currentLat, currentLon, stop.lat, stop.lon)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = stop
            }
        }
        return nearest
    }
}
