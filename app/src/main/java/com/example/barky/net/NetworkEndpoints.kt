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
    /**
     * Checks if two IPv4 addresses share the exact same local /24 subnet.
     * When local subnets do not match, LAN routing fails across routers/hotspots;
     * communication must route via public STUN / BarkyHub relay.
     */
    fun sameLocalSubnet(ipA: String?, ipB: String?): Boolean {
        if (ipA.isNullOrBlank() || ipB.isNullOrBlank()) return false
        if (!PeerEndpoint.isPrivateIp(ipA) || !PeerEndpoint.isPrivateIp(ipB)) return false
        val aParts = ipA.split(".")
        val bParts = ipB.split(".")
        if (aParts.size != 4 || bParts.size != 4) return false
        return aParts[0] == bParts[0] && aParts[1] == bParts[1] && aParts[2] == bParts[2]
    }

    /**
     * Prefer private LAN IP when both devices share the exact same local subnet (/24)
     * and a live HELLO was verified. If local subnets do not match, always returns false
     * so public STUN/hub relay path is used.
     *
     * @param liveLanHello true only when we recently received HELLO from the peer's private IP.
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
        // Check all local IPv4 interfaces for an exact /24 subnet match
        val candidates = (listOfNotNull(self.localIp) + findAllLocalIpv4()).distinct()
        for (a in candidates) {
            if (sameLocalSubnet(a, b)) {
                return true
            }
        }
        // Subnets do not match — always use public STUN / Hub path
        return false
    }
}
