package com.example.barky

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.barky.walkie.WalkieTalkieService

/**
 * System MediaProjection dialog only.
 *
 * Android 14+: must NOT call [MediaProjectionManager.getMediaProjection] here.
 * Hand the raw result Intent to [WalkieTalkieService], which starts the
 * mediaProjection FGS first, then creates the projection and streams.
 */
class ScreenShareConsentActivity : AppCompatActivity() {
    private var peerHost = ""
    private var peerUdp = 0
    private var peerVideo = 0
    private var prepareOnly = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "projection granted prepareOnly=$prepareOnly peer=$peerHost")
            if (prepareOnly) {
                // Still hand off so FGS owns projection for later auto-share
                handoffToService(result.resultCode, result.data!!, prepareOnly = true)
                Toast.makeText(this, R.string.screen_share_prepared, Toast.LENGTH_LONG).show()
            } else if (peerHost.isNotBlank()) {
                handoffToService(result.resultCode, result.data!!, prepareOnly = false)
                Toast.makeText(this, R.string.screen_share_serving, Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "grant ok but peer blank")
                Toast.makeText(this, R.string.screen_share_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "projection denied resultCode=${result.resultCode}")
            Toast.makeText(this, R.string.screen_share_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

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
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Arm BarkyService to automatically confirm the system MediaProjection dialog.
        BarkyService.armAutoConfirmMediaProjection(this)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun handoffToService(resultCode: Int, data: Intent, prepareOnly: Boolean) {
        val i = Intent(this, WalkieTalkieService::class.java).apply {
            action = WalkieTalkieService.ACTION_SERVE_SCREEN
            putExtra(WalkieTalkieService.EXTRA_PROJ_RESULT_CODE, resultCode)
            putExtra(WalkieTalkieService.EXTRA_PROJ_RESULT_DATA, data)
            putExtra(WalkieTalkieService.EXTRA_SCRN_HOST, peerHost)
            putExtra(WalkieTalkieService.EXTRA_SCRN_UDP, peerUdp)
            putExtra(WalkieTalkieService.EXTRA_SCRN_VIDEO, peerVideo)
            putExtra(WalkieTalkieService.EXTRA_PROJ_PREPARE_ONLY, prepareOnly)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Log.i(TAG, "handed projection to WalkieTalkieService peer=$peerHost")
        } catch (t: Throwable) {
            Log.e(TAG, "handoff failed", t)
            Toast.makeText(this, R.string.screen_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "ScreenShareConsent"
        const val EXTRA_PEER_HOST = "peerHost"
        const val EXTRA_PEER_UDP = "peerUdp"
        const val EXTRA_PEER_VIDEO = "peerVideo"
        const val EXTRA_PREPARE_ONLY = "prepareOnly"
    }
}
