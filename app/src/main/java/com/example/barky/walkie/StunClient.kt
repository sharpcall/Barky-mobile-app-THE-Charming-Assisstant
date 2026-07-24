package com.example.barky.walkie

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

/**
 * Minimal STUN Binding request against public STUN servers.
 * Used only to learn this device's **public** UDP address — not an app backend.
 *
 * Cellular/CGNAT: fail-fast first pass, then one longer pass. Prefer short exclusive
 * socket time so walkie RX is not blocked for tens of seconds.
 */
object StunClient {
    data class MappedAddress(val host: String, val port: Int)

    private const val TAG = "StunClient"
    private val SERVERS_FAST = listOf(
        // Barky Oracle hub coturn first (stable mapping for Firebase + hole-punch)
        "157.151.137.122" to 3478,
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun.cloudflare.com" to 3478
    )
    private val SERVERS_FULL = listOf(
        "157.151.137.122" to 3478,
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun2.l.google.com" to 19302,
        "stun3.l.google.com" to 19302,
        "stun.cloudflare.com" to 3478,
        "stun.nextcloud.com" to 3478
    )

    /**
     * Query STUN using an existing bound socket when possible so the mapped port
     * matches the walkie UDP socket (required for hole punching).
     *
     * [quick] = cellular-friendly: ~3–5s max instead of multi-server multi-second stalls.
     */
    fun discoverMappedAddress(
        preferSocket: DatagramSocket? = null,
        quick: Boolean = false
    ): MappedAddress? {
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
            // Pass 1: fast servers. Cellular needs longer waits (CGNAT + lossy first UDP).
            val t1 = if (quick) 1_200 else 1_800
            owned.soTimeout = t1
            for ((host, port) in SERVERS_FAST) {
                try {
                    val result = bindingRequest(owned, host, port, t1)
                    if (result != null) {
                        Log.i(TAG, "STUN mapped ${result.host}:${result.port} via $host:$port (fast)")
                        return result
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "STUN fast $host: ${t.message}")
                }
            }
            if (quick) {
                Log.w(TAG, "STUN quick pass failed")
                return null
            }
            // Pass 2: full list, longer timeout (cellular settle)
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return null
            }
            val t2 = 3_500
            owned.soTimeout = t2
            for ((host, port) in SERVERS_FULL) {
                try {
                    val result = bindingRequest(owned, host, port, t2)
                    if (result != null) {
                        Log.i(TAG, "STUN mapped ${result.host}:${result.port} via $host:$port (full)")
                        return result
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "STUN full $host: ${t.message}")
                }
            }
            Log.w(TAG, "STUN failed all servers")
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

    private fun bindingRequest(
        sock: DatagramSocket,
        stunHost: String,
        stunPort: Int,
        timeoutMs: Int
    ): MappedAddress? {
        val txId = ByteArray(12).also { ThreadLocalRandom.current().nextBytes(it) }
        val req = ByteBuffer.allocate(20)
        req.putShort(0x0001)
        req.putShort(0)
        req.putInt(0x2112A442)
        req.put(txId)
        val packet = DatagramPacket(req.array(), 20, InetAddress.getByName(stunHost), stunPort)
        // Triple-send — cellular/CGNAT often drops the first UDP
        repeat(3) {
            try {
                sock.send(packet)
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(resp)
            } catch (_: java.net.SocketTimeoutException) {
                // Keep waiting until overall deadline (cellular RTT can be high)
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                try {
                    sock.soTimeout = left.toInt().coerceAtLeast(50).coerceAtMost(timeoutMs)
                } catch (_: Exception) {
                }
                continue
            }
            if (resp.length < 20) continue
            // Ignore non-STUN packets that may land on the shared walkie socket
            val bb = ByteBuffer.wrap(buf, 0, resp.length)
            val type = bb.short.toInt() and 0xFFFF
            if (type != 0x0101) continue
            bb.short // length
            val magic = bb.int
            if (magic != 0x2112A442) continue
            val rtx = ByteArray(12)
            bb.get(rtx)
            if (!rtx.contentEquals(txId)) continue
            return parseMapped(bb, magic, rtx)
        }
        return null
    }

    private fun parseMapped(bb: ByteBuffer, magic: Int, txId: ByteArray): MappedAddress? {
        while (bb.remaining() >= 4) {
            val atype = bb.short.toInt() and 0xFFFF
            val alen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < alen) return null
            val end = bb.position() + alen
            // XOR-MAPPED-ADDRESS 0x0020 or MAPPED-ADDRESS 0x0001
            if ((atype == 0x0020 || atype == 0x0001) && alen >= 8) {
                bb.get() // reserved
                val family = bb.get().toInt() and 0xFF
                var port = bb.short.toInt() and 0xFFFF
                if (atype == 0x0020) port = port xor ((magic ushr 16) and 0xFFFF)
                if (family == 0x01 && bb.remaining() >= 4) {
                    val ipBytes = ByteArray(4)
                    bb.get(ipBytes)
                    if (atype == 0x0020) {
                        // XOR with magic cookie 0x2112A442
                        val mc = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                        for (i in 0..3) {
                            ipBytes[i] =
                                (ipBytes[i].toInt() xor (mc[i].toInt() and 0xFF)).toByte()
                        }
                    }
                    val host = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    return MappedAddress(host, port)
                }
            }
            bb.position(end)
        }
        return null
    }
}
