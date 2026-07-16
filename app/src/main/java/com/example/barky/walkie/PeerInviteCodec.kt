package com.example.barky.walkie

import android.util.Base64
import java.nio.ByteBuffer

/**
 * Compact shareable invite for zero-app-server internet pairing.
 *
 * Format (Base64 URL-safe, no padding), payload:
 *   magic "BK" (2) | version (1) | ipv4 (4) | udpPort (2) | videoPort (2)  = 11 bytes
 *
 * Users exchange this string out-of-band (SMS, chat, voice). No central Barky server.
 */
object PeerInviteCodec {
    private const val MAGIC0 = 'B'.code.toByte()
    private const val MAGIC1 = 'K'.code.toByte()
    private const val VERSION: Byte = 1

    data class Invite(
        val host: String,
        val udpPort: Int,
        val videoPort: Int
    )

    fun encode(host: String, udpPort: Int, videoPort: Int): String? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val ip = ByteArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            ip[i] = n.toByte()
        }
        if (udpPort !in 1..65535 || videoPort !in 1..65535) return null
        val buf = ByteBuffer.allocate(11)
        buf.put(MAGIC0)
        buf.put(MAGIC1)
        buf.put(VERSION)
        buf.put(ip)
        buf.putShort(udpPort.toShort())
        buf.putShort(videoPort.toShort())
        return Base64.encodeToString(
            buf.array(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun decode(code: String): Invite? {
        val cleaned = code.trim().replace("\\s".toRegex(), "")
        if (cleaned.isEmpty()) return null
        return try {
            val raw = Base64.decode(
                cleaned,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            if (raw.size < 11) return null
            val buf = ByteBuffer.wrap(raw)
            if (buf.get() != MAGIC0 || buf.get() != MAGIC1) return null
            val ver = buf.get()
            if (ver != VERSION) return null
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            val b3 = buf.get().toInt() and 0xFF
            val udp = buf.short.toInt() and 0xFFFF
            val video = buf.short.toInt() and 0xFFFF
            if (udp == 0 || video == 0) return null
            Invite("$b0.$b1.$b2.$b3", udp, video)
        } catch (_: Exception) {
            null
        }
    }
}
