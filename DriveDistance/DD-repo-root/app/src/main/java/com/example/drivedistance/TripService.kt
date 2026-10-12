package com.example.drivedistance

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import java.io.File

class TripService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var tempFile: File

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Temp file in cache dir = backup in case the process is killed mid-trip
        tempFile = File(cacheDir, "trip_temp.csv")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrip()
            ACTION_MARK -> markStop()
            ACTION_STOP -> finishTrip()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTrip() {
        startForeground(1, buildNotification())
        TripTracker.reset()
        tempFile.writeText("lat,lon,time\n")
        TripTracker.running = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
        TripTracker.listener?.invoke()
    }

    /** Save the current position as the next stop (B, C, D ...). */
    private fun markStop() {
        if (!TripTracker.running) return
        val s = TripTracker.markStop(false)
        if (s == null) {
            TripTracker.toast = "Move at least 20 m before marking the next stop"
            TripTracker.listener?.invoke()
        } else {
            nameStop(s)
            TripTracker.listener?.invoke()
        }
    }

    private fun finishTrip() {
        // The final position is saved as the last stop (or "A" again if we are back at the start)
        val s = TripTracker.markStop(true)
        if (s != null) nameStop(s)

        lm.removeUpdates(this)
        TripTracker.running = false
        TripTracker.endTime = System.currentTimeMillis()

        tempFile.delete()                      // temp data no longer needed
        TripTracker.listener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Writes the stop to the temp file and looks up its place name in the background. */
    private fun nameStop(s: Stop) {
        try { tempFile.appendText("#STOP,${s.label},${s.lat},${s.lon},${s.time}\n") } catch (e: Exception) { }

        val first = TripTracker.stopsSnapshot().firstOrNull()
        if (first != null && first !== s && first.label == s.label && first.name.isNotBlank()) {
            s.name = first.name              // back at A: reuse A's name, no network call
            return
        }
        PlaceNamer.lookup(s.lat, s.lon) { name ->
            s.name = name
            TripTracker.listener?.invoke()
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 30f) return   // skip poor fixes
        if (TripTracker.addPoint(loc)) {
            tempFile.appendText("${loc.latitude},${loc.longitude},${loc.time}\n")

            // First accepted point = stop A -> look up its name once
            if (TripTracker.pointCount() == 1) {
                TripTracker.stopsSnapshot().firstOrNull()?.let { nameStop(it) }
            }
            TripTracker.listener?.invoke()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("trip", "Trip tracking", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "trip")
            .setContentTitle("Trip in progress")
            .setContentText("Recording distance…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        lm.removeUpdates(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_MARK = "MARK"
        const val ACTION_STOP = "STOP"     // = FINISH
    }
}
