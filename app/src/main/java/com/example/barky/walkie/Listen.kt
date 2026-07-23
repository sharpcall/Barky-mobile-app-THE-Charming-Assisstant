package com.example.barky.walkie

import android.util.Log

/**
 * LISTEN feature — hear the peer's live microphone.
 *
 * **File name:** `Listen.kt`
 * **Package path:** `com.example.barky.walkie.Listen`
 * **Full path:** `app/src/main/java/com/example/barky/walkie/Listen.kt`
 *
 * Request side: bursts [WalkieProtocol.TYPE_LISTEN_REQ] to dual hosts (public + LAN)
 * with hole-punch for cellular. Serve side (mic capture) still runs in
 * [WalkieTalkieEngine] but start/stop orchestration goes through here.
 */
object Listen {
    private const val TAG = "Listen"

    /** File name for logs / docs. */
    const val FILE_NAME = "Listen.kt"

    /**
     * Burst LISTEN_REQ to every target host/port (cellular-safe multi-path).
     *
     * @param targets from [PeerReach.resolve] — public first when off Wi‑Fi
     * @param replyPort our local UDP for the peer to stream mic audio back
     * @param stillWanted abort mid-burst if user cancelled LISTEN
     * @param sendControl send TYPE_LISTEN_REQ with reply port payload
     * @param holePunch warm NAT before request when host is public
     * @param sendHello keep peer tables warm
     */
    fun sendReqBurst(
        targets: List<WalkiePeer>,
        replyPort: Int,
        stillWanted: () -> Boolean,
        sendControl: (peer: WalkiePeer, type: Byte, replyPort: Int) -> Unit,
        holePunch: (peer: WalkiePeer, rounds: Int) -> Unit,
        sendHello: (peer: WalkiePeer) -> Unit,
        sendPathUpdate: (peer: WalkiePeer) -> Unit = {}
    ) {
        if (targets.isEmpty()) {
            Log.w(TAG, "sendReqBurst empty targets ($FILE_NAME)")
            return
        }
        Log.i(
            TAG,
            "LISTEN_REQ burst targets=${targets.joinToString { "${it.host}:${it.port}" }} " +
                "reply=$replyPort file=$FILE_NAME"
        )
        for (t in targets) {
            if (!stillWanted()) return
            try {
                sendHello(t)
                sendPathUpdate(t)
                val public = !com.example.barky.net.PeerEndpoint.isPrivateIp(t.host)
                if (public) {
                    holePunch(t, 16)
                } else {
                    Thread.sleep(40)
                }
                // Ports for this host only
                val ports = linkedSetOf(t.port)
                for (port in ports.filter { it > 0 }) {
                    if (!stillWanted()) return
                    sendControl(t.copy(port = port), WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
                    Thread.sleep(35)
                }
            } catch (ex: Throwable) {
                Log.w(TAG, "LISTEN_REQ target ${t.host}: ${ex.message}")
            }
        }
        // Second wave after NAT settle
        Thread.sleep(280)
        if (!stillWanted()) return
        for (t in targets) {
            if (!stillWanted()) return
            try {
                sendControl(t, WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
                Thread.sleep(40)
            } catch (_: Throwable) {
            }
        }
        Thread.sleep(200)
        if (!stillWanted()) return
        // Final single shot to primary (first target)
        try {
            sendControl(targets.first(), WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
        } catch (_: Throwable) {
        }
        Log.i(TAG, "LISTEN_REQ burst done file=$FILE_NAME")
    }

    fun sendStop(
        targets: List<WalkiePeer>,
        sendControl: (peer: WalkiePeer, type: Byte) -> Unit
    ) {
        for (t in targets) {
            try {
                sendControl(t, WalkieProtocol.TYPE_LISTEN_STOP)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "LISTEN_STOP sent n=${targets.size} file=$FILE_NAME")
    }
}
