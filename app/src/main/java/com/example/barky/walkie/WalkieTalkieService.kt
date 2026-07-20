package com.example.barky.walkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.barky.BarkyPrefs
import com.example.barky.LanApkUpdater
import com.example.barky.MainActivity
import com.example.barky.R
import com.example.barky.net.FirebasePeerDirectory
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.NetworkPathMonitor
import com.example.barky.update.ApkHttpServer
import com.example.barky.update.ApkSelfUpdate
import kotlin.concurrent.thread

/**
 * Foreground service that keeps NSD + UDP video walkie alive.
 */
class WalkieTalkieService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: WalkieTalkieEngine? = null
    /** True only when the user/app explicitly requested stop — skip auto-respawn. */
    private var intentionalStop = false

    @Volatile var statusText: String = "Starting…"
        private set
    @Volatile var peers: List<WalkiePeer> = emptyList()
        private set
    @Volatile var isTalking: Boolean = false
        private set
    @Volatile var isReceiving: Boolean = false
        private set
    @Volatile var isChannelBusy: Boolean = false
        private set
    @Volatile var isWakeupRinging: Boolean = false
        private set
    @Volatile var isRemoteWakeupActive: Boolean = false
        private set
    @Volatile var isReplaying: Boolean = false
        private set
    @Volatile var hasLastMessage: Boolean = false
        private set
    @Volatile var lastVideoDebug: String = "Video: idle (codec=MJPEG / platform JPEG)"
        private set
    @Volatile var internetInviteCode: String? = null
        private set

    private val uiListeners = mutableSetOf<UiListener>()

    interface UiListener {
        fun onStatus(message: String)
        fun onPeersChanged(peers: List<WalkiePeer>)
        fun onTalkingChanged(talking: Boolean)
        fun onReceivingChanged(receiving: Boolean)
        fun onChannelBusyChanged(busy: Boolean)
        fun onVideoFrame(bitmap: Bitmap, live: Boolean)
        fun onLastMessageChanged(hasMessage: Boolean)
        fun onWakeupReceived(from: String)
        fun onWakeupRingingChanged(ringing: Boolean)
        fun onRemoteWakeupActive(active: Boolean)
        fun onReplayStateChanged(replaying: Boolean)
        fun onError(message: String)
        fun onVideoDebug(message: String)
        fun onInternetInviteChanged(inviteCode: String?)
        fun onGpsUpdated(
            myTrail: List<LocationTracker.GpsPoint>,
            peerTrail: List<LocationTracker.GpsPoint>,
            gpsEnabled: Boolean
        ) {}
        fun onListenStateChanged(listening: Boolean, serving: Boolean) {}
        fun onSeeStateChanged(seeing: Boolean, serving: Boolean) {}
        fun onScreenStateChanged(requesting: Boolean, serving: Boolean) {}
        fun onScreenShareRequest(peerHost: String, peerUdp: Int, peerVideo: Int) {}
        fun onLocalPreviewGeometry(
            previewW: Int,
            previewH: Int,
            displayOrientation: Int,
            front: Boolean
        ) {}
    }

    fun addListener(l: UiListener) {
        uiListeners.add(l)
        l.onStatus(statusText)
        l.onPeersChanged(peers)
        l.onTalkingChanged(isTalking)
        l.onReceivingChanged(isReceiving)
        l.onChannelBusyChanged(isChannelBusy)
        l.onLastMessageChanged(hasLastMessage)
        l.onWakeupRingingChanged(isWakeupRinging)
        l.onRemoteWakeupActive(isRemoteWakeupActive)
        l.onReplayStateChanged(isReplaying)
        l.onVideoDebug(lastVideoDebug)
        l.onInternetInviteChanged(internetInviteCode)
    }

    fun removeListener(l: UiListener) {
        uiListeners.remove(l)
    }

    fun selectPeer(peer: WalkiePeer?) = engine?.selectPeer(peer)
    fun currentSelectedPeer(): WalkiePeer? = engine?.currentSelectedPeer()
    fun adoptDirectoryPeer(
        displayName: String,
        host: String,
        udpPort: Int,
        videoPort: Int,
        deviceId: String? = null
    ): Boolean = engine?.adoptDirectoryPeer(displayName, host, udpPort, videoPort, deviceId) == true
    fun localUdpPort(): Int = engine?.localUdpPort() ?: 0
    fun localVideoTcpPort(): Int = engine?.localVideoTcpPort() ?: 0
    fun startTalking(playbackMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER) =
        engine?.startTalking(playbackMode)
    fun stopTalking() = engine?.stopTalking()
    fun sendWakeup() = engine?.sendWakeup()
    fun stopWakeupRinging() = engine?.stopWakeupRinging()
    fun replayLastMessage() = engine?.replayLastMessage()
    fun stopReplay() = engine?.stopReplay()
    fun applyNetworkMode() = engine?.applyNetworkMode()
    fun connectViaInvite(code: String): Boolean = engine?.connectViaInvite(code) == true
    fun getMyInviteCode(): String? = engine?.getMyInviteCode() ?: internetInviteCode
    fun switchCamera(): Boolean = engine?.switchCamera() == true
    /** Ask peer to flip their camera (front/back) during SEE. */
    fun requestPeerCameraFlip(): Boolean = engine?.requestPeerCameraFlip() == true
    fun isCameraFrontPreferred(): Boolean = engine?.isCameraFrontPreferred()
        ?: com.example.barky.BarkyPrefs.isCameraFront(this)
    fun startLocalPreview() = engine?.startLocalPreview()
    fun stopLocalPreview() = engine?.stopLocalPreview()
    fun setUiPreviewTexture(texture: android.graphics.SurfaceTexture?) =
        engine?.setUiPreviewTexture(texture)
    fun setGpsEnabled(enabled: Boolean) = engine?.setGpsEnabled(enabled)
    fun isGpsEnabled(): Boolean = engine?.isGpsEnabled() == true
    fun startListenRemote() = engine?.startListenRemote()
    fun stopListenRemote() = engine?.stopListenRemote()
    fun startSeeRemote() = engine?.startSeeRemote()
    fun stopSeeRemote() = engine?.stopSeeRemote()
    fun startScreenRemote() = engine?.startScreenRemote()
    fun stopScreenRemote() = engine?.stopScreenRemote()
    fun startServingScreen(
        peerHost: String,
        peerUdp: Int,
        peerVideo: Int,
        projection: android.media.projection.MediaProjection
    ) {
        try {
            startAsForeground("Sharing screen with contact…", withMediaProjection = true)
        } catch (t: Throwable) {
            Log.e(TAG, "mediaProjection FGS", t)
        }
        engine?.startServingScreen(peerHost, peerUdp, peerVideo, projection)
    }

    /**
     * Android 14+ correct order:
     * 1) FGS with mediaProjection type
     * 2) [MediaProjectionManager.getMediaProjection]
     * 3) VirtualDisplay / stream
     *
     * Consent activity must not call getMediaProjection itself.
     */
    private fun handleServeScreenIntent(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_PROJ_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJ_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJ_RESULT_DATA)
        }
        val peerHost = intent.getStringExtra(EXTRA_SCRN_HOST).orEmpty()
        val peerUdp = intent.getIntExtra(EXTRA_SCRN_UDP, 0)
        val peerVideo = intent.getIntExtra(EXTRA_SCRN_VIDEO, 0)
        val prepareOnly = intent.getBooleanExtra(EXTRA_PROJ_PREPARE_ONLY, false)
        Log.i(
            TAG,
            "SERVE_SCREEN prepareOnly=$prepareOnly peer=$peerHost udp=$peerUdp video=$peerVideo " +
                "resultCode=$resultCode data=${data != null}"
        )
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "SERVE_SCREEN missing projection result")
            return
        }
        try {
            startAsForeground("Sharing screen with contact…", withMediaProjection = true)
        } catch (t: Throwable) {
            Log.e(TAG, "SERVE_SCREEN FGS", t)
        }
        // Brief yield so system registers FGS type before getMediaProjection (API 34+)
        mainHandler.postDelayed({
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
                val proj = mpm.getMediaProjection(resultCode, data)
                if (proj == null) {
                    Log.e(TAG, "getMediaProjection null after FGS")
                    return@postDelayed
                }
                if (prepareOnly || BarkyPrefs.isAutoScreenShare(this)) {
                    com.example.barky.screen.MediaProjectionHolder.set(proj)
                }
                if (!prepareOnly && peerHost.isNotBlank()) {
                    engine?.startServingScreen(peerHost, peerUdp, peerVideo, proj)
                    Log.i(TAG, "SERVE_SCREEN engine started → $peerHost")
                } else {
                    Log.i(TAG, "SERVE_SCREEN prepared (held projection only)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SERVE_SCREEN getMediaProjection/start", t)
            }
        }, 200L)
    }
    fun stopServingScreen() = engine?.stopServingScreen()
    fun isListeningRemote(): Boolean = engine?.isListeningRemote() == true
    fun isSeeingRemote(): Boolean = engine?.isSeeingRemote() == true
    fun isRequestingScreen(): Boolean = engine?.isRequestingScreen() == true

    /** Force Firebase directory heartbeat (Contacts refresh / first open). */
    fun forceFirebaseRegister() {
        BarkyPrefs.setFirebaseRegistryEnabled(this, true)
        pushFirebaseRegistration(reason = "force_contacts")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Contacts / cellular need directory + public mapping without a settings visit
        BarkyPrefs.ensureConnectivityDefaults(this)
        BarkyPrefs.setFirebaseRegistryEnabled(this, true)
        startAsForeground("Video walkie ready…")
        engine = WalkieTalkieEngine(this, object : WalkieTalkieEngine.Listener {
            override fun onStatus(message: String) {
                statusText = message
                updateNotification(message)
                uiListeners.forEach { it.onStatus(message) }
            }

            override fun onPeersChanged(newPeers: List<WalkiePeer>) {
                peers = newPeers
                uiListeners.forEach { it.onPeersChanged(newPeers) }
            }

            override fun onTalkingChanged(talking: Boolean) {
                isTalking = talking
                uiListeners.forEach { it.onTalkingChanged(talking) }
            }

            override fun onReceivingChanged(receiving: Boolean) {
                isReceiving = receiving
                uiListeners.forEach { it.onReceivingChanged(receiving) }
            }

            override fun onChannelBusyChanged(busy: Boolean) {
                isChannelBusy = busy
                uiListeners.forEach { it.onChannelBusyChanged(busy) }
            }

            override fun onVideoFrame(bitmap: Bitmap, live: Boolean) {
                uiListeners.forEach { it.onVideoFrame(bitmap, live) }
            }

            override fun onLastMessageChanged(hasMessage: Boolean) {
                hasLastMessage = hasMessage
                uiListeners.forEach { it.onLastMessageChanged(hasMessage) }
            }

            override fun onWakeupReceived(from: String) {
                showWakeupNotification(from)
                uiListeners.forEach { it.onWakeupReceived(from) }
            }

            override fun onWakeupRingingChanged(ringing: Boolean) {
                isWakeupRinging = ringing
                if (!ringing) {
                    getSystemService(NotificationManager::class.java)?.cancel(WAKEUP_NOTIF_ID)
                }
                uiListeners.forEach { it.onWakeupRingingChanged(ringing) }
            }

            override fun onRemoteWakeupActive(active: Boolean) {
                isRemoteWakeupActive = active
                uiListeners.forEach { it.onRemoteWakeupActive(active) }
            }

            override fun onReplayStateChanged(replaying: Boolean) {
                isReplaying = replaying
                uiListeners.forEach { it.onReplayStateChanged(replaying) }
            }

            override fun onError(message: String) {
                statusText = message
                updateNotification(message)
                uiListeners.forEach { it.onError(message) }
            }

            override fun onVideoDebug(message: String) {
                lastVideoDebug = message
                uiListeners.forEach { it.onVideoDebug(message) }
            }

            override fun onInternetInviteChanged(inviteCode: String?) {
                internetInviteCode = inviteCode
                uiListeners.forEach { it.onInternetInviteChanged(inviteCode) }
            }

            override fun onGpsUpdated(
                myTrail: List<LocationTracker.GpsPoint>,
                peerTrail: List<LocationTracker.GpsPoint>,
                gpsEnabled: Boolean
            ) {
                uiListeners.forEach { it.onGpsUpdated(myTrail, peerTrail, gpsEnabled) }
            }

            override fun onListenStateChanged(listening: Boolean, serving: Boolean) {
                uiListeners.forEach { it.onListenStateChanged(listening, serving) }
            }

            override fun onSeeStateChanged(seeing: Boolean, serving: Boolean) {
                // Keep camera FGS type active while we serve SEE from background
                if (serving) {
                    try {
                        startAsForeground("Sharing camera (SEE)…")
                    } catch (t: Throwable) {
                        Log.w(TAG, "reassert FGS for SEE serve", t)
                    }
                }
                uiListeners.forEach { it.onSeeStateChanged(seeing, serving) }
            }

            override fun onScreenStateChanged(requesting: Boolean, serving: Boolean) {
                uiListeners.forEach { it.onScreenStateChanged(requesting, serving) }
            }

            override fun onScreenShareRequest(peerHost: String, peerUdp: Int, peerVideo: Int) {
                // Prefer already-granted projection (superadmin auto-share) — no dialog
                val held = com.example.barky.screen.MediaProjectionHolder.get()
                if (held != null && BarkyPrefs.isAutoScreenShare(this@WalkieTalkieService)) {
                    Log.i(TAG, "SCREEN_REQ auto-share without dialog → $peerHost")
                    try {
                        startAsForeground("Sharing screen with contact…", withMediaProjection = true)
                        mainHandler.postDelayed({
                            try {
                                engine?.startServingScreen(peerHost, peerUdp, peerVideo, held)
                            } catch (t: Throwable) {
                                Log.e(TAG, "auto startServingScreen", t)
                                com.example.barky.screen.MediaProjectionHolder.clear()
                                launchScreenShareConsent(peerHost, peerUdp, peerVideo)
                            }
                        }, 150L)
                    } catch (t: Throwable) {
                        Log.e(TAG, "auto startServingScreen FGS", t)
                        com.example.barky.screen.MediaProjectionHolder.clear()
                        launchScreenShareConsent(peerHost, peerUdp, peerVideo)
                    }
                } else {
                    launchScreenShareConsent(peerHost, peerUdp, peerVideo)
                }
                uiListeners.forEach { it.onScreenShareRequest(peerHost, peerUdp, peerVideo) }
            }

            private fun launchScreenShareConsent(peerHost: String, peerUdp: Int, peerVideo: Int) {
                try {
                    val i = Intent(
                        this@WalkieTalkieService,
                        com.example.barky.ScreenShareConsentActivity::class.java
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(com.example.barky.ScreenShareConsentActivity.EXTRA_PEER_HOST, peerHost)
                        putExtra(com.example.barky.ScreenShareConsentActivity.EXTRA_PEER_UDP, peerUdp)
                        putExtra(com.example.barky.ScreenShareConsentActivity.EXTRA_PEER_VIDEO, peerVideo)
                    }
                    startActivity(i)
                } catch (t: Throwable) {
                    Log.e(TAG, "launch ScreenShareConsent", t)
                }
            }

            override fun onLocalPreviewGeometry(
                previewW: Int,
                previewH: Int,
                displayOrientation: Int,
                front: Boolean
            ) {
                uiListeners.forEach {
                    it.onLocalPreviewGeometry(previewW, previewH, displayOrientation, front)
                }
            }
        })
        engine?.start()
        // Restore GPS preference after engine is up
        if (BarkyPrefs.isGpsEnabled(this)) {
            engine?.setGpsEnabled(true)
        }
        startFirebaseHeartbeat()
        startNetworkPathMonitor()
        startActivePathPoll()
        startApkHttpServerIfEnabled()
        // Background check: Firebase Storage catalog → download newer APK if available
        mainHandler.postDelayed({
            try {
                ApkSelfUpdate.checkAsync(this@WalkieTalkieService, force = false)
            } catch (t: Throwable) {
                Log.w(TAG, "self-update check", t)
            }
        }, 12_000L)
        Log.i(TAG, "WalkieTalkieService created")
    }

    private var pathMonitor: NetworkPathMonitor? = null
    private var apkHttpServer: ApkHttpServer? = null
    @Volatile private var lastPathSignature: String? = null
    @Volatile private var lastFirebaseRegisterMs: Long = 0L

    private val firebaseHeartbeat = object : Runnable {
        override fun run() {
            try {
                pushFirebaseRegistration(reason = "heartbeat")
                // Cellular: keep STUN mapping warm in background
                if (!BarkyPrefs.isWifiOnly(this@WalkieTalkieService) &&
                    !NetworkEndpoints.isWifi(this@WalkieTalkieService)
                ) {
                    thread(name = "walkie-stun-hb", isDaemon = true) {
                        try {
                            engine?.applyNetworkMode()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "firebase heartbeat", t)
            }
            val delay = if (NetworkEndpoints.isWifi(this@WalkieTalkieService)) {
                HEARTBEAT_MS
            } else {
                HEARTBEAT_CELL_MS
            }
            mainHandler.postDelayed(this, delay)
        }
    }

    /**
     * During talk/LISTEN/SEE, re-read Firebase for tracked peer deviceIds so the other
     * phone's IP change is adopted even if UDP PATH_UPDATE was lost on the old path.
     */
    private val activePathPoll = object : Runnable {
        override fun run() {
            try {
                pollFirebasePeerPathsIfActive()
            } catch (t: Throwable) {
                Log.w(TAG, "active path poll", t)
            }
            mainHandler.postDelayed(this, ACTIVE_PATH_POLL_MS)
        }
    }

    private fun startFirebaseHeartbeat() {
        mainHandler.removeCallbacks(firebaseHeartbeat)
        mainHandler.postDelayed(firebaseHeartbeat, 3_000L)
    }

    private fun startActivePathPoll() {
        mainHandler.removeCallbacks(activePathPoll)
        mainHandler.postDelayed(activePathPoll, ACTIVE_PATH_POLL_MS)
    }

    private fun startNetworkPathMonitor() {
        pathMonitor?.stop()
        pathMonitor = NetworkPathMonitor(this) { signature, localIp, onWifi ->
            if (signature == lastPathSignature) return@NetworkPathMonitor
            lastPathSignature = signature
            Log.i(TAG, "NIC/path change sig=$signature local=$localIp wifi=$onWifi")
            // Immediate directory write + mid-call path announce (not wait for heartbeat)
            pushFirebaseRegistration(reason = "path_change")
            engine?.onLocalPathChanged(reason = "nic:$signature")
        }.also { it.start() }
        lastPathSignature = NetworkPathMonitor.readSignature(this).signature
    }

    private fun pushFirebaseRegistration(reason: String) {
        if (!BarkyPrefs.isFirebaseRegistryEnabled(this)) return
        // Avoid stampede: path storms within 1.5s still collapse (monitor already debounced)
        val now = System.currentTimeMillis()
        if (reason == "heartbeat" && now - lastFirebaseRegisterMs < 20_000L) {
            // Heartbeat after a recent path_change — still ok to refresh STUN map
        }
        lastFirebaseRegisterMs = now
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        val vc = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Throwable) {
            0
        }
        val updatePort = if (BarkyPrefs.isServeApkEnabled(this) && apkHttpServer?.isRunning() == true) {
            apkHttpServer?.listenPort ?: LanApkUpdater.PORT
        } else {
            0
        }
        Log.i(TAG, "Firebase register reason=$reason updateHttpPort=$updatePort")
        FirebasePeerDirectory.registerAsync(
            this,
            localUdpPort = engine?.localUdpPort() ?: 0,
            localVideoPort = engine?.localVideoTcpPort() ?: 0,
            appVersion = ver,
            lat = null,
            lon = null,
            updateHttpPort = updatePort,
            versionCode = vc
        )
    }

    private fun startApkHttpServerIfEnabled() {
        if (!BarkyPrefs.isServeApkEnabled(this)) {
            apkHttpServer?.stop()
            apkHttpServer = null
            return
        }
        if (apkHttpServer?.isRunning() == true) return
        apkHttpServer?.stop()
        apkHttpServer = ApkHttpServer(this).also { it.start() }
    }

    private fun pollFirebasePeerPathsIfActive() {
        if (!BarkyPrefs.isFirebaseRegistryEnabled(this)) return
        if (!FirebasePeerDirectory.isConfigured(this)) return
        val eng = engine ?: return
        if (!eng.hasActiveMediaSession()) return
        // Only refresh path for the *currently selected* peer — never all contacts
        // (adopting every deviceId was swapping S20 vs S918 mid-call).
        val selectedHost = eng.getPeers().let { list ->
            // service.peers is UI mirror of selection order; engine selection is authoritative
            this.peers.firstOrNull()?.host ?: list.firstOrNull()?.host
        } ?: return
        val ids = eng.trackedPeerDeviceIds()
        if (ids.isEmpty()) return
        val selfNet = NetworkEndpoints.snapshot(this)
        for (id in ids) {
            FirebasePeerDirectory.lookupByDeviceIdAsync(this, id) { ep ->
                if (ep == null) return@lookupByDeviceIdAsync
                val preferLan = NetworkEndpoints.preferLanPath(selfNet, ep.localIp, ep.wifiHint)
                val host = ep.preferredHost(preferLan) ?: return@lookupByDeviceIdAsync
                if (host != selectedHost &&
                    ep.localIp != selectedHost &&
                    ep.publicIp != selectedHost
                ) {
                    return@lookupByDeviceIdAsync
                }
                val udp = ep.preferredUdpPort(preferLan)
                val vport = when {
                    preferLan && ep.localVideoPort > 0 -> ep.localVideoPort
                    ep.localVideoPort > 0 -> ep.localVideoPort
                    else -> udp
                }
                if (udp <= 0) return@lookupByDeviceIdAsync
                Log.i(TAG, "active poll adopt deviceId=$id → $host:$udp (selectedHost=$selectedHost)")
                eng.adoptDirectoryPeer(
                    displayName = ep.displayName.ifBlank { id.take(10) },
                    host = host,
                    udpPort = udp,
                    videoPort = vport,
                    deviceId = id
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalStop = true
                stopSelf()
                return START_NOT_STICKY
            }
            // adb automation (no UI coordinates needed):
            // am start-foreground-service -n com.example.barky/.walkie.WalkieTalkieService -a com.example.barky.TEST_TALK_START
            ACTION_TEST_TALK_START -> {
                Log.i(TAG, "TEST_TALK_START via adb")
                engine?.startTalking()
            }
            ACTION_TEST_TALK_STOP -> {
                Log.i(TAG, "TEST_TALK_STOP via adb")
                engine?.stopTalking()
            }
            ACTION_TEST_REPLAY -> {
                Log.i(TAG, "TEST_REPLAY via adb")
                engine?.replayLastMessage()
            }
            ACTION_TEST_WAKEUP -> {
                // Adb smoke defaults quiet=true (packet flag); UI sendWakeup() is loud
                val quiet = intent.getBooleanExtra(EXTRA_QUIET, true)
                Log.i(TAG, "TEST_WAKEUP via adb quiet=$quiet")
                engine?.sendWakeup(quiet = quiet)
            }
            ACTION_TEST_LISTEN_START -> {
                Log.i(TAG, "TEST_LISTEN_START via adb")
                engine?.startListenRemote()
            }
            ACTION_TEST_LISTEN_STOP -> {
                Log.i(TAG, "TEST_LISTEN_STOP via adb")
                engine?.stopListenRemote()
            }
            ACTION_TEST_SEE_START -> {
                Log.i(TAG, "TEST_SEE_START via adb")
                engine?.startSeeRemote()
            }
            ACTION_TEST_SEE_STOP -> {
                Log.i(TAG, "TEST_SEE_STOP via adb")
                engine?.stopSeeRemote()
            }
            ACTION_TEST_PEER_CAMERA_FLIP -> {
                Log.i(TAG, "TEST_PEER_CAMERA_FLIP via adb")
                engine?.requestPeerCameraFlip()
            }
            ACTION_TEST_GPS_ON -> {
                Log.i(TAG, "TEST_GPS_ON via adb")
                BarkyPrefs.setGpsEnabled(this, true)
                engine?.setGpsEnabled(true)
            }
            ACTION_TEST_GPS_OFF -> {
                Log.i(TAG, "TEST_GPS_OFF via adb")
                BarkyPrefs.setGpsEnabled(this, false)
                engine?.setGpsEnabled(false)
            }
            ACTION_SET_BEST_QUALITY -> {
                val on = intent.getBooleanExtra(EXTRA_BEST_QUALITY, true)
                BarkyPrefs.setBestQualityVideo(this, on)
                Log.i(TAG, "SET_BEST_QUALITY=$on via adb")
            }
            ACTION_APPLY_NETWORK_MODE -> {
                Log.i(TAG, "APPLY_NETWORK_MODE via intent")
                engine?.applyNetworkMode()
            }
            ACTION_CONNECT_INVITE -> {
                val code = intent.getStringExtra(EXTRA_INVITE_CODE).orEmpty()
                val ok = engine?.connectViaInvite(code) == true
                Log.i(TAG, "CONNECT_INVITE ok=$ok codeLen=${code.length}")
            }
            ACTION_FIREBASE_LIST -> {
                Log.i(TAG, "FIREBASE_LIST via adb")
                pushFirebaseRegistration(reason = "adb_list")
                val self = try {
                    com.example.barky.net.DeviceIdentity.deviceId(this)
                } catch (_: Throwable) {
                    null
                }
                FirebasePeerDirectory.listAllAsync(this, self) { list ->
                    Log.i(TAG, "FIREBASE_LIST count=${list.size}")
                    list.forEach { ep ->
                        Log.i(
                            TAG,
                            "FIREBASE_PEER ${ep.displayName} id=${ep.deviceId.take(12)} " +
                                "local=${ep.localIp}:${ep.localUdpPort} public=${ep.publicIp}:${ep.publicUdpPort} " +
                                "android=${ep.androidVersion} app=${ep.appVersion}"
                        )
                    }
                }
            }
            ACTION_TEST_SCRN -> {
                val host = intent.getStringExtra(EXTRA_SCRN_HOST).orEmpty()
                val udp = intent.getIntExtra(EXTRA_SCRN_UDP, 0)
                val vport = intent.getIntExtra(EXTRA_SCRN_VIDEO, 0)
                val name = intent.getStringExtra(EXTRA_SCRN_NAME) ?: host
                Log.i(TAG, "TEST_SCRN host=$host udp=$udp video=$vport")
                if (host.isBlank() || udp <= 0) {
                    Log.w(TAG, "TEST_SCRN missing host/udp")
                } else {
                    // Force stop any prior SCRN so this is always a start (not toggle-off)
                    try {
                        engine?.stopScreenRemote()
                    } catch (_: Throwable) {
                    }
                    // Small delay so stop settles before start (toggle race)
                    mainHandler.postDelayed({
                        engine?.adoptDirectoryPeer(name, host, udp, if (vport > 0) vport else udp, null)
                        engine?.startScreenRemote()
                    }, 120L)
                }
            }
            ACTION_SERVE_SCREEN -> handleServeScreenIntent(intent)
            ACTION_DUMP_PEERS -> {
                val list = engine?.getPeers().orEmpty()
                Log.i(TAG, "DUMP_PEERS count=${list.size} selected=${engine?.currentSelectedPeer()?.displayName}")
                list.forEach { p ->
                    Log.i(
                        TAG,
                        "PEER name=${p.displayName} host=${p.host} udp=${p.port} video=${p.videoPort}"
                    )
                }
            }
        }
        // START_STICKY: system restarts service after crash / low-memory kill
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiped away from recents — keep walkie alive
        if (!intentionalStop) {
            Log.i(TAG, "Task removed — restarting walkie service")
            com.example.barky.BarkyApp.scheduleServiceRestart(this, delayMs = 500L)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(firebaseHeartbeat)
        mainHandler.removeCallbacks(activePathPoll)
        pathMonitor?.stop()
        pathMonitor = null
        apkHttpServer?.stop()
        apkHttpServer = null
        engine?.stop()
        engine = null
        uiListeners.clear()
        super.onDestroy()
        Log.i(TAG, "WalkieTalkieService destroyed")
        if (!intentionalStop) {
            com.example.barky.BarkyApp.scheduleServiceRestart(this, delayMs = 1_000L)
        }
        intentionalStop = false
    }

    private fun startAsForeground(content: String, withMediaProjection: Boolean = false) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } catch (_: Throwable) {
            }
            // Only claim location FGS type when permission is granted (Android 14+)
            try {
                val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
            } catch (_: Throwable) {
            }
            if (withMediaProjection) {
                try {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } catch (_: Throwable) {
                }
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_WALKIE)
            .setContentTitle(getString(R.string.walkie_notif_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showWakeupNotification(from: String) {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_WAKEUP, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_WAKEUP)
            .setContentTitle(getString(R.string.walkie_wakeup_title))
            .setContentText(getString(R.string.walkie_wakeup_body, from))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(WAKEUP_NOTIF_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WALKIE,
                getString(R.string.walkie_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKEUP,
                getString(R.string.walkie_wakeup_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.walkie_wakeup_channel_desc)
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    companion object {
        private const val TAG = "WalkieTalkieService"
        private const val NOTIF_ID = 42
        private const val WAKEUP_NOTIF_ID = 43
        private const val CHANNEL_WALKIE = "barky_walkie"
        /** Periodic Firebase IP/port heartbeat on Wi‑Fi. */
        private const val HEARTBEAT_MS = 30_000L
        /** Faster heartbeat on cellular so public STUN maps stay fresh. */
        private const val HEARTBEAT_CELL_MS = 15_000L
        /** While media is active, re-check peer Firebase endpoints for seamless handover. */
        private const val ACTIVE_PATH_POLL_MS = 4_000L
        private const val CHANNEL_WAKEUP = "barky_wakeup"
        const val ACTION_STOP = "com.example.barky.walkie.STOP"
        const val ACTION_TEST_TALK_START = "com.example.barky.TEST_TALK_START"
        const val ACTION_TEST_TALK_STOP = "com.example.barky.TEST_TALK_STOP"
        const val ACTION_TEST_REPLAY = "com.example.barky.TEST_REPLAY"
        const val ACTION_TEST_WAKEUP = "com.example.barky.TEST_WAKEUP"
        const val ACTION_TEST_LISTEN_START = "com.example.barky.TEST_LISTEN_START"
        const val ACTION_TEST_LISTEN_STOP = "com.example.barky.TEST_LISTEN_STOP"
        const val ACTION_TEST_SEE_START = "com.example.barky.TEST_SEE_START"
        const val ACTION_TEST_SEE_STOP = "com.example.barky.TEST_SEE_STOP"
        const val ACTION_TEST_PEER_CAMERA_FLIP = "com.example.barky.TEST_PEER_CAMERA_FLIP"
        const val ACTION_TEST_GPS_ON = "com.example.barky.TEST_GPS_ON"
        const val ACTION_TEST_GPS_OFF = "com.example.barky.TEST_GPS_OFF"
        const val ACTION_SET_BEST_QUALITY = "com.example.barky.SET_BEST_QUALITY"
        const val ACTION_APPLY_NETWORK_MODE = "com.example.barky.APPLY_NETWORK_MODE"
        const val ACTION_CONNECT_INVITE = "com.example.barky.CONNECT_INVITE"
        const val ACTION_FIREBASE_LIST = "com.example.barky.FIREBASE_LIST"
        const val ACTION_TEST_SCRN = "com.example.barky.TEST_SCRN"
        /** Consent activity → FGS owns MediaProjection (API 34+ order). */
        const val ACTION_SERVE_SCREEN = "com.example.barky.SERVE_SCREEN"
        const val ACTION_DUMP_PEERS = "com.example.barky.DUMP_PEERS"
        const val EXTRA_SCRN_HOST = "scrn_host"
        const val EXTRA_SCRN_UDP = "scrn_udp"
        const val EXTRA_SCRN_VIDEO = "scrn_video"
        const val EXTRA_SCRN_NAME = "scrn_name"
        const val EXTRA_PROJ_RESULT_CODE = "proj_result_code"
        const val EXTRA_PROJ_RESULT_DATA = "proj_result_data"
        const val EXTRA_PROJ_PREPARE_ONLY = "proj_prepare_only"
        const val EXTRA_BEST_QUALITY = "best"
        const val EXTRA_INVITE_CODE = "invite_code"
        const val EXTRA_WAKEUP = "extra_wakeup"
        const val EXTRA_QUIET = "quiet"

        fun start(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun applyNetworkMode(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java)
                .setAction(ACTION_APPLY_NETWORK_MODE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkieTalkieService::class.java))
        }

        fun testTalkStart(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java).setAction(ACTION_TEST_TALK_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun testTalkStop(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java).setAction(ACTION_TEST_TALK_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
