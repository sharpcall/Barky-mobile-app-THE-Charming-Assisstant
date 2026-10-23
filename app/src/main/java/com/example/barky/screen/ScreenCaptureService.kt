package com.example.barky.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.example.barky.BarkyPrefs
import com.example.barky.MainActivity
import com.example.barky.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Superadmin continuous screen capture via [MediaProjection].
 * Shows a small live overlay preview (local only) — not sent through Firebase.
 */
class ScreenCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: ImageView? = null
    private val capturing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!BarkyPrefs.isSuperAdmin(this) || !BarkyPrefs.isScreenCaptureEnabled(this)) {
                    Log.w(TAG, "superadmin/capture not enabled")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (code == 0 || data == null) {
                    Log.w(TAG, "missing projection result")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                startCapture(code, data)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.screen_capture_notif_title))
            .setContentText(getString(R.string.screen_capture_notif_text))
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(open)
            .addAction(0, getString(R.string.screen_capture_stop), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (!capturing.compareAndSet(false, true)) return
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by system")
                    stopCapture()
                    stopSelf()
                }
            }, main)

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels / 2).coerceAtLeast(360)
            val height = (metrics.heightPixels / 2).coerceAtLeast(640)
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bmp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buf)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                    if (bmp != cropped) bmp.recycle()
                    main.post { showOverlayFrame(cropped) }
                } catch (t: Throwable) {
                    Log.w(TAG, "frame", t)
                } finally {
                    image.close()
                }
            }, main)

            virtualDisplay = proj.createVirtualDisplay(
                "barky-screen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                main
            )
            Log.i(TAG, "capture started ${width}x$height")
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture", t)
            capturing.set(false)
            stopSelf()
        }
    }

    private fun showOverlayFrame(bmp: Bitmap) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            var view = overlayView
            if (view == null) {
                view = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xCC000000.toInt())
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val lp = WindowManager.LayoutParams(
                    280,
                    500,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = 16
                lp.y = 120
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "no overlay permission — capture runs without preview")
                    bmp.recycle()
                    return
                }
                wm.addView(view, lp)
                overlayView = view
            }
            val old = view.drawable
            view.setImageBitmap(bmp)
            // previous bitmap may still be displayed; recycle carefully
            if (old is android.graphics.drawable.BitmapDrawable) {
                val prev = old.bitmap
                if (prev != null && prev != bmp && !prev.isRecycled) {
                    // keep last frame briefly; recycle after swap next time
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "overlay", t)
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun stopCapture() {
        capturing.set(false)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        main.post {
            try {
                overlayView?.let { v ->
                    (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
                }
            } catch (_: Exception) {
            }
            overlayView = null
        }
        BarkyPrefs.setScreenCaptureEnabled(this, false)
        Log.i(TAG, "capture stopped")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL,
            getString(R.string.screen_capture_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL = "barky_screen_capture"
        private const val NOTIF_ID = 77
        const val ACTION_START = "com.example.barky.SCREEN_CAPTURE_START"
        const val ACTION_STOP = "com.example.barky.SCREEN_CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            )
        }

        fun createScreenCaptureIntent(activity: Activity): Intent {
            val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mpm.createScreenCaptureIntent()
        }
    }
}
