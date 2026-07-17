package com.example.barky.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multi-source location: GPS, network/cell/Wi‑Fi triangulation via LocationManager,
 * and best-provider fused-style criteria. Keeps a rolling trail (default 5 hours).
 */
class LocationTracker(
    context: Context,
    private val onUpdate: (points: List<GpsPoint>, latest: GpsPoint) -> Unit
) {
    data class GpsPoint(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val accuracyM: Float = -1f,
        val provider: String = ""
    )

    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val trail = CopyOnWriteArrayList<GpsPoint>()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var enabled = false
    private var lastEmit = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            ingest(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        loadTrail()
    }

    fun isEnabled(): Boolean = enabled

    fun trailSnapshot(): List<GpsPoint> = trail.toList()

    fun latest(): GpsPoint? = trail.lastOrNull()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "start: no location permission")
            return false
        }
        if (enabled) return true
        enabled = true
        try {
            // All available providers: GPS + network (cell/Wi‑Fi triangulation)
            val providers = lm.getProviders(true).orEmpty()
            Log.i(TAG, "start providers=$providers")
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
                } catch (t: Throwable) {
                    Log.w(TAG, "requestLocationUpdates $p", t)
                }
            }
            // Best-of fused criteria (system picks GPS/network)
            try {
                val criteria = Criteria().apply {
                    accuracy = Criteria.ACCURACY_FINE
                    isCostAllowed = true
                    powerRequirement = Criteria.POWER_HIGH
                }
                val best = lm.getBestProvider(criteria, true)
                if (best != null && best !in providers) {
                    lm.requestLocationUpdates(best, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "best provider", t)
            }
            // Seed with last known from each source
            for (p in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                try {
                    lm.getLastKnownLocation(p)?.let { ingest(it) }
                } catch (_: Exception) {
                }
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            enabled = false
            return false
        }
    }

    fun stop() {
        enabled = false
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        persistTrail()
    }

    fun clearTrail() {
        trail.clear()
        persistTrail()
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ingest(location: Location) {
        if (!enabled) return
        val pt = GpsPoint(
            lat = location.latitude,
            lon = location.longitude,
            timeMs = if (location.time > 0) location.time else System.currentTimeMillis(),
            accuracyM = location.accuracy,
            provider = location.provider.orEmpty()
        )
        // Debounce noisy same-spot updates
        val last = trail.lastOrNull()
        if (last != null &&
            pt.timeMs - last.timeMs < 1_000L &&
            haversineM(last.lat, last.lon, pt.lat, pt.lon) < 2.0
        ) {
            return
        }
        trail.add(pt)
        prune()
        val now = System.currentTimeMillis()
        if (now - lastEmit >= EMIT_MIN_MS) {
            lastEmit = now
            val snap = trail.toList()
            main.post { onUpdate(snap, pt) }
        }
        // Persist occasionally
        if (trail.size % 5 == 0) persistTrail()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - HOURS_MS * WalkieProtocol.GPS_TRAIL_HOURS
        while (trail.isNotEmpty() && trail.first().timeMs < cutoff) {
            trail.removeAt(0)
        }
        while (trail.size > MAX_POINTS) {
            trail.removeAt(0)
        }
    }

    private fun loadTrail() {
        try {
            val raw = prefs.getString(KEY_TRAIL, null) ?: return
            val arr = JSONArray(raw)
            val cutoff = System.currentTimeMillis() - HOURS_MS * WalkieProtocol.GPS_TRAIL_HOURS
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getLong("t")
                if (t < cutoff) continue
                trail.add(
                    GpsPoint(
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        timeMs = t,
                        accuracyM = o.optDouble("acc", -1.0).toFloat(),
                        provider = o.optString("p", "")
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadTrail", t)
        }
    }

    private fun persistTrail() {
        try {
            prune()
            val arr = JSONArray()
            for (p in trail) {
                arr.put(
                    JSONObject()
                        .put("lat", p.lat)
                        .put("lon", p.lon)
                        .put("t", p.timeMs)
                        .put("acc", p.accuracyM.toDouble())
                        .put("p", p.provider)
                )
            }
            prefs.edit().putString(KEY_TRAIL, arr.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "persistTrail", t)
        }
    }

    companion object {
        private const val TAG = "LocationTracker"
        private const val PREFS = "barky_gps"
        private const val KEY_TRAIL = "trail_5h"
        private const val MIN_INTERVAL_MS = 2_000L
        private const val MIN_DISTANCE_M = 3f
        private const val EMIT_MIN_MS = 1_500L
        private const val HOURS_MS = 3_600_000L
        private const val MAX_POINTS = 2_000

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
