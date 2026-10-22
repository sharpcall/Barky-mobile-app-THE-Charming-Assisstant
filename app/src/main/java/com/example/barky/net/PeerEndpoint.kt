package com.example.barky.net

/**
 * How to reach a registered device for walkie.
 * Prefer [localIp] when both ends appear to share a LAN; else [publicIp].
 */
data class PeerEndpoint(
    val deviceId: String,
    val phoneNumber: String,
    val displayName: String,
    /** Build.MODEL */
    val phoneModel: String = "",
    /** Build.MANUFACTURER */
    val manufacturer: String = "",
    /** e.g. "14" from Build.VERSION.RELEASE */
    val androidVersion: String = "",
    /** Build.VERSION.SDK_INT */
    val androidSdk: Int = 0,
    val localIp: String?,
    val localUdpPort: Int,
    val localVideoPort: Int,
    val publicIp: String?,
    val publicUdpPort: Int,
    val wifiHint: String?,
    val lat: Double?,
    val lon: Double?,
    val updatedAtMs: Long,
    val appVersion: String,
    /** HTTP port serving APK for peer/internet updates (0 = not serving). */
    val updateHttpPort: Int = 0,
    val versionCode: Int = 0
) {
    fun preferredHost(preferLan: Boolean): String? {
        if (preferLan && !localIp.isNullOrBlank() && isPrivateIp(localIp)) return localIp
        if (!publicIp.isNullOrBlank()) return publicIp
        if (!localIp.isNullOrBlank()) return localIp
        return null
    }

    fun preferredUdpPort(preferLan: Boolean): Int {
        if (preferLan && !localIp.isNullOrBlank() && isPrivateIp(localIp) && localUdpPort > 0) {
            return localUdpPort
        }
        if (publicUdpPort > 0) return publicUdpPort
        return localUdpPort
    }

    companion object {
        fun isPrivateIp(ip: String): Boolean {
            if (ip.startsWith("10.")) return true
            if (ip.startsWith("192.168.")) return true
            if (ip.startsWith("172.")) {
                val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
                return second in 16..31
            }
            return false
        }
    }
}
