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
    private lateinit var walkiePttSpeaker: MaterialButton
    private lateinit var walkiePttHeadset: MaterialButton
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
    ) { result ->
        if (hasWalkiePermissions()) {
            reallyStartWalkie()
        } else {
            walkieStatus.text = getString(R.string.walkie_need_mic)
            Toast.makeText(this, R.string.walkie_need_camera, Toast.LENGTH_LONG).show()
        }
        // GPS toggle may have triggered this
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (walkieGps.isChecked) enableGps(true)
        } else if (walkieGps.isChecked && !hasLocationPermission()) {
            walkieGps.isChecked = false
            Toast.makeText(this, R.string.walkie_gps_need_perm, Toast.LENGTH_LONG).show()
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

        walkieStatus = findViewById(R.id.walkie_status)
        walkiePeers = findViewById(R.id.walkie_peers)
        internetPairCard = findViewById(R.id.internet_pair_card)
        myInviteCode = findViewById(R.id.my_invite_code)
        peerInviteEdit = findViewById(R.id.peer_invite_edit)
        connectInvite = findViewById(R.id.connect_invite)
        walkieBestQuality = findViewById(R.id.walkie_best_quality)
        walkieGps = findViewById(R.id.walkie_gps)
        walkiePttSpeaker = findViewById(R.id.walkie_ptt_speaker)
        walkiePttHeadset = findViewById(R.id.walkie_ptt_headset)
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
        walkieVideo.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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

        bindPtt(walkiePttSpeaker, WalkieProtocol.AUDIO_MODE_SPEAKER)
        bindPtt(walkiePttHeadset, WalkieProtocol.AUDIO_MODE_HEADSET)

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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindPtt(button: MaterialButton, mode: Byte) {
        var downY = 0f
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (pttLocked && isTalking) {
                        // Second tap while locked = send/stop
                        pttLocked = false
                        walkieLockHint.visibility = View.GONE
                        v.isPressed = false
                        walkieService?.stopTalking()
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
                    // HOLD → leave map, show camera
                    showMap = false
                    applyStageVisibility()
                    showLocalCameraSurface(true)
                    walkieService?.startLocalPreview()
                    downY = event.rawY
                    activePttMode = mode
                    pttLocked = false
                    v.isPressed = true
                    walkieLockHint.visibility = View.VISIBLE
                    walkieService?.startTalking(mode)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTalking && !pttLocked && downY - event.rawY > 90f) {
                        pttLocked = true
                        walkieLockHint.text = getString(R.string.walkie_ptt_locked)
                        Toast.makeText(this, R.string.walkie_ptt_locked, Toast.LENGTH_SHORT).show()
                        updatePttLabels()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pttLocked) {
                        // Keep recording until second tap
                        v.isPressed = true
                        walkieLockHint.visibility = View.VISIBLE
                        walkieLockHint.text = getString(R.string.walkie_ptt_locked)
                        return@setOnTouchListener true
                    }
                    v.isPressed = false
                    walkieLockHint.visibility = View.GONE
                    walkieService?.stopTalking()
                    true
                }
                else -> false
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
        if (!showMap) walkieService?.startLocalPreview()
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
        if (hasWalkiePermissions()) {
            reallyStartWalkie()
            requestFutureCallingPermissionsIfNeeded()
        } else if (!startRequested) {
            startRequested = true
            walkieStatus.text = getString(R.string.walkie_status_connecting)
            val needed = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= 26) {
                needed += Manifest.permission.READ_PHONE_NUMBERS
            }
            if (Build.VERSION.SDK_INT >= 33) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestFutureCallingPermissionsIfNeeded() {
        val extra = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            extra += Manifest.permission.READ_CONTACTS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            extra += Manifest.permission.CALL_PHONE
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            extra += Manifest.permission.READ_PHONE_STATE
        }
        if (Build.VERSION.SDK_INT >= 26 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            extra += Manifest.permission.READ_PHONE_NUMBERS
        }
        if (extra.isNotEmpty()) {
            permissionLauncher.launch(extra.toTypedArray())
        }
    }

    private fun hasWalkiePermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return mic && cam
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
        val ready = walkieRunning && selectedPeer != null
        walkiePttSpeaker.isEnabled = ready && (isTalking || !channelBusy)
        walkiePttHeadset.isEnabled = ready && (isTalking || !channelBusy)
        walkieWakeup.isEnabled = ready || wakeupRinging || remoteWakeupActive
        walkieListen.isEnabled = ready
        walkieSee.isEnabled = ready
        updateWakeupButton()
        updateReplayButton()
        updateReplayVisibility()
        updatePttLabels()
        updateListenSeeButtons()
    }

    private fun updatePttLabels() {
        if (!::walkiePttSpeaker.isInitialized) return
        fun style(btn: MaterialButton, idleColor: Int, mode: Byte) {
            when {
                isTalking && activePttMode == mode -> {
                    btn.text = if (pttLocked) {
                        getString(R.string.walkie_ptt_locked)
                    } else if (walkieBestQuality.isChecked) {
                        getString(R.string.walkie_ptt_recording)
                    } else {
                        getString(R.string.walkie_ptt_tx)
                    }
                    btn.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.walkie_ptt_active)
                }
                channelBusy -> {
                    btn.text = getString(R.string.walkie_ptt_busy)
                    btn.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.barky_hint)
                }
                else -> {
                    btn.text = if (mode == WalkieProtocol.AUDIO_MODE_HEADSET) {
                        getString(R.string.walkie_headset)
                    } else {
                        getString(R.string.walkie_speaker)
                    }
                    btn.backgroundTintList =
                        ContextCompat.getColorStateList(this, idleColor)
                }
            }
        }
        style(walkiePttSpeaker, R.color.walkie_ptt, WalkieProtocol.AUDIO_MODE_SPEAKER)
        style(walkiePttHeadset, R.color.walkie_headset, WalkieProtocol.AUDIO_MODE_HEADSET)
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
