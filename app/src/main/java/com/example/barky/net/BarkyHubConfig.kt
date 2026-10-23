package com.example.barky.net

/**
 * Oracle media hub (trial credit VM) used for cellular when P2P hole-punch fails.
 * STUN via coturn; custom UDP relay for Barky walkie packets (not WebRTC yet).
 */
object BarkyHubConfig {
    /** Public IP of barky-mediasoup-hub (us-ashburn-1). */
    const val HOST: String = "157.151.137.122"

    /** Barky custom UDP relay port (infra/barky-udp-relay). */
    const val RELAY_UDP_PORT: Int = 39000

    /**
     * Reliable TCP video relay for cellular/public HD (H.264).
     * Same host as UDP relay; app opens outbound only (works behind CGNAT).
     */
    const val VIDEO_TCP_PORT: Int = 39002

    /** Coturn STUN (same host). Prefer this before public STUN farms. */
    const val STUN_PORT: Int = 3478

    /** Kill-switch if hub is down — when false, direct P2P STUN hole-punch is used without timeout stalls. */
    @Volatile
    var relayEnabled: Boolean = false

    fun isHubAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return host == HOST || host.endsWith(".sslip.io")
    }
}
