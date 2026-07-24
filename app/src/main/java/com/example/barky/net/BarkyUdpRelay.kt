package com.example.barky.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Client for [infra/barky-udp-relay]: register deviceId, forward raw walkie UDP to a peer id.
 */
object BarkyUdpRelay {
    private const val TAG = "BarkyUdpRelay"
    private const val TYPE_REG: Byte = 0xF0.toByte()
    private const val TYPE_FWD: Byte = 0xF1.toByte()

    @Volatile
    private var lastRegOkMs: Long = 0L

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

    fun buildForward(destDeviceId: String, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val id = destDeviceId.toByteArray(StandardCharsets.UTF_8)
        require(id.isNotEmpty() && id.size <= 120)
        require(length >= 0 && offset >= 0 && offset + length <= payload.size)
        val buf = ByteBuffer.allocate(2 + id.size + length)
        buf.put(TYPE_FWD)
        buf.put(id.size.toByte())
        buf.put(id)
        buf.put(payload, offset, length)
        return buf.array()
    }

    /**
     * Send REG on the walkie socket so the hub maps our public path to this socket.
     */
    fun register(socket: DatagramSocket?, deviceId: String): Boolean {
        if (socket == null || socket.isClosed || deviceId.isBlank()) return false
        if (!BarkyHubConfig.relayEnabled) return false
        return try {
            val body = buildRegister(deviceId)
            val addr = InetAddress.getByName(BarkyHubConfig.HOST)
            val pkt = DatagramPacket(body, body.size, addr, BarkyHubConfig.RELAY_UDP_PORT)
            socket.send(pkt)
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
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        if (socket == null || socket.isClosed || destDeviceId.isBlank()) return false
        if (!BarkyHubConfig.relayEnabled) return false
        return try {
            val body = buildForward(destDeviceId, payload, offset, length)
            val addr = InetAddress.getByName(BarkyHubConfig.HOST)
            val pkt = DatagramPacket(body, body.size, addr, BarkyHubConfig.RELAY_UDP_PORT)
            socket.send(pkt)
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
