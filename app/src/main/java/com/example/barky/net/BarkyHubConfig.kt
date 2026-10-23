package com.example.barky.net

/**
 * Barky cloud media relay (Google Cloud Free Tier e2-micro VM).
 * Bridges cellular and mismatched Wi-Fi subnets via UDP relay (39000), TCP video relay (39002), and STUN (3478).
 */
object BarkyHubConfig {
    /** Public IP of barky-relay VM (us-central1). */
    const val HOST: String = "34.45.33.226"

    /** Barky custom UDP relay port (infra/barky-udp-relay). */
    const val RELAY_UDP_PORT: Int = 39000

    /**
     * Reliable TCP video relay for cellular/public HD (H.264).
     * Same host as UDP relay; app opens outbound only (works behind CGNAT).
     */
    const val VIDEO_TCP_PORT: Int = 39002

    /** Coturn STUN (same host). */
    const val STUN_PORT: Int = 3478

    /** Active cloud relay enabled. */
    @Volatile
    var relayEnabled: Boolean = true

    fun isHubAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return host == HOST || host.endsWith(".sslip.io")
    }
}
