package com.example.barky.walkie

import android.content.Context
import android.util.Log
import com.example.barky.BarkyPrefs
import com.example.barky.net.BarkyHubConfig
import com.example.barky.net.FirebasePeerDirectory
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.PeerEndpoint

/**
 * Wi‑Fi vs cellular path selection for walkie control + media.
 *
 * File: [PeerReach.kt] (`com.example.barky.walkie.PeerReach`)
 *
 * When Pixel is on cellular and peer is on home Wi‑Fi, we must use the peer's
 * **public STUN IP:port**, not a stale 192.168.x.x. Also returns **dual targets**
 * so control packets (LISTEN / SEE / RING) fan out to both public and LAN when known.
 */
object PeerReach {
    private const val TAG = "PeerReach"

    data class Targets(
        /** Best single host for ongoing media / selection. */
        val primary: WalkiePeer,
        /** All hosts to burst control packets at (public first when off Wi‑Fi). */
        val all: List<WalkiePeer>,
        val usedPublic: Boolean,
        val reason: String
    )

    /**
     * Resolve reachable endpoints for a selected peer.
     *
     * Call **off main thread** (may hit Firebase + STUN state).
     *
     * @param liveLanHello true if HELLO arrived recently on [peer]'s private host
     * @param deviceId Firebase device id when known
     * @param forcePublic true to never pick a private IP (cellular caller)
     */
    fun resolve(
        context: Context,
        peer: WalkiePeer,
        deviceId: String?,
        liveLanHello: Boolean,
        forcePublic: Boolean = false
    ): Targets {
        val wifiOnly = BarkyPrefs.isWifiOnly(context)
        val selfOnWifi = NetworkEndpoints.isWifi(context)
        val mustPublic = forcePublic || (!selfOnWifi && !wifiOnly)

        var pubHost: String? = null
        var pubUdp = 0
        var pubVideo = 0
        var lanHost: String? = null
        var lanUdp = 0
        var lanVideo = 0
        var name = peer.displayName

        // Seed from current peer
        if (PeerEndpoint.isPrivateIp(peer.host)) {
            lanHost = peer.host
            lanUdp = peer.port
            lanVideo = peer.videoPort
        } else {
            pubHost = peer.host
            pubUdp = peer.port
            pubVideo = peer.videoPort
        }

        // Fresh Firebase endpoints (critical after Wi‑Fi → cell).
        // When we already have a live LAN HELLO on Wi‑Fi (!mustPublic && liveLanHello),
        // never overwrite the live HELLO UDP/video ports with a stale Firebase heartbeat port
        // from before an app restart.
        val hasLiveLanPeer = !mustPublic && liveLanHello &&
            PeerEndpoint.isPrivateIp(peer.host) && peer.port > 0
        if (!hasLiveLanPeer && !wifiOnly && FirebasePeerDirectory.isConfigured(context)) {
            val ep = resolveEndpoint(context, deviceId, peer.host)
            if (ep != null) {
                name = com.example.barky.net.ContactLabels.fromEndpoint(ep).ifBlank { name }
                if (!ep.publicIp.isNullOrBlank() && ep.publicUdpPort > 0) {
                    pubHost = ep.publicIp
                    pubUdp = ep.publicUdpPort
                    pubVideo = when {
                        ep.localVideoPort > 0 -> ep.localVideoPort
                        else -> ep.publicUdpPort
                    }
                }
                if (!ep.localIp.isNullOrBlank() && ep.localUdpPort > 0 &&
                    (lanHost.isNullOrBlank() || !liveLanHello)
                ) {
                    lanHost = ep.localIp
                    lanUdp = ep.localUdpPort
                    lanVideo = if (ep.localVideoPort > 0) ep.localVideoPort else ep.localUdpPort
                }
                Log.i(
                    TAG,
                    "firebase ep id=${ep.deviceId.take(12)} lan=$lanHost:$lanUdp " +
                        "pub=$pubHost:$pubUdp selfWifi=$selfOnWifi liveLan=$liveLanHello"
                )
            }
        }

        // Avoid STUN inside resolve (caller already refreshed). Local IP + Wi‑Fi only.
        val selfSnap = NetworkEndpoints.Snapshot(
            localIp = NetworkEndpoints.findLocalIpv4(),
            publicIp = null,
            publicUdpPort = 0,
            onWifi = selfOnWifi,
            wifiSsid = null
        )

        // When local subnets do not match, LAN direct connection fails; force public STUN/hub path.
        val sameSubnet = NetworkEndpoints.sameLocalSubnet(selfSnap.localIp, lanHost)
        val mustPublicResolved = mustPublic || (!sameSubnet && !lanHost.isNullOrBlank())

        // Only prefer LAN with a live HELLO and matching local subnet (/24)
        val preferLan = !mustPublicResolved &&
            NetworkEndpoints.preferLanPath(
                selfSnap,
                lanHost,
                null,
                liveLanHello = liveLanHello && !lanHost.isNullOrBlank()
            )

        val primary: WalkiePeer = when {
            preferLan && !lanHost.isNullOrBlank() && lanUdp > 0 ->
                WalkiePeer("Barky-$name", lanHost!!, lanUdp, if (lanVideo > 0) lanVideo else lanUdp)
            !pubHost.isNullOrBlank() && pubUdp > 0 ->
                WalkiePeer("Barky-$name", pubHost!!, pubUdp, if (pubVideo > 0) pubVideo else pubUdp)
            !deviceId.isNullOrBlank() && (mustPublicResolved || !preferLan) ->
                WalkiePeer("Barky-$name", "id:$deviceId", BarkyHubConfig.RELAY_UDP_PORT, BarkyHubConfig.VIDEO_TCP_PORT)
            !lanHost.isNullOrBlank() && lanUdp > 0 && !mustPublicResolved ->
                WalkiePeer("Barky-$name", lanHost!!, lanUdp, if (lanVideo > 0) lanVideo else lanUdp)
            else -> peer
        }

        // Fan-out list: when off Wi‑Fi or subnets do not match, put public/hub first
        val ordered = LinkedHashSet<WalkiePeer>()
        if (mustPublicResolved || !preferLan) {
            if (!pubHost.isNullOrBlank() && pubUdp > 0) {
                ordered.add(WalkiePeer("Barky-$name", pubHost!!, pubUdp, if (pubVideo > 0) pubVideo else pubUdp))
            }
            if (!deviceId.isNullOrBlank()) {
                ordered.add(WalkiePeer("Barky-$name", "id:$deviceId", BarkyHubConfig.RELAY_UDP_PORT, BarkyHubConfig.VIDEO_TCP_PORT))
            }
            // Secondary: still try LAN only if caller is on Wi‑Fi and subnets match
            if (selfOnWifi && sameSubnet && !lanHost.isNullOrBlank() && lanUdp > 0) {
                ordered.add(WalkiePeer("Barky-$name", lanHost!!, lanUdp, if (lanVideo > 0) lanVideo else lanUdp))
            }
        } else {
            if (!lanHost.isNullOrBlank() && lanUdp > 0) {
                ordered.add(WalkiePeer("Barky-$name", lanHost!!, lanUdp, if (lanVideo > 0) lanVideo else lanUdp))
            }
            if (!pubHost.isNullOrBlank() && pubUdp > 0) {
                ordered.add(WalkiePeer("Barky-$name", pubHost!!, pubUdp, if (pubVideo > 0) pubVideo else pubUdp))
            }
        }
        if (ordered.isEmpty()) ordered.add(primary)

        // Guard: never primary-target private IP from cellular or across mismatched subnets
        val safePrimary = if (mustPublicResolved && PeerEndpoint.isPrivateIp(primary.host)) {
            when {
                !pubHost.isNullOrBlank() && pubUdp > 0 ->
                    WalkiePeer("Barky-$name", pubHost!!, pubUdp, if (pubVideo > 0) pubVideo else pubUdp)
                !deviceId.isNullOrBlank() ->
                    WalkiePeer("Barky-$name", "id:$deviceId", BarkyHubConfig.RELAY_UDP_PORT, BarkyHubConfig.VIDEO_TCP_PORT)
                else -> primary
            }
        } else {
            primary
        }
        if (ordered.none { it.host == safePrimary.host && it.port == safePrimary.port }) {
            ordered.add(safePrimary)
        }

        // Drop self LAN targets (same-NAT hairpin → own camera/SCRN)
        val selfLocal = NetworkEndpoints.findLocalIpv4()
        val cleaned = ordered.filter { p ->
            if (!selfLocal.isNullOrBlank() && p.host == selfLocal) {
                Log.w(TAG, "drop self LAN target ${p.host}:${p.port}")
                false
            } else {
                true
            }
        }
        val primaryOut = when {
            cleaned.any { it.host == safePrimary.host && it.port == safePrimary.port } -> safePrimary
            cleaned.isNotEmpty() -> cleaned.first()
            else -> safePrimary
        }
        val usedPublic = !PeerEndpoint.isPrivateIp(primaryOut.host)
        val reason = buildString {
            append(if (mustPublic) "forcePublic" else if (preferLan) "preferLan" else "preferPublic")
            append(" selfWifi=").append(selfOnWifi)
            append(" liveLan=").append(liveLanHello)
            append(" primary=").append(primaryOut.host).append(':').append(primaryOut.port)
            append(" alts=").append(cleaned.size)
        }
        Log.i(TAG, "resolve $reason")
        return Targets(
            primary = primaryOut,
            all = cleaned.ifEmpty { listOf(primaryOut) },
            usedPublic = usedPublic,
            reason = reason
        )
    }

    private fun resolveEndpoint(
        context: Context,
        deviceId: String?,
        host: String
    ): PeerEndpoint? {
        if (!deviceId.isNullOrBlank()) {
            FirebasePeerDirectory.lookupByDeviceIdBlocking(context, deviceId)?.let { return it }
        }
        val all = FirebasePeerDirectory.listAllBlocking(context)
        return all.firstOrNull {
            it.localIp == host || it.publicIp == host || it.deviceId == deviceId
        }
    }
}
