package com.example.barky

import android.content.Context

/** Shared app preferences (same file as overlay delay). */
object BarkyPrefs {
    const val NAME = "prefs"
    const val KEY_HIDE_DELAY = "hide_delay"
    /** When true: record full clip locally at high FPS, then upload after HOLD release. */
    const val KEY_BEST_QUALITY = "best_quality_video"
    /**
     * When true (default): walkie discovers peers only on local Wi‑Fi (NSD).
     * When false: also enable internet pairing via public STUN + share codes (no Barky server).
     */
    const val KEY_WIFI_ONLY = "walkie_wifi_only"
    /** Preferred camera for walkie capture (true = front / selfie). */
    const val KEY_CAMERA_FRONT = "walkie_camera_front"
    /** When true: accessibility unit conversion overlay is active. */
    const val KEY_CONVERSION_ENABLED = "unit_conversion_enabled"
    /** When true: share GPS trail with peer and show map. */
    const val KEY_GPS_ENABLED = "walkie_gps_enabled"
    /** Optional PC host/IP for LAN APK updates (scripts/serve-apk.ps1). */
    const val KEY_LAN_UPDATE_HOST = "lan_update_host"
    /** Soft WAKEUP for automated tests (no loud alarm). */
    const val KEY_QUIET_TESTS = "quiet_tests"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isBestQualityVideo(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BEST_QUALITY, false)

    fun setBestQualityVideo(context: Context, enabled: Boolean) {
        // commit() so HOLD immediately after check sees the new value (apply is async)
        prefs(context).edit().putBoolean(KEY_BEST_QUALITY, enabled).commit()
    }

    fun isWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
    }

    fun isCameraFront(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERA_FRONT, true)

    fun setCameraFront(context: Context, front: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERA_FRONT, front).apply()
    }

    fun toggleCameraFront(context: Context): Boolean {
        val next = !isCameraFront(context)
        setCameraFront(context, next)
        return next
    }

    fun isConversionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONVERSION_ENABLED, false)

    fun setConversionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONVERSION_ENABLED, enabled).apply()
    }

    fun isGpsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GPS_ENABLED, false)

    fun setGpsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GPS_ENABLED, enabled).apply()
    }

    /** Default false — real WAKEUP is loud; smoke sets true only for adb tests. */
    fun isQuietTests(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUIET_TESTS, false)

    fun setQuietTests(context: Context, quiet: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUIET_TESTS, quiet).apply()
    }
}
