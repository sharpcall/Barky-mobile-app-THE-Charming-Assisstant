package com.example.barky.walkie

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

/**
 * Minimal STUN Binding request against public STUN servers.
 * Used only to learn this device's **public** UDP address — not an app backend.
 */
object StunClient {
    data class MappedAddress(val host: String, val port: Int)

    private const val TAG = "StunClient"
    private val SERVERS = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun.cloudflare.com" to 3478
    )

    /**
     * Query STUN using an existing bound socket when possible so the mapped port
     * matches the walkie UDP socket (required for hole punching).
     */
    fun discoverMappedAddress(preferSocket: DatagramSocket? = null): MappedAddress? {
        val owned: DatagramSocket
        val reuse: Boolean
        if (preferSocket != null && !preferSocket.isClosed) {
            owned = preferSocket
            reuse = true
        } else {
            owned = DatagramSocket()
            reuse = false
        }
        val prevTimeout = try {
            owned.soTimeout
        } catch (_: Exception) {
            1000
        }
        try {
            owned.soTimeout = 1500
            for ((host, port) in SERVERS) {
                try {
                    val result = bindingRequest(owned, host, port)
                    if (result != null) {
                        Log.i(TAG, "STUN mapped ${result.host}:${result.port} via $host:$port")
                        return result
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "STUN $host failed: ${t.message}")
                }
            }
        } finally {
            try {
                owned.soTimeout = prevTimeout
            } catch (_: Exception) {
            }
            if (!reuse) {
                try {
                    owned.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun bindingRequest(sock: DatagramSocket, stunHost: String, stunPort: Int): MappedAddress? {
        val txId = ByteArray(12).also { ThreadLocalRandom.current().nextBytes(it) }
        // STUN header: type=Binding Request 0x0001, length=0, magic cookie, tx id
        val req = ByteBuffer.allocate(20)
        req.putShort(0x0001)
        req.putShort(0)
        req.putInt(0x2112A442)
        req.put(txId)
        val packet = DatagramPacket(req.array(), 20, InetAddress.getByName(stunHost), stunPort)
        sock.send(packet)

        val buf = ByteArray(512)
        val resp = DatagramPacket(buf, buf.size)
        sock.receive(resp)
        if (resp.length < 20) return null
        val bb = ByteBuffer.wrap(buf, 0, resp.length)
        val type = bb.short.toInt() and 0xFFFF
        val length = bb.short.toInt() and 0xFFFF
        val magic = bb.int
        if (magic != 0x2112A442) return null
        val rTx = ByteArray(12)
        bb.get(rTx)
        if (!rTx.contentEquals(txId)) return null
        // Success response 0x0101
        if (type != 0x0101) return null
        var remaining = length
        while (remaining >= 4 && bb.remaining() >= 4) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            remaining -= 4
            if (bb.remaining() < attrLen) break
            val attrVal = ByteArray(attrLen)
            bb.get(attrVal)
            remaining -= attrLen
            // padding to 4 bytes
            val pad = (4 - (attrLen % 4)) % 4
            if (pad > 0 && bb.remaining() >= pad) {
                bb.position(bb.position() + pad)
                remaining -= pad
            }
            // XOR-MAPPED-ADDRESS 0x0020 or MAPPED-ADDRESS 0x0001
            if (attrType == 0x0020 || attrType == 0x0001) {
                val mapped = parseAddress(attrVal, xor = attrType == 0x0020, txId = rTx)
                if (mapped != null) return mapped
            }
        }
        return null
    }

    private fun parseAddress(value: ByteArray, xor: Boolean, txId: ByteArray): MappedAddress? {
        if (value.size < 8) return null
        val family = value[1].toInt() and 0xFF
        var port = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        if (xor) port = port xor 0x2112
        return when (family) {
            0x01 -> { // IPv4
                if (value.size < 8) return null
                val ip = ByteArray(4)
                for (i in 0 until 4) {
                    var b = value[4 + i].toInt() and 0xFF
                    if (xor) {
                        val magic = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                        b = b xor (magic[i].toInt() and 0xFF)
                    }
                    ip[i] = b.toByte()
                }
                val host = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                    "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
                MappedAddress(host, port)
            }
            else -> null // skip IPv6 for now
        }
    }
}
