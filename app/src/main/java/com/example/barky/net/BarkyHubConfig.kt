package com.example.barky.net

/**
 * Barky cloud media relay on Oracle Cloud Always Free VM (150.136.164.242).
 * 10 TB/month free bandwidth forever. Provides UDP relay (39000), TCP video relay (39002), and STUN (3478).
 */
object BarkyHubConfig {
    /** Public IP of Oracle Always Free relay VM (us-ashburn-1). */
    const val HOST: String = "150.136.164.242"

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
