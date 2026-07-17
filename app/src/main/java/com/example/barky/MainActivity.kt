package com.example.barky

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.barky.walkie.LocationTracker
import com.example.barky.walkie.WalkiePeer
import com.example.barky.walkie.WalkieProtocol
import com.example.barky.walkie.WalkieTalkieService
import com.example.barky.BuildConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main screen: video walkie with speaker/private hold, LISTEN/SEE, GPS map.
 * Half-duplex for HOLD messages; LISTEN/SEE can run both ways.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var walkieStatus: TextView
    private lateinit var walkiePeers: TextView
    private lateinit var internetPairCard: MaterialCardView
    private lateinit var myInviteCode: TextView
    private lateinit var peerInviteEdit: TextInputEditText
    private lateinit var connectInvite: MaterialButton
    private lateinit var walkieBestQuality: MaterialCheckBox
    private lateinit var walkieGps: MaterialCheckBox
    private lateinit var walkieMic: ImageButton
    private lateinit var walkieMicMode: TextView
    private lateinit var walkieWakeup: MaterialButton
    private lateinit var walkieListen: MaterialButton
    private lateinit var walkieSee: MaterialButton
    private lateinit var walkieReplay: MaterialButton
    private lateinit var walkieFlipCamera: ImageButton
    private lateinit var walkiePreview: TextureView
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
    private var pttLocked = false
    private var activePttMode: Byte = WalkieProtocol.AUDIO_MODE_SPEAKER
    private var myTrail: List<LocationTracker.GpsPoint> = emptyList()
    private var peerTrail: List<LocationTracker.GpsPoint> = emptyList()

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
            if (isReceiving || isReplaying || isSeeing || !isTalking) {
                if (isReceiving || isReplaying || isSeeing) {
                    showLocalCameraSurface(false)
                }
                walkieVideo.setImageBitmap(bitmap)
                walkieVideo.visibility = View.VISIBLE
                walkieMap.visibility = View.GONE
            }
            if ((isTalking || isReceiving || isSeeing) && live && !isReplaying) {
                walkieReplay.visibility = View.GONE
            } else if (!isTalking && !isReceiving && !isReplaying) {
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
            runOnUiThread { updateInternetPairUi(inviteCode) }
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
                if (seeing) {
                    showMap = false
                    showLocalCameraSurface(false)
                } else if (!isTalking && !isReceiving && !isReplaying) {
                    if (gpsEnabled) {
                        showMap = true
                        applyStageVisibility()
                    } else {
                        showLocalCameraSurface(true)
                    }
                }
                updateListenSeeButtons()
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
            updateInternetPairUi(walkieService?.getMyInviteCode() ?: walkieService?.internetInviteCode)
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
        supportActionBar?.title = getString(R.string.app_name)
        // Title line shows app name; subtitle = version + build timestamp
        supportActionBar?.subtitle = try {
            BuildConfig.VERSION_LABEL
        } catch (_: Throwable) {
            try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        walkieStatus = findViewById(R.id.walkie_status)
        walkiePeers = findViewById(R.id.walkie_peers)
        internetPairCard = findViewById(R.id.internet_pair_card)
        myInviteCode = findViewById(R.id.my_invite_code)
        peerInviteEdit = findViewById(R.id.peer_invite_edit)
        connectInvite = findViewById(R.id.connect_invite)
        walkieBestQuality = findViewById(R.id.walkie_best_quality)
        walkieGps = findViewById(R.id.walkie_gps)
        walkieMic = findViewById(R.id.walkie_mic)
        walkieMicMode = findViewById(R.id.walkie_mic_mode)
        walkieWakeup = findViewById(R.id.walkie_wakeup)
        walkieListen = findViewById(R.id.walkie_listen)
        walkieSee = findViewById(R.id.walkie_see)
        walkieReplay = findViewById(R.id.walkie_replay)
        walkieFlipCamera = findViewById(R.id.walkie_flip_camera)
        walkiePreview = findViewById(R.id.walkie_preview)
        walkieVideo = findViewById(R.id.walkie_video)
        walkieVideoHint = findViewById(R.id.walkie_video_hint)
        walkieMap = findViewById(R.id.walkie_map)
        walkieLockHint = findViewById(R.id.walkie_lock_hint)
        permissionGate = findViewById(R.id.permission_gate)
        permissionGateList = findViewById(R.id.permission_gate_list)
        permissionGateBtn = findViewById(R.id.permission_gate_btn)
        walkieVideo.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        permissionGateBtn.setOnClickListener { requestAllRequiredPermissions() }

        walkieMap.settings.javaScriptEnabled = true
        walkieMap.settings.domStorageEnabled = true
        walkieMap.setBackgroundColor(0xFF0B141A.toInt())
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
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
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

        walkieFlipCamera.setOnClickListener {
            walkieService?.switchCamera()
            walkieFlipCamera.animate().rotationBy(180f).setDuration(200).start()
            updateFlipCameraLabel()
        }
        updateFlipCameraLabel()

        connectInvite.setOnClickListener {
            val code = peerInviteEdit.text?.toString().orEmpty()
            val ok = walkieService?.connectViaInvite(code) == true
            Toast.makeText(
                this,
                if (ok) R.string.walkie_connect_ok else R.string.walkie_connect_bad,
                Toast.LENGTH_SHORT
            ).show()
        }
        updateInternetPairUi(null)

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

        // Hard gate: do not open walkie until every required permission is granted
        if (!hasAllRequiredPermissions()) {
            showPermissionGate()
        } else {
            permissionGate.visibility = View.GONE
        }
    }

    /**
     * WhatsApp-style single mic:
     * hold = send video; slide ↑ = lock; slide ← = private; slide → = speaker.
     * Tap again while locked = send/stop.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindWhatsAppMic() {
        var downX = 0f
        var downY = 0f
        var modeLive = WalkieProtocol.AUDIO_MODE_SPEAKER
        walkieMic.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (pttLocked && isTalking) {
                        pttLocked = false
                        walkieLockHint.visibility = View.GONE
                        walkieService?.stopTalking()
                        updateMicChrome()
                        return@setOnTouchListener true
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
                    downX = event.rawX
                    downY = event.rawY
                    modeLive = WalkieProtocol.AUDIO_MODE_SPEAKER
                    activePttMode = modeLive
                    pttLocked = false
                    walkieLockHint.visibility = View.VISIBLE
                    walkieLockHint.text = getString(R.string.walkie_swipe_lock_hint)
                    walkieService?.startTalking(modeLive)
                    updateMicChrome()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTalking) return@setOnTouchListener true
                    val dx = event.rawX - downX
                    val dy = downY - event.rawY // up is positive
                    if (!pttLocked && dy > 100f && dy > kotlin.math.abs(dx)) {
                        pttLocked = true
                        walkieLockHint.text = getString(R.string.walkie_mic_locked)
                        Toast.makeText(this, R.string.walkie_mic_locked, Toast.LENGTH_SHORT).show()
                        updateMicChrome()
                    } else if (!pttLocked) {
                        // Horizontal drag chooses privacy mode (like WA slide gestures)
                        val newMode = if (dx < -70f) {
                            WalkieProtocol.AUDIO_MODE_HEADSET
                        } else if (dx > 70f) {
                            WalkieProtocol.AUDIO_MODE_SPEAKER
                        } else {
                            modeLive
                        }
                        if (newMode != modeLive) {
                            modeLive = newMode
                            activePttMode = modeLive
                            // Mode applies on this transmission's TALK_START already sent —
                            // store for UI; next HOLD uses it. Also re-signal for RX privacy.
                            walkieService?.startTalking(modeLive) // no-op if already talking
                            updateMicChrome()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pttLocked) {
                        walkieLockHint.visibility = View.VISIBLE
                        walkieLockHint.text = getString(R.string.walkie_mic_locked)
                        updateMicChrome()
                        return@setOnTouchListener true
                    }
                    walkieLockHint.visibility = View.GONE
                    walkieService?.stopTalking()
                    updateMicChrome()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateMicChrome() {
        if (!::walkieMic.isInitialized) return
        val private = activePttMode == WalkieProtocol.AUDIO_MODE_HEADSET
        when {
            pttLocked || isTalking -> {
                walkieMic.setBackgroundResource(R.drawable.bg_mic_circle_active)
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
            R.id.action_lan_update -> {
                LanApkUpdater(this).checkAndInstallFromLan()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        ensureWalkieRunning()
        updateInternetPairUi(walkieService?.getMyInviteCode() ?: walkieService?.internetInviteCode)
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
    }

    private fun updateInternetPairUi(inviteCode: String?) {
        if (!::internetPairCard.isInitialized) return
        val wifiOnly = BarkyPrefs.isWifiOnly(this)
        internetPairCard.visibility = if (wifiOnly) View.GONE else View.VISIBLE
        if (wifiOnly) return
        myInviteCode.text = if (inviteCode.isNullOrBlank()) {
            getString(R.string.walkie_my_code_waiting)
        } else {
            getString(R.string.walkie_my_code, inviteCode)
        }
    }

    override fun onStop() {
        walkieService?.stopLocalPreview()
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

    private fun showLocalCameraSurface(local: Boolean) {
        if (!::walkiePreview.isInitialized || !::walkieVideo.isInitialized) return
        showMap = false
        walkieMap.visibility = View.GONE
        if (local) {
            walkiePreview.visibility = View.VISIBLE
            walkieVideo.visibility = View.GONE
            walkieVideoHint.visibility = View.GONE
        } else {
            walkiePreview.visibility = View.GONE
            walkieVideo.visibility = View.VISIBLE
        }
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
        if (peers.isEmpty()) {
            selectedPeer = null
            walkieService?.selectPeer(null)
            return
        }
        val keep = selectedPeer?.let { cur ->
            peers.find { it.host == cur.host && it.port == cur.port }
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
        if (isListening) {
            walkieListen.text = getString(R.string.walkie_listen_on)
            walkieListen.setBackgroundColor(
                ContextCompat.getColor(this, R.color.walkie_listen_active)
            )
            walkieListen.setTextColor(ContextCompat.getColor(this, R.color.barky_on_primary))
        } else {
            walkieListen.text = getString(R.string.walkie_listen)
            walkieListen.setBackgroundColor(0)
            walkieListen.setTextColor(ContextCompat.getColor(this, R.color.barky_on_surface))
        }
        if (isSeeing) {
            walkieSee.text = getString(R.string.walkie_see_on)
            walkieSee.setBackgroundColor(
                ContextCompat.getColor(this, R.color.walkie_see_active)
            )
            walkieSee.setTextColor(ContextCompat.getColor(this, R.color.barky_on_primary))
        } else {
            walkieSee.text = getString(R.string.walkie_see)
            walkieSee.setBackgroundColor(0)
            walkieSee.setTextColor(ContextCompat.getColor(this, R.color.barky_on_surface))
        }
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
