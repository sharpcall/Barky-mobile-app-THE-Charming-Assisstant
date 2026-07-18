package com.example.barky.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import com.example.barky.BarkyPrefs
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Stable-ish unique Barky device id:
 * phoneNumber (or NoPhoneNumber) + Android ID + model + optional coarse coords.
 */
object DeviceIdentity {
    private const val TAG = "DeviceIdentity"
    private const val KEY_CACHED_ID = "barky_device_id_v1"

    data class Snapshot(
        val deviceId: String,
        val phoneNumber: String,
        val androidId: String,
        val model: String,
        val manufacturer: String,
        val lat: Double?,
        val lon: Double?
    )

    fun snapshot(context: Context, lat: Double? = null, lon: Double? = null): Snapshot {
        val app = context.applicationContext
        val phone = readPhoneNumber(app)
        val androidId = readAndroidId(app)
        val model = Build.MODEL ?: "unknown"
        val mfr = Build.MANUFACTURER ?: "unknown"
        val id = loadOrCreateId(app, phone, androidId, model, lat, lon)
        return Snapshot(
            deviceId = id,
            phoneNumber = phone,
            androidId = androidId,
            model = model,
            manufacturer = mfr,
            lat = lat,
            lon = lon
        )
    }

    fun deviceId(context: Context): String = snapshot(context).deviceId

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readPhoneNumber(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val raw = try {
                @Suppress("DEPRECATION")
                tm?.line1Number
            } catch (_: SecurityException) {
                null
            } catch (_: Throwable) {
                null
            }
            val cleaned = raw?.trim().orEmpty().replace(" ", "")
            if (cleaned.isBlank() || cleaned == "0000000000") "NoPhoneNumber" else cleaned
        } catch (t: Throwable) {
            Log.w(TAG, "readPhoneNumber", t)
            "NoPhoneNumber"
        }
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: "no-android-id"
        } catch (_: Throwable) {
            "no-android-id"
        }
    }

    private fun loadOrCreateId(
        context: Context,
        phone: String,
        androidId: String,
        model: String,
        lat: Double?,
        lon: Double?
    ): String {
        val prefs = BarkyPrefs.prefs(context)
        val cached = prefs.getString(KEY_CACHED_ID, null)
        if (!cached.isNullOrBlank()) return cached

        // Coords rounded so tiny GPS noise does not churn the id on first run only
        val latS = lat?.let { String.format(Locale.US, "%.3f", it) } ?: "NoLat"
        val lonS = lon?.let { String.format(Locale.US, "%.3f", it) } ?: "NoLon"
        val material = listOf(
            phone.ifBlank { "NoPhoneNumber" },
            androidId,
            model,
            Build.FINGERPRINT ?: "",
            latS,
            lonS,
            UUID.randomUUID().toString() // first-run salt so two clones of same image differ
        ).joinToString("|")
        val id = "bk_" + sha256(material).take(28)
        prefs.edit().putString(KEY_CACHED_ID, id).apply()
        Log.i(TAG, "Created deviceId=$id phone=$phone")
        return id
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }
}
