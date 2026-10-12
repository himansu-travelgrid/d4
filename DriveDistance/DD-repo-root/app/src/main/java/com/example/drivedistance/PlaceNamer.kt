package com.example.drivedistance

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Reverse geocoding using OpenStreetMap Nominatim (free, no API key).
 * Runs on a background thread. Falls back to "lat, lon" when offline / on error.
 */
object PlaceNamer {

    fun lookup(lat: Double, lon: Double, done: (String) -> Unit) {
        Thread {
            var name: String? = null
            for (attempt in 1..2) {
                name = try { fetch(lat, lon) } catch (e: Exception) { null }
                if (name != null) break
                try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            }
            done(name ?: String.format(Locale.US, "%.5f, %.5f", lat, lon))
        }.start()
    }

    private fun fetch(lat: Double, lon: Double): String? {
        val url = URL(
            String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=17&lat=%f&lon=%f",
                lat, lon
            )
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            // Nominatim usage policy requires an identifying User-Agent
            c.setRequestProperty("User-Agent", "DriveDistance/2.0 (Android app)")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val addr = json.optJSONObject("address")

            if (addr != null) {
                val street = pick(addr, "road", "pedestrian", "footway", "neighbourhood", "residential")
                val area = pick(addr, "suburb", "city_district", "village", "town", "city", "county")
                val parts = listOfNotNull(street, area).distinct()
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
            val display = json.optString("display_name", "")
            if (display.isNotBlank()) {
                return display.split(",").map { it.trim() }.take(2).joinToString(", ")
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun pick(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return null
    }
}
