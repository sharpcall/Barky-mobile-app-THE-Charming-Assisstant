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
    /** When true: heartbeat device IP map to Firebase free-tier directory. */
    const val KEY_FIREBASE_REGISTRY = "firebase_registry_enabled"
    /** Superadmin session unlocked (password gate in Settings). */
    const val KEY_SUPERADMIN = "superadmin_unlocked"
    /** Continuous MediaProjection screen capture (superadmin only). */
    const val KEY_SCREEN_CAPTURE = "superadmin_screen_capture"
    /** Offer this phone’s APK on HTTP for peer/internet updates. */
    const val KEY_SERVE_APK = "serve_apk_updates"
    /**
     * Superadmin: answer contact SCRN with held MediaProjection (one system dialog first).
     */
    const val KEY_AUTO_SCREEN_SHARE = "superadmin_auto_screen_share"
    /**
     * Superadmin password (client-side gate only — not cryptographic security).
     * Change later; extractable from APK.
     */
    const val SUPERADMIN_PASSWORD = "RootMeDontBootMe"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isBestQualityVideo(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BEST_QUALITY, false)

    fun setBestQualityVideo(context: Context, enabled: Boolean) {
        // commit() so HOLD immediately after check sees the new value (apply is async)
        prefs(context).edit().putBoolean(KEY_BEST_QUALITY, enabled).commit()
    }

    /** Default false so cellular/internet peers work without a settings visit. */
    fun isWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, false)

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

    /** Default true — directory is required for Contacts / cellular path. */
    fun isFirebaseRegistryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIREBASE_REGISTRY, true)

    fun setFirebaseRegistryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIREBASE_REGISTRY, enabled).apply()
    }

    /**
     * One-time: turn on cellular/internet path + Firebase directory.
     * Older installs had wifi-only=true and registry=false by default.
     */
    fun ensureConnectivityDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("connectivity_defaults_v1", false)) return
        p.edit()
            .putBoolean(KEY_WIFI_ONLY, false)
            .putBoolean(KEY_FIREBASE_REGISTRY, true)
            .putBoolean("connectivity_defaults_v1", true)
            .apply()
    }

    fun isSuperAdmin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUPERADMIN, false)

    fun setSuperAdmin(context: Context, unlocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUPERADMIN, unlocked).apply()
        if (!unlocked) {
            prefs(context).edit()
                .putBoolean(KEY_SCREEN_CAPTURE, false)
                .putBoolean(KEY_AUTO_SCREEN_SHARE, false)
                .apply()
        }
    }

    fun tryUnlockSuperAdmin(context: Context, password: String): Boolean {
        val ok = password == SUPERADMIN_PASSWORD
        if (ok) setSuperAdmin(context, true)
        return ok
    }

    fun isScreenCaptureEnabled(context: Context): Boolean =
        isSuperAdmin(context) && prefs(context).getBoolean(KEY_SCREEN_CAPTURE, false)

    fun setScreenCaptureEnabled(context: Context, enabled: Boolean) {
        if (!isSuperAdmin(context)) {
            prefs(context).edit().putBoolean(KEY_SCREEN_CAPTURE, false).apply()
            return
        }
        prefs(context).edit().putBoolean(KEY_SCREEN_CAPTURE, enabled).apply()
    }

    fun isServeApkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVE_APK, true)

    fun setServeApkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVE_APK, enabled).apply()
    }

    fun isAutoScreenShare(context: Context): Boolean =
        isSuperAdmin(context) && prefs(context).getBoolean(KEY_AUTO_SCREEN_SHARE, false)

    fun setAutoScreenShare(context: Context, enabled: Boolean) {
        if (!isSuperAdmin(context)) {
            prefs(context).edit().putBoolean(KEY_AUTO_SCREEN_SHARE, false).apply()
            return
        }
        prefs(context).edit().putBoolean(KEY_AUTO_SCREEN_SHARE, enabled).apply()
    }
}
