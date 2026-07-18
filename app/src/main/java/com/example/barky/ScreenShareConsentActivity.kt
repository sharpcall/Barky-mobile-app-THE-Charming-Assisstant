package com.example.barky

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.barky.walkie.WalkieTalkieService

/**
 * Shown when a **contact** requests our screen (SCRN).
 * Runs the system MediaProjection dialog, then streams to that contact.
 */
class ScreenShareConsentActivity : AppCompatActivity() {
    private var peerHost = ""
    private var peerUdp = 0
    private var peerVideo = 0
    private var service: WalkieTalkieService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WalkieTalkieService.LocalBinder).getService()
            bound = true
            launchProjection()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(result.resultCode, result.data!!)
            if (proj != null) {
                // Always hold for re-use (auto-share / second SCRN without re-prompt when allowed)
                if (BarkyPrefs.isAutoScreenShare(this) || prepareOnly) {
                    com.example.barky.screen.MediaProjectionHolder.set(proj)
                }
                if (!prepareOnly && peerHost.isNotBlank()) {
                    fun serve(svc: WalkieTalkieService?) {
                        if (svc == null) {
                            Log.e(TAG, "service null after projection grant — cannot serve")
                            Toast.makeText(this, R.string.screen_share_failed, Toast.LENGTH_LONG).show()
                            return
                        }
                        try {
                            svc.startServingScreen(peerHost, peerUdp, peerVideo, proj)
                            Toast.makeText(this, R.string.screen_share_serving, Toast.LENGTH_SHORT).show()
                            Log.i(TAG, "startServingScreen → $peerHost udp=$peerUdp video=$peerVideo")
                        } catch (t: Throwable) {
                            Log.e(TAG, "startServingScreen", t)
                            Toast.makeText(this, R.string.screen_share_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    val svc = service
                    if (svc != null) {
                        serve(svc)
                    } else {
                        // Rare race: grant returned before bind finished
                        Log.w(TAG, "waiting for service bind after projection grant")
                        bindService(
                            Intent(this, WalkieTalkieService::class.java),
                            object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                                    val s = (binder as WalkieTalkieService.LocalBinder).getService()
                                    service = s
                                    bound = true
                                    serve(s)
                                    try {
                                        unbindService(this)
                                    } catch (_: Exception) {
                                    }
                                }

                                override fun onServiceDisconnected(name: ComponentName?) {}
                            },
                            Context.BIND_AUTO_CREATE
                        )
                    }
                } else if (prepareOnly) {
                    Toast.makeText(this, R.string.screen_share_prepared, Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "getMediaProjection returned null")
                Toast.makeText(this, R.string.screen_share_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "projection denied resultCode=${result.resultCode}")
            Toast.makeText(this, R.string.screen_share_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private var prepareOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareOnly = intent.getBooleanExtra(EXTRA_PREPARE_ONLY, false)
        peerHost = intent.getStringExtra(EXTRA_PEER_HOST).orEmpty()
        peerUdp = intent.getIntExtra(EXTRA_PEER_UDP, 0)
        peerVideo = intent.getIntExtra(EXTRA_PEER_VIDEO, 0)
        if (!prepareOnly && peerHost.isBlank()) {
            finish()
            return
        }
        if (!prepareOnly) {
            Toast.makeText(this, getString(R.string.screen_share_request_toast, peerHost), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.screen_share_prepare_prompt, Toast.LENGTH_LONG).show()
        }
        bindService(Intent(this, WalkieTalkieService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun launchProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        if (bound) {
            try {
                unbindService(conn)
            } catch (_: Exception) {
            }
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenShareConsent"
        const val EXTRA_PEER_HOST = "peerHost"
        const val EXTRA_PEER_UDP = "peerUdp"
        const val EXTRA_PEER_VIDEO = "peerVideo"
        /** One-time system dialog only; store projection for auto SCRN answers. */
        const val EXTRA_PREPARE_ONLY = "prepareOnly"
    }
}
