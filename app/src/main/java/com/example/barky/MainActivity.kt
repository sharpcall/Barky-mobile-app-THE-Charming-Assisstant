package com.example.barky

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.barky.walkie.LocationTracker
import com.example.barky.walkie.WalkiePeer
import com.example.barky.walkie.WalkieProtocol
import com.example.barky.walkie.WalkieTalkieService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main screen: video walkie with speaker/private hold, LISTEN/SEE, GPS map.
 * Half-duplex for HOLD messages; LISTEN/SEE can run both ways.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var walkieStatus: TextView
    private lateinit var walkiePeers: TextView
    private lateinit var walkieBestQuality: MaterialCheckBox
    private lateinit var walkieGps: MaterialCheckBox
    private lateinit var walkieScrn: MaterialCheckBox
    private lateinit var walkieMic: ImageButton
    private lateinit var walkieMicStage: android.widget.FrameLayout
    private lateinit var walkieMicMode: TextView
    private lateinit var micHintLock: TextView
    private lateinit var micHintPrivate: TextView
    private lateinit var micHintSpeaker: TextView
    private lateinit var walkieWakeup: MaterialButton
    private lateinit var walkieListen: MaterialButton
    private lateinit var walkieSee: MaterialButton
    private lateinit var walkieReplay: MaterialButton
    private lateinit var walkieFlipCamera: ImageButton
    private lateinit var walkiePipBox: android.widget.FrameLayout
    private lateinit var walkiePreview: TextureView
    /** Flip button on self PiP — local camera only. */
    private lateinit var walkiePipFlip: ImageButton
    private lateinit var walkieVideo: ImageView
    private lateinit var walkieVideoHint: TextView
    private lateinit var walkieMap: WebView
    private lateinit var walkieLockHint: TextView
    private lateinit var permissionGate: View
    private lateinit var permissionGateList: TextView
    private lateinit var permissionGateBtn: MaterialButton

    private var walkieService: WalkieTalkieService? = null
    private var walkieBound = false
    private var peers: List<WalkiePeer> = emptyList()
    private var selectedPeer: WalkiePeer? = null
    private var walkieRunning = false
    private var wakeupRinging = false
    private var remoteWakeupActive = false
    private var isReplaying = false
    private var isTalking = false
    private var isReceiving = false
    private var channelBusy = false
    private var hasLastMessage = false
    private var hasStickyFrame = false
    private var startRequested = false
    private var gpsEnabled = false
    private var showMap = false
    private var mapReady = false
    private var isListening = false
    private var isSeeing = false
    /** True while peer is watching us (we are serving SEE). */
    private var isServingSee = false
    private var pttLocked = false
    private var activePttMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER
    private var myTrail: List<LocationTracker.GpsPoint> = emptyList()
    private var peerTrail: List<LocationTracker.GpsPoint> = emptyList()
    /** Last camera preview geometry for natural TextureView crop. */
    private var previewBufW = 0
    private var previewBufH = 0
    private var previewDisplayOrient = 90
    /** SEE PiP: enlarged when user taps the floating self-view. */
    private var pipEnlarged = false
    private var pipDragBound = false
    /** True once PiP has been placed; later frames must not reset position. */
    private var pipLayoutActive = false
    private var pipLastLeft = -1
    private var pipLastTop = -1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Keep asking until every required permission is granted — never open fully without them
        if (hasAllRequiredPermissions()) {
            permissionGate.visibility = View.GONE
            reallyStartWalkie()
        } else {
            showPermissionGate()
            // Re-prompt continuously
            walkieStatus.text = getString(R.string.walkie_perm_gate_title)
        }
    }

    private val walkieUiListener = object : WalkieTalkieService.UiListener {
        override fun onStatus(message: String) {
            if (selectedPeer != null && !message.contains("Receiving", true) &&
                !message.contains("Sending", true) &&
                !message.contains("WAKEUP", true) &&
                !message.contains("Replaying", true) &&
                !message.contains("Ringing", true) &&
                !message.contains("busy", true) &&
                !message.contains("LISTEN", true) &&
                !message.contains("SEE", true) &&
                !message.contains("GPS", true)
            ) {
                walkieStatus.text = getString(R.string.walkie_status_ready, selectedPeer!!.displayName)
            } else {
                walkieStatus.text = message
            }
        }

        override fun onPeersChanged(newPeers: List<WalkiePeer>) {
            peers = newPeers
            autoSelectPeer()
            renderPeers()
            updateControls()
        }

        override fun onTalkingChanged(talking: Boolean) {
            isTalking = talking
            if (talking) {
                showMap = false
                applyStageVisibility()
                walkieVideoHint.visibility = View.GONE
                walkieReplay.visibility = View.GONE
                showLocalCameraSurface(true)
            } else {
                pttLocked = false
                updatePttLabels()
                updateReplayVisibility()
                if (!isReceiving && !isReplaying && !isSeeing) {
                    if (gpsEnabled) {
                        showMap = true
                        applyStageVisibility()
                    } else {
                        showLocalCameraSurface(true)
                    }
                }
            }
            updateControls()
        }

        override fun onReceivingChanged(receiving: Boolean) {
            isReceiving = receiving
            if (receiving) {
                walkieStatus.text = getString(R.string.walkie_receiving)
                walkieVideoHint.visibility = View.GONE
                walkieReplay.visibility = View.GONE
                showMap = false
                showLocalCameraSurface(false)
            } else if (walkieRunning) {
                walkieStatus.text = if (selectedPeer != null) {
                    getString(R.string.walkie_status_ready, selectedPeer!!.displayName)
                } else {
                    getString(R.string.walkie_status_waiting)
                }
                if (hasStickyFrame) walkieVideoHint.visibility = View.GONE
                updateReplayVisibility()
                if (!isTalking && !isReplaying && !isSeeing) {
                    if (gpsEnabled) {
                        showMap = true
                        applyStageVisibility()
                    } else {
                        showLocalCameraSurface(true)
                    }
                }
            }
            updateControls()
        }

        override fun onChannelBusyChanged(busy: Boolean) {
            channelBusy = busy
            updatePttLabels()
            updateControls()
            if (!busy) updateReplayVisibility()
        }

        override fun onVideoFrame(bitmap: Bitmap, live: Boolean) {
            hasStickyFrame = true
            walkieVideoHint.visibility = View.GONE
            showMap = false
            val watching = isSeeing || (walkieService?.isRequestingScreen() == true)
            if (watching && live && !isTalking) {
                // Remote video fills stage; PiP self-view stays a small natural rectangle on top
                if (!pipLayoutActive) applySeeSplitLayout(true)
                walkieVideo.elevation = 0f
                // Pixel: replace drawable carefully — recycling the previous bitmap while
                // ImageView still holds it caused black flashes / flicker.
                setRemoteVideoBitmap(bitmap)
                walkieVideo.visibility = View.VISIBLE
                walkieMap.visibility = View.GONE
                if (::walkiePipBox.isInitialized) {
                    walkiePipBox.visibility = View.VISIBLE
                    walkiePipBox.bringToFront()
                }
            } else if (isReceiving || isReplaying || !isTalking) {
                if (isReceiving || isReplaying) {
                    showLocalCameraSurface(false)
                }
                setRemoteVideoBitmap(bitmap)
                walkieVideo.visibility = View.VISIBLE
                walkieMap.visibility = View.GONE
            } else {
                // Not displayed — recycle to free RAM
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if ((isTalking || isReceiving || isSeeing || watching) && live && !isReplaying) {
                walkieReplay.visibility = View.GONE
            } else if (!isTalking && !isReceiving && !isReplaying && !isSeeing && !watching) {
                updateReplayVisibility()
            }
        }

        override fun onVideoDebug(message: String) {
            android.util.Log.i("BarkyVideoUI", message)
        }

        override fun onLastMessageChanged(hasMessage: Boolean) {
            hasLastMessage = hasMessage
            updateReplayVisibility()
            updateControls()
        }

        override fun onWakeupReceived(from: String) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.walkie_wakeup_body, from),
                Toast.LENGTH_LONG
            ).show()
            walkieStatus.text = getString(R.string.walkie_wakeup_body, from)
        }

        override fun onWakeupRingingChanged(ringing: Boolean) {
            wakeupRinging = ringing
            updateWakeupButton()
        }

        override fun onRemoteWakeupActive(active: Boolean) {
            remoteWakeupActive = active
            updateWakeupButton()
        }

        override fun onReplayStateChanged(replaying: Boolean) {
            runOnUiThread {
                isReplaying = replaying
                if (replaying) {
                    showMap = false
                    showLocalCameraSurface(false)
                } else if (!isReceiving && !isTalking && !isSeeing) {
                    if (gpsEnabled) {
                        showMap = true
                        applyStageVisibility()
                    } else {
                        showLocalCameraSurface(true)
                    }
                }
                updateReplayButton()
                updateReplayVisibility()
                updateControls()
            }
        }

        override fun onError(message: String) {
            walkieStatus.text = message
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onInternetInviteChanged(inviteCode: String?) {
            // Pair-code UI removed — Firebase Contacts is the directory
        }

        override fun onGpsUpdated(
            myTrail: List<LocationTracker.GpsPoint>,
            peerTrail: List<LocationTracker.GpsPoint>,
            gpsEnabled: Boolean
        ) {
            runOnUiThread {
                this@MainActivity.myTrail = myTrail
                this@MainActivity.peerTrail = peerTrail
                this@MainActivity.gpsEnabled = gpsEnabled
                if (gpsEnabled && !isTalking && !isReceiving && !isReplaying && !isSeeing) {
                    showMap = true
                    applyStageVisibility()
                }
                pushMapTrail()
            }
        }

        override fun onListenStateChanged(listening: Boolean, serving: Boolean) {
            runOnUiThread {
                isListening = listening
                updateListenSeeButtons()
            }
        }

        override fun onSeeStateChanged(seeing: Boolean, serving: Boolean) {
            runOnUiThread {
                isSeeing = seeing
                isServingSee = serving
                if (seeing) {
                    showMap = false
                    // WhatsApp-style: big remote + small local self-view
                    applySeeSplitLayout(true)
                    // If we are also serving SEE, cameraSource already owns the lens —
                    // do not open a second Camera (dual-SEE crash).
                    if (!isServingSee) {
                        walkieService?.startLocalPreview()
                    }
                } else if (!isTalking && !isReceiving && !isReplaying) {
                    applySeeSplitLayout(false)
                    if (gpsEnabled) {
                        showMap = true
                        applyStageVisibility()
                    } else {
                        showLocalCameraSurface(true)
                    }
                } else {
                    applySeeSplitLayout(false)
                }
                updateListenSeeButtons()
                updateControls()
            }
        }

        override fun onLocalPreviewGeometry(
            previewW: Int,
            previewH: Int,
            displayOrientation: Int,
            front: Boolean
        ) {
            runOnUiThread {
                previewBufW = previewW
                previewBufH = previewH
                previewDisplayOrient = displayOrientation
                // Re-size floating PiP to camera aspect when geometry arrives
                if (isSeeing && pipLayoutActive) {
                    layoutFloatingPip(preservePosition = true)
                }
                applyNaturalPreviewTransform()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieService = binder.getService()
            walkieBound = true
            walkieRunning = true
            walkieService?.addListener(walkieUiListener)
            peers = walkieService?.peers.orEmpty()
            channelBusy = walkieService?.isChannelBusy == true
            hasLastMessage = walkieService?.hasLastMessage == true
            isReplaying = walkieService?.isReplaying == true
            isTalking = walkieService?.isTalking == true
            isListening = walkieService?.isListeningRemote() == true
            isSeeing = walkieService?.isSeeingRemote() == true
            gpsEnabled = walkieService?.isGpsEnabled() == true
            walkieGps.isChecked = gpsEnabled
            autoSelectPeer()
            renderPeers()
            updateControls()
            updateReplayButton()
            updateListenSeeButtons()
            if (walkiePreview.isAvailable) {
                walkieService?.setUiPreviewTexture(walkiePreview.surfaceTexture)
            }
            if (!gpsEnabled) {
                walkieService?.startLocalPreview()
                showLocalCameraSurface(!isReceiving && !isReplaying && !isSeeing)
            } else {
                showMap = true
                applyStageVisibility()
            }
            walkieStatus.text = if (selectedPeer != null) {
                getString(R.string.walkie_status_ready, selectedPeer!!.displayName)
            } else {
                getString(R.string.walkie_status_waiting)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            walkieService?.removeListener(walkieUiListener)
            walkieService = null
            walkieBound = false
            walkieRunning = false
            updateControls()
            walkieStatus.text = getString(R.string.walkie_status_connecting)
            startRequested = false
            ensureWalkieRunning()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Title + subtitle so version/date never overflow the top bar
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = appVersionSubtitle()

        walkieStatus = findViewById(R.id.walkie_status)
        walkiePeers = findViewById(R.id.walkie_peers)
        walkieBestQuality = findViewById(R.id.walkie_best_quality)
        walkieGps = findViewById(R.id.walkie_gps)
        walkieScrn = findViewById(R.id.walkie_scrn)
        walkieMic = findViewById(R.id.walkie_mic)
        walkieMicStage = findViewById(R.id.walkie_mic_stage)
        walkieMicMode = findViewById(R.id.walkie_mic_mode)
        micHintLock = findViewById(R.id.mic_hint_lock)
        micHintPrivate = findViewById(R.id.mic_hint_private)
        micHintSpeaker = findViewById(R.id.mic_hint_speaker)
        walkieWakeup = findViewById(R.id.walkie_wakeup)
        walkieListen = findViewById(R.id.walkie_listen)
        walkieSee = findViewById(R.id.walkie_see)
        walkieReplay = findViewById(R.id.walkie_replay)
        walkieFlipCamera = findViewById(R.id.walkie_flip_camera)
        walkiePipBox = findViewById(R.id.walkie_pip_box)
        walkiePreview = findViewById(R.id.walkie_preview)
        walkiePipFlip = findViewById(R.id.walkie_pip_flip)
        walkieVideo = findViewById(R.id.walkie_video)
        walkieVideoHint = findViewById(R.id.walkie_video_hint)
        walkieMap = findViewById(R.id.walkie_map)
        walkieLockHint = findViewById(R.id.walkie_lock_hint)
        permissionGate = findViewById(R.id.permission_gate)
        permissionGateList = findViewById(R.id.permission_gate_list)
        permissionGateBtn = findViewById(R.id.permission_gate_btn)
        // SOFTWARE layer avoids ImageView hardware-composite flicker on Pixel and some Samsungs
        walkieVideo.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        permissionGateBtn.setOnClickListener { requestAllRequiredPermissions() }

        walkieMap.settings.javaScriptEnabled = true
        walkieMap.settings.domStorageEnabled = true
        walkieMap.setBackgroundColor(0xFF0F0A1A.toInt())
        walkieMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                pushMapTrail()
            }
        }
        walkieMap.loadUrl("file:///android_asset/gps_map.html")

        walkiePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                walkieService?.setUiPreviewTexture(surface)
                if (!showMap) {
                    walkieService?.startLocalPreview()
                    showLocalCameraSurface(true)
                }
                applyNaturalPreviewTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyNaturalPreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                walkieService?.setUiPreviewTexture(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        walkieBestQuality.isSaveEnabled = false
        walkieBestQuality.isChecked = BarkyPrefs.isBestQualityVideo(this)
        walkieBestQuality.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setBestQualityVideo(this, checked)
            updatePttLabels()
        }
        BarkyPrefs.setBestQualityVideo(this, walkieBestQuality.isChecked)

        walkieGps.isChecked = BarkyPrefs.isGpsEnabled(this)
        walkieGps.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasLocationPermission()) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    return@setOnCheckedChangeListener
                }
                enableGps(true)
            } else {
                enableGps(false)
            }
        }

        // SCRN = view **contact’s** screen (not our own). Peer gets MediaProjection consent.
        walkieScrn.isChecked = walkieService?.isRequestingScreen() == true
        walkieScrn.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (selectedPeer == null && peers.isEmpty()) {
                    walkieScrn.isChecked = false
                    Toast.makeText(this, R.string.walkie_scrn_need_contact, Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                walkieService?.startScreenRemote()
                Toast.makeText(this, R.string.walkie_scrn_requesting, Toast.LENGTH_SHORT).show()
            } else {
                walkieService?.stopScreenRemote()
            }
        }

        // Stage flip: while SEE → flip *peer* camera; idle/HOLD → flip own camera
        walkieFlipCamera.setOnClickListener {
            if (isSeeing) {
                val ok = walkieService?.requestPeerCameraFlip() == true
                Toast.makeText(
                    this,
                    if (ok) R.string.walkie_flip_peer_toast else R.string.walkie_connect_bad,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                walkieService?.switchCamera()
                updateFlipCameraLabel()
            }
            walkieFlipCamera.animate().rotationBy(180f).setDuration(200).start()
        }
        // Self PiP flip: always local camera
        walkiePipFlip.setOnClickListener {
            walkieService?.switchCamera()
            walkiePipFlip.animate().rotationBy(180f).setDuration(200).start()
            Toast.makeText(this, R.string.walkie_flip_self_toast, Toast.LENGTH_SHORT).show()
            updateFlipCameraLabel()
        }
        updateFlipCameraLabel()

        walkieReplay.setOnClickListener {
            if (isReplaying) walkieService?.stopReplay()
            else walkieService?.replayLastMessage()
        }

        bindWhatsAppMic()

        walkieWakeup.setOnClickListener {
            if (wakeupRinging || remoteWakeupActive) {
                walkieService?.stopWakeupRinging()
            } else if (selectedPeer == null) {
                Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
            } else {
                walkieService?.sendWakeup()
            }
        }

        walkieListen.setOnClickListener {
            if (selectedPeer == null) {
                Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isListening) walkieService?.stopListenRemote()
            else walkieService?.startListenRemote()
        }

        walkieSee.setOnClickListener {
            if (selectedPeer == null) {
                Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isSeeing) walkieService?.stopSeeRemote()
            else walkieService?.startSeeRemote()
        }

        updateControls()
        updateMicChrome()
        bindFloatingPip()

        // Hard gate: do not open walkie until every required permission is granted
        if (!hasAllRequiredPermissions()) {
            showPermissionGate()
        } else {
            permissionGate.visibility = View.GONE
        }
    }

    /**
     * WhatsApp-style mic on a **wide stage** (not only the 72dp button):
     * hold → drag; finger can leave the button. ↑ lock, ← private, → speaker.
     * Mic icon follows the finger; targets highlight when active.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindWhatsAppMic() {
        var downRawX = 0f
        var downRawY = 0f
        var modeLive = WalkieProtocol.AUDIO_MODE_SPEAKER
        var dragging = false
        val density = resources.displayMetrics.density
        val lockPx = 72f * density
        val sidePx = 48f * density
        val maxDragX = 110f * density
        val maxDragY = 100f * density

        fun showDragUi(show: Boolean) {
            val v = if (show) View.VISIBLE else View.INVISIBLE
            micHintLock.visibility = v
            micHintPrivate.visibility = v
            micHintSpeaker.visibility = v
            if (!show) {
                micHintLock.alpha = 0.35f
                micHintPrivate.alpha = 0.35f
                micHintSpeaker.alpha = 0.35f
                walkieMic.translationX = 0f
                walkieMic.translationY = 0f
                walkieMic.scaleX = 1f
                walkieMic.scaleY = 1f
            }
        }

        fun applyDragVisual(dx: Float, dyUp: Float) {
            // Mic follows finger (clamped)
            val tx = dx.coerceIn(-maxDragX, maxDragX)
            val ty = (-dyUp).coerceIn(-maxDragY, 20f * density)
            walkieMic.translationX = tx
            walkieMic.translationY = ty
            val pull = (kotlin.math.sqrt((tx * tx + ty * ty).toDouble()) / (90f * density)).toFloat()
                .coerceIn(0f, 0.25f)
            walkieMic.scaleX = 1f + pull
            walkieMic.scaleY = 1f + pull
            // Highlight targets like WhatsApp slide zones
            micHintLock.alpha = if (dyUp > lockPx * 0.45f) 1f else 0.35f
            micHintPrivate.alpha = if (dx < -sidePx * 0.45f) 1f else 0.35f
            micHintSpeaker.alpha = if (dx > sidePx * 0.45f) 1f else 0.35f
        }

        // Touch on the whole stage so slides outside the button still count
        walkieMicStage.setOnTouchListener { stage, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(true)
                    if (pttLocked && isTalking) {
                        pttLocked = false
                        walkieLockHint.visibility = View.GONE
                        showDragUi(false)
                        walkieService?.stopTalking()
                        updateMicChrome()
                        return@setOnTouchListener true
                    }
                    // Only start if finger is near the mic button
                    val micCx = walkieMic.x + walkieMic.width / 2f
                    val micCy = walkieMic.y + walkieMic.height / 2f
                    val dist = kotlin.math.hypot(
                        (event.x - micCx).toDouble(),
                        (event.y - micCy).toDouble()
                    )
                    if (dist > 56f * density && !isTalking) {
                        return@setOnTouchListener false
                    }
                    if (!hasAllRequiredPermissions()) {
                        showPermissionGate()
                        return@setOnTouchListener true
                    }
                    if (selectedPeer == null) {
                        Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (channelBusy || walkieService?.isChannelBusy == true) {
                        Toast.makeText(this, R.string.walkie_channel_busy_toast, Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (isReplaying) walkieService?.stopReplay()
                    BarkyPrefs.setBestQualityVideo(this, walkieBestQuality.isChecked)
                    showMap = false
                    applyStageVisibility()
                    showLocalCameraSurface(true)
                    walkieService?.startLocalPreview()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    modeLive = WalkieProtocol.AUDIO_MODE_SPEAKER
                    activePttMode = modeLive
                    pttLocked = false
                    dragging = true
                    walkieLockHint.visibility = View.VISIBLE
                    walkieLockHint.text = getString(R.string.walkie_swipe_lock_hint)
                    showDragUi(true)
                    walkieService?.startTalking(modeLive)
                    updateMicChrome()
                    applyDragVisual(0f, 0f)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging || !isTalking || pttLocked) return@setOnTouchListener true
                    stage.parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.rawX - downRawX
                    val dyUp = downRawY - event.rawY
                    applyDragVisual(dx, dyUp)
                    // Zone detection (generous thresholds)
                    when {
                        dyUp > lockPx && dyUp >= kotlin.math.abs(dx) -> {
                            pttLocked = true
                            walkieLockHint.text = getString(R.string.walkie_mic_locked)
                            walkieMicMode.text = getString(R.string.walkie_mic_locked)
                            Toast.makeText(this, R.string.walkie_mic_locked, Toast.LENGTH_SHORT).show()
                            showDragUi(false)
                            walkieMic.animate().translationX(0f).translationY(0f)
                                .scaleX(1f).scaleY(1f).setDuration(150).start()
                            updateMicChrome()
                        }
                        dx < -sidePx -> {
                            if (modeLive != WalkieProtocol.AUDIO_MODE_HEADSET) {
                                modeLive = WalkieProtocol.AUDIO_MODE_HEADSET
                                activePttMode = modeLive
                                walkieService?.startTalking(modeLive)
                                updateMicChrome()
                            }
                        }
                        dx > sidePx -> {
                            if (modeLive != WalkieProtocol.AUDIO_MODE_SPEAKER) {
                                modeLive = WalkieProtocol.AUDIO_MODE_SPEAKER
                                activePttMode = modeLive
                                walkieService?.startTalking(modeLive)
                                updateMicChrome()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!dragging) return@setOnTouchListener true
                    dragging = false
                    if (pttLocked) {
                        showDragUi(false)
                        walkieLockHint.visibility = View.VISIBLE
                        walkieLockHint.text = getString(R.string.walkie_mic_locked)
                        walkieMic.animate().translationX(0f).translationY(0f)
                            .scaleX(1f).scaleY(1f).setDuration(150).start()
                        updateMicChrome()
                        return@setOnTouchListener true
                    }
                    showDragUi(false)
                    walkieLockHint.visibility = View.GONE
                    walkieMic.animate().translationX(0f).translationY(0f)
                        .scaleX(1f).scaleY(1f).setDuration(120).start()
                    walkieService?.stopTalking()
                    updateMicChrome()
                    true
                }
                else -> false
            }
        }
        // Button also forwards: press starts if stage didn't capture first
        walkieMic.isClickable = false
        walkieMic.isFocusable = false
    }

    private fun updateMicChrome() {
        if (!::walkieMic.isInitialized) return
        val private = activePttMode == WalkieProtocol.AUDIO_MODE_HEADSET
        when {
            pttLocked || isTalking -> {
                walkieMic.setBackgroundResource(
                    if (private) R.drawable.bg_mic_circle_private
                    else R.drawable.bg_mic_circle_active
                )
                walkieMicMode.text = if (pttLocked) {
                    getString(R.string.walkie_mic_locked)
                } else if (private) {
                    getString(R.string.walkie_mic_mode_private)
                } else {
                    getString(R.string.walkie_mic_mode_speaker)
                }
            }
            private -> {
                walkieMic.setBackgroundResource(R.drawable.bg_mic_circle_private)
                walkieMicMode.text = getString(R.string.walkie_mic_mode_private)
            }
            else -> {
                walkieMic.setBackgroundResource(R.drawable.bg_mic_circle)
                walkieMicMode.text = getString(R.string.walkie_mic_hint)
            }
        }
    }

    private fun enableGps(on: Boolean) {
        if (on) {
            walkieService?.setGpsEnabled(true)
            gpsEnabled = true
            showMap = !isTalking && !isReceiving && !isSeeing
            applyStageVisibility()
            BarkyPrefs.setGpsEnabled(this, true)
        } else {
            walkieService?.setGpsEnabled(false)
            gpsEnabled = false
            showMap = false
            applyStageVisibility()
            showLocalCameraSurface(!isReceiving && !isReplaying && !isSeeing)
            walkieService?.startLocalPreview()
            BarkyPrefs.setGpsEnabled(this, false)
        }
    }

    private fun applyStageVisibility() {
        if (showMap && gpsEnabled) {
            walkieMap.visibility = View.VISIBLE
            walkiePreview.visibility = View.GONE
            walkieVideo.visibility = View.GONE
            walkieVideoHint.visibility = View.GONE
            walkieService?.stopLocalPreview()
            pushMapTrail()
        }
    }

    private fun pushMapTrail() {
        if (!mapReady || !::walkieMap.isInitialized) return
        try {
            val mine = JSONArray()
            for (p in myTrail) {
                mine.put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", p.timeMs))
            }
            val peer = JSONArray()
            for (p in peerTrail) {
                peer.put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", p.timeMs))
            }
            val js = "setTrail($mine, $peer);"
            walkieMap.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            android.util.Log.w("BarkyVideoUI", "pushMapTrail", t)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_contacts -> {
                startActivity(Intent(this, ContactsActivity::class.java))
                true
            }
            R.id.action_lan_update -> {
                // LAN + Firebase peers + optional public apkUrl (works without Wi‑Fi if reachable)
                LanApkUpdater(this).checkAndInstall(preferLanScan = true)
                true
            }
            R.id.action_wireless_debug -> {
                enableWirelessDebugging()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Turn on Wireless debugging (adb over Wi‑Fi) when possible, and always open the
     * system Wireless debugging screen so the user can confirm / pair.
     */
    private fun enableWirelessDebugging() {
        var flipped = false
        try {
            // Requires WRITE_SECURE_SETTINGS (granted once via adb install script)
            val cr = contentResolver
            android.provider.Settings.Global.putInt(cr, "adb_enabled", 1)
            flipped = android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            if (flipped) {
                Toast.makeText(this, R.string.wireless_debug_on, Toast.LENGTH_SHORT).show()
            }
        } catch (se: SecurityException) {
            android.util.Log.i("Barky", "WRITE_SECURE_SETTINGS not granted — open settings UI")
        } catch (t: Throwable) {
            android.util.Log.w("Barky", "enableWirelessDebugging", t)
        }
        // Always surface the system panel (port is shown there)
        val opened = openWirelessDebugSettings()
        if (!flipped && opened) {
            Toast.makeText(this, R.string.wireless_debug_open_settings, Toast.LENGTH_LONG).show()
        } else if (!flipped && !opened) {
            Toast.makeText(this, R.string.wireless_debug_need_dev, Toast.LENGTH_LONG).show()
        }
    }

    private fun openWirelessDebugSettings(): Boolean {
        val candidates = listOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
        )
        for (action in candidates) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** Compact subtitle under "Barky": "5.29 · 07-19 14:22" */
    private fun appVersionSubtitle(): String {
        return try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val raw = pi.versionName ?: "?"
            // Keep short version + optional tag start: "5.29-violet-pulse" → "5.29"
            val shortVer = raw.substringBefore('-').ifBlank { raw }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            "$shortVer · ${fmt.format(Date(pi.lastUpdateTime))}"
        } catch (_: Exception) {
            ""
        }
    }

    override fun onStart() {
        super.onStart()
        ensureWalkieRunning()
        if (!BarkyPrefs.isWifiOnly(this)) {
            walkieService?.applyNetworkMode()
        }
        if (!showMap) walkieService?.startLocalPreview()
    }

    override fun onResume() {
        super.onResume()
        // Continuously re-check: missing any permission → block UI and re-prompt
        if (!hasAllRequiredPermissions()) {
            showPermissionGate()
        } else {
            if (::permissionGate.isInitialized) permissionGate.visibility = View.GONE
            if (!showMap) walkieService?.startLocalPreview()
        }
        if (::walkieScrn.isInitialized) {
            walkieScrn.isChecked = walkieService?.isRequestingScreen() == true
        }
    }

    override fun onStop() {
        // Drop UI surface so background SEE/HOLD never holds a destroyed TextureView
        // (that killed one-way camera when YouTube/home took focus).
        walkieService?.setUiPreviewTexture(null)
        // Keep local idle preview off in background, but do NOT stop SEE serve —
        // WalkieTalkieService (camera FGS) owns that stream.
        if (!isServingSee) {
            walkieService?.stopLocalPreview()
        }
        if (walkieBound) {
            walkieService?.removeListener(walkieUiListener)
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
            walkieBound = false
            walkieService = null
        }
        super.onStop()
    }

    private fun ensureWalkieRunning() {
        if (!hasAllRequiredPermissions()) {
            showPermissionGate()
            return
        }
        permissionGate.visibility = View.GONE
        reallyStartWalkie()
    }

    /** Every permission required by any Barky feature (walkie, GPS, future call). */
    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 26) {
            list += Manifest.permission.READ_PHONE_NUMBERS
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun missingPermissions(): List<String> {
        return requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionGate() {
        if (!::permissionGate.isInitialized) return
        permissionGate.visibility = View.VISIBLE
        val missing = missingPermissions()
        val lines = missing.joinToString("\n") { "• " + friendlyPermName(it) }
        permissionGateList.text = getString(R.string.walkie_perm_gate_body, lines.ifBlank { "• (checking…)" })
        // Auto-launch system dialog so the user is prompted continuously
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestAllRequiredPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            permissionGate.visibility = View.GONE
            reallyStartWalkie()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun friendlyPermName(perm: String): String = when (perm) {
        Manifest.permission.RECORD_AUDIO -> "Microphone (HOLD / LISTEN / SEE)"
        Manifest.permission.CAMERA -> "Camera (video messages / SEE)"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Location (GPS map + peer path)"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications (walkie status / WAKEUP)"
        Manifest.permission.READ_CONTACTS -> "Contacts (calling features)"
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS -> "Phone (calling features)"
        else -> perm.substringAfterLast('.')
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun updateFlipCameraLabel() {
        if (!::walkieFlipCamera.isInitialized) return
        val front = BarkyPrefs.isCameraFront(this)
        walkieFlipCamera.contentDescription = getString(
            if (front) R.string.walkie_camera_front else R.string.walkie_camera_back
        )
    }

    /** Swap remote frame without recycling the bitmap ImageView still displays (Pixel flash). */
    private var lastRemoteBitmap: Bitmap? = null
    private fun setRemoteVideoBitmap(bitmap: Bitmap) {
        if (!::walkieVideo.isInitialized) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val prev = lastRemoteBitmap
        walkieVideo.setImageBitmap(bitmap)
        lastRemoteBitmap = bitmap
        // Recycle previous only after ImageView has taken the new one
        if (prev != null && prev !== bitmap && !prev.isRecycled) {
            walkieVideo.post {
                try {
                    if (lastRemoteBitmap !== prev && !prev.isRecycled) prev.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun showLocalCameraSurface(local: Boolean) {
        if (!::walkiePreview.isInitialized || !::walkieVideo.isInitialized) return
        showMap = false
        walkieMap.visibility = View.GONE
        if (isSeeing) {
            applySeeSplitLayout(true)
            return
        }
        // Reset any PiP layout params
        resetPreviewFullBleed()
        if (local) {
            walkiePreview.visibility = View.VISIBLE
            walkieVideo.visibility = View.GONE
            walkieVideoHint.visibility = View.GONE
            applyNaturalPreviewTransform()
        } else {
            walkiePreview.visibility = View.GONE
            walkieVideo.visibility = View.VISIBLE
            walkieVideo.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    /**
     * WhatsApp-style dual SEE: large remote + floating local PiP
     * (draggable; tap to grow / shrink). Position is sticky until SEE ends.
     */
    private fun applySeeSplitLayout(enabled: Boolean) {
        if (!::walkiePipBox.isInitialized || !::walkiePreview.isInitialized || !::walkieVideo.isInitialized) return
        if (enabled) {
            showMap = false
            walkieMap.visibility = View.GONE
            walkieVideoHint.visibility = View.GONE
            // Remote peer fills stage (under self PiP)
            walkieVideo.layoutParams = walkieVideo.layoutParams.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
            walkieVideo.scaleType = ImageView.ScaleType.CENTER_CROP
            walkieVideo.elevation = 0f
            walkieVideo.visibility = View.VISIBLE
            // Self PiP: natural camera aspect, small corner — never full-screen cover
            layoutFloatingPip(preservePosition = pipLayoutActive)
            pipLayoutActive = true
            walkiePipBox.visibility = View.VISIBLE
            walkiePreview.visibility = View.VISIBLE
            walkiePipBox.elevation = 24f
            walkiePipBox.bringToFront()
            walkieMicStage.elevation = 8f
            walkiePreview.alpha = 1f
            if (::walkiePipFlip.isInitialized) {
                walkiePipFlip.visibility = View.VISIBLE
                walkiePipFlip.bringToFront()
            }
            // Second layout pass after measure so both phones get non-zero parent size
            walkiePipBox.post {
                layoutFloatingPip(preservePosition = true)
                applyNaturalPreviewTransform()
            }
        } else {
            pipEnlarged = false
            pipLayoutActive = false
            pipLastLeft = -1
            pipLastTop = -1
            if (::walkiePipFlip.isInitialized) walkiePipFlip.visibility = View.GONE
            resetPreviewFullBleed()
        }
    }

    /**
     * Natural PiP size from camera buffer + display orientation (no stretch).
     * Large self-view; flip is a small overlay *inside* this box (not beside it).
     */
    private fun pipSizePx(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        // Larger self-view; flip badge sits ON this box (not outside)
        val baseW = ((if (pipEnlarged) 280f else 228f) * density).toInt()
        val uprightAspectHOverW = if (previewBufW > 0 && previewBufH > 0) {
            val orient = previewDisplayOrient
            val uprightW: Float
            val uprightH: Float
            if (orient % 180 == 0) {
                uprightW = previewBufW.toFloat()
                uprightH = previewBufH.toFloat()
            } else {
                uprightW = previewBufH.toFloat()
                uprightH = previewBufW.toFloat()
            }
            (uprightH / uprightW).coerceIn(1.15f, 2.0f)
        } else {
            4f / 3f
        }
        var pipW = baseW
        var pipH = (baseW * uprightAspectHOverW).toInt()
        val parent = walkiePipBox.parent as? android.view.ViewGroup
        val parentW = if ((parent?.width ?: 0) > 0) parent!!.width else resources.displayMetrics.widthPixels
        val parentH = if ((parent?.height ?: 0) > 0) parent!!.height else resources.displayMetrics.heightPixels
        val maxW = (parentW * (if (pipEnlarged) 0.58f else 0.50f)).toInt().coerceAtLeast(baseW)
        val maxH = (parentH * (if (pipEnlarged) 0.62f else 0.52f)).toInt().coerceAtLeast(baseW)
        if (pipW > maxW) {
            val s = maxW.toFloat() / pipW
            pipW = maxW
            pipH = (pipH * s).toInt()
        }
        if (pipH > maxH) {
            val s = maxH.toFloat() / pipH
            pipH = maxH
            pipW = (pipW * s).toInt()
        }
        return pipW.coerceAtLeast(1) to pipH.coerceAtLeast(1)
    }

    private fun layoutFloatingPip(preservePosition: Boolean) {
        if (!::walkiePipBox.isInitialized) return
        val density = resources.displayMetrics.density
        val (pipW, pipH) = pipSizePx()
        // Hard guard: never leave PiP full-screen (that hid remote SEE video on some layouts)
        if (pipW <= 0 || pipH <= 0) return
        val parent = walkiePipBox.parent as? android.view.ViewGroup
        val parentW = if ((parent?.width ?: 0) > 0) parent!!.width else resources.displayMetrics.widthPixels
        val parentH = if ((parent?.height ?: 0) > 0) parent!!.height else resources.displayMetrics.heightPixels
        val lp = (walkiePipBox.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(pipW, pipH)
        // If we previously had match_parent, force down to PiP size
        val oldW = lp.width
        val oldH = lp.height
        val useSaved = preservePosition && pipLastLeft >= 0 && pipLastTop >= 0
        var left = if (useSaved) {
            pipLastLeft
        } else if (preservePosition &&
            lp.gravity == (android.view.Gravity.TOP or android.view.Gravity.START) &&
            lp.width in 1 until parentW
        ) {
            lp.leftMargin
        } else {
            parentW - pipW - (12 * density).toInt()
        }
        var top = if (useSaved) {
            pipLastTop
        } else if (preservePosition &&
            lp.gravity == (android.view.Gravity.TOP or android.view.Gravity.START) &&
            lp.height in 1 until parentH
        ) {
            lp.topMargin
        } else {
            parentH - pipH - (150 * density).toInt()
        }
        left = left.coerceIn(0, (parentW - pipW).coerceAtLeast(0))
        top = top.coerceIn(0, (parentH - pipH).coerceAtLeast(0))
        pipLastLeft = left
        pipLastTop = top
        lp.width = pipW
        lp.height = pipH
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.setMargins(left, top, 0, 0)
        walkiePipBox.layoutParams = lp
        // Preview fills the box; flip is a badge ON the self-picture (bottom-end)
        walkiePreview.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        walkiePreview.alpha = 1f
        // Flip badge always on floating self-view (not only when isSeeing is set late)
        if (::walkiePipFlip.isInitialized) {
            val dens = resources.displayMetrics.density
            val badge = (40 * dens).toInt()
            val m = (8 * dens).toInt()
            walkiePipFlip.layoutParams = android.widget.FrameLayout.LayoutParams(badge, badge).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(m, m, m, m)
            }
            walkiePipFlip.visibility = View.VISIBLE
            walkiePipFlip.elevation = 30f
            walkiePipFlip.bringToFront()
            walkiePipBox.clipChildren = false
            walkiePipBox.clipToPadding = false
        }
        if (oldW != pipW || oldH != pipH) {
            walkiePreview.post { applyNaturalPreviewTransform() }
        }
    }

    /**
     * Drag/tap on the **TextureView** only so the PiP flip button still receives clicks.
     * (A touch listener on the whole [walkiePipBox] was eating flip-button taps.)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindFloatingPip() {
        if (pipDragBound || !::walkiePipBox.isInitialized || !::walkiePreview.isInitialized) return
        pipDragBound = true
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        walkiePreview.isClickable = true
        walkiePreview.setOnTouchListener { _, event ->
            if (!isSeeing) return@setOnTouchListener false
            val box = walkiePipBox
            val lp = box.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            val parent = box.parent as? android.view.ViewGroup ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    box.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = if (pipLastLeft >= 0) pipLastLeft else lp.leftMargin
                    startTop = if (pipLastTop >= 0) pipLastTop else lp.topMargin
                    moved = false
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    val maxL = (parent.width - box.width).coerceAtLeast(0)
                    val maxT = (parent.height - box.height).coerceAtLeast(0)
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    lp.leftMargin = (startLeft + dx).coerceIn(0, maxL)
                    lp.topMargin = (startTop + dy).coerceIn(0, maxT)
                    lp.rightMargin = 0
                    lp.bottomMargin = 0
                    pipLastLeft = lp.leftMargin
                    pipLastTop = lp.topMargin
                    box.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    box.parent?.requestDisallowInterceptTouchEvent(false)
                    val cur = box.layoutParams as? android.widget.FrameLayout.LayoutParams
                    if (cur != null) {
                        pipLastLeft = cur.leftMargin
                        pipLastTop = cur.topMargin
                    }
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        pipEnlarged = !pipEnlarged
                        layoutFloatingPip(preservePosition = true)
                        Toast.makeText(
                            this,
                            if (pipEnlarged) R.string.pip_enlarged else R.string.pip_normal,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun resetPreviewFullBleed() {
        if (!::walkiePipBox.isInitialized || !::walkiePreview.isInitialized) return
        val lp = (walkiePipBox.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = android.view.Gravity.CENTER
        lp.setMargins(0, 0, 0, 0)
        walkiePipBox.layoutParams = lp
        walkiePreview.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        walkiePipBox.elevation = 0f
        walkiePreview.elevation = 0f
        walkiePreview.translationX = 0f
        walkiePreview.translationY = 0f
        if (::walkiePipFlip.isInitialized) walkiePipFlip.visibility = View.GONE
        if (::walkiePreview.isInitialized) walkiePreview.alpha = 1f
        applyNaturalPreviewTransform()
    }

    /**
     * Center-crop TextureView into the PiP box so proportions stay natural (no stretch).
     * Box size already matches upright camera aspect; transform fills without squashing.
     */
    private fun applyNaturalPreviewTransform() {
        if (!::walkiePreview.isInitialized) return
        val viewWidth = walkiePreview.width
        val viewHeight = walkiePreview.height
        if (viewWidth <= 0 || viewHeight <= 0 || previewBufW <= 0 || previewBufH <= 0) return
        val orient = previewDisplayOrient
        // Buffer size as presented after setDisplayOrientation
        val bufW: Float
        val bufH: Float
        if (orient % 180 == 0) {
            bufW = previewBufW.toFloat()
            bufH = previewBufH.toFloat()
        } else {
            bufW = previewBufH.toFloat()
            bufH = previewBufW.toFloat()
        }
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val bufAspect = bufW / bufH
        val scale: Float
        val dx: Float
        val dy: Float
        if (bufAspect > viewAspect) {
            // Buffer wider than view → match height, crop sides
            scale = viewHeight / bufH
            dx = (viewWidth - bufW * scale) * 0.5f
            dy = 0f
        } else {
            // Buffer taller than view → match width, crop top/bottom
            scale = viewWidth / bufW
            dx = 0f
            dy = (viewHeight - bufH * scale) * 0.5f
        }
        val matrix = Matrix()
        // Map sensor buffer (pre-orientation) into view; TextureView already has displayOrientation on camera
        // Camera1 feeds TextureView in display orientation, so buffer is upright-sized as bufW×bufH
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        walkiePreview.setTransform(matrix)
    }

    private fun reallyStartWalkie() {
        walkieStatus.text = getString(R.string.walkie_status_connecting)
        WalkieTalkieService.start(this)
        if (!walkieBound) {
            try {
                bindService(
                    Intent(this, WalkieTalkieService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (t: Exception) {
                walkieStatus.text = "Walkie bind failed: ${t.message}"
            }
        }
        walkieRunning = true
        updateControls()
    }

    private fun autoSelectPeer() {
        // Prefer engine selection (Contacts adopt) — match by host only so Firebase
        // vs HELLO port differences do not drop the chosen phone (S20/S918 swap bug).
        val engineSel = walkieService?.currentSelectedPeer()
        if (engineSel != null) {
            selectedPeer = peers.find { it.host == engineSel.host }
                ?: engineSel
            // Keep engine selection; do not overwrite with peers.first()
            return
        }
        if (peers.isEmpty()) {
            selectedPeer = null
            walkieService?.selectPeer(null)
            return
        }
        val keep = selectedPeer?.let { cur ->
            peers.find { it.host == cur.host }
        }
        selectedPeer = keep ?: peers.first()
        walkieService?.selectPeer(selectedPeer)
    }

    private fun updateControls() {
        val ready = walkieRunning && selectedPeer != null && hasAllRequiredPermissions()
        if (::walkieMic.isInitialized) {
            walkieMic.isEnabled = ready && (isTalking || !channelBusy)
        }
        walkieWakeup.isEnabled = ready || wakeupRinging || remoteWakeupActive
        walkieListen.isEnabled = ready
        walkieSee.isEnabled = ready
        updateWakeupButton()
        updateReplayButton()
        updateReplayVisibility()
        updateMicChrome()
        updateListenSeeButtons()
    }

    private fun updatePttLabels() {
        updateMicChrome()
    }

    private fun updateListenSeeButtons() {
        if (!::walkieListen.isInitialized) return
        val ready = walkieRunning && selectedPeer != null && hasAllRequiredPermissions()
        // Independent: either can start/stop regardless of the other
        walkieListen.isEnabled = ready
        walkieSee.isEnabled = ready
        walkieFlipCamera.isEnabled = walkieRunning && hasAllRequiredPermissions()
        if (isListening) {
            walkieListen.text = getString(R.string.walkie_listen_on)
            walkieListen.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.walkie_listen_active)
        } else {
            walkieListen.text = getString(R.string.walkie_listen)
            walkieListen.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.barky_secondary)
        }
        walkieListen.setTextColor(ContextCompat.getColor(this, R.color.barky_on_primary))
        if (isSeeing) {
            walkieSee.text = getString(R.string.walkie_see_on)
            walkieSee.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.walkie_see_active)
        } else {
            walkieSee.text = getString(R.string.walkie_see)
            walkieSee.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.barky_accent)
        }
        walkieSee.setTextColor(ContextCompat.getColor(this, R.color.barky_on_primary))
    }

    private fun updateWakeupButton() {
        if (!::walkieWakeup.isInitialized) return
        if (wakeupRinging || remoteWakeupActive) {
            walkieWakeup.isEnabled = true
            walkieWakeup.text = getString(R.string.walkie_stop_ringing)
            walkieWakeup.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.walkie_ptt_active)
        } else {
            walkieWakeup.text = getString(R.string.walkie_wakeup)
            walkieWakeup.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.walkie_wakeup)
            walkieWakeup.isEnabled = walkieRunning && selectedPeer != null
        }
    }

    private fun updateReplayButton() {
        if (!::walkieReplay.isInitialized) return
        if (isReplaying) {
            walkieReplay.isEnabled = true
            walkieReplay.text = getString(R.string.walkie_stop_replay)
        } else {
            walkieReplay.text = getString(R.string.walkie_replay)
            walkieReplay.isEnabled = walkieRunning && hasLastMessage
        }
    }

    private fun updateReplayVisibility() {
        if (!::walkieReplay.isInitialized) return
        val show = hasLastMessage && !isTalking && !isReceiving && !isSeeing
        walkieReplay.visibility = if (show || isReplaying) View.VISIBLE else View.GONE
        walkieReplay.isEnabled = (show || isReplaying) && walkieRunning
        if (show || isReplaying) {
            walkieVideoHint.visibility = View.GONE
            walkieReplay.bringToFront()
        }
    }

    private fun renderPeers() {
        if (peers.isEmpty()) {
            walkiePeers.text = getString(R.string.walkie_peers_searching)
            if (walkieRunning) {
                walkieStatus.text = getString(R.string.walkie_status_waiting)
            }
            return
        }
        val peer = selectedPeer ?: peers.first()
        walkiePeers.text = getString(R.string.walkie_peer_linked, "${peer.displayName} (${peer.host})")
        if (!walkieStatus.text.toString().contains("Receiving", true) &&
            !walkieStatus.text.toString().contains("Sending", true) &&
            !walkieStatus.text.toString().contains("busy", true)
        ) {
            walkieStatus.text = getString(R.string.walkie_status_ready, peer.displayName)
        }
    }
}
