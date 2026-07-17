package com.example.barky

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.barky.walkie.WalkiePeer
import com.example.barky.walkie.WalkieTalkieService
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

/**
 * Main screen is video walkie only. Overlay/delay live under Settings (toolbar gear).
 * Half-duplex: while one phone transmits, the other cannot send.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var walkieStatus: TextView
    private lateinit var walkiePeers: TextView
    private lateinit var internetPairCard: MaterialCardView
    private lateinit var myInviteCode: TextView
    private lateinit var peerInviteEdit: TextInputEditText
    private lateinit var connectInvite: MaterialButton
    private lateinit var walkieBestQuality: MaterialCheckBox
    private lateinit var walkiePtt: MaterialButton
    private lateinit var walkieWakeup: MaterialButton
    private lateinit var walkieReplay: MaterialButton
    private lateinit var walkieFlipCamera: ImageButton
    private lateinit var walkieVideo: ImageView
    private lateinit var walkieVideoHint: TextView

    private var walkieService: WalkieTalkieService? = null
    private var walkieBound = false
    private var peers: List<WalkiePeer> = emptyList()
    private var selectedPeer: WalkiePeer? = null
    private var walkieRunning = false
    private var wakeupRinging = false
    private var remoteWakeupActive = false
    private var isReplaying = false
    private var isTalking = false
    private var channelBusy = false
    private var hasLastMessage = false
    private var hasStickyFrame = false
    private var startRequested = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasWalkiePermissions()) {
            reallyStartWalkie()
        } else {
            walkieStatus.text = getString(R.string.walkie_need_mic)
            Toast.makeText(this, R.string.walkie_need_camera, Toast.LENGTH_LONG).show()
        }
    }

    private val walkieUiListener = object : WalkieTalkieService.UiListener {
        override fun onStatus(message: String) {
            if (selectedPeer != null && !message.contains("Receiving", true) &&
                !message.contains("Sending", true) &&
                !message.contains("WAKEUP", true) &&
                !message.contains("Replaying", true) &&
                !message.contains("Ringing", true) &&
                !message.contains("busy", true)
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
                walkiePtt.text = getString(R.string.walkie_ptt_tx)
                walkiePtt.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.walkie_ptt_active)
                walkieVideoHint.visibility = View.GONE
                walkieReplay.visibility = View.GONE
            } else {
                updatePttLabel()
                updateReplayVisibility()
            }
            updateControls()
        }

        override fun onReceivingChanged(receiving: Boolean) {
            if (receiving) {
                walkieStatus.text = getString(R.string.walkie_receiving)
                walkieVideoHint.visibility = View.GONE
                walkieReplay.visibility = View.GONE
            } else if (walkieRunning) {
                walkieStatus.text = if (selectedPeer != null) {
                    getString(R.string.walkie_status_ready, selectedPeer!!.displayName)
                } else {
                    getString(R.string.walkie_status_waiting)
                }
                // Keep last frame sticky — do not restore orange placeholder
                if (hasStickyFrame) {
                    walkieVideoHint.visibility = View.GONE
                }
                updateReplayVisibility()
            }
            updateControls()
        }

        override fun onChannelBusyChanged(busy: Boolean) {
            channelBusy = busy
            updatePttLabel()
            updateControls()
        }

        override fun onVideoFrame(bitmap: Bitmap, live: Boolean) {
            hasStickyFrame = true
            walkieVideoHint.visibility = View.GONE
            // Keep previous frame until new one is set (reduces black flash)
            walkieVideo.setImageBitmap(bitmap)
            if (live && !isReplaying) {
                walkieReplay.visibility = View.GONE
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
            runOnUiThread {
                updateInternetPairUi(inviteCode)
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
            autoSelectPeer()
            renderPeers()
            updateControls()
            updateReplayButton()
            updateInternetPairUi(walkieService?.getMyInviteCode() ?: walkieService?.internetInviteCode)
            walkieService?.startLocalPreview()
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
        walkiePtt = findViewById(R.id.walkie_ptt)
        walkieWakeup = findViewById(R.id.walkie_wakeup)
        walkieReplay = findViewById(R.id.walkie_replay)
        walkieFlipCamera = findViewById(R.id.walkie_flip_camera)
        walkieVideo = findViewById(R.id.walkie_video)
        walkieVideoHint = findViewById(R.id.walkie_video_hint)
        // Smoother continuous video (less flash when swapping bitmaps)
        walkieVideo.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        walkieBestQuality.isChecked = BarkyPrefs.isBestQualityVideo(this)
        walkieBestQuality.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setBestQualityVideo(this, checked)
            updatePttLabel()
        }

        walkieFlipCamera.setOnClickListener {
            walkieService?.switchCamera()
            // Brief feedback; preview / TX stream should flip immediately
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
            if (isReplaying) {
                walkieService?.stopReplay()
            } else {
                walkieService?.replayLastMessage()
            }
        }

        walkiePtt.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (selectedPeer == null) {
                        Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (channelBusy || walkieService?.isChannelBusy == true) {
                        Toast.makeText(this, R.string.walkie_channel_busy_toast, Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (isReplaying) walkieService?.stopReplay()
                    v.isPressed = true
                    walkieService?.startTalking()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    walkieService?.stopTalking()
                    true
                }
                else -> false
            }
        }

        walkieWakeup.setOnClickListener {
            if (wakeupRinging || remoteWakeupActive) {
                walkieService?.stopWakeupRinging()
            } else if (selectedPeer == null) {
                Toast.makeText(this, R.string.walkie_status_waiting, Toast.LENGTH_SHORT).show()
            } else {
                walkieService?.sendWakeup()
            }
        }

        updateControls()
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
        // Refresh internet pair card after returning from Settings
        updateInternetPairUi(walkieService?.getMyInviteCode() ?: walkieService?.internetInviteCode)
        if (!BarkyPrefs.isWifiOnly(this)) {
            walkieService?.applyNetworkMode()
        }
        // Live viewfinder so flip works before HOLD
        walkieService?.startLocalPreview()
    }

    override fun onResume() {
        super.onResume()
        walkieService?.startLocalPreview()
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
            // Ask for future-calling permissions without blocking walkie start
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

    private fun updateFlipCameraLabel() {
        if (!::walkieFlipCamera.isInitialized) return
        // Icon stays; content description reflects current preferred lens
        val front = BarkyPrefs.isCameraFront(this)
        walkieFlipCamera.contentDescription = getString(
            if (front) R.string.walkie_camera_front else R.string.walkie_camera_back
        )
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
        val canSend = ready && !channelBusy && !isTalking
        // Keep enabled for release if currently talking; disable if busy and not talking
        walkiePtt.isEnabled = ready && (isTalking || !channelBusy)
        walkieWakeup.isEnabled = ready || wakeupRinging || remoteWakeupActive
        updateWakeupButton()
        updateReplayButton()
        updateReplayVisibility()
        updatePttLabel()
    }

    private fun updatePttLabel() {
        if (!::walkiePtt.isInitialized) return
        when {
            isTalking -> {
                val best = ::walkieBestQuality.isInitialized && walkieBestQuality.isChecked
                walkiePtt.text = getString(
                    if (best) R.string.walkie_ptt_recording else R.string.walkie_ptt_tx
                )
                walkiePtt.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.walkie_ptt_active)
            }
            channelBusy -> {
                walkiePtt.text = getString(R.string.walkie_ptt_busy)
                walkiePtt.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.barky_hint)
            }
            else -> {
                walkiePtt.text = getString(R.string.walkie_ptt)
                walkiePtt.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.walkie_ptt)
            }
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
        val show = hasLastMessage && !isTalking && !channelBusy
        walkieReplay.visibility = if (show || isReplaying) View.VISIBLE else View.GONE
        if (show || isReplaying) {
            walkieVideoHint.visibility = View.GONE
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
