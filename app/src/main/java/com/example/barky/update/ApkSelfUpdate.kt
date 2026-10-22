package com.example.barky.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.example.barky.BarkyPrefs
import com.example.barky.BarkyService
import com.example.barky.net.FirebasePeerDirectory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks Firebase `_meta/app_update` for a newer [versionCode] and downloads the APK over
 * Wi‑Fi or cellular data. When [BarkyService] accessibility is enabled, it automatically
 * confirms the system PackageInstaller "Update"/"Install" prompt for hands-free updates.
 */
object ApkSelfUpdate {
    private const val TAG = "ApkSelfUpdate"
    private val io = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)

    /**
     * @param force if true, ignore last-check throttle
     * @param onResult optional UI toast callback on main thread (null = silent log only)
     */
    fun checkAsync(
        context: Context,
        force: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        val app = context.applicationContext
        if (!FirebasePeerDirectory.isConfigured(app)) {
            onResult?.invoke("Firebase not configured")
            return
        }
        if (!force) {
            val last = BarkyPrefs.prefs(app).getLong(KEY_LAST_CHECK_MS, 0L)
            if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) {
                Log.d(TAG, "skip check (throttled)")
                return
            }
        }
        if (!inFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                BarkyPrefs.prefs(app).edit()
                    .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                    .apply()
                val myCode = myVersionCode(app)
                val cat = FirebasePeerDirectory.fetchUpdateCatalogBlocking(app)
                if (cat == null || cat.apkUrl.isBlank()) {
                    Log.i(TAG, "no app_update catalog")
                    onResult?.invoke("No update catalog on Firebase")
                    return@execute
                }
                val remote = cat.minVersionCode
                Log.i(
                    TAG,
                    "APK_SELF_UPDATE_CHECK remoteVc=$remote name=${cat.versionName} myVc=$myCode url=${cat.apkUrl}"
                )
                if (remote <= myCode) {
                    onResult?.invoke("Already up to date (v$myCode)")
                    return@execute
                }
                onResult?.invoke("Downloading ${cat.versionName} (code $remote)…")
                val file = download(app, cat.apkUrl)
                Log.i(TAG, "APK_SELF_UPDATE_DOWNLOADED bytes=${file.length()} remoteVc=$remote — opening installer")
                openInstaller(app, file)
                onResult?.invoke("Install prompt opened for ${cat.versionName}")
            } catch (t: Throwable) {
                Log.w(TAG, "self-update failed", t)
                onResult?.invoke("Update failed: ${t.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun myVersionCode(context: Context): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            pi.versionCode
        } catch (_: Throwable) {
            0
        }
    }

    fun myVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "barky-firebase-update.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }
        c.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        c.disconnect()
        if (out.length() < 50_000L) error("APK too small (${out.length()} B)")
        return out
    }

    private fun openInstaller(context: Context, file: File) {
        // Arm BarkyService accessibility auto-click so the PackageInstaller prompt is confirmed hands-free.
        BarkyService.armAutoConfirmPackageInstall(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private const val KEY_LAST_CHECK_MS = "apk_self_update_last_check_ms"
    /** Throttle periodic background checks to 2 minutes so cellular/OTA updates apply rapidly. */
    private const val MIN_INTERVAL_MS = 2 * 60 * 1000L
}
