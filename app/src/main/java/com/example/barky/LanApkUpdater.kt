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
import com.example.barky.net.FirebasePeerDirectory
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.PeerEndpoint
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Download Barky APK and open the system installer.
 *
 * Sources (in order):
 * 1. Explicit host pref / LAN scan (Wi‑Fi) — [scripts/serve-apk.ps1]
 * 2. Firebase `_meta/app_update` HTTPS/HTTP URL (works on cellular if URL is public)
 * 3. Firebase peer directory: each device’s localIp/publicIp + updateHttpPort (8765)
 *    — works on cellular when NAT/port allows; same LAN always works
 *
 * Media calls stay P2P; only the APK bytes use HTTP.
 */
class LanApkUpdater(private val activity: Activity) {
    fun checkAndInstallFromLan() = checkAndInstall(preferLanScan = true)

    fun checkAndInstall(preferLanScan: Boolean = true) {
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
        thread(name = "barky-apk-update", isDaemon = true) {
            try {
                val myCode = try {
                    val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    @Suppress("DEPRECATION")
                    pi.versionCode
                } catch (_: Throwable) {
                    0
                }
                val url = findApkUrl(preferLanScan, myCode)
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
                Log.e(TAG, "apk update", t)
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

    private fun findApkUrl(preferLanScan: Boolean, myVersionCode: Int): String? {
        val candidates = mutableListOf<String>()
        // Explicit override
        val pref = BarkyPrefs.prefs(activity).getString(BarkyPrefs.KEY_LAN_UPDATE_HOST, null)
        if (!pref.isNullOrBlank()) {
            candidates += "http://$pref:$PORT/barky.apk"
            candidates += "http://$pref:$PORT/app-debug.apk"
        }
        // Firebase meta + peer directory (works without Wi‑Fi if path is reachable)
        if (FirebasePeerDirectory.isConfigured(activity)) {
            try {
                val metaUrl = FirebasePeerDirectory.fetchUpdateCatalogBlocking(activity)
                if (metaUrl != null) {
                    if (metaUrl.apkUrl.isNotBlank()) {
                        // Only pull Firebase-hosted APK when remote version is newer
                        if (metaUrl.minVersionCode > myVersionCode) {
                            candidates.add(0, metaUrl.apkUrl)
                            Log.i(
                                TAG,
                                "Firebase Storage catalog newer: remote=${metaUrl.minVersionCode} " +
                                    "local=$myVersionCode name=${metaUrl.versionName}"
                            )
                        } else {
                            Log.i(
                                TAG,
                                "Firebase catalog not newer remote=${metaUrl.minVersionCode} local=$myVersionCode"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "meta catalog", t)
            }
            try {
                val peers = FirebasePeerDirectory.listAllBlocking(activity)
                val selfNet = NetworkEndpoints.snapshot(activity)
                for (ep in peers) {
                    val port = ep.updateHttpPort.takeIf { it > 0 } ?: PORT
                    // Prefer LAN host when private; always try public too (cellular path)
                    val hosts = linkedSetOf<String>()
                    if (!ep.localIp.isNullOrBlank()) hosts += ep.localIp
                    if (!ep.publicIp.isNullOrBlank()) hosts += ep.publicIp
                    for (h in hosts) {
                        candidates += "http://$h:$port/barky.apk"
                        candidates += "http://$h:$port/app-debug.apk"
                        candidates += "http://$h:$port/"
                    }
                    Log.d(
                        TAG,
                        "peer update candidates ${ep.displayName} local=${ep.localIp} public=${ep.publicIp} port=$port selfWifi=${selfNet.onWifi}"
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "peer directory", t)
            }
        }
        if (preferLanScan) {
            for (base in localIpv4Bases()) {
                candidates += "http://$base.1:$PORT/barky.apk"
                candidates += "http://$base.1:$PORT/app-debug.apk"
                for (host in 2..20) {
                    candidates += "http://$base.$host:$PORT/barky.apk"
                }
            }
        }
        // Longer timeout for cellular / public IP
        for (u in candidates.distinct()) {
            val timeout = if (u.contains("192.168.") || u.contains("10.") || isPrivateHost(u)) 400
            else 2_500
            if (headOk(u, timeout)) {
                Log.i(TAG, "found $u")
                return u
            }
        }
        return null
    }

    private fun isPrivateHost(url: String): Boolean {
        return try {
            val host = URL(url).host ?: return false
            PeerEndpoint.isPrivateIp(host)
        } catch (_: Exception) {
            false
        }
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

    private fun headOk(url: String, timeoutMs: Int = 250): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..399
        } catch (_: Exception) {
            // Some servers reject HEAD — try GET range
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                val code = c.responseCode
                c.disconnect()
                code in 200..399
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun downloadApk(url: String): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "barky-update.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
        }
        c.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        c.disconnect()
        if (out.length() < 50_000) error("APK too small (${out.length()} B)")
        return out
    }

    private fun installApk(file: File) {
        // Arm BarkyService accessibility auto-click so the PackageInstaller prompt is confirmed hands-free.
        BarkyService.armAutoConfirmPackageInstall(activity)
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
