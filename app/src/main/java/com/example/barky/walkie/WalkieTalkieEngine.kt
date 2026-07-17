package com.example.barky.walkie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.barky.BarkyPrefs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Same-Wi‑Fi one-way video walkie: live audio + live JPEG video, last message replay.
 */
class WalkieTalkieEngine(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onPeersChanged(peers: List<WalkiePeer>)
        fun onTalkingChanged(talking: Boolean)
        fun onReceivingChanged(receiving: Boolean)
        /** True while the other phone holds the channel (half-duplex lock). */
        fun onChannelBusyChanged(busy: Boolean)
        fun onVideoFrame(bitmap: Bitmap, live: Boolean)
        fun onLastMessageChanged(hasMessage: Boolean)
        fun onWakeupReceived(from: String)
        fun onWakeupRingingChanged(ringing: Boolean)
        /** True while a wakeup we sent is still active on the remote (Stop can cancel it). */
        fun onRemoteWakeupActive(active: Boolean)
        fun onReplayStateChanged(replaying: Boolean)
        fun onError(message: String)
        /** Live video pipeline diagnostics shown on-screen (TX and RX). */
        fun onVideoDebug(message: String)
        /** Internet-mode share code (null when Wi‑Fi-only or STUN failed). */
        fun onInternetInviteChanged(inviteCode: String?)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var socket: DatagramSocket? = null
    private var videoServer: ServerSocket? = null
    private var localPort: Int = 0
    private var localVideoPort: Int = 0
    private var registeredName: String? = null

    private val peers = ConcurrentHashMap<String, WalkiePeer>()
    @Volatile private var selectedPeerKey: String? = null

    private val running = AtomicBoolean(false)
    private val talking = AtomicBoolean(false)
    private val receiving = AtomicBoolean(false)
    private val replaying = AtomicBoolean(false)
    /** Remote peer is transmitting — local PTT must stay locked. */
    private val channelBusy = AtomicBoolean(false)
    /** Current HOLD is BEST-quality local record (send after release). */
    private val bestQualitySession = AtomicBoolean(false)
    /** True while bulk-uploading a recorded BEST clip. */
    private val flushingRecorded = AtomicBoolean(false)

    private var receiveThread: Thread? = null
    private var videoAcceptThread: Thread? = null
    private var recordThread: Thread? = null
    private var flushThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val playbackLock = Any()
    private val udpSendLock = Any()
    private val videoOutLock = Any()

    /** Outgoing TCP stream for video while PTT is held. */
    private var videoOutSocket: Socket? = null
    private var videoOut: DataOutputStream? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val resolveListeners = ConcurrentHashMap<String, NsdManager.ResolveListener>()

    private val wakeupPlayer = WakeupPlayer(appContext) { ringing ->
        mainHandler.post { listener.onWakeupRingingChanged(ringing) }
    }
    private var savedMusicVolume = -1

    private var cameraSource: CameraFrameSource? = null
    /** Local idle preview (so flip works before HOLD). */
    private var previewSource: CameraFrameSource? = null
    @Volatile private var localPreviewWanted = false
    private val videoFrameId = AtomicInteger(1)
    private val videoAssembly = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val videoAssemblyTime = ConcurrentHashMap<Int, Long>()

    // Incoming message being assembled for replay
    private val incomingAudio = ByteArrayOutputStream()
    private val incomingFrames = mutableListOf<WalkieVideoFrame>()
    private var incomingStartMs = 0L
    private var incomingFrom = ""
    @Volatile private var lastMessage: WalkieMessage? = null
    /** After a clip is finalized, drop late TCP/UDP stragglers until next TALK_START / new session. */
    @Volatile private var rxClipSealed = false
    @Volatile private var lastStoredAtMs = 0L

    // Outgoing capture for local last-message too
    private val outgoingAudio = ByteArrayOutputStream()
    private val outgoingFrames = mutableListOf<WalkieVideoFrame>()
    private var outgoingStartMs = 0L
    /** host -> learned video TCP port from UDP HELLO (most reliable) */
    private val learnedVideoPorts = ConcurrentHashMap<String, Int>()
    private var helloThread: Thread? = null

    @Volatile private var videoTxOk = 0
    @Volatile private var videoTxFail = 0
    /** Public UDP endpoint from STUN (internet mode). */
    @Volatile private var myInviteCode: String? = null
    @Volatile private var publicHost: String? = null
    @Volatile private var publicUdpPort: Int = 0
    /** When true, UDP receive loop yields so STUN can use the same socket. */
    @Volatile private var stunExclusive = false
    @Volatile private var videoRxOk = 0
    @Volatile private var lastVideoError: String = ""
    @Volatile private var tcpInOpen = false
    private var rxAudioWithoutVideoCheck: Runnable? = null

    private fun videoDebug(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { listener.onVideoDebug(msg) }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "walkie-start", isDaemon = true) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    soTimeout = 1000
                    broadcast = false
                    try {
                        sendBufferSize = max(sendBufferSize, 256 * 1024)
                        receiveBufferSize = max(receiveBufferSize, 256 * 1024)
                    } catch (_: Exception) {
                    }
                }
                socket = sock
                localPort = sock.localPort
                // Dedicated TCP listen port for video
                val ss = ServerSocket(0)
                ss.reuseAddress = true
                ss.soTimeout = 2000
                videoServer = ss
                localVideoPort = ss.localPort
                postStatus("Ready — audio UDP $localPort, video TCP $localVideoPort")
                Log.i(TAG, "UDP=$localPort videoTCP=$localVideoPort")
                // STUN on the walkie UDP socket *before* the receive loop starts
                if (!BarkyPrefs.isWifiOnly(appContext)) {
                    refreshInternetInvite()
                } else {
                    myInviteCode = null
                    mainHandler.post { listener.onInternetInviteChanged(null) }
                }
                startReceiver(sock)
                startVideoAcceptLoop(ss)
                startHelloBeacon()
                registerNsd(localPort, localVideoPort)
                startDiscovery()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                postError("Walkie start failed: ${t.message}")
                stop()
            }
        }
    }

    fun isWifiOnlyMode(): Boolean = BarkyPrefs.isWifiOnly(appContext)

    fun getMyInviteCode(): String? = myInviteCode

    /**
     * Switch front/back immediately on live preview or during HOLD.
     */
    fun switchCamera(): Boolean {
        val talkCam = cameraSource
        if (talkCam != null && talking.get()) {
            talkCam.switchCamera()
            return true
        }
        val prev = previewSource
        if (prev != null) {
            prev.switchCamera()
            return true
        }
        // No camera open yet — flip preference and start preview so user sees it now
        BarkyPrefs.toggleCameraFront(appContext)
        if (localPreviewWanted && !talking.get() && !receiving.get() && !replaying.get()) {
            startLocalPreviewInternal()
        }
        val front = BarkyPrefs.isCameraFront(appContext)
        postStatus(if (front) "Front camera" else "Back camera")
        return true
    }

    fun isCameraFrontPreferred(): Boolean = BarkyPrefs.isCameraFront(appContext)

    /** Start local viewfinder so flip is instant (before HOLD). */
    fun startLocalPreview() {
        localPreviewWanted = true
        if (talking.get() || receiving.get() || replaying.get()) return
        startLocalPreviewInternal()
    }

    fun stopLocalPreview() {
        localPreviewWanted = false
        stopLocalPreviewInternal()
    }

    private fun startLocalPreviewInternal() {
        if (previewSource != null || talking.get()) return
        if (!running.get()) return
        try {
            stopLocalPreviewInternal()
            val cam = CameraFrameSource(
                context = appContext,
                compactForUdp = { false },
                config = CaptureConfig.Live,
                preferFrontCamera = BarkyPrefs.isCameraFront(appContext),
                onFacingChanged = { front ->
                    BarkyPrefs.setCameraFront(appContext, front)
                    videoDebug("Preview camera: ${if (front) "front" else "back"}")
                    postStatus(if (front) "Front camera" else "Back camera")
                },
                onError = { msg -> videoDebug("Preview camera: $msg") }
            ) { jpeg ->
                if (talking.get() || replaying.get() || receiving.get()) return@CameraFrameSource
                // Preview-only path: slower UI updates, never hides Replay
                decodeAndEmit(jpeg, live = true, localPreview = true)
            }
            previewSource = cam
            cam.start()
            videoDebug("Local preview started front=${BarkyPrefs.isCameraFront(appContext)}")
        } catch (t: Throwable) {
            Log.w(TAG, "startLocalPreview", t)
        }
    }

    private fun stopLocalPreviewInternal() {
        val p = previewSource
        previewSource = null
        try {
            p?.stopAndWait(400L)
        } catch (_: Exception) {
        }
    }

    private fun maybeResumeLocalPreview() {
        if (!localPreviewWanted) return
        if (talking.get() || receiving.get() || replaying.get() || flushingRecorded.get()) return
        mainHandler.postDelayed({
            if (localPreviewWanted && !talking.get() && !receiving.get() &&
                !replaying.get() && !flushingRecorded.get()
            ) {
                startLocalPreviewInternal()
            }
        }, 250)
    }

    /** Re-read prefs after Settings change: Wi‑Fi-only vs internet pairing. */
    fun applyNetworkMode() {
        if (!running.get()) return
        thread(name = "walkie-net-mode", isDaemon = true) {
            if (BarkyPrefs.isWifiOnly(appContext)) {
                myInviteCode = null
                publicHost = null
                publicUdpPort = 0
                Log.i(TAG, "Network mode: Wi‑Fi only (NSD local discovery)")
                postStatus("Walkie: Wi‑Fi only — looking for peers on this network")
                mainHandler.post { listener.onInternetInviteChanged(null) }
            } else {
                Log.i(TAG, "Network mode: Internet pairing enabled (STUN + share code)")
                postStatus("Walkie: Internet mode — discovering public address…")
                refreshInternetInvite()
            }
        }
    }

    /**
     * Pair with a remote phone using their share code (no Barky central server).
     * Performs UDP hole-punch HELLO bursts toward the peer's public endpoint.
     */
    fun connectViaInvite(code: String): Boolean {
        val inv = PeerInviteCodec.decode(code)
        if (inv == null) {
            postError("Invalid pair code")
            return false
        }
        if (!running.get()) {
            postError("Walkie is not running")
            return false
        }
        val peer = WalkiePeer(
            serviceName = WalkieProtocol.SERVICE_NAME_PREFIX + "inet-" + inv.host,
            host = inv.host,
            port = inv.udpPort,
            videoPort = inv.videoPort
        )
        peers[key(peer)] = peer
        learnedVideoPorts[inv.host] = inv.videoPort
        selectedPeerKey = key(peer)
        postPeers()
        postStatus("Internet peer set — hole-punching ${inv.host}:${inv.udpPort}…")
        Log.i(TAG, "connectViaInvite host=${inv.host} udp=${inv.udpPort} video=${inv.videoPort}")
        thread(name = "walkie-hole-punch", isDaemon = true) {
            // Simultaneous open: both phones should punch after exchanging codes
            repeat(30) {
                if (!running.get()) return@thread
                try {
                    sendHelloTo(peer)
                    // Tiny payload keeps NAT mapping warm
                    val poke = byteArrayOf(WalkieProtocol.TYPE_PING)
                    sendUdp(
                        DatagramPacket(
                            poke,
                            poke.size,
                            InetAddress.getByName(peer.host),
                            peer.port
                        )
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "hole punch", t)
                }
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            postStatus("Paired with ${peer.displayName} — try HOLD to send")
            videoDebug("Internet hole-punch finished toward ${peer.host}:${peer.port}")
        }
        return true
    }

    private fun refreshInternetInvite() {
        val sock = socket
        if (sock == null || sock.isClosed) {
            postError("Internet mode: UDP socket not ready")
            return
        }
        stunExclusive = true
        val mapped = try {
            // Let any in-flight receive timeout
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
            }
            StunClient.discoverMappedAddress(sock)
        } finally {
            stunExclusive = false
        }
        if (mapped == null) {
            myInviteCode = null
            mainHandler.post { listener.onInternetInviteChanged(null) }
            postError("Internet mode: could not learn public address (STUN failed)")
            Log.w(TAG, "STUN failed — internet pairing unavailable")
            return
        }
        publicHost = mapped.host
        publicUdpPort = mapped.port
        // Encode public UDP mapping + our TCP video listen port (video may fall back to UDP)
        val code = PeerInviteCodec.encode(mapped.host, mapped.port, localVideoPort)
        myInviteCode = code
        Log.i(
            TAG,
            "Internet invite ready public=${mapped.host}:${mapped.port} " +
                "localUdp=$localPort localVideoTcp=$localVideoPort code=$code"
        )
        postStatus("Internet mode ready — share your pair code")
        mainHandler.post { listener.onInternetInviteChanged(code) }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        talking.set(false)
        flushingRecorded.set(false)
        bestQualitySession.set(false)
        localPreviewWanted = false
        stopLocalPreviewInternal()
        stopTalking()
        stopReplay()
        markNotReceiving()
        wakeupPlayer.stop()
        closeVideoOut()
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {
        }
        registrationListener = null
        resolveListeners.clear()
        try {
            videoServer?.close()
        } catch (_: Exception) {
        }
        videoServer = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        receiveThread = null
        videoAcceptThread = null
        helloThread = null
        releaseAudioTrack()
        restorePlaybackAudio()
        peers.clear()
        learnedVideoPorts.clear()
        selectedPeerKey = null
        postPeers()
        postStatus("Walkie stopped")
    }

    fun selectPeer(peer: WalkiePeer?) {
        selectedPeerKey = peer?.let { key(it) }
        postStatus(
            if (peer == null) "No peer selected"
            else "Selected ${peer.displayName} (${peer.host})"
        )
    }

    fun getPeers(): List<WalkiePeer> = peers.values.sortedBy { it.displayName }

    fun hasLastMessage(): Boolean = lastMessage?.hasContent == true

    fun isChannelBusy(): Boolean = channelBusy.get() || receiving.get() || flushingRecorded.get()

    fun startTalking() {
        if (!running.get()) {
            postError("Walkie is not running")
            return
        }
        if (flushingRecorded.get()) {
            postError("Still sending previous recorded video…")
            return
        }
        // Auto-pick sole / first peer (needed for adb TEST_TALK and single-peer LAN)
        var peer = currentPeer()
        if (peer == null) {
            peer = getPeers().firstOrNull()
            if (peer != null) {
                selectPeer(peer)
                Log.i(TAG, "Auto-selected peer ${peer.displayName} for talk")
            }
        }
        if (peer == null) {
            postError("Select a peer first")
            return
        }
        if (channelBusy.get() || receiving.get()) {
            postError("Channel busy — other phone is talking")
            mainHandler.post { listener.onChannelBusyChanged(true) }
            return
        }
        if (replaying.get()) stopReplay()
        if (!talking.compareAndSet(false, true)) return
        // Free camera hardware for TX session
        stopLocalPreviewInternal()

        val best = BarkyPrefs.isBestQualityVideo(appContext)
        bestQualitySession.set(best)
        videoTxOk = 0
        videoTxFail = 0
        lastVideoError = ""
        outgoingAudio.reset()
        synchronized(outgoingFrames) { outgoingFrames.clear() }
        outgoingStartMs = System.currentTimeMillis()
        mainHandler.post { listener.onTalkingChanged(true) }

        if (best) {
            // Record locally only (high FPS); network send happens on stopTalking
            postStatus("Recording BEST quality… (sends when you release)")
            videoDebug("TX BEST record start (local only, no live send) → later to ${peer.host}")
            Log.i(TAG, "BEST quality record start")
            thread(name = "walkie-video-record", isDaemon = true) {
                if (!talking.get()) return@thread
                val maxFrames = WalkieProtocol.BEST_MAX_STORED_FRAMES
                val cam = CameraFrameSource(
                    context = appContext,
                    compactForUdp = { false },
                    config = CaptureConfig.BestRecord,
                    preferFrontCamera = BarkyPrefs.isCameraFront(appContext),
                    onFacingChanged = { front ->
                        BarkyPrefs.setCameraFront(appContext, front)
                        videoDebug("Camera facing: ${if (front) "front" else "back"}")
                    },
                    onError = { msg -> videoDebug("Camera: $msg") }
                ) { jpeg ->
                    if (!talking.get()) return@CameraFrameSource
                    val offset = System.currentTimeMillis() - outgoingStartMs
                    synchronized(outgoingFrames) {
                        while (outgoingFrames.size >= maxFrames) {
                            outgoingFrames.removeAt(0)
                        }
                        outgoingFrames.add(WalkieVideoFrame(offset, jpeg.copyOf()))
                    }
                    decodeAndEmit(jpeg, live = true)
                    val n = synchronized(outgoingFrames) { outgoingFrames.size }
                    if (n == 1 || n % 20 == 0) {
                        videoDebug("BEST recorded frames=$n lastJpeg=${jpeg.size}B")
                    }
                }
                cameraSource = cam
                if (talking.get()) cam.start() else cam.stop()
            }
            recordThread = thread(name = "walkie-ptt-record", isDaemon = true) {
                recordAudio(peer, sendLive = false)
            }
            return
        }

        // —— Live path (unchanged behavior when BEST checkbox is off) ——
        sendTalkStart(peer)
        postStatus("Sending video message to ${peer.displayName}…")
        videoDebug(
            "TX start → ${peer.host} udp=${peer.port} videoTcp=${peer.videoPort} " +
                "learned=${learnedVideoPorts[peer.host]} codec=MJPEG"
        )

        thread(name = "walkie-video-out", isDaemon = true) {
            sendHelloTo(peer)
            Thread.sleep(100)
            val linked = openVideoOut(peer)
            videoDebug(
                if (linked) "TX: TCP video connected to ${peer.host}:${currentPeer()?.videoPort ?: peer.videoPort}"
                else "TX: TCP failed — ${lastVideoError.ifBlank { "unknown" }}; trying UDP frames"
            )
            if (!talking.get()) return@thread
            val cam = CameraFrameSource(
                context = appContext,
                compactForUdp = { synchronized(videoOutLock) { videoOut == null } },
                config = CaptureConfig.Live,
                preferFrontCamera = BarkyPrefs.isCameraFront(appContext),
                onFacingChanged = { front ->
                    BarkyPrefs.setCameraFront(appContext, front)
                    videoDebug("Camera facing: ${if (front) "front" else "back"}")
                },
                onError = { msg -> videoDebug("Camera: $msg") }
            ) { jpeg ->
                if (!talking.get()) return@CameraFrameSource
                val offset = System.currentTimeMillis() - outgoingStartMs
                synchronized(outgoingFrames) {
                    while (outgoingFrames.size >= WalkieProtocol.MAX_STORED_FRAMES) {
                        outgoingFrames.removeAt(0)
                    }
                    outgoingFrames.add(WalkieVideoFrame(offset, jpeg.copyOf()))
                }
                val viaTcp = sendVideoFrameTcp(jpeg)
                if (viaTcp) {
                    videoTxOk++
                    if (videoTxOk == 1 || videoTxOk % 15 == 0) {
                        videoDebug("TX OK frames=$videoTxOk lastJpeg=${jpeg.size}B via=TCP")
                    }
                } else {
                    val viaUdp = sendVideoFrameUdp(jpeg, peer)
                    if (viaUdp) {
                        videoTxOk++
                        if (videoTxOk == 1 || videoTxOk % 15 == 0) {
                            videoDebug("TX OK frames=$videoTxOk lastJpeg=${jpeg.size}B via=UDP")
                        }
                    } else {
                        videoTxFail++
                        videoDebug(
                            "TX FAIL frames_ok=$videoTxOk fails=$videoTxFail — $lastVideoError"
                        )
                    }
                }
                decodeAndEmit(jpeg, live = true)
            }
            cameraSource = cam
            if (talking.get()) {
                cam.start()
                if (!linked) {
                    thread(name = "walkie-video-retry", isDaemon = true) {
                        repeat(10) {
                            if (!talking.get() || synchronized(videoOutLock) { videoOut != null }) return@thread
                            Thread.sleep(350)
                            if (openVideoOut(peer)) {
                                videoDebug("TX: TCP connected on retry")
                                return@thread
                            }
                        }
                        if (talking.get() && synchronized(videoOutLock) { videoOut == null }) {
                            videoDebug("TX: TCP never connected — UDP only (if network allows)")
                        }
                    }
                }
            } else {
                cam.stop()
            }
        }

        recordThread = thread(name = "walkie-ptt", isDaemon = true) {
            recordAudio(peer, sendLive = true)
        }
    }

    fun stopTalking() {
        val wasBest = bestQualitySession.get()
        val wasTalking = talking.getAndSet(false)
        if (!wasTalking && !flushingRecorded.get()) return

        if (wasBest && wasTalking) {
            // Drain mic + camera fully so the last ~2s of HOLD are not lost
            val rec = recordThread
            recordThread = null
            try {
                rec?.join(1_200)
            } catch (_: InterruptedException) {
            }
            val cam = cameraSource
            cameraSource = null
            cam?.stopAndWait(800L)

            val peer = currentPeer()
            val audio = outgoingAudio.toByteArray()
            val frames = synchronized(outgoingFrames) { outgoingFrames.toList() }
            if (peer == null || (audio.isEmpty() && frames.isEmpty())) {
                bestQualitySession.set(false)
                mainHandler.post { listener.onTalkingChanged(false) }
                postStatus("Ready")
                videoDebug("BEST record empty — nothing to send")
                maybeResumeLocalPreview()
                return
            }
            val metrics = clipMetrics(audio, frames)
            postStatus("Sending BEST quality video (${frames.size} frames)…")
            videoDebug(
                "BEST flush start frames=${frames.size} audio=${audio.size}B " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs}"
            )
            Log.i(
                TAG,
                "CLIP_TX mode=BEST frames=${frames.size} videoMs=${metrics.videoMs} " +
                    "audioMs=${metrics.audioMs} audioBytes=${audio.size}"
            )
            if (!flushingRecorded.compareAndSet(false, true)) {
                mainHandler.post { listener.onTalkingChanged(false) }
                maybeResumeLocalPreview()
                return
            }
            flushThread = thread(name = "walkie-best-flush", isDaemon = true) {
                try {
                    flushRecordedClip(peer, audio, frames)
                } finally {
                    flushingRecorded.set(false)
                    bestQualitySession.set(false)
                    mainHandler.post { listener.onTalkingChanged(false) }
                    postStatus("Ready")
                    maybeResumeLocalPreview()
                }
            }
            return
        }

        if (!wasTalking) return
        bestQualitySession.set(false)
        // Live: let mic finish the last PCM chunk before we close the stream
        val rec = recordThread
        recordThread = null
        try {
            rec?.join(500)
        } catch (_: InterruptedException) {
        }
        val cam = cameraSource
        cameraSource = null
        cam?.stopAndWait(500L)
        // Brief settle so any last encoded frame is queued/sent
        try {
            Thread.sleep(80)
        } catch (_: InterruptedException) {
        }

        sendVideoTcpEnd()
        closeVideoOut()
        currentPeer()?.let { sendTalkEnd(it) }

        val audio = outgoingAudio.toByteArray()
        val frames = synchronized(outgoingFrames) { outgoingFrames.toList() }
        if (audio.isNotEmpty() || frames.isNotEmpty()) {
            val metrics = clipMetrics(audio, frames)
            val dur = max(metrics.videoMs, metrics.audioMs)
            storeLastMessage(WalkieMessage(audio, frames, dur, "me"))
            videoDebug(
                "TX saved last message: frames=${frames.size} audio=${audio.size}B " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs}"
            )
            Log.i(
                TAG,
                "CLIP_TX mode=LIVE frames=${frames.size} videoMs=${metrics.videoMs} " +
                    "audioMs=${metrics.audioMs} audioBytes=${audio.size}"
            )
        } else {
            videoDebug("TX ended with no audio/video to save for replay")
        }

        mainHandler.post { listener.onTalkingChanged(false) }
        postStatus("Ready")
        maybeResumeLocalPreview()
    }

    private data class ClipMetrics(val videoMs: Long, val audioMs: Long, val frames: Int)

    private fun clipMetrics(audio: ByteArray, frames: List<WalkieVideoFrame>): ClipMetrics {
        val videoMs = if (frames.isNotEmpty()) {
            // last frame timestamp + one frame interval estimate
            frames.maxOf { it.timeOffsetMs } + WalkieProtocol.BEST_VIDEO_MIN_INTERVAL_MS
        } else 0L
        val audioMs = if (audio.isNotEmpty()) {
            audio.size / 2L * 1000L / WalkieProtocol.SAMPLE_RATE
        } else 0L
        return ClipMetrics(videoMs, audioMs, frames.size)
    }

    /**
     * BEST: send the whole clip as **one TCP bundle** (audio + all frames).
     * No partial finalize, no trailing cut from TALK_END racing UDP audio.
     */
    private fun flushRecordedClip(
        peer: WalkiePeer,
        audio: ByteArray,
        frames: List<WalkieVideoFrame>
    ) {
        try {
            sendTalkStart(peer)
            sendHelloTo(peer)
            Thread.sleep(100)
            val linked = openVideoOut(peer)
            videoDebug(
                if (linked) "BEST TX: TCP connected for bundle to ${peer.host}"
                else "BEST TX: TCP failed — ${lastVideoError.ifBlank { "unknown" }}"
            )
            if (!linked) {
                postError("BEST send failed: no TCP video link")
                sendTalkEnd(peer)
                return
            }

            val ok = sendBestBundle(audio, frames)
            closeVideoOut()
            // Unlock remote channel after the full bundle is on the wire
            sendTalkEnd(peer)

            // Store locally with normalized single-clip timeline (same as peer receives)
            val normalized = normalizeFrames(frames)
            val metrics = clipMetrics(audio, normalized)
            val dur = max(metrics.videoMs, metrics.audioMs)
            storeLastMessage(WalkieMessage(audio, normalized, dur, "me"))
            videoTxOk = frames.size
            videoDebug(
                "BEST bundle sent ok=$ok frames=${normalized.size} audio=${audio.size}B " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs} singleFile=true"
            )
            Log.i(
                TAG,
                "BEST flush done frames=${normalized.size} audio=${audio.size} " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs} ok=$ok singleFile=true"
            )
            Log.i(
                TAG,
                "CLIP_TX mode=BEST_BUNDLE frames=${normalized.size} videoMs=${metrics.videoMs} " +
                    "audioMs=${metrics.audioMs} audioBytes=${audio.size}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "flushRecordedClip", t)
            postError("BEST send failed: ${t.message}")
            closeVideoOut()
            try {
                sendTalkEnd(peer)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendBestBundle(audio: ByteArray, frames: List<WalkieVideoFrame>): Boolean {
        synchronized(videoOutLock) {
            val out = videoOut ?: return false
            return try {
                val normalized = normalizeFrames(frames)
                // One file on the wire: magic | audio | frames(with t0=0 timestamps)
                out.writeInt(WalkieProtocol.TCP_BUNDLE_MAGIC)
                out.writeInt(audio.size)
                if (audio.isNotEmpty()) out.write(audio)
                out.writeInt(normalized.size)
                for (fr in normalized) {
                    out.writeLong(fr.timeOffsetMs)
                    out.writeInt(fr.jpeg.size)
                    out.write(fr.jpeg)
                }
                out.flush()
                Log.i(
                    TAG,
                    "BEST single-file write bytes audio=${audio.size} frames=${normalized.size}"
                )
                true
            } catch (t: Throwable) {
                lastVideoError = "BEST bundle write: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, lastVideoError, t)
                false
            }
        }
    }

    private val remoteWakeupClear = Runnable {
        mainHandler.post { listener.onRemoteWakeupActive(false) }
    }

    fun sendWakeup() {
        val peer = currentPeer()
        if (peer == null) {
            postError("Select a peer first")
            return
        }
        mainHandler.removeCallbacks(remoteWakeupClear)
        mainHandler.post { listener.onRemoteWakeupActive(true) }
        thread(name = "walkie-wakeup", isDaemon = true) {
            try {
                val payload = byteArrayOf(WalkieProtocol.TYPE_WAKEUP)
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                repeat(8) {
                    sendUdp(packet)
                    Thread.sleep(40)
                }
                Log.i(TAG, "WAKEUP sent to ${peer.displayName} host=${peer.host}")
                postStatus("WAKEUP sent to ${peer.displayName}")
                // Match remote alarm duration
                mainHandler.postDelayed(remoteWakeupClear, 12_000L)
            } catch (t: Throwable) {
                Log.e(TAG, "sendWakeup failed", t)
                postError("Wakeup send failed: ${t.message}")
                mainHandler.post { listener.onRemoteWakeupActive(false) }
            }
        }
    }

    /** Stop local ringing and/or cancel a wakeup we sent to the peer. */
    fun stopWakeupRinging() {
        wakeupPlayer.stop()
        // Also tell remote to stop if we (or anyone) want silence
        currentPeer()?.let { peer ->
            thread(name = "walkie-wakeup-stop", isDaemon = true) {
                try {
                    val payload = byteArrayOf(WalkieProtocol.TYPE_WAKEUP_STOP)
                    val packet = DatagramPacket(
                        payload, payload.size, InetAddress.getByName(peer.host), peer.port
                    )
                    repeat(6) {
                        sendUdp(packet)
                        Thread.sleep(30)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "stop remote wakeup", t)
                }
            }
        }
        mainHandler.removeCallbacks(remoteWakeupClear)
        mainHandler.post { listener.onRemoteWakeupActive(false) }
        postStatus("Ringing stopped")
    }

    fun isWakeupRinging(): Boolean = wakeupPlayer.isRinging

    fun replayLastMessage() {
        val msg = lastMessage
        if (msg == null || !msg.hasContent) {
            postError("No message to replay")
            videoDebug("Replay: no last message stored")
            return
        }
        if (talking.get() || flushingRecorded.get()) {
            postError("Stop transmitting first")
            return
        }
        if (replaying.getAndSet(false)) {
            mainHandler.post { listener.onReplayStateChanged(false) }
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
            }
        }
        if (!replaying.compareAndSet(false, true)) return
        stopLocalPreviewInternal()
        mainHandler.post { listener.onReplayStateChanged(true) }
        postStatus("Replaying last message…")
        val metrics = clipMetrics(msg.audioPcm, msg.frames)
        videoDebug(
            "Replay start: frames=${msg.frames.size} audio=${msg.audioPcm.size}B " +
                "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs} from=${msg.fromHost}"
        )
        Log.i(
            TAG,
            "Replay start frames=${msg.frames.size} videoMs=${metrics.videoMs} " +
                "audioMs=${metrics.audioMs} sync=single_clock"
        )
        if (msg.frames.isEmpty()) {
            videoDebug("Replay WARNING: last message has audio but 0 video frames stored")
        }
        thread(name = "walkie-replay", isDaemon = true) {
            try {
                playSyncedClip(
                    audio = msg.audioPcm.copyOf(),
                    frames = msg.frames.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) },
                    active = { replaying.get() },
                    label = "Replay"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "replay failed", t)
                videoDebug("Replay error: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                replaying.set(false)
                mainHandler.post {
                    listener.onReplayStateChanged(false)
                    listener.onStatus("Ready")
                }
                Log.i(TAG, "Replay finished — UI should show Replay not Stop")
                maybeResumeLocalPreview()
            }
        }
    }

    fun stopReplay() {
        val was = replaying.getAndSet(false)
        if (!was) {
            mainHandler.post { listener.onReplayStateChanged(false) }
            return
        }
        mainHandler.post { listener.onReplayStateChanged(false) }
        postStatus("Replay stopped")
        maybeResumeLocalPreview()
    }

    /**
     * Play audio + video as **one clip** on a shared wall clock.
     * Frame timestamps are absolute offsets from t=0; no extra per-frame pad
     * (that used to make video overrun audio by seconds).
     */
    private fun playSyncedClip(
        audio: ByteArray,
        frames: List<WalkieVideoFrame>,
        active: () -> Boolean,
        label: String
    ) {
        preparePlaybackAudio()
        val normalized = normalizeFrames(frames)
        val metrics = clipMetrics(audio, normalized)
        // Single duration: clip ends when the longer of A/V ends; frames after audio still
        // use the same clock so they do not invent extra time.
        val durationMs = max(metrics.audioMs, metrics.videoMs).coerceAtLeast(0L)
        val playStart = android.os.SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "$label sync play start frames=${normalized.size} videoMs=${metrics.videoMs} " +
                "audioMs=${metrics.audioMs} durationMs=$durationMs"
        )

        if (audio.isNotEmpty()) {
            thread(name = "walkie-sync-audio", isDaemon = true) {
                try {
                    val track = ensureAudioTrack()
                    var off = 0
                    val chunk = WalkieProtocol.BYTES_PER_FRAME
                    while (active() && off < audio.size) {
                        val n = min(chunk, audio.size - off)
                        track.write(audio, off, n)
                        off += n
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "$label audio", t)
                }
            }
        }

        var shown = 0
        for (frame in normalized) {
            if (!active()) break
            val due = playStart + frame.timeOffsetMs
            val now = android.os.SystemClock.elapsedRealtime()
            val wait = due - now
            if (wait > 0) {
                try {
                    Thread.sleep(min(wait, 2_000L))
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!active()) break
            // Skip frames that are hopelessly late (>120ms) to keep lock with audio
            val late = android.os.SystemClock.elapsedRealtime() - due
            if (late > 120 && shown > 0) {
                continue
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
            } catch (t: Throwable) {
                Log.w(TAG, "$label decode", t)
                null
            }
            if (bmp == null) continue
            shown++
            mainHandler.post { listener.onVideoFrame(bmp, live = false) }
            if (shown == 1 || shown % 15 == 0 || shown == normalized.size) {
                videoDebug("$label frame $shown/${normalized.size} t=${frame.timeOffsetMs}ms")
            }
        }

        // Hold last frame only until clip duration (do not invent extra video time)
        val endAt = playStart + durationMs
        while (active()) {
            val left = endAt - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) break
            try {
                Thread.sleep(min(left, 50L))
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.i(TAG, "$label sync play done shown=$shown/${normalized.size} durationMs=$durationMs")
        videoDebug("$label done: showed $shown/${normalized.size} frames over ${durationMs}ms (A/V synced)")
    }

    /** Shift frame times so first frame is t=0 (stable single-clip timeline). */
    private fun normalizeFrames(frames: List<WalkieVideoFrame>): List<WalkieVideoFrame> {
        if (frames.isEmpty()) return frames
        val t0 = frames.minOf { it.timeOffsetMs }
        if (t0 == 0L) return frames
        return frames.map { WalkieVideoFrame(it.timeOffsetMs - t0, it.jpeg) }
    }

    private fun sendTalkStart(peer: WalkiePeer) {
        thread(name = "walkie-talk-start", isDaemon = true) {
            try {
                val payload = byteArrayOf(WalkieProtocol.TYPE_TALK_START)
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                repeat(4) {
                    sendUdp(packet)
                    Thread.sleep(15)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendTalkStart", t)
            }
        }
    }

    private fun sendTalkEnd(peer: WalkiePeer) {
        thread(name = "walkie-end", isDaemon = true) {
            try {
                val payload = byteArrayOf(WalkieProtocol.TYPE_TALK_END)
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                repeat(4) {
                    sendUdp(packet)
                    Thread.sleep(20)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendTalkEnd", t)
            }
        }
    }

    private fun setChannelBusy(busy: Boolean, reason: String) {
        val prev = channelBusy.getAndSet(busy)
        if (prev != busy) {
            Log.i(TAG, "channelBusy=$busy ($reason)")
            mainHandler.post { listener.onChannelBusyChanged(busy) }
            if (busy) {
                postStatus("Channel busy — wait…")
            }
        }
        // Safety: auto-clear stuck busy after 45s
        if (busy) {
            mainHandler.removeCallbacks(clearBusyRunnable)
            mainHandler.postDelayed(clearBusyRunnable, 45_000L)
        } else {
            mainHandler.removeCallbacks(clearBusyRunnable)
        }
    }

    private val clearBusyRunnable = Runnable {
        if (channelBusy.get() && !receiving.get()) {
            setChannelBusy(false, "timeout")
            postStatus("Ready")
        }
    }

    /** @return true if TCP video out is connected */
    private fun openVideoOut(peer: WalkiePeer): Boolean {
        if (synchronized(videoOutLock) { videoOut != null && videoOutSocket?.isConnected == true }) {
            return true
        }
        closeVideoOut()
        val learned = learnedVideoPorts[peer.host]
        val portsToTry = listOfNotNull(
            learned,
            peer.videoPort.takeIf { it > 0 },
            peer.port.takeIf { it > 0 }
        ).distinct()
        var lastError: Throwable? = null
        for (vport in portsToTry) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(peer.host, vport), 2500)
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                synchronized(videoOutLock) {
                    videoOutSocket = s
                    videoOut = out
                }
                learnedVideoPorts[peer.host] = vport
                // Remember on peer object map
                peers[key(peer)] = peer.copy(videoPort = vport)
                Log.i(TAG, "TCP video OUT OK ${peer.host}:$vport")
                postStatus("Video link OK → ${peer.displayName}:$vport")
                return true
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "TCP video OUT failed ${peer.host}:$vport — ${t.message}")
            }
        }
        lastVideoError =
            "TCP connect failed to ${peer.host} ports=$portsToTry — ${lastError?.javaClass?.simpleName}: ${lastError?.message}"
        Log.e(TAG, lastVideoError, lastError)
        videoDebug("TX: $lastVideoError")
        return false
    }

    private fun startHelloBeacon() {
        helloThread = thread(name = "walkie-hello", isDaemon = true) {
            while (running.get()) {
                try {
                    for (peer in peers.values.toList()) {
                        sendHelloTo(peer)
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendHelloTo(peer: WalkiePeer) {
        try {
            val buf = ByteArray(3)
            buf[0] = WalkieProtocol.TYPE_HELLO
            buf[1] = ((localVideoPort ushr 8) and 0xFF).toByte()
            buf[2] = (localVideoPort and 0xFF).toByte()
            sendUdp(
                DatagramPacket(
                    buf,
                    buf.size,
                    InetAddress.getByName(peer.host),
                    peer.port
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sendHello", t)
        }
    }

    private fun handleHello(data: ByteArray, fromHost: String, fromUdpPort: Int) {
        if (data.size < 3) return
        val vport = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        if (vport <= 0 || vport > 65535) return
        learnedVideoPorts[fromHost] = vport
        // Upsert peer so audio-discovered hosts also become full peers
        val existing = peers.values.find { it.host == fromHost }
        val peer = existing?.copy(port = fromUdpPort, videoPort = vport)
            ?: WalkiePeer(
                serviceName = "udp-$fromHost",
                host = fromHost,
                port = fromUdpPort,
                videoPort = vport
            )
        peers[key(peer)] = peer
        if (selectedPeerKey == null || peers.size == 1) {
            selectedPeerKey = key(peer)
        }
        postPeers()
        Log.i(TAG, "HELLO from $fromHost videoPort=$vport udpPort=$fromUdpPort")
    }

    private fun closeVideoOut() {
        synchronized(videoOutLock) {
            try {
                videoOut?.flush()
            } catch (_: Exception) {
            }
            try {
                videoOut?.close()
            } catch (_: Exception) {
            }
            try {
                videoOutSocket?.close()
            } catch (_: Exception) {
            }
            videoOut = null
            videoOutSocket = null
        }
    }

    private fun sendVideoTcpEnd() {
        synchronized(videoOutLock) {
            try {
                videoOut?.writeInt(0) // end marker
                videoOut?.flush()
            } catch (_: Exception) {
            }
        }
    }

    /** @return true if sent on TCP */
    private fun sendVideoFrameTcp(jpeg: ByteArray): Boolean {
        synchronized(videoOutLock) {
            val out = videoOut ?: return false
            return try {
                out.writeInt(jpeg.size)
                out.write(jpeg)
                out.flush()
                true
            } catch (t: Throwable) {
                lastVideoError = "TCP send: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, lastVideoError, t)
                try {
                    videoOut?.close()
                } catch (_: Exception) {
                }
                try {
                    videoOutSocket?.close()
                } catch (_: Exception) {
                }
                videoOut = null
                videoOutSocket = null
                false
            }
        }
    }

    /** @return true if UDP send succeeded */
    private fun sendVideoFrameUdp(jpeg: ByteArray, peer: WalkiePeer): Boolean {
        return try {
            val sock = socket ?: run {
                lastVideoError = "UDP send: socket null"
                return false
            }
            val dest = InetAddress.getByName(peer.host)
            val frameId = videoFrameId.getAndIncrement() and 0xFFFF
            val chunk = WalkieProtocol.VIDEO_CHUNK
            val parts = max(1, (jpeg.size + chunk - 1) / chunk)
            if (parts > 32) {
                lastVideoError = "UDP frame too large bytes=${jpeg.size} parts=$parts"
                Log.w(TAG, lastVideoError)
                return false
            }
            synchronized(udpSendLock) {
                repeat(2) {
                    for (i in 0 until parts) {
                        val off = i * chunk
                        val len = min(chunk, jpeg.size - off)
                        val buf = ByteArray(WalkieProtocol.VIDEO_HEADER + len)
                        buf[0] = WalkieProtocol.TYPE_VIDEO
                        buf[1] = ((frameId ushr 8) and 0xFF).toByte()
                        buf[2] = (frameId and 0xFF).toByte()
                        buf[3] = i.toByte()
                        buf[4] = parts.toByte()
                        System.arraycopy(jpeg, off, buf, 5, len)
                        sock.send(DatagramPacket(buf, buf.size, dest, peer.port))
                    }
                }
            }
            true
        } catch (t: Throwable) {
            lastVideoError = "UDP send: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, lastVideoError, t)
            false
        }
    }

    private fun startVideoAcceptLoop(server: ServerSocket) {
        videoAcceptThread = thread(name = "walkie-video-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    client.tcpNoDelay = true
                    val from = client.inetAddress?.hostAddress ?: "peer"
                    tcpInOpen = true
                    videoDebug("RX: TCP video connected from $from (expecting MJPEG)")
                    Log.i(TAG, "TCP video IN from $from")
                    thread(name = "walkie-video-in", isDaemon = true) {
                        handleVideoTcpIn(client, from)
                    }
                } catch (t: Throwable) {
                    if (running.get()) Log.w(TAG, "video accept", t)
                }
            }
        }
    }

    private fun handleVideoTcpIn(client: Socket, from: String) {
        var frames = 0
        try {
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))
            while (running.get() && !client.isClosed) {
                val len = try {
                    input.readInt()
                } catch (t: Exception) {
                    if (frames == 0) {
                        lastVideoError = "RX TCP closed before any frame: ${t.javaClass.simpleName}: ${t.message}"
                        videoDebug(lastVideoError)
                    }
                    break
                }
                // BEST: single atomic audio+video bundle
                if (len == WalkieProtocol.TCP_BUNDLE_MAGIC) {
                    receiveBestBundle(input, from)
                    frames = videoRxOk
                    break
                }
                if (len == 0) {
                    videoDebug("RX: TCP end marker (frames=$frames) — wait for trailing audio")
                    // Do not finalize immediately: last UDP audio packets often arrive after
                    // the TCP end marker. Give them time, then seal.
                    scheduleFinalizeAfterVideoEnd(from)
                    break
                }
                if (len < 0 || len > 500_000) {
                    lastVideoError = "RX bad TCP video len=$len"
                    videoDebug(lastVideoError)
                    break
                }
                val jpeg = ByteArray(len)
                input.readFully(jpeg)
                if (rxClipSealed) continue
                beginIncomingIfNeeded(from)
                if (rxClipSealed) continue
                val offset = System.currentTimeMillis() - incomingStartMs
                if (jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
                    synchronized(incomingAudio) {
                        while (incomingFrames.size >= WalkieProtocol.MAX_STORED_FRAMES) {
                            incomingFrames.removeAt(0)
                        }
                        incomingFrames.add(WalkieVideoFrame(offset, jpeg.copyOf()))
                    }
                    frames++
                    videoRxOk++
                    if (frames == 1 || frames % 15 == 0) {
                        videoDebug("RX OK frames=$frames lastJpeg=${jpeg.size}B via=TCP from=$from")
                    }
                    decodeAndEmit(jpeg, live = true)
                } else {
                    lastVideoError = "RX TCP payload not JPEG (${jpeg.size}B)"
                    videoDebug(lastVideoError)
                }
            }
        } catch (t: Throwable) {
            lastVideoError = "RX TCP error: ${t.javaClass.simpleName}: ${t.message}"
            videoDebug(lastVideoError)
            Log.w(TAG, "handleVideoTcpIn", t)
        } finally {
            tcpInOpen = false
            try {
                client.close()
            } catch (_: Exception) {
            }
            if (receiving.get() && !rxClipSealed) {
                if (frames == 0) {
                    videoDebug("RX: TCP session ended with 0 video frames (audio may still play)")
                }
                scheduleFinalizeAfterVideoEnd(from)
            }
        }
    }

    /**
     * Read full BEST bundle (single file on the wire), store once, then play as
     * **one synced A/V clip** (not audio stream + separate frame drip).
     */
    private fun receiveBestBundle(input: DataInputStream, from: String) {
        try {
            rxClipSealed = false
            val audioLen = input.readInt()
            if (audioLen < 0 || audioLen > 20_000_000) {
                lastVideoError = "BEST RX bad audioLen=$audioLen"
                videoDebug(lastVideoError)
                return
            }
            val audio = ByteArray(audioLen)
            if (audioLen > 0) input.readFully(audio)
            val frameCount = input.readInt()
            if (frameCount < 0 || frameCount > WalkieProtocol.BEST_MAX_STORED_FRAMES + 50) {
                lastVideoError = "BEST RX bad frameCount=$frameCount"
                videoDebug(lastVideoError)
                return
            }
            beginIncomingIfNeeded(from)
            postStatus("Receiving BEST quality clip…")
            val frames = ArrayList<WalkieVideoFrame>(frameCount)
            for (i in 0 until frameCount) {
                val tOff = input.readLong()
                val jLen = input.readInt()
                if (jLen < 0 || jLen > 500_000) {
                    lastVideoError = "BEST RX bad jpegLen=$jLen at $i"
                    videoDebug(lastVideoError)
                    break
                }
                val jpeg = ByteArray(jLen)
                input.readFully(jpeg)
                frames.add(WalkieVideoFrame(tOff, jpeg))
                if (i == 0 || i == frameCount - 1 || (i + 1) % 20 == 0) {
                    videoDebug("BEST RX download ${i + 1}/$frameCount jpeg=${jLen}B")
                }
            }
            videoRxOk = frames.size
            val normalized = normalizeFrames(frames)
            // Store complete single clip (normalized timeline)
            synchronized(incomingAudio) {
                incomingAudio.reset()
                if (audio.isNotEmpty()) incomingAudio.write(audio)
                incomingFrames.clear()
                incomingFrames.addAll(normalized)
            }
            finalizeIncoming(from, mode = "BEST_BUNDLE")
            markNotReceiving()
            val metrics = clipMetrics(audio, normalized)
            videoDebug(
                "BEST RX complete single-file frames=${normalized.size} " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs} — playing synced"
            )
            Log.i(
                TAG,
                "BEST RX single-file ready frames=${normalized.size} " +
                    "videoMs=${metrics.videoMs} audioMs=${metrics.audioMs}"
            )
            // Play as one synced clip after full download (not partial/live A/V race)
            if (!replaying.get() && !talking.get()) {
                thread(name = "walkie-best-rx-play", isDaemon = true) {
                    val playing = AtomicBoolean(true)
                    try {
                        // Surface as temporary "replay" so Stop works if we add it later
                        mainHandler.post { listener.onReceivingChanged(true) }
                        playSyncedClip(
                            audio = audio.copyOf(),
                            frames = normalized.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) },
                            active = { playing.get() && running.get() && !talking.get() },
                            label = "BEST_RX"
                        )
                    } finally {
                        playing.set(false)
                        mainHandler.post {
                            listener.onReceivingChanged(false)
                            listener.onStatus("Ready")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            lastVideoError = "BEST RX bundle: ${t.javaClass.simpleName}: ${t.message}"
            videoDebug(lastVideoError)
            Log.w(TAG, "receiveBestBundle", t)
        }
    }

    private var videoEndFinalize: Runnable? = null

    /** After live TCP ends, wait for trailing UDP audio before sealing the clip. */
    private fun scheduleFinalizeAfterVideoEnd(from: String) {
        mainHandler.removeCallbacks(receivingIdle)
        videoEndFinalize?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!rxClipSealed) {
                finalizeIncoming(from.ifBlank { incomingFrom })
                markNotReceiving()
            }
        }
        videoEndFinalize = r
        // ~1.2s covers the last second of UDP audio after TCP end
        mainHandler.postDelayed(r, 1_200L)
    }

    private fun sendUdp(packet: DatagramPacket) {
        synchronized(udpSendLock) {
            socket?.send(packet)
        }
    }

    private fun currentPeer(): WalkiePeer? {
        val base = selectedPeerKey?.let { peers[it] }
            ?: peers.values.singleOrNull()
            ?: return null
        val learned = learnedVideoPorts[base.host]
        return if (learned != null && learned > 0) base.copy(videoPort = learned) else base
    }

    private fun registerNsd(udpPort: Int, videoTcpPort: Int) {
        val info = NsdServiceInfo().apply {
            // Encode video port in the name as a reliable fallback (NSD TXT attrs often drop)
            serviceName = WalkieProtocol.SERVICE_NAME_PREFIX + safeDeviceName() + "-v$videoTcpPort"
            serviceType = WalkieProtocol.SERVICE_TYPE
            setPort(udpPort)
            try {
                setAttribute(WalkieProtocol.ATTR_VIDEO_PORT, videoTcpPort.toString())
            } catch (t: Throwable) {
                Log.w(TAG, "NSD setAttribute vport failed", t)
            }
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredName = serviceInfo.serviceName
                postStatus("Advertised as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postError("NSD register failed ($errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = reg
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    private fun startDiscovery() {
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                postStatus("Discovering peers…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == registeredName) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                peers.entries.removeIf { it.value.serviceName == name }
                if (selectedPeerKey != null && peers[selectedPeerKey] == null) {
                    selectedPeerKey = null
                }
                postPeers()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                postError("Discovery start failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = disc
        nsdManager.discoverServices(WalkieProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        if (resolveListeners.containsKey(name)) return
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveListeners.remove(serviceInfo.serviceName)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolveListeners.remove(resolved.serviceName)
                if (resolved.serviceName == registeredName) return
                val host = resolveHost(resolved) ?: return
                if (host == "0.0.0.0" || host.startsWith("127.")) return
                val vport = learnedVideoPorts[host]
                    ?: resolveVideoPort(resolved)
                    ?: parseVideoPortFromName(resolved.serviceName)
                    ?: resolved.port
                val peer = WalkiePeer(resolved.serviceName, host, resolved.port, vport)
                peers[key(peer)] = peer
                if (peers.size == 1 || selectedPeerKey == null) {
                    selectedPeerKey = key(peer)
                }
                postPeers()
                postStatus("Found ${peer.displayName}")
                Log.i(TAG, "NSD peer ${peer.displayName} host=$host udp=${peer.port} video=${peer.videoPort}")
                // Exchange video ports over UDP (works even if NSD attributes drop)
                sendHelloTo(peer)
            }
        }
        resolveListeners[name] = resolveListener
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (t: Throwable) {
            resolveListeners.remove(name)
        }
    }

    private fun resolveHost(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addrs = info.hostAddresses
            val v4 = addrs.firstOrNull { it.hostAddress?.contains(':') != true }
            return (v4 ?: addrs.firstOrNull())?.hostAddress?.substringBefore('%')
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress?.substringBefore('%')
    }

    private fun resolveVideoPort(info: NsdServiceInfo): Int? {
        return try {
            val map = info.attributes ?: return null
            val raw = map[WalkieProtocol.ATTR_VIDEO_PORT] ?: return null
            val s = if (raw is ByteArray) {
                // NSD attributes are often null-terminated
                String(raw).trim { it <= ' ' || it == '\u0000' }
            } else {
                raw.toString().trim()
            }
            s.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVideoPortFromName(name: String): Int? {
        // Optional encoding: Barky-Device-v12345
        val m = Regex("-v(\\d{2,5})$").find(name) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun startReceiver(sock: DatagramSocket) {
        receiveThread = thread(name = "walkie-rx", isDaemon = true) {
            // Larger buffer so multi-chunk video never truncates
            val buf = ByteArray(64 * 1024)
            while (running.get()) {
                try {
                    if (stunExclusive) {
                        Thread.sleep(30)
                        continue
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    if (packet.length < 1) continue
                    // Copy out of the reusable socket buffer immediately
                    val snapshot = packet.data.copyOfRange(
                        packet.offset,
                        packet.offset + packet.length
                    )
                    val from = packet.address?.hostAddress ?: "peer"
                    val fromPort = packet.port
                    when (snapshot[0]) {
                        WalkieProtocol.TYPE_AUDIO -> handleAudioBytes(snapshot, from)
                        WalkieProtocol.TYPE_VIDEO -> handleVideoBytes(snapshot, from)
                        WalkieProtocol.TYPE_WAKEUP -> handleWakeupFrom(from)
                        WalkieProtocol.TYPE_WAKEUP_STOP -> {
                            wakeupPlayer.stop()
                            postStatus("Remote stopped ringing")
                        }
                        WalkieProtocol.TYPE_HELLO -> handleHello(snapshot, from, fromPort)
                        WalkieProtocol.TYPE_TALK_START -> {
                            // New remote clip — unseal and clear any trailing junk from previous
                            rxClipSealed = false
                            stopLocalPreviewInternal() // avoid local/remote ImageView fight (flash)
                            synchronized(incomingAudio) {
                                incomingAudio.reset()
                                incomingFrames.clear()
                            }
                            if (!talking.get()) {
                                setChannelBusy(true, "remote TALK_START from $from")
                            }
                        }
                        WalkieProtocol.TYPE_TALK_END -> handleTalkEndFrom(from)
                        WalkieProtocol.TYPE_PING -> sendPongTo(from, fromPort)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (t: Throwable) {
                    if (running.get()) Log.w(TAG, "rx error", t)
                }
            }
        }
    }

    private fun beginIncomingIfNeeded(from: String) {
        // New media after a sealed clip opens a fresh session (TALK_START may have been missed)
        if (rxClipSealed && !receiving.get()) {
            rxClipSealed = false
        }
        if (rxClipSealed) return

        if (!receiving.get()) {
            synchronized(incomingAudio) {
                incomingAudio.reset()
                incomingFrames.clear()
                incomingStartMs = System.currentTimeMillis()
                incomingFrom = from
            }
            videoRxOk = 0
            if (!receiving.getAndSet(true)) {
                stopLocalPreviewInternal()
                // Half-duplex: lock local send while receiving
                if (!talking.get()) {
                    setChannelBusy(true, "receiving from $from")
                }
                preparePlaybackAudio()
                mainHandler.post { listener.onReceivingChanged(true) }
                postStatus("Receiving message… (expecting video)")
                videoDebug(
                    "RX: audio/control from $from — waiting for video " +
                        "(tcpInOpen=$tcpInOpen learnedVport=${learnedVideoPorts[from]})"
                )
                // If we get audio but no video, surface why
                val check = Runnable {
                    if (receiving.get() && videoRxOk == 0) {
                        videoDebug(
                            "RX ERROR: audio playing but 0 video frames so far. " +
                                "tcpInOpen=$tcpInOpen lastErr=${lastVideoError.ifBlank { "none" }}. " +
                                "Sender may be failing TX (check other phone Video debug line)."
                        )
                    }
                }
                rxAudioWithoutVideoCheck = check
                mainHandler.postDelayed(check, 1200)
            }
        }
        mainHandler.removeCallbacks(receivingIdle)
        mainHandler.postDelayed(receivingIdle, RECEIVE_IDLE_MS)
    }

    private fun handleAudioBytes(data: ByteArray, from: String) {
        val dataLen = data.size - 1
        if (dataLen < 2) return
        val pcm = data.copyOfRange(1, data.size)
        // Learn peer from live audio path
        ensurePeerFromHost(from)
        if (rxClipSealed && !receiving.get()) {
            // late audio after finalize — do not reopen a 0-frame junk clip
            return
        }
        beginIncomingIfNeeded(from)
        if (rxClipSealed) return
        synchronized(incomingAudio) {
            try {
                incomingAudio.write(pcm)
            } catch (_: Exception) {
            }
        }
        playPcm(pcm)
    }

    private fun ensurePeerFromHost(fromHost: String) {
        // No-op if already known; HELLO updates video ports
        if (peers.values.any { it.host == fromHost }) return
    }

    private fun handleVideoBytes(data: ByteArray, from: String) {
        if (data.size < WalkieProtocol.VIDEO_HEADER) return
        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val part = data[3].toInt() and 0xFF
        val parts = data[4].toInt() and 0xFF
        if (parts <= 0 || part >= parts || parts > 64) return
        val chunk = data.copyOfRange(WalkieProtocol.VIDEO_HEADER, data.size)

        beginIncomingIfNeeded(from)

        val jpeg: ByteArray? = synchronized(videoAssembly) {
            var slots = videoAssembly[frameId]
            if (slots == null || slots.size != parts) {
                slots = arrayOfNulls(parts)
                videoAssembly[frameId] = slots
            }
            // Ignore duplicate retransmits of the same part
            if (slots[part] == null) {
                slots[part] = chunk
            }
            videoAssemblyTime[frameId] = System.currentTimeMillis()

            if (slots.all { it != null }) {
                videoAssembly.remove(frameId)
                videoAssemblyTime.remove(frameId)
                val total = slots.sumOf { it!!.size }
                val full = ByteArray(total)
                var p = 0
                for (s in slots) {
                    val b = s!!
                    System.arraycopy(b, 0, full, p, b.size)
                    p += b.size
                }
                full
            } else {
                null
            }
        }

        // Drop stale partial frames
        val now = System.currentTimeMillis()
        synchronized(videoAssembly) {
            val stale = videoAssemblyTime.filter { now - it.value > 2500 }.keys
            for (id in stale) {
                videoAssembly.remove(id)
                videoAssemblyTime.remove(id)
            }
        }

        if (jpeg != null && jpeg.size >= 2) {
            val looksJpeg = (jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
            if (!looksJpeg) {
                lastVideoError = "RX UDP frameId=$frameId not JPEG (${jpeg.size}B)"
                videoDebug(lastVideoError)
                return
            }
            val offset = System.currentTimeMillis() - incomingStartMs
            synchronized(incomingAudio) {
                while (incomingFrames.size >= WalkieProtocol.MAX_STORED_FRAMES) {
                    incomingFrames.removeAt(0)
                }
                incomingFrames.add(WalkieVideoFrame(offset, jpeg.copyOf()))
            }
            videoRxOk++
            if (videoRxOk == 1 || videoRxOk % 15 == 0) {
                videoDebug("RX OK frames=$videoRxOk lastJpeg=${jpeg.size}B via=UDP from=$from")
            }
            decodeAndEmit(jpeg, live = true)
        }
    }

    private fun handleTalkEndFrom(from: String) {
        // Prefer a short delay so any packets still in flight are kept
        mainHandler.removeCallbacks(receivingIdle)
        videoEndFinalize?.let { mainHandler.removeCallbacks(it) }
        val host = from.ifBlank { incomingFrom }
        val r = Runnable {
            if (!rxClipSealed) {
                finalizeIncoming(host)
                markNotReceiving()
            }
            setChannelBusy(false, "remote TALK_END from $from")
        }
        videoEndFinalize = r
        mainHandler.postDelayed(r, 400L)
    }

    private fun handleWakeupFrom(from: String) {
        mainHandler.post {
            listener.onWakeupReceived(from)
            wakeupPlayer.start()
        }
    }

    private fun sendPongTo(host: String, port: Int) {
        try {
            val pong = byteArrayOf(WalkieProtocol.TYPE_PONG)
            sendUdp(DatagramPacket(pong, pong.size, InetAddress.getByName(host), port))
        } catch (_: Exception) {
        }
    }

    private fun finalizeIncoming(from: String, mode: String = "LIVE") {
        val audio: ByteArray
        val frames: List<WalkieVideoFrame>
        synchronized(incomingAudio) {
            audio = incomingAudio.toByteArray()
            // Deep-copy frame JPEGs for stable replay
            frames = incomingFrames.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) }
            incomingAudio.reset()
            incomingFrames.clear()
        }
        if (audio.isEmpty() && frames.isEmpty()) return
        val metrics = clipMetrics(audio, frames)
        val dur = max(metrics.videoMs, metrics.audioMs)
        val msg = WalkieMessage(audio, frames, dur, from)
        storeLastMessage(msg)
        Log.i(
            TAG,
            "CLIP_RX mode=$mode frames=${frames.size} videoMs=${metrics.videoMs} " +
                "audioMs=${metrics.audioMs} audioBytes=${audio.size} from=$from"
        )
        // Seal so late frames / duplicate TALK_END cannot replace this clip with junk
        if (msg.frames.isNotEmpty() || msg.audioPcm.isNotEmpty()) {
            rxClipSealed = true
        }
    }

    /**
     * Keeps **only the newest** complete message in RAM (always overwrite previous clip).
     * No files on disk. Shorter newer clips must replace longer older ones (Replay = last sent/received).
     */
    private fun storeLastMessage(candidate: WalkieMessage) {
        if (!candidate.hasContent) return
        val prev = lastMessage
        val now = System.currentTimeMillis()
        if (prev != null) {
            // Race: late audio-only finalize must not wipe a good video clip
            if (prev.frames.isNotEmpty() && candidate.frames.isEmpty()) {
                videoDebug(
                    "Keep previous last message (had ${prev.frames.size} frames); " +
                        "ignoring audio-only finalize"
                )
                return
            }
            // Race: 1–2 trailing frames with no audio right after a real clip
            val recent = lastStoredAtMs > 0L && (now - lastStoredAtMs) < 2_000L
            if (recent &&
                prev.frames.size >= 3 && prev.audioPcm.isNotEmpty() &&
                candidate.frames.size <= 2 && candidate.audioPcm.isEmpty()
            ) {
                videoDebug(
                    "Ignore trailing fragment frames=${candidate.frames.size} " +
                        "(keeping prev frames=${prev.frames.size})"
                )
                return
            }
        }
        val prevFrames = prev?.frames?.size ?: 0
        lastMessage = candidate
        lastStoredAtMs = now
        mainHandler.post { listener.onLastMessageChanged(true) }
        videoDebug(
            "Stored last message (overwrote previous frames=$prevFrames): " +
                "frames=${candidate.frames.size} audio=${candidate.audioPcm.size}B " +
                "from=${candidate.fromHost} (RAM only, maxFrames=${WalkieProtocol.MAX_STORED_FRAMES})"
        )
        Log.i(
            TAG,
            "Stored last message audio=${candidate.audioPcm.size} frames=${candidate.frames.size} " +
                "from=${candidate.fromHost} replacedPrevFrames=$prevFrames"
        )
    }

    private val receivingIdle = Runnable {
        finalizeIncoming(incomingFrom)
        markNotReceiving()
    }

    private fun markNotReceiving() {
        mainHandler.removeCallbacks(receivingIdle)
        videoEndFinalize?.let { mainHandler.removeCallbacks(it) }
        videoEndFinalize = null
        rxAudioWithoutVideoCheck?.let { mainHandler.removeCallbacks(it) }
        rxAudioWithoutVideoCheck = null
        if (receiving.getAndSet(false)) {
            if (videoRxOk == 0) {
                videoDebug(
                    "RX done: 0 video frames received. lastErr=${lastVideoError.ifBlank { "none" }}"
                )
            } else {
                videoDebug("RX done: $videoRxOk video frames")
            }
            setChannelBusy(false, "receive finished")
            mainHandler.post {
                listener.onReceivingChanged(false)
                listener.onStatus("Ready")
            }
            maybeResumeLocalPreview()
        }
    }



    /**
     * Capture mic PCM into [outgoingAudio]. When [sendLive] is true, also stream UDP audio
     * (live mode). BEST mode uses sendLive=false and uploads after release.
     * Cleanup of camera / TALK_END is owned by [stopTalking] / flush — not here.
     */
    private fun recordAudio(peer: WalkiePeer, sendLive: Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(
            WalkieProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            postError("Microphone not available")
            talking.set(false)
            mainHandler.post { listener.onTalkingChanged(false) }
            return
        }
        val bufferSize = max(minBuf, WalkieProtocol.BYTES_PER_FRAME * 4)
        var recorder: AudioRecord? = null
        try {
            recorder = buildRecorder(bufferSize)
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                postError("Mic init failed — grant microphone permission")
                talking.set(false)
                mainHandler.post { listener.onTalkingChanged(false) }
                return
            }
            recorder.startRecording()
            val frame = ByteArray(WalkieProtocol.BYTES_PER_FRAME)
            val packetBuf = ByteArray(1 + WalkieProtocol.BYTES_PER_FRAME)
            packetBuf[0] = WalkieProtocol.TYPE_AUDIO
            val dest = InetAddress.getByName(peer.host)

            while (talking.get() && running.get()) {
                var filled = 0
                while (filled < frame.size && talking.get()) {
                    val n = recorder.read(frame, filled, frame.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < 2) continue
                applyGainInPlace(frame, filled, GAIN)
                try {
                    outgoingAudio.write(frame, 0, filled)
                } catch (_: Exception) {
                }
                if (sendLive) {
                    System.arraycopy(frame, 0, packetBuf, 1, filled)
                    sendUdp(DatagramPacket(packetBuf, 1 + filled, dest, peer.port))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recordAudio", t)
            postError("PTT error: ${t.message}")
        } finally {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildRecorder(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        for (source in sources) {
            try {
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(WalkieProtocol.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        source,
                        WalkieProtocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun applyGainInPlace(pcm: ByteArray, length: Int, gain: Float): Int {
        val bb = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            var s = bb.getShort(i).toInt()
            s = (s * gain).toInt()
            s = min(32767, max(-32768, s))
            peak = max(peak, abs(s))
            bb.putShort(i, s.toShort())
            i += 2
        }
        return peak
    }

    /** Drop UI frames if the main thread is still painting (stops flash/stutter). */
    private val uiEmitBusy = AtomicBoolean(false)
    @Volatile private var lastUiEmitAt = 0L

    private fun decodeAndEmit(jpeg: ByteArray, live: Boolean, localPreview: Boolean = false) {
        try {
            if (live) {
                val now = android.os.SystemClock.elapsedRealtime()
                // Preview: ~10 fps is enough for flip UI; live TX/RX: ~15–20 fps
                val minGap = if (localPreview) 100L else 50L
                if (now - lastUiEmitAt < minGap) return
                if (!uiEmitBusy.compareAndSet(false, true)) return
                lastUiEmitAt = now
            }
            val opts = BitmapFactory.Options().apply {
                // RGB_565 is faster / less GC pressure for continuous stream
                inPreferredConfig =
                    if (localPreview) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            if (bmp == null) {
                if (live) uiEmitBusy.set(false)
                return
            }
            // Never recycle bitmaps still held by ImageView — that causes black flash
            mainHandler.post {
                try {
                    listener.onVideoFrame(bmp, live)
                } finally {
                    if (live) uiEmitBusy.set(false)
                }
            }
        } catch (t: Throwable) {
            if (live) uiEmitBusy.set(false)
            Log.w(TAG, "decode jpeg", t)
        }
    }

    private fun playPcm(pcm: ByteArray) {
        synchronized(playbackLock) {
            try {
                val track = ensureAudioTrack()
                var offset = 0
                while (offset < pcm.size) {
                    val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    } else {
                        track.write(pcm, offset, pcm.size - offset)
                    }
                    if (written < 0) {
                        releaseAudioTrackLocked()
                        val retry = ensureAudioTrack()
                        val w2 = retry.write(pcm, offset, pcm.size - offset)
                        if (w2 < 0) break
                        offset += w2
                    } else if (written == 0) {
                        break
                    } else {
                        offset += written
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playPcm", t)
                releaseAudioTrackLocked()
            }
        }
    }

    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                if (it.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        it.play()
                    } catch (_: Exception) {
                    }
                }
                return it
            }
            releaseAudioTrackLocked()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            WalkieProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = max(minBuf * 2, WalkieProtocol.BYTES_PER_FRAME * 16)

        fun build(lowLatency: Boolean): AudioTrack {
            val b = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(WalkieProtocol.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            return b.build()
        }

        var track = build(true)
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            track = build(false)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack not initialized")
        }
        track.setVolume(1.0f)
        track.play()
        audioTrack = track
        return track
    }

    private fun preparePlaybackAudio() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            }
            if (savedMusicVolume < 0) {
                savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = max(savedMusicVolume, (maxVol * 0.75f).toInt().coerceAtLeast(1))
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "preparePlaybackAudio", t)
        }
    }

    private fun restorePlaybackAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            if (savedMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseAudioTrack() {
        synchronized(playbackLock) { releaseAudioTrackLocked() }
    }

    private fun releaseAudioTrackLocked() {
        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun key(peer: WalkiePeer) = "${peer.host}:${peer.port}"

    private fun safeDeviceName(): String {
        val model = Build.MODEL?.replace(Regex("[^A-Za-z0-9_-]"), "")?.take(18)
        val suffix = (1000 + (System.currentTimeMillis() % 9000)).toString()
        return if (model.isNullOrBlank()) "Phone-$suffix" else "$model-$suffix"
    }

    private fun postStatus(msg: String) {
        mainHandler.post { listener.onStatus(msg) }
    }

    private fun postError(msg: String) {
        mainHandler.post { listener.onError(msg) }
    }

    private fun postPeers() {
        mainHandler.post { listener.onPeersChanged(getPeers()) }
    }

    companion object {
        private const val TAG = "WalkieTalkieEngine"
        /** Live RX: idle before treating a gap as end-of-message (was 600 — cut last second). */
        private const val RECEIVE_IDLE_MS = 1_500L
        private const val GAIN = 3.5f
    }
}
