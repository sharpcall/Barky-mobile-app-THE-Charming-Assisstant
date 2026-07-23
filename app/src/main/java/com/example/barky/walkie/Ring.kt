package com.example.barky.walkie

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress

/**
 * WAKEUP / Ring feature — loud remote alarm + stop.
 *
 * **File name:** `Ring.kt`
 * **Package path:** `com.example.barky.walkie.Ring`
 * **Full path:** `app/src/main/java/com/example/barky/walkie/Ring.kt`
 *
 * Playback uses [WakeupPlayer]. Network send fans out to dual public/LAN targets
 * with hole-punch so cellular ↔ Wi‑Fi works.
 */
class Ring(
    context: Context,
    onRingingChanged: (Boolean) -> Unit,
    private val sendUdp: (DatagramPacket) -> Unit,
    private val holePunch: (WalkiePeer, Int) -> Unit = { _, _ -> },
    private val sendHello: (WalkiePeer) -> Unit = {},
    private val sendPathUpdate: (WalkiePeer) -> Unit = {}
) {
    private val player = WakeupPlayer(context, onRingingChanged)

    val isRinging: Boolean get() = player.isRinging

    /**
     * Send WAKEUP RING to all [targets] (public first when off Wi‑Fi).
     * @param quiet soft beep for adb smoke only
     */
    fun send(
        targets: List<WalkiePeer>,
        quiet: Boolean = false,
        onStatus: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (targets.isEmpty()) {
            onError("Select a peer first")
            return
        }
        val payload = if (quiet) {
            byteArrayOf(WalkieProtocol.TYPE_WAKEUP, 1)
        } else {
            byteArrayOf(WalkieProtocol.TYPE_WAKEUP, 0)
        }
        Log.i(
            TAG,
            "RING send quiet=$quiet targets=${targets.joinToString { "${it.host}:${it.port}" }} " +
                "file=$FILE_NAME"
        )
        for (t in targets) {
            try {
                sendHello(t)
                sendPathUpdate(t)
                val public = !com.example.barky.net.PeerEndpoint.isPrivateIp(t.host)
                if (public) {
                    holePunch(t, 20)
                }
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(t.host), t.port
                )
                repeat(10) {
                    sendUdp(packet)
                    Thread.sleep(40)
                }
                Log.i(TAG, "RING sent → ${t.displayName} ${t.host}:${t.port}")
            } catch (ex: Throwable) {
                Log.e(TAG, "RING send failed ${t.host}", ex)
            }
        }
        onStatus("WAKEUP RING sent to ${targets.first().displayName}")
    }

    /** Stop local alarm and/or tell remotes to stop. */
    fun stop(
        targets: List<WalkiePeer> = emptyList(),
        alsoRemote: Boolean = true
    ) {
        player.stop()
        if (!alsoRemote || targets.isEmpty()) return
        val payload = byteArrayOf(WalkieProtocol.TYPE_WAKEUP_STOP)
        for (t in targets) {
            try {
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(t.host), t.port
                )
                repeat(6) {
                    sendUdp(packet)
                    Thread.sleep(30)
                }
            } catch (ex: Throwable) {
                Log.w(TAG, "RING stop remote ${t.host}", ex)
            }
        }
        Log.i(TAG, "RING stop local+remote n=${targets.size} file=$FILE_NAME")
    }

    fun onIncoming(from: String, quiet: Boolean) {
        Log.i(TAG, "RING incoming from=$from quiet=$quiet file=$FILE_NAME")
        player.start(quiet = quiet)
    }

    fun stopLocalOnly() {
        player.stop()
    }

    companion object {
        private const val TAG = "Ring"
        /** File name for logs / docs. */
        const val FILE_NAME = "Ring.kt"
    }
}
