package com.example.barky.walkie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
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
import com.example.barky.net.ContactLabels
import com.example.barky.net.DeviceIdentity
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.PeerEndpoint
import com.example.barky.walkie.codec.AvcVideoDecoder
import com.example.barky.walkie.codec.AvcVideoEncoder
import com.example.barky.walkie.codec.OpusAudioCodec
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
import java.util.concurrent.CopyOnWriteArrayList
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
        /** Local GPS trail + peer trail for map (5h window). */
        fun onGpsUpdated(
            myTrail: List<LocationTracker.GpsPoint>,
            peerTrail: List<LocationTracker.GpsPoint>,
            gpsEnabled: Boolean
        ) {}
        fun onListenStateChanged(listening: Boolean, serving: Boolean) {}
        fun onSeeStateChanged(seeing: Boolean, serving: Boolean) {}
        /** SCRN: viewing contact screen / serving our screen. */
        fun onScreenStateChanged(requesting: Boolean, serving: Boolean) {}
        /**
         * Contact asked us to share **our** screen. UI must run MediaProjection consent
         * then call [startServingScreen].
         */
        fun onScreenShareRequest(peerHost: String, peerUdp: Int, peerVideo: Int) {}
        /** Camera preview buffer size for natural TextureView center-crop. */
        fun onLocalPreviewGeometry(
            previewW: Int,
            previewH: Int,
            displayOrientation: Int,
            front: Boolean
        ) {}
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
    /** Last HELLO wall-clock per host — used to drop stale ghost peers. */
    private val peerLastHelloMs = ConcurrentHashMap<String, Long>()
    @Volatile private var selectedPeerKey: String? = null
    /**
     * When true, Contacts / user picked a peer — HELLO/NSD must NOT steal selection.
     * (Was a real bug: fresher HELLO from another phone mixed S20/S918/Pixel.)
     */
    private val userLockedSelection = AtomicBoolean(false)
    /** Peer we asked to SEE (so peer-flip targets the phone we are watching). */
    @Volatile private var seeWatchPeer: WalkiePeer? = null

    private val running = AtomicBoolean(false)
    private val talking = AtomicBoolean(false)
    private val receiving = AtomicBoolean(false)
    private val replaying = AtomicBoolean(false)
    /** Remote peer is transmitting — local PTT must stay locked. */
    private val channelBusy = AtomicBoolean(false)
    /** Message that arrived while Replay was active — play when Replay ends. */
    @Volatile private var pendingAfterReplay: WalkieMessage? = null
    /**
     * True if the current (or just finished) RX overlapped an active Replay.
     * Clip may finalize *after* Replay ends; still auto-play then.
     */
    private val rxOverlappedReplay = AtomicBoolean(false)
    /** How the *receiver* should play the next clip (speaker vs earpiece/headset). */
    @Volatile private var pendingTxPlaybackMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER
    @Volatile private var rxPlaybackMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER
    /**
     * Preferred local playback route for the next AudioTrack rebuild.
     * Pixel (USAGE_MEDIA) ignores [AudioManager.setCommunicationDevice]; the track
     * must call [AudioTrack.setPreferredDevice] or LISTEN can stay on earpiece.
     */
    @Volatile private var preferHeadsetPlayback: Boolean = false
    /** We requested peer mic (LISTEN). */
    private val requestingListen = AtomicBoolean(false)
    /** Peer requested our mic. */
    private val servingListen = AtomicBoolean(false)
    /** We requested peer camera+mic (SEE). */
    private val requestingSee = AtomicBoolean(false)
    /** Peer requested our camera+mic. */
    private val servingSee = AtomicBoolean(false)
    /** Live monitor RX — do not store as last-message clip. */
    private val monitorReceiving = AtomicBoolean(false)
    private var monitorAudioThread: Thread? = null
    private var monitorVideoThread: Thread? = null
    private val peerGpsTrail = CopyOnWriteArrayList<LocationTracker.GpsPoint>()
    private var locationTracker: LocationTracker? = null
    private val gpsShareRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (locationTracker?.isEnabled() == true) {
                val latest = locationTracker?.latest()
                if (latest != null) {
                    sendGpsPoint(latest)
                } else {
                    Log.w(TAG, "GPS share tick: enabled but no fix yet peers=${getPeers().size}")
                }
            }
            mainHandler.postDelayed(this, GPS_SHARE_INTERVAL_MS)
        }
    }
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
    /** Active SEE serve camera (own file: [SeeCameraSession]). */
    @Volatile private var seeCameraSession: SeeCameraSession? = null
    /** Local idle preview (so flip works before HOLD). */
    private var previewSource: CameraFrameSource? = null
    @Volatile private var localPreviewWanted = false
    /** TextureView surface from MainActivity — hardware preview, no JPEG flicker. */
    @Volatile private var uiPreviewTexture: SurfaceTexture? = null
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
    /** deviceId → current preferred host (for Firebase path refresh / PATH_UPDATE). */
    private val peerHostByDeviceId = ConcurrentHashMap<String, String>()
    /** host → deviceId when known. */
    private val hostToDeviceId = ConcurrentHashMap<String, String>()
    /** host → HELLO capability flags (CAP_OPUS | CAP_AVC). */
    private val peerCaps = ConcurrentHashMap<String, Int>()
    private val localCaps: Int by lazy {
        var c = 0
        if (OpusAudioCodec.isAvailable()) c = c or WalkieProtocol.CAP_OPUS
        if (AvcVideoEncoder.isAvailable() && AvcVideoDecoder.isAvailable()) {
            c = c or WalkieProtocol.CAP_AVC
        }
        Log.i(TAG, "local codec caps=0x${c.toString(16)} opus=${c and WalkieProtocol.CAP_OPUS != 0} avc=${c and WalkieProtocol.CAP_AVC != 0}")
        c
    }
    private val opusTx = OpusAudioCodec()
    private val opusRx = OpusAudioCodec()
    private var avcEncoder: AvcVideoEncoder? = null
    private val avcDecoder = AvcVideoDecoder()

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

    fun localUdpPort(): Int = localPort
    fun localVideoTcpPort(): Int = localVideoPort

    /**
     * Adopt a Firebase/LAN directory entry as the active peer (for Contacts).
     * Prefers LAN host when both private; else public STUN mapping.
     * [deviceId] (optional) enables mid-call Firebase path refresh for that contact.
     */
    fun adoptDirectoryPeer(
        displayName: String,
        host: String,
        udpPort: Int,
        videoPort: Int,
        deviceId: String? = null
    ): Boolean {
        if (host.isBlank() || udpPort <= 0) {
            postError("Invalid contact address")
            return false
        }
        val cleanName = friendlyDisplayName(displayName, host)
        val peer = WalkiePeer(
            serviceName = "Barky-$cleanName",
            host = host,
            port = udpPort,
            videoPort = if (videoPort > 0) videoPort else udpPort
        )
        val vport = if (videoPort > 0) videoPort else udpPort
        // If we already track this deviceId, remap when host/ports change (Firebase poll / Contacts).
        val existingHost = deviceId?.takeIf { it.isNotBlank() }?.let { peerHostByDeviceId[it] }
        val existing = existingHost?.let { h -> peers.values.find { it.host == h } }
            ?: peers.values.find { it.host == host }
        if (existing != null &&
            existing.host == host &&
            existing.port == udpPort &&
            existing.videoPort == vport
        ) {
            // Unchanged path — still select + upgrade ugly NSD names to Contacts title
            peerLastHelloMs[host] = System.currentTimeMillis()
            val renamed = existing.copy(serviceName = "Barky-$cleanName")
            // Drop other host:port keys so selection is unique
            peers.entries.removeIf { it.value.host == host && it.key != key(renamed) }
            selectedPeerKey = key(renamed)
            userLockedSelection.set(true)
            peers[key(renamed)] = renamed
            if (!deviceId.isNullOrBlank()) {
                peerHostByDeviceId[deviceId] = host
                hostToDeviceId[host] = deviceId
            }
            postPeers()
            postStatus("Selected $cleanName @ $host:$udpPort")
            Log.i(
                TAG,
                "adoptDirectoryPeer re-select $cleanName → $host udp=$udpPort " +
                    "video=$vport deviceId=$deviceId LOCKED"
            )
            return true
        }
        if (existingHost != null && existingHost != host) {
            remapPeerHost(
                oldHost = existingHost,
                newHost = host,
                udpPort = udpPort,
                videoPort = vport,
                deviceId = deviceId,
                displayName = cleanName
            )
        } else {
            peers[key(peer)] = peer
            selectedPeerKey = key(peer)
            userLockedSelection.set(true)
            if (!deviceId.isNullOrBlank()) {
                peerHostByDeviceId[deviceId] = host
                hostToDeviceId[host] = deviceId
            }
            postPeers()
            thread(name = "walkie-adopt-hello", isDaemon = true) {
                try {
                    sendHelloTo(peer)
                    sendPathUpdateTo(peer)
                    // Internet / cellular peer: aggressive hole-punch both ways
                    if (!com.example.barky.net.PeerEndpoint.isPrivateIp(host) &&
                        !BarkyPrefs.isWifiOnly(appContext)
                    ) {
                        if (publicHost.isNullOrBlank()) {
                            try {
                                refreshInternetInvite()
                            } catch (_: Throwable) {
                            }
                        }
                        holePunchPeer(peer, rounds = 40)
                    }
                } catch (_: Throwable) {
                }
            }
        }
        userLockedSelection.set(true)
        postStatus("Selected $cleanName @ $host:$udpPort")
        Log.i(
            TAG,
            "adoptDirectoryPeer $cleanName → $host udp=$udpPort video=$videoPort " +
                "deviceId=$deviceId LOCKED"
        )
        return true
    }

    /** UDP HELLO/PING burst so public-IP peers can receive SCRN/LISTEN on cellular. */
    private fun holePunchPeer(peer: WalkiePeer, rounds: Int = 15) {
        repeat(rounds) {
            if (!running.get()) return
            try {
                sendHelloTo(peer)
                val poke = byteArrayOf(WalkieProtocol.TYPE_PING)
                sendUdp(
                    DatagramPacket(
                        poke,
                        poke.size,
                        InetAddress.getByName(peer.host),
                        peer.port
                    )
                )
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                return
            }
        }
        Log.i(TAG, "holePunch done → ${peer.host}:${peer.port}")
    }

    /** Strip NSD junk → stable label (Pixel 8 / S20 / S23). Never return bare IP. */
    private fun friendlyDisplayName(raw: String, host: String): String {
        return ContactLabels.fromPeerName(raw, host)
    }

    private fun isUglyPeerName(serviceName: String): Boolean {
        val d = serviceName.removePrefix(WalkieProtocol.SERVICE_NAME_PREFIX)
        return d.startsWith("udp-") || d.startsWith("path-") || d.startsWith("inet-") ||
            d.contains(Regex("-v\\d{2,5}$")) || d.matches(Regex("^[A-Za-z0-9]+-\\d{4}$"))
    }

    /** Device ids of peers we care about for Firebase path poll during a live session. */
    fun trackedPeerDeviceIds(): List<String> = peerHostByDeviceId.keys.toList()

    fun hasActiveMediaSession(): Boolean =
        talking.get() || receiving.get() || requestingListen.get() || servingListen.get() ||
            requestingSee.get() || servingSee.get() || channelBusy.get()

    /**
     * Called when NIC / Wi‑Fi / cell / local IP changes.
     * Refresh STUN public mapping, announce PATH_UPDATE + HELLO to peers, reconnect video TCP.
     */
    fun onLocalPathChanged(reason: String = "nic") {
        if (!running.get()) return
        thread(name = "walkie-path-change", isDaemon = true) {
            try {
                Log.i(TAG, "onLocalPathChanged reason=$reason")
                // Wi‑Fi→cell: wait for default network to validate before STUN
                try {
                    Thread.sleep(1_200)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!BarkyPrefs.isWifiOnly(appContext)) {
                    try {
                        refreshInternetInvite()
                    } catch (t: Throwable) {
                        Log.w(TAG, "STUN after path change", t)
                    }
                }
                val known = peers.values.toList()
                // Burst HELLO + PATH_UPDATE so the other side remaps immediately
                repeat(8) {
                    if (!running.get()) return@thread
                    for (p in known) {
                        try {
                            sendHelloTo(p)
                            sendPathUpdateTo(p)
                            val poke = byteArrayOf(WalkieProtocol.TYPE_PING)
                            sendUdp(
                                DatagramPacket(
                                    poke,
                                    poke.size,
                                    InetAddress.getByName(p.host),
                                    p.port
                                )
                            )
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        Thread.sleep(60)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
                // If we were pushing video (SEE serve / HOLD), re-open TCP to peer
                if (servingSee.get() || talking.get()) {
                    val dest = monitorTarget ?: bestLivePeer()
                    if (dest != null) {
                        closeVideoOut()
                        openVideoOut(dest)
                        Log.i(TAG, "video TCP reconnected after path change → ${dest.host}")
                    }
                }
                postStatus("Network path updated — re-announced to peers")
                videoDebug("Path change ($reason): re-HELLO + PATH_UPDATE to ${known.size} peers")
            } catch (t: Throwable) {
                Log.w(TAG, "onLocalPathChanged", t)
            }
        }
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
     * Switch front/back on HOLD, SEE-serve, SEE-watch PiP, or idle preview.
     * Never opens a second Camera when [cameraSource] already owns the hardware
     * (dual-SEE crash fix).
     */
    fun switchCamera(): Boolean {
        val talkCam = cameraSource
        if (talkCam != null) {
            talkCam.switchCamera()
            Log.i(TAG, "switchCamera via cameraSource talking=${talking.get()} servingSee=${servingSee.get()}")
            return true
        }
        val seeCam = seeCameraSession
        if (seeCam != null && seeCam.isRunning()) {
            val ok = seeCam.switchCamera()
            Log.i(TAG, "switchCamera via SeeCameraSession ok=$ok")
            return ok
        }
        val prev = previewSource
        if (prev != null) {
            prev.switchCamera()
            Log.i(TAG, "switchCamera via previewSource")
            return true
        }
        // Prefer existing hardware only — do not start a second Camera while busy
        if (servingSee.get() || talking.get()) {
            BarkyPrefs.toggleCameraFront(appContext)
            Log.w(TAG, "switchCamera: camera busy, toggled preference only")
            return false
        }
        // SEE watch PiP (not serving): ensure local preview then flip
        if (requestingSee.get()) {
            localPreviewWanted = true
            startLocalPreviewInternal()
            previewSource?.let {
                it.switchCamera()
                return true
            }
        }
        // No camera open yet — flip preference and start preview so user sees it now
        BarkyPrefs.toggleCameraFront(appContext)
        if (localPreviewWanted && !talking.get() && !replaying.get()) {
            startLocalPreviewInternal()
        }
        val front = BarkyPrefs.isCameraFront(appContext)
        postStatus(if (front) "Front camera" else "Back camera")
        return true
    }

    /**
     * Ask peer to flip *their* camera (front/back). Works for one-way SEE and dual SEE.
     * Sends a **single** control (not the 5× reliable burst) so the peer toggles once.
     */
    fun requestPeerCameraFlip(): Boolean {
        // Prefer the phone we are actively watching (SEE), not a stale selected ghost
        val primary = seeWatchPeer ?: bestLivePeer() ?: currentPeer() ?: getPeers().firstOrNull()
        if (primary == null || primary.port <= 0) {
            postError("No peer to flip camera")
            return false
        }
        thread(name = "walkie-cam-flip-req", isDaemon = true) {
            try {
                // Fan-out to all live hosts (ghost IPs pruned) — each debounces to one toggle
                val targets = linkedMapOf<String, WalkiePeer>()
                targets[primary.host] = primary
                bestLivePeer()?.let { targets[it.host] = it }
                getPeers().forEach { p ->
                    val age = System.currentTimeMillis() - (peerLastHelloMs[p.host] ?: 0L)
                    if (age < 12_000L) targets[p.host] = p
                }
                for (peer in targets.values) {
                    val ports = linkedSetOf(peer.port)
                    peers.values.filter { it.host == peer.host }.forEach { ports.add(it.port) }
                    for (port in ports.filter { it > 0 }) {
                        val p = peer.copy(port = port)
                        repeat(3) {
                            sendControlOnce(p, WalkieProtocol.TYPE_CAMERA_FLIP)
                            Thread.sleep(25)
                        }
                    }
                    Log.i(TAG, "CAMERA_FLIP_REQ → ${peer.host} ports=$ports")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "requestPeerCameraFlip", t)
            }
        }
        postStatus("Asked peer to flip camera…")
        Log.i(TAG, "CAMERA_FLIP_REQ primary → ${primary.host}:${primary.port}")
        return true
    }

    /** Debounce peer flip storms (UDP duplicates / multi-port fan-out). */
    @Volatile private var lastCameraFlipAt = 0L

    /** Peer asked us to flip our lens (while we serve SEE / have a camera open). */
    private fun handleCameraFlipReq(from: String) {
        val now = System.currentTimeMillis()
        if (now - lastCameraFlipAt < 900L) {
            Log.i(TAG, "CAMERA_FLIP from $from ignored (debounce)")
            return
        }
        lastCameraFlipAt = now
        Log.i(TAG, "CAMERA_FLIP from $from servingSee=${servingSee.get()} talking=${talking.get()}")
        val ok = switchCamera()
        postStatus(if (ok) "Camera flipped (peer request)" else "Camera flip requested")
    }

    fun isCameraFrontPreferred(): Boolean = BarkyPrefs.isCameraFront(appContext)

    /** Bind TextureView surface for flicker-free local camera display. */
    fun setUiPreviewTexture(texture: SurfaceTexture?) {
        uiPreviewTexture = texture
        Log.i(TAG, "uiPreviewTexture set=${texture != null}")
        // Never restart a second Camera while serve/HOLD owns the hardware
        if (texture != null && localPreviewWanted && !talking.get() &&
            !servingSee.get() && cameraSource == null
        ) {
            stopLocalPreviewInternal()
            startLocalPreviewInternal()
        }
    }

    /** Start local viewfinder so flip is instant (before HOLD). */
    fun startLocalPreview() {
        localPreviewWanted = true
        // Allow local PiP while SEE (watching peer) — WhatsApp-style dual view
        if (talking.get() || replaying.get()) return
        if (receiving.get() && !requestingSee.get()) return
        // While serving SEE we already own the camera via cameraSource
        if (servingSee.get() && cameraSource != null) return
        startLocalPreviewInternal()
    }

    fun stopLocalPreview() {
        localPreviewWanted = false
        stopLocalPreviewInternal()
    }

    private fun startLocalPreviewInternal() {
        if (previewSource != null || talking.get()) return
        if (!running.get()) return
        // Dual-SEE / serve-SEE already owns Camera1 — never open a second instance
        if (servingSee.get() || cameraSource != null) {
            Log.i(TAG, "startLocalPreviewInternal skip: camera owned by serve/HOLD")
            return
        }
        try {
            stopLocalPreviewInternal()
            val hasHw = uiPreviewTexture != null
            val cam = CameraFrameSource(
                context = appContext,
                compactForUdp = { false },
                config = CaptureConfig.Preview,
                preferFrontCamera = BarkyPrefs.isCameraFront(appContext),
                externalSurfaceTexture = { uiPreviewTexture },
                onFacingChanged = { front ->
                    BarkyPrefs.setCameraFront(appContext, front)
                    videoDebug("Preview camera: ${if (front) "front" else "back"}")
                    postStatus(if (front) "Front camera" else "Back camera")
                },
                onPreviewReady = { w, h, orient, front ->
                    mainHandler.post {
                        listener.onLocalPreviewGeometry(w, h, orient, front)
                    }
                },
                onError = { msg -> videoDebug("Preview camera: $msg") }
            ) { jpeg ->
                // Never paint local JPEGs into the remote video surface during SEE
                // (Pixel often has uiPreviewTexture null briefly → local frames filled walkie_video).
                if (talking.get() || replaying.get() || receiving.get() || requestingSee.get()) {
                    return@CameraFrameSource
                }
                // Hardware TextureView already shows camera — skip JPEG UI (kills flicker)
                if (uiPreviewTexture != null) return@CameraFrameSource
                decodeAndEmit(jpeg, live = true, localPreview = true)
            }
            previewSource = cam
            cam.start()
            videoDebug(
                "Local preview started front=${BarkyPrefs.isCameraFront(appContext)} " +
                    "hardwareSurface=$hasHw"
            )
            Log.i(TAG, "Local preview hardwareSurface=$hasHw")
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

    @Volatile private var lastStunOkMs = 0L
    @Volatile private var stunFailStreak = 0
    @Volatile private var stunRetryScheduled = false

    private fun refreshInternetInvite() {
        val sock = socket
        if (sock == null || sock.isClosed) {
            postError("Internet mode: UDP socket not ready")
            return
        }
        val onWifi = NetworkEndpoints.isWifi(appContext)
        // Quick first when we already have a mapping or on cellular (fail-fast)
        val quick = !onWifi || !publicHost.isNullOrBlank()
        Log.i(TAG, "STUN begin quick=$quick wifi=$onWifi last=$publicHost:$publicUdpPort")
        stunExclusive = true
        val mapped = try {
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
            }
            StunClient.discoverMappedAddress(sock, quick = quick)
                ?: if (quick) StunClient.discoverMappedAddress(sock, quick = false) else null
        } finally {
            stunExclusive = false
        }
        Log.i(TAG, "STUN end mapped=${mapped?.host}:${mapped?.port}")
        if (mapped == null) {
            stunFailStreak++
            Log.w(
                TAG,
                "STUN failed streak=$stunFailStreak keep last public=$publicHost:$publicUdpPort wifi=$onWifi"
            )
            // Don't spam "STUN busy" on every fail — only when mapping missing or every ~20s
            val now = System.currentTimeMillis()
            if (publicHost.isNullOrBlank() || now - lastStunOkMs > 20_000L) {
                postStatus(
                    if (!publicHost.isNullOrBlank()) {
                        "Public path: $publicHost (refreshing…)"
                    } else {
                        "Finding public path for cellular… (retry $stunFailStreak)"
                    }
                )
            }
            scheduleStunRetry(if (onWifi) 5_000L else 2_500L)
            return
        }
        stunFailStreak = 0
        lastStunOkMs = System.currentTimeMillis()
        val changed = publicHost != mapped.host || publicUdpPort != mapped.port
        publicHost = mapped.host
        publicUdpPort = mapped.port
        val code = PeerInviteCodec.encode(mapped.host, mapped.port, localVideoPort)
        myInviteCode = code
        Log.i(
            TAG,
            "Internet invite ready public=${mapped.host}:${mapped.port} " +
                "localUdp=$localPort localVideoTcp=$localVideoPort wifi=$onWifi changed=$changed"
        )
        postStatus("Public path ${mapped.host}:${mapped.port}")
        mainHandler.post { listener.onInternetInviteChanged(code) }
        // Keep NAT mapping warm on cellular
        if (!onWifi) {
            scheduleStunRetry(25_000L)
        }
        // Push Firebase + PATH_UPDATE so peers learn our public endpoint
        if (changed) {
            try {
                for (p in getPeers()) {
                    sendHelloTo(p)
                    sendPathUpdateTo(p)
                    if (!com.example.barky.net.PeerEndpoint.isPrivateIp(p.host)) {
                        thread(name = "walkie-punch-path", isDaemon = true) {
                            holePunchPeer(p, rounds = 12)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleStunRetry(delayMs: Long) {
        if (BarkyPrefs.isWifiOnly(appContext)) return
        if (stunRetryScheduled) return
        stunRetryScheduled = true
        mainHandler.postDelayed({
            stunRetryScheduled = false
            if (!running.get() || BarkyPrefs.isWifiOnly(appContext)) return@postDelayed
            thread(name = "walkie-stun-retry", isDaemon = true) {
                try {
                    refreshInternetInvite()
                } catch (_: Throwable) {
                }
            }
        }, delayMs)
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
        stopListenRemote()
        stopSeeRemote()
        stopServingListen()
        stopServingSee()
        setGpsEnabled(false)
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
        userLockedSelection.set(false)
        postPeers()
        postStatus("Walkie stopped")
    }

    fun selectPeer(peer: WalkiePeer?) {
        selectedPeerKey = peer?.let { key(it) }
        userLockedSelection.set(peer != null)
        postStatus(
            if (peer == null) "No peer selected"
            else "Selected ${peer.displayName} (${peer.host})"
        )
    }

    /** UI-facing selection (Contacts / SCRN / SEE). */
    fun currentSelectedPeer(): WalkiePeer? = currentPeer()

    fun getPeers(): List<WalkiePeer> = peers.values.sortedBy { it.displayName }

    fun hasLastMessage(): Boolean = lastMessage?.hasContent == true

    fun isChannelBusy(): Boolean = channelBusy.get() || receiving.get() || flushingRecorded.get()

    /**
     * @param playbackMode WalkieProtocol.AUDIO_MODE_SPEAKER or AUDIO_MODE_HEADSET —
     * tells the *receiver* how to play this message (privacy earpiece vs loudspeaker).
     */
    fun startTalking(playbackMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER) {
        if (!running.get()) {
            postError("Walkie is not running")
            return
        }
        // Mid-hold mode change (slide left/right on mic) — re-signal privacy mode only
        if (talking.get()) {
            pendingTxPlaybackMode = playbackMode
            currentPeer()?.let { sendTalkStart(it) }
            Log.i(TAG, "playback mode update while talking → $playbackMode")
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
        // Camera exclusive: pause SEE serve while we send a message
        if (servingSee.get()) stopServingSee()
        if (!talking.compareAndSet(false, true)) return
        pendingTxPlaybackMode = playbackMode
        // Free camera hardware for TX session
        stopLocalPreviewInternal()

        // Re-read prefs with app context (same file as Activity); log for adb smoke
        val best = BarkyPrefs.isBestQualityVideo(appContext)
        bestQualitySession.set(best)
        Log.i(
            TAG,
            "startTalking bestQuality=$best playbackMode=$playbackMode (from prefs)"
        )
        videoDebug(
            "startTalking mode=${if (best) "BEST_RECORD" else "LIVE_STREAM"} " +
                "rxPlayback=${if (playbackMode == WalkieProtocol.AUDIO_MODE_HEADSET) "headset" else "speaker"}"
        )
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
                    externalSurfaceTexture = { uiPreviewTexture },
                    onFacingChanged = { front ->
                        BarkyPrefs.setCameraFront(appContext, front)
                        videoDebug("Camera facing: ${if (front) "front" else "back"}")
                    },
                    onPreviewReady = { w, h, orient, front ->
                        mainHandler.post { listener.onLocalPreviewGeometry(w, h, orient, front) }
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
                    // TextureView shows self; only fall back to JPEG UI without surface
                    if (uiPreviewTexture == null) {
                        decodeAndEmit(jpeg, live = true)
                    }
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
                externalSurfaceTexture = { uiPreviewTexture },
                onFacingChanged = { front ->
                    BarkyPrefs.setCameraFront(appContext, front)
                    videoDebug("Camera facing: ${if (front) "front" else "back"}")
                },
                onPreviewReady = { w, h, orient, front ->
                    mainHandler.post { listener.onLocalPreviewGeometry(w, h, orient, front) }
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
                // Self-view via TextureView (no JPEG flash); still encode/send jpeg
                if (uiPreviewTexture == null) {
                    decodeAndEmit(jpeg, live = true)
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

    /**
     * @param quiet true only for automated smoke (soft beep). UI always uses loud.
     */
    fun sendWakeup(quiet: Boolean = false) {
        val peer = currentPeer()
        if (peer == null) {
            postError("Select a peer first")
            return
        }
        mainHandler.removeCallbacks(remoteWakeupClear)
        mainHandler.post { listener.onRemoteWakeupActive(true) }
        thread(name = "walkie-wakeup", isDaemon = true) {
            try {
                // Optional 2nd byte: 1 = quiet smoke ring (receiver does not blast)
                val payload = if (quiet) {
                    byteArrayOf(WalkieProtocol.TYPE_WAKEUP, 1)
                } else {
                    byteArrayOf(WalkieProtocol.TYPE_WAKEUP, 0)
                }
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                repeat(8) {
                    sendUdp(packet)
                    Thread.sleep(40)
                }
                Log.i(TAG, "WAKEUP RING sent quiet=$quiet to ${peer.displayName} host=${peer.host}")
                postStatus("WAKEUP RING sent to ${peer.displayName}")
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
        // Snapshot now so an incoming message can safely overwrite lastMessage during replay
        val audioSnap = msg.audioPcm.copyOf()
        val framesSnap = msg.frames.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) }
        thread(name = "walkie-replay", isDaemon = true) {
            try {
                playSyncedClip(
                    audio = audioSnap,
                    frames = framesSnap,
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
                // If a new video arrived mid-replay, play it next (no crash / no drop)
                playPendingAfterReplayIfAny()
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
        playPendingAfterReplayIfAny()
    }

    private fun playPendingAfterReplayIfAny() {
        val next = pendingAfterReplay
        pendingAfterReplay = null
        if (next == null || !next.hasContent) {
            maybeResumeLocalPreview()
            return
        }
        if (talking.get() || flushingRecorded.get()) {
            maybeResumeLocalPreview()
            return
        }
        Log.i(TAG, "Auto-playing queued message after replay frames=${next.frames.size}")
        videoDebug("Playing next video that arrived during Replay…")
        if (!replaying.compareAndSet(false, true)) {
            maybeResumeLocalPreview()
            return
        }
        stopLocalPreviewInternal()
        mainHandler.post { listener.onReplayStateChanged(true) }
        postStatus("Playing next message…")
        val audioSnap = next.audioPcm.copyOf()
        val framesSnap = next.frames.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) }
        thread(name = "walkie-replay-queued", isDaemon = true) {
            try {
                playSyncedClip(
                    audio = audioSnap,
                    frames = framesSnap,
                    active = { replaying.get() },
                    label = "QueuedReplay"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "queued replay failed", t)
            } finally {
                replaying.set(false)
                mainHandler.post {
                    listener.onReplayStateChanged(false)
                    listener.onStatus("Ready")
                }
                playPendingAfterReplayIfAny()
            }
        }
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
                    // Hold playbackLock for the whole stream so live RX cannot write
                    // the same AudioTrack mid-replay (native SIGABRT / releaseBuffer).
                    synchronized(playbackLock) {
                        val track = ensureAudioTrack()
                        var off = 0
                        val chunk = WalkieProtocol.BYTES_PER_FRAME
                        while (active() && off < audio.size) {
                            val n = min(chunk, audio.size - off)
                            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                track.write(audio, off, n, AudioTrack.WRITE_BLOCKING)
                            } else {
                                track.write(audio, off, n)
                            }
                            if (written <= 0) break
                            off += written
                        }
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
                val mode = pendingTxPlaybackMode
                val payload = byteArrayOf(WalkieProtocol.TYPE_TALK_START, mode)
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                repeat(4) {
                    sendUdp(packet)
                    Thread.sleep(15)
                }
                Log.i(TAG, "TALK_START sent mode=$mode to ${peer.host}")
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
        // Never on main — Pixel StrictMode throws NetworkOnMainThreadException
        thread(name = "walkie-hello", isDaemon = true) {
            try {
                // [HELLO][vport:u16][caps:u8] — older peers ignore trailing caps
                val buf = ByteArray(4)
                buf[0] = WalkieProtocol.TYPE_HELLO
                buf[1] = ((localVideoPort ushr 8) and 0xFF).toByte()
                buf[2] = (localVideoPort and 0xFF).toByte()
                buf[3] = (localCaps and 0xFF).toByte()
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
    }

    private fun peerWantsOpus(host: String): Boolean {
        val caps = peerCaps[host] ?: 0
        return (localCaps and WalkieProtocol.CAP_OPUS) != 0 &&
            (caps and WalkieProtocol.CAP_OPUS) != 0
    }

    private fun peerWantsAvc(host: String): Boolean {
        val caps = peerCaps[host] ?: 0
        return (localCaps and WalkieProtocol.CAP_AVC) != 0 &&
            (caps and WalkieProtocol.CAP_AVC) != 0
    }

    /** Build PATH_UPDATE payload with current local/public endpoints. */
    private fun buildPathUpdatePayload(): ByteArray {
        val localIp = NetworkEndpoints.findLocalIpv4() ?: ""
        val pubIp = publicHost ?: ""
        val pubPort = publicUdpPort
        val onWifi = NetworkEndpoints.isWifi(appContext)
        val deviceId = try {
            DeviceIdentity.deviceId(appContext)
        } catch (_: Throwable) {
            ""
        }
        val localBytes = localIp.toByteArray(Charsets.UTF_8)
        val pubBytes = pubIp.toByteArray(Charsets.UTF_8)
        val idBytes = deviceId.toByteArray(Charsets.UTF_8)
        fun clampLen(n: Int) = min(255, max(0, n))
        val lb = clampLen(localBytes.size)
        val pb = clampLen(pubBytes.size)
        val ib = clampLen(idBytes.size)
        val out = ByteArray(1 + 2 + 2 + 2 + 1 + 1 + lb + 1 + pb + 1 + ib)
        var i = 0
        out[i++] = WalkieProtocol.TYPE_PATH_UPDATE
        out[i++] = ((localPort ushr 8) and 0xFF).toByte()
        out[i++] = (localPort and 0xFF).toByte()
        out[i++] = ((localVideoPort ushr 8) and 0xFF).toByte()
        out[i++] = (localVideoPort and 0xFF).toByte()
        out[i++] = ((pubPort ushr 8) and 0xFF).toByte()
        out[i++] = (pubPort and 0xFF).toByte()
        out[i++] = if (onWifi) 1 else 0
        out[i++] = lb.toByte()
        System.arraycopy(localBytes, 0, out, i, lb)
        i += lb
        out[i++] = pb.toByte()
        System.arraycopy(pubBytes, 0, out, i, pb)
        i += pb
        out[i++] = ib.toByte()
        System.arraycopy(idBytes, 0, out, i, ib)
        return out
    }

    private fun sendPathUpdateTo(peer: WalkiePeer) {
        thread(name = "walkie-path-tx", isDaemon = true) {
            try {
                val buf = buildPathUpdatePayload()
                sendUdp(
                    DatagramPacket(
                        buf,
                        buf.size,
                        InetAddress.getByName(peer.host),
                        peer.port
                    )
                )
                Log.i(TAG, "PATH_UPDATE → ${peer.host}:${peer.port} len=${buf.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "sendPathUpdate", t)
            }
        }
    }

    private fun handlePathUpdate(data: ByteArray, fromHost: String, fromUdpPort: Int) {
        if (data.size < 9) return
        var i = 1
        fun u16(): Int {
            val v = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            return v
        }
        fun str(): String {
            if (i >= data.size) return ""
            val len = data[i].toInt() and 0xFF
            i++
            if (len <= 0 || i + len > data.size) return ""
            val s = String(data, i, len, Charsets.UTF_8)
            i += len
            return s
        }
        val peerLocalUdp = u16()
        val peerVideo = u16()
        val peerPublicUdp = u16()
        val flags = data[i++].toInt() and 0xFF
        val peerLocalIp = str()
        val peerPublicIp = str()
        val deviceId = str()
        val onWifi = flags and 1 != 0
        // Prefer LAN only with live HELLO on private IP; else public mapping (cellular-safe).
        val selfLocal = NetworkEndpoints.findLocalIpv4()
        val liveLanHello = peerLocalIp.isNotBlank() &&
            PeerEndpoint.isPrivateIp(peerLocalIp) &&
            (System.currentTimeMillis() - (peerLastHelloMs[peerLocalIp] ?: 0L)) < 15_000L
        val preferLan = NetworkEndpoints.preferLanPath(
            NetworkEndpoints.Snapshot(selfLocal, publicHost, publicUdpPort, NetworkEndpoints.isWifi(appContext), null),
            peerLocalIp.takeIf { it.isNotBlank() },
            null,
            liveLanHello = liveLanHello
        )
        val newHost = when {
            preferLan && peerLocalIp.isNotBlank() -> peerLocalIp
            peerPublicIp.isNotBlank() -> peerPublicIp
            peerLocalIp.isNotBlank() -> peerLocalIp
            else -> fromHost
        }
        val newUdp = when {
            preferLan && peerLocalUdp > 0 -> peerLocalUdp
            peerPublicUdp > 0 -> peerPublicUdp
            peerLocalUdp > 0 -> peerLocalUdp
            else -> fromUdpPort
        }
        val newVideo = if (peerVideo > 0) peerVideo else newUdp
        Log.i(
            TAG,
            "PATH_UPDATE from=$fromHost → host=$newHost udp=$newUdp video=$newVideo " +
                "local=$peerLocalIp public=$peerPublicIp wifi=$onWifi deviceId=$deviceId"
        )
        val oldHost = when {
            !deviceId.isBlank() && peerHostByDeviceId.containsKey(deviceId) ->
                peerHostByDeviceId[deviceId]
            else -> fromHost
        }
        if (oldHost != null && (oldHost != newHost || peers.values.none { it.host == newHost })) {
            remapPeerHost(
                oldHost = oldHost,
                newHost = newHost,
                udpPort = newUdp,
                videoPort = newVideo,
                deviceId = deviceId.takeIf { it.isNotBlank() },
                displayName = null
            )
        } else {
            // Fresh peer path
            val peer = WalkiePeer(
                serviceName = "Barky-path-$newHost",
                host = newHost,
                port = newUdp,
                videoPort = newVideo
            )
            peers[key(peer)] = peer
            learnedVideoPorts[newHost] = newVideo
            peerLastHelloMs[newHost] = System.currentTimeMillis()
            if (!deviceId.isBlank()) {
                peerHostByDeviceId[deviceId] = newHost
                hostToDeviceId[newHost] = deviceId
            }
            if (selectedPeerKey == null) selectedPeerKey = key(peer)
            postPeers()
        }
        // Keep NAT warm back
        val p = peers.values.find { it.host == newHost }
        if (p != null) {
            sendHelloTo(p)
            sendPathUpdateTo(p)
        }
    }

    /**
     * Move a peer's host/ports after NIC change so ongoing talk/SEE/LISTEN target the new IP.
     */
    private fun remapPeerHost(
        oldHost: String,
        newHost: String,
        udpPort: Int,
        videoPort: Int,
        deviceId: String?,
        displayName: String?
    ) {
        if (newHost.isBlank() || udpPort <= 0) return
        if (oldHost == newHost) {
            // Same host, update ports only
            peers.entries.filter { it.value.host == newHost }.forEach { e ->
                val updated = e.value.copy(port = udpPort, videoPort = videoPort)
                peers[e.key] = updated
                if (selectedPeerKey == e.key) selectedPeerKey = key(updated)
            }
            learnedVideoPorts[newHost] = videoPort
            peerLastHelloMs[newHost] = System.currentTimeMillis()
            postPeers()
            return
        }
        val oldPeers = peers.entries.filter { it.value.host == oldHost }
        val baseName = displayName
            ?: oldPeers.firstOrNull()?.value?.displayName
            ?: newHost
        // Remove old host entries
        oldPeers.forEach { peers.remove(it.key) }
        peerLastHelloMs.remove(oldHost)
        learnedVideoPorts.remove(oldHost)
        hostToDeviceId.remove(oldHost)
        val peer = WalkiePeer(
            serviceName = "Barky-$baseName",
            host = newHost,
            port = udpPort,
            videoPort = videoPort
        )
        peers[key(peer)] = peer
        learnedVideoPorts[newHost] = videoPort
        peerLastHelloMs[newHost] = System.currentTimeMillis()
        if (!deviceId.isNullOrBlank()) {
            peerHostByDeviceId[deviceId] = newHost
            hostToDeviceId[newHost] = deviceId
        }
        // Retarget session state
        if (selectedPeerKey != null && peers[selectedPeerKey] == null) {
            selectedPeerKey = key(peer)
        } else if (selectedPeerKey == null || oldPeers.any { it.key == selectedPeerKey }) {
            selectedPeerKey = key(peer)
        }
        if (seeWatchPeer?.host == oldHost) seeWatchPeer = peer
        if (monitorTarget?.host == oldHost) monitorTarget = peer
        postPeers()
        Log.i(TAG, "remapped peer $oldHost → $newHost:$udpPort (video=$videoPort) deviceId=$deviceId")
        // Reconnect video if we were streaming to old host
        if (servingSee.get() || talking.get()) {
            thread(name = "walkie-remap-video", isDaemon = true) {
                try {
                    closeVideoOut()
                    openVideoOut(peer)
                } catch (t: Throwable) {
                    Log.w(TAG, "remap video reconnect", t)
                }
            }
        }
        // Announce back so peer also has our path (bidirectional)
        sendHelloTo(peer)
        sendPathUpdateTo(peer)
        videoDebug("Peer path remapped $oldHost → $newHost:$udpPort")
    }

    private fun handleHello(data: ByteArray, fromHost: String, fromUdpPort: Int) {
        if (data.size < 3) return
        // Never treat our own IP as a peer (breaks selection / SCRN targeting)
        val selfIp = NetworkEndpoints.findLocalIpv4()
        if (!selfIp.isNullOrBlank() && fromHost == selfIp) return
        val vport = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        if (vport <= 0 || vport > 65535) return
        if (data.size >= 4) {
            peerCaps[fromHost] = data[3].toInt() and 0xFF
        }
        learnedVideoPorts[fromHost] = vport
        // Upsert peer so audio-discovered hosts also become full peers.
        // Never replace a good Contacts/Firebase name with udp-/NSD junk.
        val existing = peers.values.find { it.host == fromHost }
        val peer = if (existing != null) {
            existing.copy(port = fromUdpPort, videoPort = vport)
        } else {
            WalkiePeer(
                serviceName = "udp-$fromHost",
                host = fromHost,
                port = fromUdpPort,
                videoPort = vport
            )
        }
        // Collapse host:oldPort keys → single live key
        peers.entries.removeIf { it.value.host == fromHost && it.key != key(peer) }
        peers[key(peer)] = peer
        peerLastHelloMs[fromHost] = System.currentTimeMillis()
        // Never steal Contacts selection — only auto-pick when nothing is locked
        if (!userLockedSelection.get()) {
            if (selectedPeerKey == null || peers.size == 1) {
                selectedPeerKey = key(peer)
            }
        } else {
            // Keep lock on the same host if key rotated (port change)
            val sel = selectedPeerKey?.let { peers[it] }
            if (sel == null) {
                val byHost = peers.values.find { it.host == fromHost && peerLastHelloMs.containsKey(it.host) }
                // Only re-bind key if selected host still matches an existing peer
                val lockedHost = peers.values.firstOrNull {
                    selectedPeerKey?.startsWith("${it.host}:") == true || selectedPeerKey == key(it)
                }?.host
                if (lockedHost != null) {
                    peers.values.find { it.host == lockedHost }?.let { selectedPeerKey = key(it) }
                }
            } else if (sel.host == fromHost) {
                selectedPeerKey = key(peer)
            }
        }
        pruneStalePeers()
        postPeers()
        Log.i(
            TAG,
            "HELLO from $fromHost videoPort=$vport udpPort=$fromUdpPort " +
                "sel=${currentPeer()?.host} locked=${userLockedSelection.get()}"
        )
        // Push latest GPS whenever we learn a peer (mutual map paths)
        if (locationTracker?.isEnabled() == true) {
            locationTracker?.latest()?.let { sendGpsPoint(it) }
        }
    }

    /** Drop peer hosts that have not HELLO'd recently (ghosts break flip/SEE targeting). */
    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        val keepHosts = HashSet<String>()
        seeWatchPeer?.host?.let { keepHosts.add(it) }
        monitorTarget?.host?.let { keepHosts.add(it) }
        selectedPeerKey?.let { peers[it]?.host?.let { h -> keepHosts.add(h) } }
        // Longer TTL so brief Wi‑Fi blips don't drop the only live peer mid one-way SEE
        val staleHosts = peerLastHelloMs.entries
            .filter { (host, t) -> now - t > 20_000L && host !in keepHosts }
            .map { it.key }
            .toSet()
        if (staleHosts.isEmpty()) return
        val removeKeys = peers.keys.filter { k ->
            val host = peers[k]?.host
            host != null && host in staleHosts
        }
        removeKeys.forEach { peers.remove(it) }
        staleHosts.forEach { peerLastHelloMs.remove(it) }
        if (removeKeys.isNotEmpty()) {
            Log.i(TAG, "pruned stale peers hosts=$staleHosts")
            if (selectedPeerKey != null && peers[selectedPeerKey] == null) {
                selectedPeerKey = peers.keys.firstOrNull()
            }
        }
    }

    /** Prefer peer with newest HELLO (live device over ghost IP). */
    private fun bestLivePeer(): WalkiePeer? = allLivePeers().maxByOrNull { peerLastHelloMs[it.host] ?: 0L }
        ?: currentPeer()
        ?: getPeers().firstOrNull()

    /** One representative peer per host that HELLO'd recently. */
    private fun allLivePeers(): List<WalkiePeer> {
        pruneStalePeers()
        val now = System.currentTimeMillis()
        val live = peers.values.filter { p ->
            val t = peerLastHelloMs[p.host] ?: 0L
            now - t < 15_000L && p.port > 0
        }
        if (live.isEmpty()) return getPeers().filter { it.port > 0 }
        return live.groupBy { it.host }.values.mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<WalkiePeer> { peerLastHelloMs[it.host] ?: 0L }
                    .thenBy { it.videoPort }
                    .thenBy { it.port }
            )
        }
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

    /** @return true if sent on TCP (JPEG or H.264 access unit). */
    private fun sendVideoFrameTcp(payload: ByteArray, avc: Boolean = false): Boolean {
        synchronized(videoOutLock) {
            val out = videoOut ?: return false
            return try {
                if (avc) {
                    // [len][0x01][access unit]
                    out.writeInt(1 + payload.size)
                    out.write(WalkieProtocol.TCP_CODEC_AVC.toInt())
                    out.write(payload)
                } else {
                    out.writeInt(payload.size)
                    out.write(payload)
                }
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
                val payload = ByteArray(len)
                input.readFully(payload)
                // H.264: first byte TCP_CODEC_AVC
                if (payload.isNotEmpty() && payload[0] == WalkieProtocol.TCP_CODEC_AVC) {
                    val au = payload.copyOfRange(1, payload.size)
                    frames++
                    videoRxOk++
                    monitorVideoRxCount++
                    if (monitorVideoRxCount == 1 || seeWatchPeer?.host != from) {
                        seeWatchPeer = peers.values.firstOrNull { it.host == from }
                            ?: WalkiePeer("see-$from", from, 0, learnedVideoPorts[from] ?: 0)
                    }
                    if (monitorVideoRxCount == 1 || monitorVideoRxCount % 30 == 0) {
                        Log.i(TAG, "SEE_VIDEO_AVC_TCP n=$monitorVideoRxCount bytes=${au.size} from=$from")
                    }
                    if (!replaying.get()) emitAvcAccessUnit(au, from, storeClip = false)
                    continue
                }
                val jpeg = payload
                // SEE / SCRN remote: live TCP JPEG frames
                if ((requestingSee.get() || requestingScreen.get()) &&
                    jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()
                ) {
                    frames++
                    videoRxOk++
                    monitorVideoRxCount++
                    if (monitorVideoRxCount == 1 || seeWatchPeer?.host != from) {
                        val live = peers.values.firstOrNull { it.host == from }
                            ?: WalkiePeer("see-$from", from, 0, learnedVideoPorts[from] ?: 0)
                        seeWatchPeer = live
                        if (requestingScreen.get()) screenWatchPeer = live
                    }
                    if (monitorVideoRxCount == 1 || monitorVideoRxCount % 30 == 0) {
                        Log.i(
                            TAG,
                            "SEE/SCRN_VIDEO_TCP_RX n=$monitorVideoRxCount bytes=${jpeg.size} " +
                                "from=$from scrn=${requestingScreen.get()}"
                        )
                    }
                    if (!replaying.get()) decodeAndEmit(jpeg, live = true)
                    continue
                }
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
                    if (!replaying.get()) {
                        decodeAndEmit(jpeg, live = true)
                    }
                } else {
                    lastVideoError = "RX TCP payload not JPEG/AVC (${jpeg.size}B)"
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
            // If replaying, message is already stored + queued in finalizeIncoming — do not interrupt
            if (replaying.get()) {
                Log.i(TAG, "BEST RX stored while replaying — will play after Replay ends")
                videoDebug("New video queued — finishes after current Replay")
                return
            }
            // Play as one synced clip after full download (not partial/live A/V race)
            if (!talking.get()) {
                thread(name = "walkie-best-rx-play", isDaemon = true) {
                    val playing = AtomicBoolean(true)
                    try {
                        mainHandler.post { listener.onReceivingChanged(true) }
                        playSyncedClip(
                            audio = audio.copyOf(),
                            frames = normalized.map { WalkieVideoFrame(it.timeOffsetMs, it.jpeg.copyOf()) },
                            active = {
                                playing.get() && running.get() && !talking.get() && !replaying.get()
                            },
                            label = "BEST_RX"
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "BEST_RX play failed", t)
                    } finally {
                        playing.set(false)
                        mainHandler.post {
                            listener.onReceivingChanged(false)
                            listener.onStatus("Ready")
                        }
                        maybeResumeLocalPreview()
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
                val existing = peers.values.find { it.host == host }
                val nsdName = resolved.serviceName
                val serviceName = when {
                    existing != null && !isUglyPeerName(existing.serviceName) -> existing.serviceName
                    else -> {
                        val clean = friendlyDisplayName(nsdName, host)
                        if (clean == host) nsdName else "Barky-$clean"
                    }
                }
                val peer = WalkiePeer(serviceName, host, resolved.port, vport)
                peers.entries.removeIf { it.value.host == host && it.key != key(peer) }
                peers[key(peer)] = peer
                if (!userLockedSelection.get() && (peers.size == 1 || selectedPeerKey == null)) {
                    selectedPeerKey = key(peer)
                } else if (selectedPeerKey != null) {
                    val sel = peers[selectedPeerKey]
                    if (sel?.host == host) selectedPeerKey = key(peer)
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
                        WalkieProtocol.TYPE_AUDIO_OPUS -> handleAudioOpus(snapshot, from)
                        WalkieProtocol.TYPE_VIDEO -> handleVideoBytes(snapshot, from)
                        WalkieProtocol.TYPE_VIDEO_AVC -> handleVideoAvcUdp(snapshot, from)
                        WalkieProtocol.TYPE_WAKEUP -> {
                            val quiet = snapshot.size >= 2 && snapshot[1] != 0.toByte()
                            handleWakeupFrom(from, quiet)
                        }
                        WalkieProtocol.TYPE_WAKEUP_STOP -> {
                            wakeupPlayer.stop()
                            postStatus("Remote stopped ringing")
                        }
                        WalkieProtocol.TYPE_HELLO -> handleHello(snapshot, from, fromPort)
                        WalkieProtocol.TYPE_TALK_START -> {
                            // Optional 2nd byte = speaker vs headset for privacy playback
                            val mode = if (snapshot.size >= 2) snapshot[1] else WalkieProtocol.AUDIO_MODE_SPEAKER
                            rxPlaybackMode = mode
                            preparePlaybackAudio(headset = mode == WalkieProtocol.AUDIO_MODE_HEADSET)
                            Log.i(TAG, "TALK_START from $from playbackMode=$mode")
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
                        WalkieProtocol.TYPE_LISTEN_REQ -> handleListenReq(snapshot, from, fromPort)
                        WalkieProtocol.TYPE_LISTEN_STOP -> stopServingListen()
                        WalkieProtocol.TYPE_SEE_REQ -> handleSeeReq(snapshot, from, fromPort)
                        WalkieProtocol.TYPE_SEE_STOP -> stopServingSee()
                        WalkieProtocol.TYPE_SCREEN_REQ -> handleScreenReq(snapshot, from, fromPort)
                        WalkieProtocol.TYPE_SCREEN_STOP -> stopServingScreen()
                        WalkieProtocol.TYPE_GPS -> handleGpsPacket(snapshot)
                        WalkieProtocol.TYPE_MONITOR_AUDIO -> handleMonitorAudio(snapshot)
                        WalkieProtocol.TYPE_MONITOR_AUDIO_OPUS -> handleMonitorAudioOpus(snapshot)
                        WalkieProtocol.TYPE_MONITOR_VIDEO -> handleMonitorVideo(snapshot, from)
                        WalkieProtocol.TYPE_MONITOR_VIDEO_AVC -> handleMonitorVideoAvc(snapshot, from)
                        WalkieProtocol.TYPE_CAMERA_FLIP -> handleCameraFlipReq(from)
                        WalkieProtocol.TYPE_PATH_UPDATE -> handlePathUpdate(snapshot, from, fromPort)
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
                if (replaying.get()) {
                    // Buffer only — do not yank UI or touch AudioTrack (Replay owns it).
                    // Concurrent AudioTrack.write from live RX + Replay = native SIGABRT.
                    rxOverlappedReplay.set(true)
                    Log.i(TAG, "Incoming while replaying — buffering, play after Replay")
                    videoDebug("Receiving during Replay — will play next when Replay ends")
                    postStatus("Receiving new video (plays after Replay)…")
                } else {
                    preparePlaybackAudio()
                    mainHandler.post { listener.onReceivingChanged(true) }
                    postStatus("Receiving message… (expecting video)")
                }
                videoDebug(
                    "RX: audio/control from $from — waiting for video " +
                        "(tcpInOpen=$tcpInOpen learnedVport=${learnedVideoPorts[from]} " +
                        "replaying=${replaying.get()})"
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

    private fun handleAudioOpus(data: ByteArray, from: String) {
        if (data.size < 2) return
        if (!opusRx.isDecoderReady()) opusRx.startDecoder()
        val pcm = opusRx.decode(data.copyOfRange(1, data.size)) ?: return
        handleDecodedPcm(pcm, from, storeClip = true)
    }

    private fun handleAudioBytes(data: ByteArray, from: String) {
        val dataLen = data.size - 1
        if (dataLen < 2) return
        val pcm = data.copyOfRange(1, data.size)
        handleDecodedPcm(pcm, from, storeClip = true)
    }

    private fun handleDecodedPcm(pcm: ByteArray, from: String, storeClip: Boolean) {
        // Learn peer from live audio path
        ensurePeerFromHost(from)
        if (storeClip) {
            if (rxClipSealed && !receiving.get()) {
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
        }
        // Never write live RX audio while Replay owns AudioTrack
        if (!replaying.get()) {
            playPcm(pcm)
        }
    }

    private fun handleMonitorAudioOpus(data: ByteArray) {
        if (data.size < 2) return
        if (!opusRx.isDecoderReady()) opusRx.startDecoder()
        val pcm = opusRx.decode(data.copyOfRange(1, data.size)) ?: return
        handleMonitorAudio(
            ByteArray(1 + pcm.size).also {
                it[0] = WalkieProtocol.TYPE_MONITOR_AUDIO
                System.arraycopy(pcm, 0, it, 1, pcm.size)
            }
        )
    }

    private fun handleVideoAvcUdp(data: ByteArray, from: String) {
        if (data.size < WalkieProtocol.VIDEO_HEADER) return
        // Assemble chunks then H.264 decode (same reassembly as JPEG TYPE_VIDEO)
        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val part = data[3].toInt() and 0xFF
        val parts = data[4].toInt() and 0xFF
        if (parts <= 0 || part >= parts || parts > 64) return
        val chunk = data.copyOfRange(WalkieProtocol.VIDEO_HEADER, data.size)
        val au: ByteArray? = synchronized(videoAssembly) {
            var slots = videoAssembly[frameId]
            if (slots == null || slots.size != parts) {
                slots = arrayOfNulls(parts)
                videoAssembly[frameId] = slots
            }
            if (slots[part] == null) slots[part] = chunk
            videoAssemblyTime[frameId] = System.currentTimeMillis()
            if (slots.all { it != null }) {
                videoAssembly.remove(frameId)
                videoAssemblyTime.remove(frameId)
                val total = slots.sumOf { it!!.size }
                val out = ByteArray(total)
                var o = 0
                for (s in slots) {
                    val b = s!!
                    System.arraycopy(b, 0, out, o, b.size)
                    o += b.size
                }
                out
            } else null
        }
        if (au != null) emitAvcAccessUnit(au, from, storeClip = true)
    }

    private fun handleMonitorVideoAvc(data: ByteArray, from: String) {
        if (!requestingSee.get() && !requestingScreen.get()) return
        if (data.size < WalkieProtocol.VIDEO_HEADER) return
        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val part = data[3].toInt() and 0xFF
        val parts = data[4].toInt() and 0xFF
        if (parts <= 0 || part >= parts || parts > 64) return
        val chunk = data.copyOfRange(WalkieProtocol.VIDEO_HEADER, data.size)
        val au: ByteArray? = synchronized(videoAssembly) {
            var slots = videoAssembly[frameId]
            if (slots == null || slots.size != parts) {
                slots = arrayOfNulls(parts)
                videoAssembly[frameId] = slots
            }
            if (slots[part] == null) slots[part] = chunk
            videoAssemblyTime[frameId] = System.currentTimeMillis()
            if (slots.all { it != null }) {
                videoAssembly.remove(frameId)
                videoAssemblyTime.remove(frameId)
                val total = slots.sumOf { it!!.size }
                val out = ByteArray(total)
                var o = 0
                for (s in slots) {
                    val b = s!!
                    System.arraycopy(b, 0, out, o, b.size)
                    o += b.size
                }
                out
            } else null
        }
        if (au != null) emitAvcAccessUnit(au, from, storeClip = false)
    }

    private fun emitAvcAccessUnit(au: ByteArray, from: String, storeClip: Boolean) {
        // Screen is 720x1280; camera SEE often smaller — decoder rescales
        if (!avcDecoder.isReady()) avcDecoder.start(720, 1280)
        val bmp = avcDecoder.decodeToBitmap(au) ?: return
        if (storeClip) {
            beginIncomingIfNeeded(from)
            // Store not used for AVC clips yet — live display only
        }
        if (requestingSee.get() || requestingScreen.get()) {
            if (seeWatchPeer?.host != from) {
                seeWatchPeer = peers.values.firstOrNull { it.host == from }
                    ?: WalkiePeer("avc-$from", from, 0, learnedVideoPorts[from] ?: 0)
            }
        }
        mainHandler.post {
            try {
                listener.onVideoFrame(bmp, live = true)
            } catch (_: Throwable) {
            }
        }
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
            // Never paint live RX frames over an active Replay (crash / flicker)
            if (!replaying.get()) {
                decodeAndEmit(jpeg, live = true)
            }
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

    /** When true, WAKEUP uses soft beep only (adb smoke / quiet house). */
    @Volatile var quietWakeup: Boolean = false

    private fun handleWakeupFrom(from: String, quiet: Boolean) {
        mainHandler.post {
            listener.onWakeupReceived(from)
            Log.i(TAG, "WAKEUP RING from=$from quiet=$quiet")
            wakeupPlayer.start(quiet = quiet)
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
        val overlapped = rxOverlappedReplay.getAndSet(false)
        val stillReplaying = replaying.get()
        if (stillReplaying) {
            pendingAfterReplay = msg
            // Keep overlap flag so stop/end path also sees queue if needed
            rxOverlappedReplay.set(true)
            Log.i(TAG, "Queued incoming clip while replaying frames=${frames.size}")
            videoDebug("New video saved — will play right after Replay")
            postStatus("New video queued (after Replay)…")
        } else if (overlapped) {
            // Clip finished after Replay already ended — auto-play now
            Log.i(TAG, "Auto-play clip that overlapped Replay frames=${frames.size}")
            videoDebug("Playing video that arrived during Replay…")
            pendingAfterReplay = msg
            playPendingAfterReplayIfAny()
        }
        Log.i(
            TAG,
            "CLIP_RX mode=$mode frames=${frames.size} videoMs=${metrics.videoMs} " +
                "audioMs=${metrics.audioMs} audioBytes=${audio.size} from=$from " +
                "queued=$stillReplaying overlapped=$overlapped"
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
            // If still replaying, don't reset UI to "Ready"/preview yet
            if (!replaying.get()) {
                mainHandler.post {
                    listener.onReceivingChanged(false)
                    listener.onStatus("Ready")
                }
                maybeResumeLocalPreview()
            } else {
                mainHandler.post {
                    listener.onReceivingChanged(false)
                }
            }
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
            // Warm decoder for reply path
            if ((localCaps and WalkieProtocol.CAP_OPUS) != 0) opusRx.startDecoder()
            val useOpus = sendLive && peerWantsOpus(peer.host) && opusTx.startEncoder()
            if (useOpus) {
                videoDebug("TX audio codec=Opus ${OpusAudioCodec.BIT_RATE}bps")
            } else if (sendLive) {
                videoDebug("TX audio codec=PCM 16kHz (Opus unavailable or peer legacy)")
            }
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
                    if (useOpus) {
                        val opus = opusTx.encode(frame, filled)
                        if (opus != null && opus.isNotEmpty()) {
                            val ob = ByteArray(1 + opus.size)
                            ob[0] = WalkieProtocol.TYPE_AUDIO_OPUS
                            System.arraycopy(opus, 0, ob, 1, opus.size)
                            sendUdp(DatagramPacket(ob, ob.size, dest, peer.port))
                        }
                    } else {
                        System.arraycopy(frame, 0, packetBuf, 1, filled)
                        sendUdp(DatagramPacket(packetBuf, 1 + filled, dest, peer.port))
                    }
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
            // Local idle preview must never feed the full-screen remote ImageView while SEE is on
            if (localPreview && requestingSee.get()) return
            // Never interrupt Replay UI with live/remote frames (crash + flicker source)
            if (live && replaying.get()) return
            if (live) {
                val now = android.os.SystemClock.elapsedRealtime()
                // Pixel ImageView+TextureView combos flash hard — slower UI paint
                val pixelOem = android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                    android.os.Build.BRAND.equals("google", ignoreCase = true)
                // SEE/SCRN remote: all OEMs paint slower to cut flicker (encode rate unchanged)
                val watchingScreen = requestingScreen.get()
                val watchingRemote = requestingSee.get() || watchingScreen
                // SCRN: paint ~30fps. SEE camera: slightly slower to cut flicker.
                val minGap = when {
                    localPreview && pixelOem -> 160L
                    localPreview -> 120L
                    watchingScreen -> 32L // smooth screen share UI
                    watchingRemote && pixelOem -> 66L
                    watchingRemote -> 50L
                    pixelOem -> 80L
                    else -> 50L
                }
                if (now - lastUiEmitAt < minGap) return
                if (!uiEmitBusy.compareAndSet(false, true)) return
                lastUiEmitAt = now
            }
            val opts = BitmapFactory.Options().apply {
                // ARGB_8888 on Pixel reduces RGB_565 dither flicker; RGB_565 elsewhere for GC
                val pixelOem = android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                    android.os.Build.BRAND.equals("google", ignoreCase = true)
                inPreferredConfig = if (pixelOem && !localPreview) {
                    Bitmap.Config.ARGB_8888
                } else {
                    Bitmap.Config.RGB_565
                }
                // Local preview: half-res decode (flip still clear, much less GC flash)
                if (localPreview) inSampleSize = 2
                if (pixelOem && !localPreview) inSampleSize = 1
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
                // Preferred device is set once at build time (not every PCM write)
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
        applyPreferredOutputDevice(track)
        track.setVolume(1.0f)
        track.play()
        audioTrack = track
        return track
    }

    /**
     * Pin [AudioTrack] to loudspeaker or earpiece. Required on Pixel 8+:
     * [AudioManager.setCommunicationDevice] only affects communication usages;
     * our tracks use [AudioAttributes.USAGE_MEDIA], so without
     * [AudioTrack.setPreferredDevice] LISTEN can stay on the earpiece while
     * Samsung still follows speakerphone flags.
     */
    private fun applyPreferredOutputDevice(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val device = if (preferHeadsetPlayback) {
                outs.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: outs.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            } else {
                outs.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }
            if (device != null) {
                val ok = track.setPreferredDevice(device)
                Log.i(
                    TAG,
                    "AudioTrack preferredDevice type=${device.type} ok=$ok " +
                        "route=${if (preferHeadsetPlayback) "headset/earpiece" else "SPEAKER"}"
                )
            } else {
                Log.w(TAG, "No preferred output device (headset=$preferHeadsetPlayback)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyPreferredOutputDevice", t)
        }
    }

    /**
     * @param headset true = earpiece/headset route (private); false = loudspeaker.
     */
    private fun preparePlaybackAudio(headset: Boolean = false) {
        try {
            preferHeadsetPlayback = headset
            if (headset) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val ear = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (ear != null) audioManager.setCommunicationDevice(ear)
                    else audioManager.clearCommunicationDevice()
                }
            } else {
                // LISTEN / speaker HOLD: media path + speakerphone (OEM-agnostic)
                try {
                    audioManager.stopBluetoothSco()
                } catch (_: Exception) {
                }
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Prefer clearing comm device so USAGE_MEDIA is free to use preferred speaker
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                }
            }
            if (savedMusicVolume < 0) {
                savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = if (headset) {
                max(1, (maxVol * 0.55f).toInt())
            } else {
                max(savedMusicVolume, (maxVol * 0.75f).toInt().coerceAtLeast(1))
            }
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < target || headset) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            // Force track rebuild so setPreferredDevice applies on next write
            releaseAudioTrack()
            Log.i(TAG, "preparePlaybackAudio headset=$headset")
        } catch (t: Throwable) {
            Log.w(TAG, "preparePlaybackAudio", t)
        }
    }

    private fun restorePlaybackAudio() {
        try {
            preferHeadsetPlayback = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            if (savedMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
        } catch (_: Exception) {
        }
    }

    // —— GPS ——

    fun isGpsEnabled(): Boolean = locationTracker?.isEnabled() == true

    fun setGpsEnabled(enabled: Boolean) {
        if (enabled) {
            if (locationTracker == null) {
                locationTracker = LocationTracker(appContext) { trail, latest ->
                    mainHandler.post {
                        listener.onGpsUpdated(trail, peerGpsTrail.toList(), true)
                    }
                    // Share latest fix to peer
                    sendGpsPoint(latest)
                }
            }
            val ok = locationTracker?.start() == true
            if (!ok) {
                postError("GPS permission needed — enable location")
                mainHandler.post {
                    listener.onGpsUpdated(emptyList(), peerGpsTrail.toList(), false)
                }
                return
            }
            mainHandler.removeCallbacks(gpsShareRunnable)
            mainHandler.post(gpsShareRunnable)
            BarkyPrefs.setGpsEnabled(appContext, true)
            postStatus("GPS tracking ON")
            Log.i(TAG, "GPS ON")
            val trail = locationTracker?.trailSnapshot().orEmpty()
            mainHandler.post {
                listener.onGpsUpdated(trail, peerGpsTrail.toList(), true)
            }
        } else {
            mainHandler.removeCallbacks(gpsShareRunnable)
            locationTracker?.stop()
            BarkyPrefs.setGpsEnabled(appContext, false)
            postStatus("GPS tracking OFF")
            Log.i(TAG, "GPS OFF")
            mainHandler.post {
                listener.onGpsUpdated(
                    locationTracker?.trailSnapshot().orEmpty(),
                    peerGpsTrail.toList(),
                    false
                )
            }
        }
    }

    fun getMyGpsTrail(): List<LocationTracker.GpsPoint> =
        locationTracker?.trailSnapshot().orEmpty()

    fun getPeerGpsTrail(): List<LocationTracker.GpsPoint> = peerGpsTrail.toList()

    private fun sendGpsPoint(pt: LocationTracker.GpsPoint) {
        // Deduplicate peers by host (keep highest known UDP port / latest entry)
        val byHost = LinkedHashMap<String, WalkiePeer>()
        for (p in getPeers()) {
            val prev = byHost[p.host]
            if (prev == null || p.port > 0) byHost[p.host] = p
        }
        currentPeer()?.let { byHost[it.host] = it }
        val targets = byHost.values.filter { it.port > 0 }
        if (targets.isEmpty()) {
            Log.w(TAG, "sendGpsPoint: no peers yet lat=${pt.lat}")
            return
        }
        thread(name = "walkie-gps", isDaemon = true) {
            try {
                val buf = ByteBuffer.allocate(WalkieProtocol.GPS_PAYLOAD_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                buf.put(WalkieProtocol.TYPE_GPS)
                buf.putDouble(pt.lat)
                buf.putDouble(pt.lon)
                buf.putLong(pt.timeMs)
                val bytes = buf.array()
                for (peer in targets) {
                    try {
                        val packet = DatagramPacket(
                            bytes, bytes.size, InetAddress.getByName(peer.host), peer.port
                        )
                        // Burst so one dropped packet does not leave peer blind
                        repeat(3) {
                            sendUdp(packet)
                            Thread.sleep(5)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "sendGpsPoint → ${peer.host}:${peer.port}", t)
                    }
                }
                Log.i(
                    TAG,
                    "GPS_TX lat=${"%.5f".format(pt.lat)} lon=${"%.5f".format(pt.lon)} " +
                        "peers=${targets.joinToString { "${it.host}:${it.port}" }}"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "sendGpsPoint", t)
            }
        }
    }

    private fun handleGpsPacket(data: ByteArray) {
        if (data.size < WalkieProtocol.GPS_PAYLOAD_BYTES) return
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.get() // type
            val lat = buf.double
            val lon = buf.double
            val t = buf.long
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            val pt = LocationTracker.GpsPoint(lat, lon, t, provider = "peer")
            peerGpsTrail.add(pt)
            val cutoff = System.currentTimeMillis() - 3_600_000L * WalkieProtocol.GPS_TRAIL_HOURS
            while (peerGpsTrail.isNotEmpty() && peerGpsTrail.first().timeMs < cutoff) {
                peerGpsTrail.removeAt(0)
            }
            while (peerGpsTrail.size > 2_000) peerGpsTrail.removeAt(0)
            Log.i(TAG, "GPS_RX lat=${"%.5f".format(lat)} lon=${"%.5f".format(lon)} trail=${peerGpsTrail.size}")
            mainHandler.post {
                listener.onGpsUpdated(
                    locationTracker?.trailSnapshot().orEmpty(),
                    peerGpsTrail.toList(),
                    locationTracker?.isEnabled() == true
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handleGpsPacket", t)
        }
    }

    // —— LISTEN (remote mic) / SEE (remote cam+mic) ——

    fun isListeningRemote(): Boolean = requestingListen.get()
    fun isSeeingRemote(): Boolean = requestingSee.get()
    fun isServingListen(): Boolean = servingListen.get()
    fun isServingSee(): Boolean = servingSee.get()
    fun isRequestingScreen(): Boolean = requestingScreen.get()
    fun isServingScreen(): Boolean = servingScreen.get()

    /** Peer we are currently streaming monitor audio/video to (the requester). */
    @Volatile private var monitorTarget: WalkiePeer? = null
    /** We asked peer to share **their** screen (SCRN). */
    private val requestingScreen = AtomicBoolean(false)
    /** Peer is viewing our screen (we serve MediaProjection frames). */
    private val servingScreen = AtomicBoolean(false)
    @Volatile private var screenWatchPeer: WalkiePeer? = null
    private var screenShareThread: Thread? = null

    fun startListenRemote() {
        // Selected contact only — never firstOrNull (wrong phone)
        var peer = currentPeer()
        if (peer == null) {
            postError("No peer for LISTEN — Contacts → Select first")
            return
        }
        selectPeer(peer)
        userLockedSelection.set(true)
        peer = currentPeer() ?: peer
        if (!requestingListen.compareAndSet(false, true)) {
            stopListenRemote()
            return
        }
        monitorReceiving.set(true)
        monitorAudioRxCount = 0
        // Loud speaker path for monitor; rebuild AudioTrack cleanly
        releaseAudioTrack()
        preparePlaybackAudio(headset = false)
        forceListenVolume() // Pixel often left near-mute after adb smoke
        val target = peer
        val reply = localPort
        // All network off main thread (Pixel StrictMode)
        thread(name = "walkie-listen-req", isDaemon = true) {
            try {
                sendHelloTo(target)
                Thread.sleep(80)
                // Burst to all known peer ports for this host (stale port was a common miss)
                sendListenReqBurst(target, reply)
            } catch (t: Throwable) {
                Log.e(TAG, "LISTEN_REQ send path", t)
            }
        }
        mainHandler.post { listener.onListenStateChanged(true, servingListen.get()) }
        postStatus("LISTEN — hearing peer mic…")
        Log.i(TAG, "LISTEN_REQ → ${peer.host}:${peer.port} replyPort=$localPort")
    }

    /**
     * LISTEN always plays on the **loudspeaker** on every device (Pixel + Samsung).
     * Clears earpiece / BT-SCO / communication routing left over from Private HOLD.
     * Also pins [preferHeadsetPlayback]=false so [applyPreferredOutputDevice] chooses
     * [TYPE_BUILTIN_SPEAKER] on the next AudioTrack (Pixel media-path fix).
     */
    private fun forceListenVolume() {
        try {
            preferHeadsetPlayback = false
            // Drop any in-call / earpiece path (Pixel often stays on earpiece after Private)
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Exception) {
            }
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = max(max * 3 / 4, 2)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            // Rebuild track after route change so Pixel actually uses speaker
            releaseAudioTrack()
            Log.i(TAG, "forceListenVolume SPEAKER music=$target/$max")
        } catch (t: Throwable) {
            Log.w(TAG, "forceListenVolume", t)
        }
    }

    private fun sendListenReqBurst(peer: WalkiePeer, replyPort: Int) {
        val hosts = linkedSetOf(peer.host)
        val ports = linkedSetOf(peer.port)
        peers.values.filter { it.host == peer.host }.forEach { ports.add(it.port) }
        // Also try latest HELLO-learned peers
        getPeers().forEach { p ->
            if (p.host == peer.host) ports.add(p.port)
        }
        for (port in ports.filter { it > 0 }) {
            val p = peer.copy(port = port)
            sendControlWithPort(p, WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
            Thread.sleep(40)
        }
        Thread.sleep(200)
        if (requestingListen.get()) {
            for (port in ports.filter { it > 0 }) {
                sendControlWithPort(peer.copy(port = port), WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
            }
        }
        Thread.sleep(250)
        if (requestingListen.get()) {
            val p = currentPeer() ?: peer
            sendControlWithPort(p, WalkieProtocol.TYPE_LISTEN_REQ, replyPort)
        }
    }

    fun stopListenRemote() {
        if (!requestingListen.getAndSet(false) && !monitorReceiving.get()) return
        val peer = currentPeer() ?: getPeers().firstOrNull()
        peer?.let { sendControl(it, WalkieProtocol.TYPE_LISTEN_STOP) }
        if (!requestingSee.get()) monitorReceiving.set(false)
        mainHandler.post { listener.onListenStateChanged(false, servingListen.get()) }
        postStatus("LISTEN stopped")
        Log.i(TAG, "LISTEN_STOP")
    }

    fun startSeeRemote() {
        // Selected contact only — never bestLivePeer (mixed S20/S918/Pixel).
        var peer = currentPeer() ?: seeWatchPeer
        if (peer == null) {
            postError("No peer for SEE — Contacts → Select first")
            return
        }
        selectPeer(peer)
        userLockedSelection.set(true)
        peer = currentPeer() ?: peer
        if (!requestingSee.compareAndSet(false, true)) {
            stopSeeRemote()
            return
        }
        seeWatchPeer = peer
        // SEE = video only. Audio is independent (LISTEN button). Same code path on every phone.
        monitorVideoRxCount = 0
        // Keep local camera for PiP (WhatsApp-style) unless we already serve camera
        if (!servingSee.get()) {
            localPreviewWanted = true
            startLocalPreviewInternal()
        }
        if (requestingListen.get()) {
            monitorReceiving.set(true)
            preparePlaybackAudio(headset = false)
        }
        val reply = localPort
        val target = peer
        val lockedHost = target.host
        val viaPublic = !com.example.barky.net.PeerEndpoint.isPrivateIp(lockedHost)
        thread(name = "walkie-see-req", isDaemon = true) {
            try {
                sendHelloTo(target)
                sendPathUpdateTo(target)
                // Cellular / internet: hole-punch like SCRN so peer can reply through NAT
                if (viaPublic && !BarkyPrefs.isWifiOnly(appContext)) {
                    if (publicHost.isNullOrBlank()) {
                        try {
                            refreshInternetInvite()
                        } catch (_: Throwable) {
                        }
                    }
                    holePunchPeer(target, rounds = 20)
                } else {
                    Thread.sleep(80)
                }
                sendSeeReqBurst(target, reply)
                Thread.sleep(350)
                if (requestingSee.get()) {
                    sendSeeReqBurst(target, reply)
                    // Keep NAT warm while waiting for first video frames
                    if (viaPublic) holePunchPeer(target, rounds = 8)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SEE_REQ send path", t)
            }
        }
        mainHandler.post {
            listener.onSeeStateChanged(true, servingSee.get())
        }
        postStatus(
            if (requestingListen.get()) "SEE+LISTEN — ${peer.displayName}"
            else "SEE — ${peer.displayName}"
        )
        Log.i(
            TAG,
            "SEE_REQ ONLY → ${peer.host}:${peer.port} name=${peer.displayName} " +
                "replyPort=$localPort withAudio=${requestingListen.get()} public=$viaPublic"
        )
    }

    /** Burst SEE_REQ to all known UDP ports for this host. */
    private fun sendSeeReqBurst(peer: WalkiePeer, replyPort: Int) {
        val ports = linkedSetOf(peer.port)
        peers.values.filter { it.host == peer.host }.forEach { ports.add(it.port) }
        getPeers().forEach { p -> if (p.host == peer.host) ports.add(p.port) }
        for (port in ports.filter { it > 0 }) {
            sendControlWithPort(peer.copy(port = port), WalkieProtocol.TYPE_SEE_REQ, replyPort)
            Thread.sleep(35)
        }
    }

    fun stopSeeRemote() {
        if (!requestingSee.getAndSet(false)) {
            // Still notify UI in case of desync
            mainHandler.post { listener.onSeeStateChanged(false, servingSee.get()) }
            return
        }
        val peer = seeWatchPeer ?: currentPeer() ?: getPeers().firstOrNull()
        seeWatchPeer = null
        peer?.let { sendControl(it, WalkieProtocol.TYPE_SEE_STOP) }
        // LISTEN is independent — leave requestingListen / audio alone
        if (!requestingListen.get()) monitorReceiving.set(false)
        mainHandler.post { listener.onSeeStateChanged(false, servingSee.get()) }
        if (!talking.get() && !replaying.get()) {
            localPreviewWanted = true
            maybeResumeLocalPreview()
        }
        postStatus("SEE stopped")
        Log.i(TAG, "SEE_STOP (LISTEN independent requestingListen=${requestingListen.get()})")
    }

    private fun handleListenReq(data: ByteArray, from: String, fromPort: Int) {
        // Prefer UDP source port (real socket); payload as secondary
        val payloadPort = parseControlPort(data, 0)
        val replyPort = when {
            fromPort in 1..65535 -> fromPort
            payloadPort in 1..65535 -> payloadPort
            else -> 0
        }
        val peer = resolvePeerForStream(from, replyPort)
        Log.i(
            TAG,
            "LISTEN_REQ from $from fromPort=$fromPort payloadPort=$payloadPort " +
                "usePort=${peer.port} vport=${peer.videoPort}"
        )
        startServingListen(peer)
    }

    private fun handleSeeReq(data: ByteArray, from: String, fromPort: Int) {
        val payloadPort = parseControlPort(data, 0)
        val replyPort = when {
            fromPort in 1..65535 -> fromPort
            payloadPort in 1..65535 -> payloadPort
            else -> 0
        }
        val peer = resolvePeerForStream(from, replyPort)
        Log.i(
            TAG,
            "SEE_REQ from $from fromPort=$fromPort payloadPort=$payloadPort " +
                "usePort=${peer.port} vport=${peer.videoPort} (video-only serve)"
        )
        // SEE = camera/video only. Mic streams only if peer also sent LISTEN_REQ.
        startServingSee(peer)
    }

    /**
     * SCRN: ask **peer** to share their phone screen (not our own).
     * Received frames use the same path as SEE (monitor video).
     */
    fun startScreenRemote() {
        // Must be the Selected contact only — bestLivePeer mixed S20/S918
        var peer = currentPeer() ?: screenWatchPeer
        if (peer == null) {
            postError("No contact selected for SCRN — open Contacts and Select first")
            return
        }
        // Pin selection so HELLO cannot retarget mid-SCRN
        selectPeer(peer)
        userLockedSelection.set(true)
        peer = currentPeer() ?: peer
        if (!requestingScreen.compareAndSet(false, true)) {
            stopScreenRemote()
            return
        }
        screenWatchPeer = peer
        seeWatchPeer = peer // reuse SEE display path for remote frames
        requestingSee.set(true)
        monitorReceiving.set(true)
        monitorVideoRxCount = 0
        val replyUdp = localPort
        val replyVideo = localVideoPort
        val target = peer
        val lockedHost = target.host
        thread(name = "walkie-screen-req", isDaemon = true) {
            try {
                sendHelloTo(target)
                sendPathUpdateTo(target)
                if (!com.example.barky.net.PeerEndpoint.isPrivateIp(target.host)) {
                    holePunchPeer(target, rounds = 12)
                } else {
                    Thread.sleep(80)
                }
                // Same host only — never fan-out to other phones
                val ports = linkedSetOf(target.port)
                peers.values.filter { it.host == lockedHost }.forEach { ports.add(it.port) }
                Log.i(
                    TAG,
                    "SCREEN_REQ burst host=$lockedHost ports=$ports " +
                        "replyUdp=$replyUdp replyVideo=$replyVideo name=${target.displayName}"
                )
                repeat(4) {
                    for (port in ports.filter { it > 0 }) {
                        sendScreenReq(target.copy(port = port), replyUdp, replyVideo)
                        Thread.sleep(35)
                    }
                    Thread.sleep(100)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SCREEN_REQ send", t)
            }
        }
        mainHandler.post {
            listener.onSeeStateChanged(true, servingSee.get() || servingScreen.get())
            listener.onScreenStateChanged(true, servingScreen.get())
        }
        postStatus("SCRN — ${peer.displayName} (${peer.host})")
        Log.i(
            TAG,
            "SCREEN_REQ ONLY → ${peer.host}:${peer.port} name=${peer.displayName} " +
                "replyUdp=$replyUdp replyVideo=$replyVideo locked=$lockedHost"
        )
    }

    fun stopScreenRemote() {
        if (!requestingScreen.getAndSet(false)) {
            mainHandler.post { listener.onScreenStateChanged(false, servingScreen.get()) }
            return
        }
        val peer = screenWatchPeer ?: seeWatchPeer ?: currentPeer()
        peer?.let { sendControl(it, WalkieProtocol.TYPE_SCREEN_STOP) }
        screenWatchPeer = null
        if (!requestingSee.get()) {
            // If we only used SEE flags for display, clear carefully
        }
        // Stop treating as SEE receive if we set it for screen-only
        requestingSee.set(false)
        if (!requestingListen.get()) monitorReceiving.set(false)
        mainHandler.post {
            listener.onSeeStateChanged(false, servingSee.get() || servingScreen.get())
            listener.onScreenStateChanged(false, servingScreen.get())
        }
        postStatus("SCRN stopped")
        Log.i(TAG, "SCREEN_STOP sent")
    }

    @Volatile private var lastScreenReqUiMs = 0L

    private fun handleScreenReq(data: ByteArray, from: String, fromPort: Int) {
        val payloadUdp = parseControlPort(data, 0)
        val payloadVideo = if (data.size >= 5) {
            ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        } else 0
        val replyUdp = when {
            fromPort in 1..65535 -> fromPort
            payloadUdp in 1..65535 -> payloadUdp
            else -> 0
        }
        val replyVideo = when {
            payloadVideo in 1..65535 -> payloadVideo
            else -> learnedVideoPorts[from] ?: 0
        }
        if (replyVideo > 0) learnedVideoPorts[from] = replyVideo
        val peer = resolvePeerForStream(from, replyUdp).let {
            if (replyVideo > 0) it.copy(videoPort = replyVideo) else it
        }
        // Burst SCRN sends many packets — only one consent dialog / auto-share attempt
        val now = System.currentTimeMillis()
        if (now - lastScreenReqUiMs < 4_000L) {
            Log.d(TAG, "SCREEN_REQ from $from (debounced UI)")
            return
        }
        if (servingScreen.get()) {
            Log.i(TAG, "SCREEN_REQ from $from ignored — already serving screen")
            return
        }
        lastScreenReqUiMs = now
        Log.i(
            TAG,
            "SCREEN_REQ from $from → MediaProjection " +
                "replyUdp=${peer.port} replyVideo=${peer.videoPort}"
        )
        monitorTarget = peer
        mainHandler.post {
            listener.onScreenShareRequest(peer.host, peer.port, peer.videoPort)
        }
    }

    /**
     * After user accepts system screen-share dialog, stream their **own** screen to [peer]
     * so the contact who pressed SCRN can watch.
     */
    fun startServingScreen(
        peerHost: String,
        peerUdp: Int,
        peerVideo: Int,
        projection: android.media.projection.MediaProjection
    ) {
        if (servingScreen.get()) {
            Log.w(TAG, "already serving screen")
            return
        }
        val vport = when {
            peerVideo > 0 -> peerVideo
            learnedVideoPorts[peerHost] != null -> learnedVideoPorts[peerHost]!!
            else -> 0
        }
        val peer = resolvePeerForStream(peerHost, peerUdp).let {
            if (vport > 0) it.copy(videoPort = vport) else it
        }
        monitorTarget = peer
        servingScreen.set(true)
        mainHandler.post { listener.onScreenStateChanged(requestingScreen.get(), true) }
        stopLocalPreviewInternal()
        screenShareThread = thread(name = "walkie-serve-screen", isDaemon = true) {
            var frames = 0
            var virtualDisplay: android.hardware.display.VirtualDisplay? = null
            var imageReader: android.media.ImageReader? = null
            var avcEnc: com.example.barky.walkie.codec.AvcVideoEncoder? = null
            try {
                Thread.sleep(100)
                var tcpOk = openVideoOut(peer)
                if (!tcpOk) {
                    Log.w(TAG, "SCREEN TCP not ready — UDP monitor until retry")
                }
                // Smooth/fast: 720p-ish @ ~30fps, H.264 when peer supports it
                val w = 720
                val h = 1280
                val density = appContext.resources.displayMetrics.densityDpi.coerceIn(160, 480)
                val useAvc = peerWantsAvc(peer.host)
                if (useAvc) {
                    avcEnc = com.example.barky.walkie.codec.AvcVideoEncoder(
                        width = w,
                        height = h,
                        bitRate = com.example.barky.walkie.codec.AvcVideoEncoder.SCREEN_BITRATE,
                        frameRate = com.example.barky.walkie.codec.AvcVideoEncoder.SCREEN_FRAME_RATE,
                        iFrameIntervalSec = 1
                    )
                    if (!avcEnc.start()) {
                        Log.w(TAG, "SCREEN AVC encoder failed — JPEG fallback")
                        avcEnc.release()
                        avcEnc = null
                    } else {
                        Log.i(TAG, "SCREEN using H.264/AVC ${w}x$h @30fps")
                    }
                }
                val reader = android.media.ImageReader.newInstance(
                    w, h, android.graphics.PixelFormat.RGBA_8888, 4
                )
                imageReader = reader
                try {
                    projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection onStop")
                            servingScreen.set(false)
                        }
                    }, mainHandler)
                } catch (t: Throwable) {
                    Log.w(TAG, "projection callback", t)
                }
                val vdFlags =
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                virtualDisplay = projection.createVirtualDisplay(
                    "barky-contact-screen",
                    w, h, density,
                    vdFlags,
                    reader.surface, null, null
                ) ?: throw IllegalStateException("createVirtualDisplay returned null")
                Log.i(
                    TAG,
                    "VirtualDisplay ${w}x$h dens=$density avc=${avcEnc != null} → " +
                        "${peer.host} udp=${peer.port} v=${peer.videoPort}"
                )
                var lastTx = 0L
                var lastTcpRetry = 0L
                var nullImages = 0
                // ~30 fps target (33ms). Was 80ms (~12fps) — felt choppy.
                val minIntervalMs = 33L
                while (servingScreen.get() && running.get()) {
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }
                    if (image == null) {
                        nullImages++
                        if (nullImages == 40 || nullImages % 150 == 0) {
                            Log.w(TAG, "SCREEN no frames yet nullImages=$nullImages")
                        }
                        Thread.sleep(8)
                        continue
                    }
                    nullImages = 0
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastTx < minIntervalMs) {
                            image.close()
                            continue
                        }
                        lastTx = now
                        if (!tcpOk && now - lastTcpRetry > 800) {
                            lastTcpRetry = now
                            tcpOk = openVideoOut(peer)
                        }
                        val enc = avcEnc
                        var sent = false
                        if (enc != null && enc.isReady()) {
                            val bmp = rgbaImageToBitmap(image, w, h)
                            if (bmp != null) {
                                val units = enc.encodeBitmap(bmp)
                                bmp.recycle()
                                for (au in units) {
                                    val viaTcp = tcpOk && sendVideoFrameTcp(au, avc = true)
                                    if (!viaTcp) {
                                        tcpOk = false
                                        sent = sendMonitorVideoAvcUdp(au, peer) || sent
                                    } else {
                                        sent = true
                                    }
                                }
                            }
                        }
                        if (!sent) {
                            // JPEG path (fallback or no AVC units yet)
                            val jpeg = rgbaImageToJpeg(image, w, h, quality = 72) ?: continue
                            val viaTcp = tcpOk && sendVideoFrameTcp(jpeg)
                            if (!viaTcp) tcpOk = false
                            // Prefer TCP when up (smooth). UDP only as backup to avoid double load.
                            sent = viaTcp || sendMonitorVideoUdp(jpeg, peer)
                        }
                        if (sent) {
                            frames++
                            if (frames == 1 || frames % 60 == 0) {
                                Log.i(
                                    TAG,
                                    "SCREEN_TX n=$frames avc=${enc != null} tcp=$tcpOk " +
                                        "→ ${peer.host}:${peer.port}"
                                )
                            }
                        } else if (frames == 0) {
                            Log.w(TAG, "SCREEN_TX failed to ${peer.host}:${peer.port}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "screen frame", t)
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "serve screen failed", t)
                postError("Screen share failed: ${t.message}")
            } finally {
                try {
                    avcEnc?.release()
                } catch (_: Exception) {
                }
                try {
                    virtualDisplay?.release()
                } catch (_: Exception) {
                }
                try {
                    imageReader?.close()
                } catch (_: Exception) {
                }
                val held = com.example.barky.screen.MediaProjectionHolder.get()
                if (held !== projection) {
                    try {
                        projection.stop()
                    } catch (_: Exception) {
                    }
                }
                closeVideoOut()
                servingScreen.set(false)
                mainHandler.post { listener.onScreenStateChanged(requestingScreen.get(), false) }
                if (!talking.get()) maybeResumeLocalPreview()
                Log.i(TAG, "SCREEN serve end frames=$frames")
            }
        }
        postStatus("Sharing screen (smooth)…")
        Log.i(TAG, "SCREEN serve START → ${peer.host}:udp=${peer.port} video=${peer.videoPort}")
    }

    fun stopServingScreen() {
        if (!servingScreen.getAndSet(false)) return
        mainHandler.post { listener.onScreenStateChanged(requestingScreen.get(), false) }
        Log.i(TAG, "stopServingScreen")
    }

    private fun rgbaImageToBitmap(image: android.media.Image, width: Int, height: Int): android.graphics.Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp: android.graphics.Bitmap
            if (rowPadding == 0 && pixelStride == 4) {
                bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888
                )
                buffer.rewind()
                bmp.copyPixelsFromBuffer(buffer)
            } else {
                val packed = ByteArray(width * height * 4)
                var dst = 0
                val row = ByteArray(rowStride)
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    val toRead = min(rowStride, buffer.remaining())
                    if (toRead <= 0) break
                    buffer.get(row, 0, toRead)
                    var src = 0
                    for (x in 0 until width) {
                        packed[dst++] = row[src]
                        packed[dst++] = row[src + 1]
                        packed[dst++] = row[src + 2]
                        packed[dst++] = row[src + 3]
                        src += pixelStride
                    }
                }
                bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packed))
            }
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "rgbaImageToBitmap", t)
            null
        }
    }

    /** Safe RGBA ImageReader → JPEG (handles rowStride/pixelStride padding). */
    private fun rgbaImageToJpeg(
        image: android.media.Image,
        width: Int,
        height: Int,
        quality: Int = 70
    ): ByteArray? {
        return try {
            val bmp = rgbaImageToBitmap(image, width, height) ?: return null
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), baos)
            bmp.recycle()
            baos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "rgbaImageToJpeg", t)
            null
        }
    }

    private fun parseControlPort(data: ByteArray, fallback: Int): Int {
        if (data.size >= 3) {
            val p = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            if (p in 1..65535) return p
        }
        return fallback
    }

    private fun resolvePeerForStream(fromHost: String, udpPort: Int): WalkiePeer {
        val existing = peers.values.firstOrNull { it.host == fromHost }
        val vport = learnedVideoPorts[fromHost] ?: existing?.videoPort ?: 0
        // Drop stale host:oldPort keys so monitor audio targets the live socket
        val stale = peers.keys.filter { it.startsWith("$fromHost:") && udpPort > 0 && !it.endsWith(":$udpPort") }
        stale.forEach { peers.remove(it) }
        val peer = WalkiePeer(
            serviceName = existing?.serviceName ?: "peer-$fromHost",
            host = fromHost,
            port = if (udpPort > 0) udpPort else (existing?.port ?: 0),
            videoPort = vport
        )
        if (peer.port > 0) {
            peers[key(peer)] = peer
            if (selectedPeerKey == null) selectedPeerKey = key(peer)
            postPeers()
        }
        return peer
    }

    private fun startServingListen(target: WalkiePeer? = null) {
        val peer = target ?: currentPeer() ?: getPeers().firstOrNull()
        if (peer == null || peer.port <= 0) {
            Log.w(TAG, "startServingListen: no target peer")
            servingListen.set(false)
            return
        }
        monitorTarget = peer
        if (servingListen.get() && monitorAudioThread?.isAlive == true) {
            Log.i(TAG, "LISTEN serve retarget (thread alive) ${peer.host}:${peer.port}")
            return
        }
        // Flag stuck true with dead thread — restart
        servingListen.set(true)
        mainHandler.post { listener.onListenStateChanged(requestingListen.get(), true) }
        monitorAudioThread = thread(name = "walkie-serve-listen", isDaemon = true) {
            try {
                recordMonitorAudio(peer)
            } catch (t: Throwable) {
                Log.w(TAG, "serve listen", t)
            } finally {
                servingListen.set(false)
                mainHandler.post { listener.onListenStateChanged(requestingListen.get(), false) }
            }
        }
        postStatus("Mic open for peer (LISTEN)")
        Log.i(TAG, "LISTEN serve START → ${peer.host}:${peer.port}")
    }

    private fun stopServingListen() {
        if (!servingListen.getAndSet(false)) return
        // recordMonitorAudio loop checks servingListen
        mainHandler.post { listener.onListenStateChanged(requestingListen.get(), false) }
        Log.i(TAG, "stopServingListen")
    }

    private fun startServingSee(target: WalkiePeer? = null) {
        // Restart if flag stuck true but video thread already died (same on all OEMs)
        if (servingSee.get()) {
            if (monitorVideoThread?.isAlive == true) {
                target?.let { monitorTarget = it }
                Log.i(TAG, "SEE serve already active → ${monitorTarget?.host}:${monitorTarget?.port}")
                return
            }
            Log.w(TAG, "SEE serve flag stuck without thread — restarting")
            servingSee.set(false)
        }
        if (talking.get()) {
            Log.w(TAG, "SEE serve skipped: PTT active")
            return
        }
        if (!servingSee.compareAndSet(false, true)) return
        val peer = target ?: currentPeer() ?: getPeers().firstOrNull() ?: run {
            servingSee.set(false)
            return
        }
        monitorTarget = peer
        mainHandler.post { listener.onSeeStateChanged(requestingSee.get(), true) }
        // Release idle preview so Camera1 is free (critical for dual SEE — one camera only)
        stopLocalPreviewInternal()
        try {
            listener.onStatus("Sharing camera (SEE)…")
        } catch (_: Throwable) {
        }
        // Video only — mic is owned by LISTEN serve (if any). Camera in SeeCameraSession.
        monitorVideoThread = thread(name = "walkie-serve-see-video", isDaemon = true) {
            var frames = 0
            var session: SeeCameraSession? = null
            try {
                // Public path: warm NAT before video so cellular requester can receive
                if (!com.example.barky.net.PeerEndpoint.isPrivateIp(peer.host)) {
                    holePunchPeer(peer, rounds = 10)
                }
                val tcpOk = openVideoOut(peer)
                Log.i(
                    TAG,
                    "SEE serve video start → ${peer.host}:${peer.port} tcp=$tcpOk " +
                        "vport=${peer.videoPort} bgSafeSurface=true uiTex=${uiPreviewTexture != null}"
                )
                val useAvc = peerWantsAvc(peer.host)
                if (useAvc) videoDebug("TX video codec=H.264/AVC 640x480")
                else videoDebug("TX video codec=MJPEG")
                val seeSession = SeeCameraSession(
                    context = appContext,
                    isActive = { servingSee.get() },
                    isTcpConnected = { synchronized(videoOutLock) { videoOut != null } },
                    useAvc = useAvc,
                    onPreviewReady = { w, h, orient, front ->
                        mainHandler.post { listener.onLocalPreviewGeometry(w, h, orient, front) }
                    },
                    onError = { msg ->
                        videoDebug("SEE cam: $msg")
                        Log.w(TAG, "SEE cam error: $msg")
                    },
                    onAvcUnit = { au ->
                        val viaTcp = sendVideoFrameTcp(au, avc = true)
                        val ok = viaTcp || sendMonitorVideoAvcUdp(au, peer)
                        if (ok) frames++
                    },
                    onJpegFrame = { jpeg ->
                        val viaTcp = sendVideoFrameTcp(jpeg)
                        val ok = viaTcp || sendMonitorVideoUdp(jpeg, peer)
                        if (ok) frames++
                    }
                )
                session = seeSession
                seeCameraSession = seeSession
                seeSession.start()
                // Mirror CameraFrameSource so HOLD/preview/flip guards see the busy camera
                cameraSource = seeSession.cameraSource()
                var waited = 0
                while (servingSee.get() && running.get()) {
                    frames = maxOf(frames, seeSession.framesSent())
                    if (frames == 0 && waited >= 2500) {
                        Log.w(TAG, "SEE serve abort: no frames (camera busy or open failed)")
                        postError("Camera busy — close Camera/YouTube video and try SEE again")
                        break
                    }
                    Thread.sleep(200)
                    waited += 200
                }
                closeVideoOut()
            } catch (t: Throwable) {
                Log.w(TAG, "serve see", t)
            } finally {
                try {
                    session?.stop()
                } catch (_: Throwable) {
                }
                if (seeCameraSession === session) seeCameraSession = null
                // Only clear if HOLD is not using cameraSource (SEE owns it while serving)
                if (!talking.get()) cameraSource = null
                servingSee.set(false)
                mainHandler.post { listener.onSeeStateChanged(requestingSee.get(), false) }
                if (!talking.get()) maybeResumeLocalPreview()
                Log.i(TAG, "SEE serve video end frames=$frames")
            }
        }
        postStatus("Camera open for peer (SEE video)")
        Log.i(TAG, "SEE serve START → ${peer.host}:${peer.port} vport=${peer.videoPort}")
    }

    private fun stopServingSee() {
        if (!servingSee.getAndSet(false)) return
        mainHandler.post { listener.onSeeStateChanged(requestingSee.get(), false) }
        Log.i(TAG, "stopServingSee")
    }

    private fun recordMonitorAudio(peer: WalkiePeer) {
        var recorder: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                WalkieProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuf, WalkieProtocol.BYTES_PER_FRAME * 4)
            // Pixel 8 is picky: try VOICE_COMMUNICATION then MIC then DEFAULT
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            for (src in sources) {
                try {
                    val r = AudioRecord(
                        src,
                        WalkieProtocol.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        recorder = r
                        Log.i(TAG, "monitor mic source=$src OK")
                        break
                    }
                    r.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "monitor mic source=$src fail", t)
                }
            }
            if (recorder == null) {
                Log.e(TAG, "monitor mic not init on any source")
                postError("Mic unavailable for LISTEN/SEE")
                return
            }
            recorder.startRecording()
            val useOpus = peerWantsOpus(peer.host) && opusTx.startEncoder()
            val frame = ByteArray(WalkieProtocol.BYTES_PER_FRAME)
            val packetBuf = ByteArray(1 + WalkieProtocol.BYTES_PER_FRAME)
            packetBuf[0] = WalkieProtocol.TYPE_MONITOR_AUDIO
            var sent = 0
            // Mic stream is LISTEN-only (SEE is video-only)
            while (running.get() && servingListen.get()) {
                val dest = monitorTarget ?: peer
                if (dest.port <= 0) {
                    Thread.sleep(50)
                    continue
                }
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) continue
                try {
                    if (useOpus) {
                        val opus = opusTx.encode(frame, n)
                        if (opus != null && opus.isNotEmpty()) {
                            val ob = ByteArray(1 + opus.size)
                            ob[0] = WalkieProtocol.TYPE_MONITOR_AUDIO_OPUS
                            System.arraycopy(opus, 0, ob, 1, opus.size)
                            sendUdp(
                                DatagramPacket(ob, ob.size, InetAddress.getByName(dest.host), dest.port)
                            )
                            sent++
                        }
                    } else {
                        System.arraycopy(frame, 0, packetBuf, 1, n)
                        sendUdp(
                            DatagramPacket(
                                packetBuf, 1 + n, InetAddress.getByName(dest.host), dest.port
                            )
                        )
                        sent++
                    }
                    if (sent == 1 || sent % 50 == 0) {
                        Log.i(
                            TAG,
                            "MONITOR_AUDIO_TX frames=$sent opus=$useOpus → ${dest.host}:${dest.port}"
                        )
                    }
                } catch (_: Exception) {
                }
            }
            Log.i(TAG, "monitor audio end sent=$sent")
        } catch (t: Throwable) {
            Log.w(TAG, "recordMonitorAudio", t)
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

    @Volatile private var monitorAudioRxCount = 0
    @Volatile private var monitorVideoRxCount = 0

    private fun handleMonitorAudio(data: ByteArray) {
        if (data.size < 3) return
        // Audio only when LISTEN is on (SEE is video-only unless LISTEN also pressed)
        if (!requestingListen.get()) {
            return
        }
        monitorReceiving.set(true)
        val pcm = data.copyOfRange(1, data.size)
        if (!replaying.get()) {
            // Soft mic sources often quiet — same gain path on every device
            playPcm(amplifyPcm16(pcm, MONITOR_GAIN))
            monitorAudioRxCount++
            if (monitorAudioRxCount == 1) {
                forceListenVolume()
            }
            if (monitorAudioRxCount <= 5 || monitorAudioRxCount % 25 == 0) {
                Log.i(
                    TAG,
                    "MONITOR_AUDIO_RX n=$monitorAudioRxCount bytes=${pcm.size} " +
                        "listening=${requestingListen.get()} seeing=${requestingSee.get()}"
                )
            }
        }
    }

    /** 16-bit little-endian PCM gain (clips to range). */
    private fun amplifyPcm16(pcm: ByteArray, gain: Float): ByteArray {
        if (gain == 1f || pcm.size < 2) return pcm
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val g = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (g and 0xFF).toByte()
            out[i + 1] = ((g shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun handleMonitorVideo(data: ByteArray, from: String) {
        if (!requestingSee.get() && !requestingScreen.get()) return
        if (data.size < WalkieProtocol.VIDEO_HEADER) return
        handleVideoBytesAsMonitor(data)
    }

    private fun handleVideoBytesAsMonitor(data: ByteArray) {
        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val part = data[3].toInt() and 0xFF
        val parts = data[4].toInt() and 0xFF
        if (parts <= 0 || part >= parts || parts > 64) return
        val chunk = data.copyOfRange(WalkieProtocol.VIDEO_HEADER, data.size)
        val jpeg: ByteArray? = synchronized(videoAssembly) {
            var slots = videoAssembly[frameId]
            if (slots == null || slots.size != parts) {
                slots = arrayOfNulls(parts)
                videoAssembly[frameId] = slots
            }
            if (slots[part] == null) slots[part] = chunk
            videoAssemblyTime[frameId] = System.currentTimeMillis()
            if (slots.all { it != null }) {
                videoAssembly.remove(frameId)
                videoAssemblyTime.remove(frameId)
                val total = slots.sumOf { it!!.size }
                val out = ByteArray(total)
                var o = 0
                for (s in slots) {
                    val b = s!!
                    System.arraycopy(b, 0, out, o, b.size)
                    o += b.size
                }
                out
            } else null
        }
        if (jpeg != null && jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            if (!replaying.get()) {
                monitorVideoRxCount++
                if (monitorVideoRxCount == 1 || monitorVideoRxCount % 30 == 0) {
                    Log.i(TAG, "MONITOR_VIDEO_RX n=$monitorVideoRxCount bytes=${jpeg.size}")
                }
                decodeAndEmit(jpeg, live = true)
            }
        }
    }

    private fun sendMonitorVideoUdp(jpeg: ByteArray, peer: WalkiePeer): Boolean {
        return sendChunkedMonitorVideo(jpeg, peer, WalkieProtocol.TYPE_MONITOR_VIDEO)
    }

    private fun sendMonitorVideoAvcUdp(au: ByteArray, peer: WalkiePeer): Boolean {
        return sendChunkedMonitorVideo(au, peer, WalkieProtocol.TYPE_MONITOR_VIDEO_AVC)
    }

    private fun sendChunkedMonitorVideo(payload: ByteArray, peer: WalkiePeer, type: Byte): Boolean {
        return try {
            val chunk = WalkieProtocol.VIDEO_CHUNK
            val parts = (payload.size + chunk - 1) / chunk
            if (parts > 64 || parts <= 0) return false
            val frameId = (System.currentTimeMillis() and 0xFFFF).toInt()
            var offset = 0
            for (part in 0 until parts) {
                val len = min(chunk, payload.size - offset)
                val buf = ByteArray(WalkieProtocol.VIDEO_HEADER + len)
                buf[0] = type
                buf[1] = ((frameId shr 8) and 0xFF).toByte()
                buf[2] = (frameId and 0xFF).toByte()
                buf[3] = part.toByte()
                buf[4] = parts.toByte()
                System.arraycopy(payload, offset, buf, WalkieProtocol.VIDEO_HEADER, len)
                val packet = DatagramPacket(
                    buf, buf.size, InetAddress.getByName(peer.host), peer.port
                )
                sendUdp(packet)
                offset += len
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendChunkedMonitorVideo type=$type", t)
            false
        }
    }

    private fun sendControl(peer: WalkiePeer, type: Byte) {
        sendControlWithPort(peer, type, 0)
    }

    /** Single UDP control (for toggle commands like camera flip). */
    private fun sendControlOnce(peer: WalkiePeer, type: Byte) {
        try {
            val payload = byteArrayOf(type)
            val packet = DatagramPacket(
                payload, payload.size, InetAddress.getByName(peer.host), peer.port
            )
            sendUdp(packet)
            Log.i(TAG, "CTRL once type=$type to ${peer.host}:${peer.port}")
        } catch (t: Throwable) {
            Log.w(TAG, "sendControlOnce $type", t)
        }
    }

    private fun sendControlWithPort(peer: WalkiePeer, type: Byte, replyUdpPort: Int) {
        thread(name = "walkie-ctrl-$type", isDaemon = true) {
            try {
                val payload = if (replyUdpPort > 0) {
                    byteArrayOf(
                        type,
                        ((replyUdpPort shr 8) and 0xFF).toByte(),
                        (replyUdpPort and 0xFF).toByte()
                    )
                } else {
                    byteArrayOf(type)
                }
                val packet = DatagramPacket(
                    payload, payload.size, InetAddress.getByName(peer.host), peer.port
                )
                // Reliable burst for session control — NOT for toggle/flip (use sendControlOnce)
                val times = if (type == WalkieProtocol.TYPE_CAMERA_FLIP) 1 else 5
                repeat(times) {
                    sendUdp(packet)
                    if (times > 1) Thread.sleep(25)
                }
                Log.i(TAG, "CTRL sent type=$type to ${peer.host}:${peer.port} replyPort=$replyUdpPort n=$times")
            } catch (t: Throwable) {
                Log.w(TAG, "sendControl $type", t)
            }
        }
    }

    /** SCRN control: [type][replyUdp:u16][replyVideoTcp:u16] so peer can open TCP video out. */
    private fun sendScreenReq(peer: WalkiePeer, replyUdp: Int, replyVideo: Int) {
        try {
            val payload = byteArrayOf(
                WalkieProtocol.TYPE_SCREEN_REQ,
                ((replyUdp shr 8) and 0xFF).toByte(),
                (replyUdp and 0xFF).toByte(),
                ((replyVideo shr 8) and 0xFF).toByte(),
                (replyVideo and 0xFF).toByte()
            )
            val packet = DatagramPacket(
                payload, payload.size, InetAddress.getByName(peer.host), peer.port
            )
            sendUdp(packet)
        } catch (t: Throwable) {
            Log.w(TAG, "sendScreenReq", t)
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
        // Stable label (no random) so Contacts/peers don't reshuffle every restart
        val model = Build.MODEL?.replace(Regex("[^A-Za-z0-9]"), "")?.take(16)
        val idTail = try {
            com.example.barky.net.DeviceIdentity.deviceId(appContext).takeLast(4)
        } catch (_: Throwable) {
            "dev"
        }
        return if (model.isNullOrBlank()) "Phone-$idTail" else "$model-$idTail"
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
        private const val GPS_SHARE_INTERVAL_MS = 2_500L
        /** Soft mic streams need gain so Pixel LISTEN is audible. */
        private const val MONITOR_GAIN = 3.0f
    }
}
