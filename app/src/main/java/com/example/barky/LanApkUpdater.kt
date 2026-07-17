package com.example.barky

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Download Barky APK from a PC on the same Wi‑Fi (scripts/serve-apk.ps1)
 * and open the system package installer — no wireless debugging required
 * for *phone-side* install once the server is up.
 */
class LanApkUpdater(private val activity: Activity) {
    fun checkAndInstallFromLan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, R.string.lan_update_need_install_perm, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Exception) {
            }
            return
        }
        Toast.makeText(activity, R.string.lan_update_searching, Toast.LENGTH_SHORT).show()
        thread(name = "barky-lan-update", isDaemon = true) {
            try {
                val url = findApkUrl()
                if (url == null) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, R.string.lan_update_none, Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                Log.i(TAG, "Downloading $url")
                val file = downloadApk(url)
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.lan_update_found, Toast.LENGTH_SHORT).show()
                    installApk(file)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "lan update", t)
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.lan_update_fail, t.message ?: "error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun findApkUrl(): String? {
        val candidates = mutableListOf<String>()
        // Explicit override (set by developer / prefs)
        val pref = BarkyPrefs.prefs(activity).getString(BarkyPrefs.KEY_LAN_UPDATE_HOST, null)
        if (!pref.isNullOrBlank()) {
            candidates += "http://$pref:$PORT/barky.apk"
            candidates += "http://$pref:$PORT/app-debug.apk"
        }
        // Same /24 as our Wi‑Fi: probe .1 and a few common PC addresses + gateway
        for (base in localIpv4Bases()) {
            candidates += "http://$base.1:$PORT/barky.apk"
            candidates += "http://$base.1:$PORT/app-debug.apk"
            // Short scan of common DHCP range (.2–.20), 250ms HEAD each
            for (host in 2..20) {
                candidates += "http://$base.$host:$PORT/barky.apk"
            }
        }
        for (u in candidates.distinct()) {
            if (headOk(u)) return u
        }
        return null
    }

    private fun localIpv4Bases(): List<String> {
        val bases = mutableSetOf<String>()
        try {
            val wm = activity.applicationContext.getSystemService(Activity.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val a = ip and 0xFF
                val b = ip shr 8 and 0xFF
                val c = ip shr 16 and 0xFF
                bases += "$a.$b.$c"
            }
        } catch (_: Exception) {
        }
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val p = addr.hostAddress?.split(".") ?: continue
                        if (p.size == 4) bases += "${p[0]}.${p[1]}.${p[2]}"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return bases.toList()
    }

    private fun headOk(url: String): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 250
                readTimeout = 250
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadApk(url: String): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "barky-update.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 60_000
        }
        c.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        c.disconnect()
        if (out.length() < 50_000) error("APK too small (${out.length()} B)")
        return out
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    companion object {
        private const val TAG = "LanApkUpdater"
        const val PORT = 8765
    }
}
