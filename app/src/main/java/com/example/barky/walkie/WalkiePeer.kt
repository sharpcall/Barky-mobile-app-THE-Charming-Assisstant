package com.example.barky.walkie

data class WalkiePeer(
    val serviceName: String,
    val host: String,
    /** UDP port for audio/control (NSD service port). */
    val port: Int,
    /** Dedicated TCP port for video stream. */
    val videoPort: Int = port
) {
    val displayName: String
        get() = serviceName.removePrefix(WalkieProtocol.SERVICE_NAME_PREFIX)
            .ifBlank { serviceName }
}
