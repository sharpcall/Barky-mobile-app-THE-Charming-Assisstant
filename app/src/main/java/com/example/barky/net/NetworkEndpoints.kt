package com.example.barky.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.example.barky.walkie.StunClient
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Discovers **local Wi‑Fi IPv4** and **public mapped address** (STUN).
 * Used for Firebase registration and path selection (LAN first, then public IP).
 */
object NetworkEndpoints {
    private const val TAG = "NetworkEndpoints"

    data class Snapshot(
        val localIp: String?,
        val publicIp: String?,
        val publicUdpPort: Int,
        val onWifi: Boolean,
        val wifiSsid: String?
    )

    fun snapshot(context: Context, walkieSocket: DatagramSocket? = null): Snapshot {
        val onWifi = isWifi(context)
        val local = findLocalIpv4()
        // Never STUN on the shared walkie socket from here — concurrent STUN drops
        // LISTEN/SEE/RING hub DELIVER packets. Prefer last known prefs / no STUN.
        // [walkieSocket] kept for API compat; unused for STUN.
        @Suppress("UNUSED_PARAMETER")
        val unusedWalkie = walkieSocket
        val prefIp = try {
            com.example.barky.BarkyPrefs.lastStunPublicIp(context)
        } catch (_: Throwable) {
            null
        }
        val prefPort = try {
            com.example.barky.BarkyPrefs.lastStunPublicUdp(context)
        } catch (_: Throwable) {
            0
        }
        val ssid = if (onWifi) readSsid(context) else null
        return Snapshot(
            localIp = local,
            publicIp = prefIp,
            publicUdpPort = prefPort,
            onWifi = onWifi,
            wifiSsid = ssid
        )
    }

    fun isWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            false
        }
    }

    fun findLocalIpv4(): String? = findAllLocalIpv4().firstOrNull()

    /** All non-loopback IPv4s (Wi‑Fi + cellular) — needed to hide self in Contacts. */
    fun findAllLocalIpv4(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        out.add(host)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findAllLocalIpv4", t)
        }
        return out.toList()
    }

    @Suppress("DEPRECATION")
    private fun readSsid(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"')
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Prefer LAN host when both sides report private IPs (optionally same wifi hint).
     * Otherwise use public STUN mapping.
     *
     * @param liveLanHello true only when we recently received HELLO from the peer's private IP.
     *   Without a live HELLO, never stick to a stale LAN address after Wi‑Fi↔cellular moves.
     */
    fun preferLanPath(
        self: Snapshot,
        peerLocalIp: String?,
        peerWifiHint: String?,
        liveLanHello: Boolean = false
    ): Boolean {
        // Cellular / no Wi‑Fi: always public path (STUN)
        if (!self.onWifi) return false
        val a = self.localIp
        val b = peerLocalIp
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (!PeerEndpoint.isPrivateIp(a) || !PeerEndpoint.isPrivateIp(b)) return false
        // Stale Firebase localIp without live HELLO → do not force dead LAN route
        if (!liveLanHello) return false
        // Same /16 or /24 family on home LAN is a strong hint
        val aParts = a.split(".")
        val bParts = b.split(".")
        if (aParts.size == 4 && bParts.size == 4) {
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1] && aParts[2] == bParts[2]) {
                return true
            }
            // Same /16 + live HELLO is enough on home routers
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1]) return true
        }
        if (!self.wifiSsid.isNullOrBlank() && self.wifiSsid == peerWifiHint) return true
        return false
    }
}
