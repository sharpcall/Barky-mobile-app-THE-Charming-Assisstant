package com.example.barky.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Client for [infra/barky-udp-relay].
 *
 * REG 0xF0, FWD 0xF1 (dest+src+payload), ACK 0xF2, DELIVER 0xF3 (src+payload from hub).
 */
object BarkyUdpRelay {
    private const val TAG = "BarkyUdpRelay"
    const val TYPE_REG: Byte = 0xF0.toByte()
    const val TYPE_FWD: Byte = 0xF1.toByte()
    const val TYPE_ACK: Byte = 0xF2.toByte()
    const val TYPE_DELIVER: Byte = 0xF3.toByte()

    @Volatile
    private var lastRegOkMs: Long = 0L

    data class Delivered(
        val srcDeviceId: String,
        val payload: ByteArray
    )

    fun lastRegisterOkAgeMs(): Long {
        val t = lastRegOkMs
        return if (t <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - t
    }

    fun buildRegister(deviceId: String): ByteArray {
        val id = deviceId.toByteArray(StandardCharsets.UTF_8)
        require(id.isNotEmpty() && id.size <= 120)
        val buf = ByteBuffer.allocate(2 + id.size)
        buf.put(TYPE_REG)
        buf.put(id.size.toByte())
        buf.put(id)
        return buf.array()
    }

    fun buildForward(destDeviceId: String, srcDeviceId: String, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val dest = destDeviceId.toByteArray(StandardCharsets.UTF_8)
        val src = srcDeviceId.toByteArray(StandardCharsets.UTF_8)
        require(dest.isNotEmpty() && dest.size <= 120)
        require(src.isNotEmpty() && src.size <= 120)
        require(length >= 0 && offset >= 0 && offset + length <= payload.size)
        val buf = ByteBuffer.allocate(3 + dest.size + src.size + length)
        buf.put(TYPE_FWD)
        buf.put(dest.size.toByte())
        buf.put(dest)
        buf.put(src.size.toByte())
        buf.put(src)
        buf.put(payload, offset, length)
        return buf.array()
    }

    /** Parse hub DELIVER; returns null if not a deliver frame. */
    fun parseDeliver(data: ByteArray): Delivered? {
        if (data.isEmpty() || data[0] != TYPE_DELIVER) return null
        if (data.size < 2) return null
        val srcLen = data[1].toInt() and 0xFF
        if (srcLen < 0 || data.size < 2 + srcLen + 1) return null
        val srcId = if (srcLen == 0) "" else String(data, 2, srcLen, StandardCharsets.UTF_8)
        val payload = data.copyOfRange(2 + srcLen, data.size)
        if (payload.isEmpty()) return null
        return Delivered(srcId, payload)
    }

    fun register(socket: DatagramSocket?, deviceId: String): Boolean {
        if (socket == null || socket.isClosed || deviceId.isBlank()) return false
        if (!BarkyHubConfig.relayEnabled) return false
        return try {
            val body = buildRegister(deviceId)
            val addr = InetAddress.getByName(BarkyHubConfig.HOST)
            socket.send(DatagramPacket(body, body.size, addr, BarkyHubConfig.RELAY_UDP_PORT))
            lastRegOkMs = System.currentTimeMillis()
            Log.i(TAG, "REG → ${BarkyHubConfig.HOST}:${BarkyHubConfig.RELAY_UDP_PORT} id=${deviceId.take(12)}…")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "REG failed: ${t.message}")
            false
        }
    }

    fun sendForward(
        socket: DatagramSocket?,
        destDeviceId: String,
        srcDeviceId: String,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        if (socket == null || socket.isClosed || destDeviceId.isBlank() || srcDeviceId.isBlank()) return false
        if (!BarkyHubConfig.relayEnabled) return false
        return try {
            val body = buildForward(destDeviceId, srcDeviceId, payload, offset, length)
            val addr = InetAddress.getByName(BarkyHubConfig.HOST)
            socket.send(DatagramPacket(body, body.size, addr, BarkyHubConfig.RELAY_UDP_PORT))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "FWD failed: ${t.message}")
            false
        }
    }

    fun noteAck() {
        lastRegOkMs = System.currentTimeMillis()
    }
}
