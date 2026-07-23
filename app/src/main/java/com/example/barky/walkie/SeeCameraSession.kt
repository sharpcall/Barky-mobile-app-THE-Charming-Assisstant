package com.example.barky.walkie

import android.content.Context
import android.util.Log
import com.example.barky.BarkyPrefs
import com.example.barky.walkie.codec.AvcVideoEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SEE camera capture + optional H.264 encode.
 *
 * Owns [CameraFrameSource] (and temporary AVC encoder) for the remote SEE serve path.
 * Transport (TCP/UDP send) stays in [WalkieTalkieEngine] via the callbacks below so
 * path selection / hole-punch logic is not duplicated here.
 */
class SeeCameraSession(
    private val context: Context,
    private val isActive: () -> Boolean,
    /** True when video TCP is connected — used for JPEG compact sizing. */
    private val isTcpConnected: () -> Boolean,
    private val useAvc: Boolean,
    private val onPreviewReady: (w: Int, h: Int, orient: Int, front: Boolean) -> Unit = { _, _, _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onAvcUnit: (ByteArray) -> Unit = {},
    private val onJpegFrame: (ByteArray) -> Unit = {}
) {
    private val started = AtomicBoolean(false)
    private val frameCount = AtomicInteger(0)
    @Volatile private var camera: CameraFrameSource? = null
    @Volatile private var encoder: AvcVideoEncoder? = null

    fun framesSent(): Int = frameCount.get()

    fun isRunning(): Boolean = started.get() && camera != null

    /** Underlying Camera1 source (for flip while SEE is serving). */
    fun cameraSource(): CameraFrameSource? = camera

    fun switchCamera(): Boolean {
        val cam = camera ?: return false
        cam.switchCamera()
        return true
    }

    /**
     * Open camera (dummy SurfaceTexture — safe when app is backgrounded).
     * Returns false if open was skipped because already running.
     */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "SeeCameraSession already started")
            return false
        }
        frameCount.set(0)
        val preferFront = BarkyPrefs.isCameraFront(context)
        var enc: AvcVideoEncoder? = null
        if (useAvc) {
            enc = AvcVideoEncoder(640, 480, 2_000_000).also { it.start() }
            if (enc.isReady() != true) {
                enc.release()
                enc = null
                Log.w(TAG, "AVC encoder not ready — falling back to MJPEG")
            } else {
                Log.i(TAG, "SEE camera codec=H.264/AVC 640x480")
            }
        } else {
            Log.i(TAG, "SEE camera codec=MJPEG")
        }
        encoder = enc

        val cam = if (enc != null && enc.isReady()) {
            val readyEnc = enc
            CameraFrameSource(
                context = context,
                compactForUdp = { !isTcpConnected() },
                config = CaptureConfig.Live,
                preferFrontCamera = preferFront,
                externalSurfaceTexture = { null },
                onFacingChanged = { front -> BarkyPrefs.setCameraFront(context, front) },
                onPreviewReady = onPreviewReady,
                onError = { msg ->
                    onError(msg)
                    Log.w(TAG, "SEE cam error: $msg")
                },
                onNv21Frame = { nv21, w, h ->
                    if (!isActive()) return@CameraFrameSource
                    val units = readyEnc.encodeNv21(nv21, w, h)
                    for (au in units) {
                        onAvcUnit(au)
                        val n = frameCount.incrementAndGet()
                        if (n == 1 || n % 30 == 0) {
                            Log.i(TAG, "SEE AVC_TX n=$n bytes=${au.size}")
                        }
                    }
                },
                onJpegFrame = { }
            )
        } else {
            CameraFrameSource(
                context = context,
                compactForUdp = { !isTcpConnected() },
                config = CaptureConfig.Live,
                preferFrontCamera = preferFront,
                externalSurfaceTexture = { null },
                onFacingChanged = { front -> BarkyPrefs.setCameraFront(context, front) },
                onPreviewReady = onPreviewReady,
                onError = { msg ->
                    onError(msg)
                    Log.w(TAG, "SEE cam error: $msg")
                }
            ) { jpeg ->
                if (!isActive()) return@CameraFrameSource
                onJpegFrame(jpeg)
                val n = frameCount.incrementAndGet()
                if (n == 1 || n % 30 == 0) {
                    Log.i(TAG, "SEE JPEG_TX n=$n bytes=${jpeg.size}")
                }
            }
        }
        camera = cam
        if (isActive()) cam.start() else cam.stop()
        Log.i(TAG, "SeeCameraSession started front=$preferFront avc=${enc != null}")
        return true
    }

    fun stop() {
        started.set(false)
        val cam = camera
        camera = null
        try {
            cam?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        encoder = null
        Log.i(TAG, "SeeCameraSession stopped frames=${frameCount.get()}")
    }

    companion object {
        private const val TAG = "SeeCameraSession"
    }
}
