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

    /**
     * Primary local IPv4 address, preferring Wi‑Fi (`wlan*` / `192.168.*`) over cellular (`rmnet*`)
     * so dual-active SIM + Wi‑Fi phones (e.g. Samsung S20) always match LAN peers on Wi‑Fi.
     */
    fun findLocalIpv4(): String? = findAllLocalIpv4().firstOrNull()

    /**
     * All non-loopback IPv4s (Wi‑Fi first, then other interfaces) — needed to hide self in
     * Contacts and to match LAN subnets accurately when both SIM data and Wi‑Fi are active.
     */
    fun findAllLocalIpv4(): List<String> {
        val wifiAddrs = LinkedHashSet<String>()
        val otherAddrs = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val ifName = ni.name?.lowercase().orEmpty()
                val isWifiIface = ifName.startsWith("wlan") || ifName.startsWith("wifi") ||
                    ifName.startsWith("ap")
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        if (isWifiIface || host.startsWith("192.168.")) {
                            wifiAddrs.add(host)
                        } else {
                            otherAddrs.add(host)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findAllLocalIpv4", t)
        }
        return (wifiAddrs + otherAddrs).toList()
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
        val b = peerLocalIp
        if (b.isNullOrBlank() || !PeerEndpoint.isPrivateIp(b)) return false
        // Stale Firebase localIp without live HELLO → do not force dead LAN route
        if (!liveLanHello) return false
        val bParts = b.split(".")
        // Check all local IPv4 interfaces (in case SIM rmnet is active alongside wlan0)
        val candidates = (listOfNotNull(self.localIp) + findAllLocalIpv4()).distinct()
        for (a in candidates) {
            if (!PeerEndpoint.isPrivateIp(a)) continue
            val aParts = a.split(".")
            if (aParts.size == 4 && bParts.size == 4) {
                if (aParts[0] == bParts[0] && aParts[1] == bParts[1] && aParts[2] == bParts[2]) {
                    return true
                }
                // Same /16 + live HELLO is enough on home routers
                if (aParts[0] == bParts[0] && aParts[1] == bParts[1]) return true
            }
        }
        if (!self.wifiSsid.isNullOrBlank() && self.wifiSsid == peerWifiHint) return true
        return false
    }
}
