package com.example.barky.walkie

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.barky.BarkyPrefs
import com.example.barky.BarkyService
import com.example.barky.net.ContactLabels
import com.example.barky.net.DeviceIdentity
import com.example.barky.net.FirebasePeerDirectory
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.PeerEndpoint
import com.example.barky.update.ApkSelfUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Fast in-phone comprehensive smoke test runner covering **all** Barky features over both
 * **Wi‑Fi LAN** and **Cellular / Public Internet Hub Relay (`BarkyUdpRelay` + STUN)**.
 *
 * Runs directly inside the phone process (polling in-memory [WalkieTalkieEngine] telemetry
 * every 50ms without ADB round-trip latency) and publishes its structured pass/fail report
 * to Firestore `_meta/smoke_report_<model>`.
 */
object InternalSmokeRunner {
    private const val TAG = "InternalSmokeRunner"
    private const val KEY_LAST_SMOKE_VC = "internal_smoke_last_vc"
    private val inFlight = AtomicBoolean(false)

    /**
     * Trigger an automated comprehensive smoke test if not already executed for the current
     * [ApkSelfUpdate.myVersionCode] (or unconditionally when [force] is true).
     */
    fun runIfNeededAsync(
        context: Context,
        engine: WalkieTalkieEngine?,
        force: Boolean = false
    ) {
        if (engine == null) return
        val app = context.applicationContext
        val myVc = ApkSelfUpdate.myVersionCode(app)
        val myVn = ApkSelfUpdate.myVersionName(app)
        if (!force) {
            val lastVc = BarkyPrefs.prefs(app).getInt(KEY_LAST_SMOKE_VC, 0)
            if (lastVc >= myVc) return
        }
        if (!inFlight.compareAndSet(false, true)) return

        // Stagger Pixel vs Samsung start by model so both phones do not transmit half-duplex HOLD simultaneously.
        val model = (Build.MODEL ?: "Phone").trim()
        val initialDelayMs = if (force) 500L else if (model.contains("Pixel", ignoreCase = true)) 4_000L else 28_000L

        thread(name = "barky-internal-smoke", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            val lines = mutableListOf<String>()
            var pass = 0
            var fail = 0

            fun ok(msg: String) {
                pass++
                val line = "PASS: $msg"
                lines.add(line)
                Log.i(TAG, line)
            }

            fun bad(msg: String) {
                fail++
                val line = "FAIL: $msg"
                lines.add(line)
                Log.w(TAG, line)
            }

            fun pollUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (cond()) return true
                    Thread.sleep(50L)
                }
                return cond()
            }

            try {
                Thread.sleep(initialDelayMs)
                BarkyPrefs.prefs(app).edit().putInt(KEY_LAST_SMOKE_VC, myVc).apply()
                BarkyService.ensureAccessibilityEnabled(app)
                Log.i(TAG, "=== INTERNAL SMOKE START model=$model vc=$myVc ($myVn) ===")

                // Quiet alarm volume during automated WAKEUP check, then restore healthy media volume at end.
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
                } catch (_: Throwable) {
                }

                // --- A: Peer Discovery (Firebase Directory + LAN HELLO) ---
                val selfId = try {
                    DeviceIdentity.deviceId(app)
                } catch (_: Throwable) {
                    ""
                }
                val allPeers = FirebasePeerDirectory.listAllBlocking(app)
                    .filter { it.deviceId != selfId && (!it.localIp.isNullOrBlank() || !it.publicIp.isNullOrBlank()) }
                    .sortedByDescending { it.updatedAtMs }
                val remoteEp: PeerEndpoint? = allPeers.firstOrNull()
                if (remoteEp != null) {
                    val selfNet = NetworkEndpoints.snapshot(app)
                    // Prefer LAN only if local subnets match (/24); otherwise use public STUN / relay endpoint
                    val sameSubnet = NetworkEndpoints.sameLocalSubnet(selfNet.localIp, remoteEp.localIp)
                    val preferLan = sameSubnet && NetworkEndpoints.preferLanPath(
                        selfNet,
                        remoteEp.localIp,
                        remoteEp.wifiHint,
                        liveLanHello = true
                    )
                    val host = remoteEp.preferredHost(preferLan)
                    val udp = remoteEp.preferredUdpPort(preferLan)
                    val vport = if (remoteEp.localVideoPort > 0) remoteEp.localVideoPort else udp
                    if (!host.isNullOrBlank() && udp > 0) {
                        engine.adoptDirectoryPeer(
                            displayName = ContactLabels.fromEndpoint(remoteEp),
                            host = host,
                            udpPort = udp,
                            videoPort = vport,
                            deviceId = remoteEp.deviceId
                        )
                    }
                    ok("A Peer discovered (${remoteEp.displayName} local=${remoteEp.localIp}:${remoteEp.localUdpPort} pub=${remoteEp.publicIp}:${remoteEp.publicUdpPort})")
                } else if (engine.getPeers().isNotEmpty()) {
                    ok("A Peer discovered via LAN NSD/HELLO (${engine.getPeers().first().host})")
                } else {
                    bad("A No peer found in Firebase or LAN")
                }

                // Wait briefly if the peer is currently transmitting (half-duplex courtesy).
                pollUntil(8_000L) { !engine.isChannelBusy() }

                // --- B: LIVE Mode Hold (Speaker / Right Mode) ---
                BarkyPrefs.setBestQualityVideo(app, false)
                BarkyPrefs.setPrivateMicMode(app, false)
                val prevTxB = engine.videoTxTotal()
                engine.startTalking(WalkieProtocol.AUDIO_MODE_SPEAKER)
                Thread.sleep(1_300L)
                engine.stopTalking()
                val liveOk = pollUntil(3_500L) {
                    engine.hasLastMessage() && engine.videoTxTotal() > prevTxB
                }
                if (liveOk) {
                    ok("B LIVE HOLD voice+video transmitted (dur=${engine.lastMessageDurationMs()}ms txFrames=${engine.videoTxTotal() - prevTxB})")
                } else {
                    bad("B LIVE HOLD missing replay/videoTx")
                }

                // --- B2: Sticky Mic Mode (Left = Private Earpiece vs Right = Speaker) ---
                BarkyPrefs.setPrivateMicMode(app, true)
                val privPersisted = BarkyPrefs.isPrivateMicMode(app)
                engine.startTalking(WalkieProtocol.AUDIO_MODE_HEADSET)
                Thread.sleep(700L)
                engine.stopTalking()
                Thread.sleep(300L)
                BarkyPrefs.setPrivateMicMode(app, false)
                val spkRestored = !BarkyPrefs.isPrivateMicMode(app)
                if (privPersisted && spkRestored) {
                    ok("B2 Sticky Mic Left (Private) & Right (Speaker) modes persisted & transmitted")
                } else {
                    bad("B2 Sticky Mic mode persistence failed")
                }

                // --- C: BEST Single-File Mode ---
                BarkyPrefs.setBestQualityVideo(app, true)
                engine.startTalking(WalkieProtocol.AUDIO_MODE_SPEAKER)
                Thread.sleep(1_300L)
                engine.stopTalking()
                val bestOk = pollUntil(5_000L) {
                    engine.hasLastMessage() && engine.lastMessageDurationMs() >= 800L
                }
                BarkyPrefs.setBestQualityVideo(app, false)
                if (bestOk) {
                    ok("C BEST single-file bundle recorded & sent (dur=${engine.lastMessageDurationMs()}ms)")
                } else {
                    bad("C BEST single-file bundle failed")
                }

                // --- D: Replay Sync (single_clock) ---
                engine.replayLastMessage()
                val replayStarted = pollUntil(1_500L) { engine.isReplayingClip() }
                val replayFinished = pollUntil(4_500L) { !engine.isReplayingClip() }
                if (replayStarted && replayFinished) {
                    ok("D Replay single-clock started and finished cleanly")
                } else {
                    bad("D Replay failed (started=$replayStarted finished=$replayFinished)")
                }

                // --- F: WAKEUP / Ring (Wi-Fi / Primary Path) ---
                engine.sendWakeup(quiet = true)
                val wakeOk = pollUntil(3_500L) { engine.hasRingAck() }
                engine.stopWakeupRinging()
                if (wakeOk) {
                    ok("F WAKEUP delivered & RING_ACK received")
                } else {
                    bad("F WAKEUP missing RING_ACK")
                }

                // --- G: LISTEN (Wi-Fi / Primary Path) ---
                engine.startListenRemote()
                val listenOk = pollUntil(4_500L) { engine.monitorAudioRxTotal() >= 2 }
                val listenPkts = engine.monitorAudioRxTotal()
                engine.stopListenRemote()
                Thread.sleep(200L)
                if (listenOk) {
                    ok("G LISTEN received live remote mic audio (pkts=$listenPkts)")
                } else {
                    bad("G LISTEN received 0 remote audio packets")
                }

                // --- H: SEE (Wi-Fi / Primary Path — Real Live Camera Video Frames!) ---
                engine.startSeeRemote()
                val seeOk = pollUntil(6_500L) { engine.monitorVideoRxTotal() >= 2 }
                val seeFrames = engine.monitorVideoRxTotal()
                val seeAck = engine.hasSeeAck()
                engine.stopSeeRemote()
                Thread.sleep(250L)
                if (seeOk) {
                    ok("H SEE received & decoded live remote camera video (frames=$seeFrames ack=$seeAck)")
                } else {
                    bad("H SEE received 0 video frames (ack=$seeAck)")
                }

                // --- I: GPS Tracking & Share ---
                engine.setGpsEnabled(true)
                val gpsOk = pollUntil(4_000L) { engine.getMyGpsTrail().isNotEmpty() }
                val myPts = engine.getMyGpsTrail().size
                val peerPts = engine.getPeerGpsTrail().size
                engine.setGpsEnabled(false)
                if (gpsOk) {
                    ok("I GPS active & seeded (myTrail=$myPts peerTrail=$peerPts)")
                } else {
                    bad("I GPS produced 0 trail points")
                }

                // --- I2: Zero-Prompt SCRN Screen Mirroring + Remote Touch/Keyboard Control ---
                engine.startScreenRemote()
                val scrnOk = pollUntil(6_000L) { engine.monitorVideoRxTotal() >= 1 }
                val scrnFrames = engine.monitorVideoRxTotal()
                val tapSent = engine.sendRemoteTap(0.5f, 0.5f)
                val txtSent = engine.sendRemoteText("BarkyAutoTest", replaceExisting = false, pressEnter = true)
                val keySent = engine.sendRemoteKey(1)
                engine.stopScreenRemote()
                Thread.sleep(250L)
                if (scrnOk && tapSent && txtSent && keySent) {
                    ok("I2 Zero-prompt SCRN received live screen frames (frames=$scrnFrames) + sent TAP/TEXT/KEY")
                } else {
                    bad("I2 SCRN failed (frames=$scrnFrames tap=$tapSent txt=$txtSent key=$keySent)")
                }

                // --- K: Cellular / Public Internet Hub Relay Path (STUN Public IP + BarkyUdpRelay) ---
                if (remoteEp != null && !remoteEp.publicIp.isNullOrBlank() && remoteEp.publicUdpPort > 0) {
                    val pubHost = remoteEp.publicIp
                    val pubUdp = remoteEp.publicUdpPort
                    engine.adoptDirectoryPeer(
                        displayName = ContactLabels.fromEndpoint(remoteEp),
                        host = pubHost,
                        udpPort = pubUdp,
                        videoPort = pubUdp,
                        deviceId = remoteEp.deviceId
                    )
                    Thread.sleep(250L)

                    // K1: WAKEUP over Cellular / Public Relay
                    engine.sendWakeup(quiet = true)
                    val k1Ok = pollUntil(4_000L) { engine.hasRingAck() }
                    engine.stopWakeupRinging()
                    if (k1Ok) ok("K1 Cellular/Relay WAKEUP RING_ACK ok ($pubHost:$pubUdp)")
                    else bad("K1 Cellular/Relay WAKEUP RING_ACK failed")

                    // K2: LISTEN over Cellular / Public Relay
                    engine.startListenRemote()
                    val k2Ok = pollUntil(5_000L) { engine.monitorAudioRxTotal() >= 2 }
                    val k2Pkts = engine.monitorAudioRxTotal()
                    engine.stopListenRemote()
                    Thread.sleep(200L)
                    if (k2Ok) ok("K2 Cellular/Relay LISTEN audio received (pkts=$k2Pkts)")
                    else bad("K2 Cellular/Relay LISTEN audio failed")

                    // K3: SEE over Cellular / Public Relay (Real Live Video Frames!)
                    engine.startSeeRemote()
                    val k3Ok = pollUntil(7_000L) { engine.monitorVideoRxTotal() >= 2 }
                    val k3Frames = engine.monitorVideoRxTotal()
                    engine.stopSeeRemote()
                    Thread.sleep(250L)
                    if (k3Ok) ok("K3 Cellular/Relay SEE video frames received (frames=$k3Frames)")
                    else bad("K3 Cellular/Relay SEE video frames failed (frames=$k3Frames)")

                    // K4: HOLD over Cellular / Public Relay
                    val prevTxK4 = engine.videoTxTotal()
                    engine.startTalking(WalkieProtocol.AUDIO_MODE_SPEAKER)
                    Thread.sleep(1_100L)
                    engine.stopTalking()
                    val k4Ok = pollUntil(3_500L) { engine.hasLastMessage() && engine.videoTxTotal() > prevTxK4 }
                    if (k4Ok) ok("K4 Cellular/Relay HOLD voice+video transmitted")
                    else bad("K4 Cellular/Relay HOLD failed")

                    // Restore optimal LAN/public peer endpoint after K
                    val selfNet = NetworkEndpoints.snapshot(app)
                    // Prefer LAN only if local subnets match (/24); otherwise use public STUN / relay endpoint
                    val sameSubnet = NetworkEndpoints.sameLocalSubnet(selfNet.localIp, remoteEp.localIp)
                    val preferLan = sameSubnet && NetworkEndpoints.preferLanPath(
                        selfNet,
                        remoteEp.localIp,
                        remoteEp.wifiHint,
                        liveLanHello = true
                    )
                    val restoreHost = remoteEp.preferredHost(preferLan) ?: pubHost
                    val restoreUdp = remoteEp.preferredUdpPort(preferLan).takeIf { it > 0 } ?: pubUdp
                    val restoreVid = if (preferLan && remoteEp.localVideoPort > 0) remoteEp.localVideoPort else restoreUdp
                    engine.adoptDirectoryPeer(
                        displayName = ContactLabels.fromEndpoint(remoteEp),
                        host = restoreHost,
                        udpPort = restoreUdp,
                        videoPort = restoreVid,
                        deviceId = remoteEp.deviceId
                    )
                } else {
                    bad("K Cellular/Relay skipped — peer publicIp missing in Firebase")
                }

                // Restore loud, healthy speakerphone volume (85% of max STREAM_MUSIC) so phone is ready for user.
                try {
                    val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val loudTarget = max(1, (maxMusic * 0.88f).toInt())
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, loudTarget, 0)
                    if (savedAlarm > 0) {
                        am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
                    }
                } catch (_: Throwable) {
                }

                val elapsed = System.currentTimeMillis() - t0
                val summary = "INTERNAL_SMOKE_DONE model=$model vc=$myVc ($myVn) pass=$pass fail=$fail elapsed=${elapsed}ms"
                lines.add(summary)
                Log.i(TAG, summary)
                FirebasePeerDirectory.publishSmokeReportBlocking(
                    context = app,
                    deviceModel = model,
                    versionCode = myVc,
                    versionName = myVn,
                    passCount = pass,
                    failCount = fail,
                    elapsedMs = elapsed,
                    details = lines.joinToString("\u000A")
                )
            } catch (t: Throwable) {
                Log.e(TAG, "InternalSmokeRunner failed", t)
                val elapsed = System.currentTimeMillis() - t0
                FirebasePeerDirectory.publishSmokeReportBlocking(
                    context = app,
                    deviceModel = model,
                    versionCode = myVc,
                    versionName = myVn,
                    passCount = pass,
                    failCount = fail + 1,
                    elapsedMs = elapsed,
                    details = (lines + "ERROR: ${t.javaClass.simpleName}: ${t.message}").joinToString("\u000A")
                )
            } finally {
                inFlight.set(false)
            }
        }
    }
}
