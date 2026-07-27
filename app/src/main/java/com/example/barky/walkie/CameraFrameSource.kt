package com.example.barky.walkie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Encode / pacing options for [CameraFrameSource]. */
data class CaptureConfig(
    val minIntervalMs: Long,
    val maxEdge: Int,
    val jpegQuality: Int,
    val maxBytesTcp: Int
) {
    companion object {
        val Live = CaptureConfig(
            minIntervalMs = WalkieProtocol.VIDEO_MIN_INTERVAL_MS,
            maxEdge = WalkieProtocol.VIDEO_OUT_MAX_EDGE,
            jpegQuality = WalkieProtocol.VIDEO_JPEG_QUALITY_START,
            maxBytesTcp = WalkieProtocol.VIDEO_JPEG_MAX_BYTES_TCP
        )
        /** Idle viewfinder: slower capture → less UI flicker while flipping lenses. */
        val Preview = CaptureConfig(
            minIntervalMs = 120L,
            maxEdge = 640,
            jpegQuality = 70,
            maxBytesTcp = 80_000
        )
        /** SEE remote camera: max-quality MJPEG (resolution + q), not bandwidth-capped. */
        val SeeLive = CaptureConfig(
            minIntervalMs = WalkieProtocol.SEE_VIDEO_MIN_INTERVAL_MS,
            maxEdge = WalkieProtocol.SEE_VIDEO_MAX_EDGE,
            jpegQuality = WalkieProtocol.SEE_VIDEO_JPEG_QUALITY,
            maxBytesTcp = WalkieProtocol.SEE_VIDEO_JPEG_MAX_BYTES
        )
        /** Local record-only path: denser frames, sharper JPEGs (no network pressure while capturing). */
        val BestRecord = CaptureConfig(
            minIntervalMs = WalkieProtocol.BEST_VIDEO_MIN_INTERVAL_MS,
            maxEdge = WalkieProtocol.VIDEO_OUT_MAX_EDGE,
            jpegQuality = WalkieProtocol.BEST_VIDEO_JPEG_QUALITY,
            maxBytesTcp = WalkieProtocol.BEST_VIDEO_JPEG_MAX_BYTES
        )
    }
}

/**
 * Camera → upright JPEG frames on a **background thread**.
 *
 * Codec: Motion JPEG via Android platform JPEG encoder
 * (`YuvImage.compressToJpeg` + `Bitmap.compress`). No third-party library.
 *
 * Important: Camera preview callbacks run on the main thread; we copy NV21
 * and encode/send off-main to avoid NetworkOnMainThreadException.
 */
@Suppress("DEPRECATION")
class CameraFrameSource(
    private val context: Context,
    private val compactForUdp: () -> Boolean = { false },
    private val config: CaptureConfig = CaptureConfig.Live,
    /** Preferred lens at start (user can [switchCamera] later). */
    preferFrontCamera: Boolean = true,
    /**
     * Optional TextureView surface for **hardware** local preview (no JPEG→ImageView flicker).
     * When null, a dummy SurfaceTexture is used (encode-only).
     */
    private val externalSurfaceTexture: () -> SurfaceTexture? = { null },
    private val onFacingChanged: (front: Boolean) -> Unit = {},
    /**
     * Preview buffer size (sensor landscape) + display orientation applied via
     * [Camera.setDisplayOrientation]. UI uses this to center-crop TextureView.
     */
    private val onPreviewReady: (previewW: Int, previewH: Int, displayOrientation: Int, front: Boolean) -> Unit =
        { _, _, _, _ -> },
    private val onError: (String) -> Unit = {},
    /** Optional NV21 path for H.264 encoder (sensor order, preview size). */
    private val onNv21Frame: ((nv21: ByteArray, width: Int, height: Int) -> Unit)? = null,
    private val onJpegFrame: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null
    /** True if we created SurfaceTexture ourselves (must release). */
    private var ownsSurfaceTexture = false
    private var lastSentAt = 0L
    private var facingFront: Boolean = preferFrontCamera
    private var preferFront: Boolean = preferFrontCamera
    private var sensorOrientation: Int = 90
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var frameCount = 0

    fun isFrontFacing(): Boolean = facingFront

    /** True when drawing through TextureView — UI should not JPEG-decode for local view. */
    fun usesHardwarePreview(): Boolean = externalSurfaceTexture() != null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val wt = HandlerThread("walkie-encode").also { it.start() }
        workerThread = wt
        worker = Handler(wt.looper)

        worker?.post {
            try {
                openAndStartCamera()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                onError("Camera start failed: ${t.javaClass.simpleName}: ${t.message}")
                stop()
            }
        }
    }

    /** Flip front ↔ back without ending the talk session. */
    fun switchCamera() {
        if (!running.get()) return
        worker?.post {
            try {
                preferFront = !preferFront
                releaseCamera()
                openAndStartCamera()
                onFacingChanged(facingFront)
                Log.i(TAG, "switched camera front=$facingFront")
            } catch (t: Throwable) {
                Log.e(TAG, "switchCamera failed", t)
                onError("Camera switch failed: ${t.message}")
            }
        }
    }

    fun stop() {
        stopAndWait(400L)
    }

    /** Stop camera and wait for the encode thread to finish the last JPEG. */
    fun stopAndWait(timeoutMs: Long = 600L) {
        running.set(false)
        val w = worker
        val wt = workerThread
        if (w != null && wt != null) {
            val done = CountDownLatch(1)
            w.post {
                try {
                    releaseCamera()
                } finally {
                    done.countDown()
                }
            }
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            wt.quitSafely()
            try {
                wt.join(300)
            } catch (_: InterruptedException) {
            }
        } else {
            releaseCamera()
        }
        worker = null
        workerThread = null
    }

    private fun openAndStartCamera() {
        val cam = openCamera() ?: run {
            onError("Camera.open failed — grant camera permission / close other camera apps")
            running.set(false)
            return
        }
        camera = cam
        val params = cam.parameters
        val sizes = params.supportedPreviewSizes
        if (sizes.isNullOrEmpty()) {
            onError("No camera preview sizes")
            stop()
            return
        }
        val size = pickPreviewSize(sizes)
        params.setPreviewSize(size.width, size.height)
        params.previewFormat = ImageFormat.NV21
        // Prefer faster FPS range when available
        try {
            val ranges = params.supportedPreviewFpsRange
            val best = ranges?.maxByOrNull { it[1] }
            if (best != null) params.setPreviewFpsRange(best[0], best[1])
        } catch (_: Exception) {
        }
        try {
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
        } catch (_: Exception) {
        }
        cam.parameters = params
        val displayOrientation = try {
            val o = computeDisplayOrientation()
            cam.setDisplayOrientation(o)
            o
        } catch (_: Exception) {
            90
        }

        fun makeDummy(): SurfaceTexture {
            ownsSurfaceTexture = true
            return SurfaceTexture(10).also { dummy ->
                dummy.setOnFrameAvailableListener({
                    try {
                        dummy.updateTexImage()
                    } catch (_: Exception) {
                    }
                }, worker)
            }
        }
        // Prefer UI TextureView only when still valid; otherwise dummy (background SEE / destroyed UI)
        val external = try {
            externalSurfaceTexture()
        } catch (_: Throwable) {
            null
        }
        var st: SurfaceTexture
        if (external != null) {
            ownsSurfaceTexture = false
            st = external
            try {
                st.setDefaultBufferSize(size.width, size.height)
                cam.setPreviewTexture(st)
            } catch (t: Throwable) {
                // Released TextureView after backgrounding — fall back to dummy for FGS stream
                Log.w(TAG, "UI SurfaceTexture unusable, dummy surface for encode", t)
                st = makeDummy()
                cam.setPreviewTexture(st)
            }
        } else {
            st = makeDummy()
            try {
                cam.setPreviewTexture(st)
            } catch (t: Throwable) {
                onError("setPreviewTexture failed: ${t.message}")
                throw t
            }
        }
        surfaceTexture = st
        try {
            onPreviewReady(size.width, size.height, displayOrientation, facingFront)
        } catch (_: Exception) {
        }

        // Drop backlog: if encoder is busy, skip rather than queue lag
        val encoding = AtomicBoolean(false)
        cam.setPreviewCallback { data, camera ->
            if (!running.get() || data == null) return@setPreviewCallback
            val now = System.currentTimeMillis()
            if (now - lastSentAt < config.minIntervalMs) return@setPreviewCallback
            if (!encoding.compareAndSet(false, true)) return@setPreviewCallback
            lastSentAt = now
            val ps = try {
                camera.parameters.previewSize
            } catch (_: Exception) {
                encoding.set(false)
                return@setPreviewCallback
            }
            val copy = data.copyOf()
            val w = ps.width
            val h = ps.height
            worker?.post {
                try {
                    if (!running.get()) return@post
                    // Prefer H.264 NV21 path when wired — rotate/mirror to upright
                    // (same orientation as JPEG) so remote SEE is not sideways.
                    val nv21Cb = onNv21Frame
                    if (nv21Cb != null) {
                        frameCount++
                        val upright = nv21ToUpright(copy, w, h)
                        nv21Cb(upright.data, upright.width, upright.height)
                    } else {
                        val jpeg = nv21ToUprightJpeg(copy, w, h)
                        if (jpeg != null && jpeg.isNotEmpty()) {
                            frameCount++
                            onJpegFrame(jpeg)
                        } else {
                            onError("JPEG encode returned empty (preview ${w}x${h})")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "encode", t)
                    onError("Encode error: ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    encoding.set(false)
                }
            }
        }
        cam.startPreview()
        Log.i(TAG, "preview ${size.width}x${size.height} front=$facingFront interval=${config.minIntervalMs}ms q=${config.jpegQuality}")
        onError("Camera OK ${size.width}x${size.height} (MJPEG encode thread)")
    }

    private fun releaseCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
        } catch (_: Exception) {
        }
        try {
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
        try {
            if (ownsSurfaceTexture) {
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
            }
        } catch (_: Exception) {
        }
        surfaceTexture = null
        ownsSurfaceTexture = false
    }

    private fun nv21ToUprightJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        val maxEdge = config.maxEdge
        val longer = max(width, height)
        val scaleDown = if (longer > maxEdge) maxEdge.toFloat() / longer else 1f
        val srcW: Int
        val srcH: Int
        val srcNv21: ByteArray
        if (scaleDown < 0.99f) {
            srcW = max(2, (width * scaleDown).roundToInt() and 0x7FFFFFFE)
            srcH = max(2, (height * scaleDown).roundToInt() and 0x7FFFFFFE)
            srcNv21 = scaleNv21(nv21, width, height, srcW, srcH)
        } else {
            srcW = width
            srcH = height
            srcNv21 = nv21
        }

        val yuv = YuvImage(srcNv21, ImageFormat.NV21, srcW, srcH, null)
        val rawJpeg = ByteArrayOutputStream(srcW * srcH / 2)
        // First pass quality tracks config (SEE max q=95); avoid hard-coded soft 85
        val yuvQ = config.jpegQuality.coerceIn(70, 100)
        if (!yuv.compressToJpeg(Rect(0, 0, srcW, srcH), yuvQ, rawJpeg)) return null
        val rawBytes = rawJpeg.toByteArray()

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null

        val matrix = Matrix()
        val rotation = computeJpegRotation()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (facingFront) matrix.postScale(-1f, 1f)

        val bmp = if (rotation != 0 || facingFront) {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }

        val maxBytes = if (compactForUdp()) {
            WalkieProtocol.VIDEO_JPEG_MAX_BYTES_UDP
        } else {
            config.maxBytesTcp
        }
        var quality = config.jpegQuality.coerceIn(35, 100)
        var out: ByteArray
        do {
            val baos = ByteArrayOutputStream(maxBytes.coerceIn(64_000, 1_200_000))
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            out = baos.toByteArray()
            // Only step quality down if over budget (SEE budget is large → keep high q)
            quality -= 5
        } while (out.size > maxBytes && quality >= 50)

        // Cellular/relay: must fit ONE datagram (maxBytes) or RX reassembly fails under loss.
        if (compactForUdp() && out.size > maxBytes) {
            var w = max(32, bmp.width / 2)
            var h = max(32, bmp.height / 2)
            var q = 40
            var attempts = 0
            while (out.size > maxBytes && attempts < 12) {
                val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                val baos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, q, baos)
                if (small !== bmp) small.recycle()
                out = baos.toByteArray()
                if (out.size > maxBytes) {
                    w = max(32, (w * 3) / 5)
                    h = max(32, (h * 3) / 5)
                    q = max(22, q - 4)
                }
                attempts++
            }
            Log.i(TAG, "UDP compact JPEG ${out.size}B max=$maxBytes attempts=$attempts ${w}x$h q=$q")
        }

        bmp.recycle()
        return out
    }

    private fun scaleNv21(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh + dw * dh / 2)
        val xRatio = sw.toFloat() / dw
        val yRatio = sh.toFloat() / dh
        for (y in 0 until dh) {
            val sy = (y * yRatio).toInt().coerceIn(0, sh - 1)
            val srcRow = sy * sw
            val dstRow = y * dw
            for (x in 0 until dw) {
                val sx = (x * xRatio).toInt().coerceIn(0, sw - 1)
                out[dstRow + x] = src[srcRow + sx]
            }
        }
        val srcUv = sw * sh
        val dstUv = dw * dh
        val uvDh = dh / 2
        val uvDw = dw / 2
        for (y in 0 until uvDh) {
            val sy = (y * yRatio).toInt().coerceIn(0, sh / 2 - 1)
            val srcRow = srcUv + sy * sw
            val dstRow = dstUv + y * dw
            for (x in 0 until uvDw) {
                val sx = (x * xRatio).toInt().coerceIn(0, sw / 2 - 1) * 2
                val di = dstRow + x * 2
                val si = srcRow + sx
                out[di] = src[si]
                out[di + 1] = src[si + 1]
            }
        }
        return out
    }

    private fun computeJpegRotation(): Int {
        val degrees = displayRotationDegrees()
        return if (facingFront) {
            (sensorOrientation + degrees) % 360
        } else {
            (sensorOrientation - degrees + 360) % 360
        }
    }

    private data class UprightNv21(val data: ByteArray, val width: Int, val height: Int)

    /**
     * Match [nv21ToUprightJpeg]: rotate sensor NV21 to display-upright and mirror front camera.
     * Used by H.264 so cellular SEE is not tilted 90°.
     */
    private fun nv21ToUpright(src: ByteArray, width: Int, height: Int): UprightNv21 {
        val rot = computeJpegRotation()
        var data = src
        var w = width
        var h = height
        if (rot != 0) {
            val rotated = rotateNv21(data, w, h, rot) ?: return UprightNv21(src, width, height)
            data = rotated.first
            w = rotated.second
            h = rotated.third
        }
        if (facingFront) {
            data = mirrorNv21Horizontal(data, w, h)
        }
        return UprightNv21(data, w, h)
    }

    /** Rotate NV21 by 0/90/180/270. Returns (bytes, newW, newH) or null. */
    private fun rotateNv21(src: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int>? {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return Triple(src, width, height)
        val frameSize = width * height
        if (src.size < frameSize + frameSize / 2) return null
        return when (d) {
            90 -> {
                val outW = height
                val outH = width
                val out = ByteArray(outW * outH + outW * outH / 2)
                var i = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        out[i++] = src[y * width + x]
                    }
                }
                var uvOut = outW * outH
                val uvHeight = height / 2
                for (x in 0 until width step 2) {
                    for (y in uvHeight - 1 downTo 0) {
                        val srcIx = frameSize + y * width + x
                        out[uvOut++] = src[srcIx]
                        out[uvOut++] = src[srcIx + 1]
                    }
                }
                Triple(out, outW, outH)
            }
            180 -> {
                val out = ByteArray(src.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(height - 1 - y) * width + (width - 1 - x)] = src[y * width + x]
                    }
                }
                val uvHeight = height / 2
                for (y in 0 until uvHeight) {
                    for (x in 0 until width step 2) {
                        val srcIx = frameSize + y * width + x
                        val dstIx = frameSize + (uvHeight - 1 - y) * width + (width - 2 - x)
                        out[dstIx] = src[srcIx]
                        out[dstIx + 1] = src[srcIx + 1]
                    }
                }
                Triple(out, width, height)
            }
            270 -> {
                val outW = height
                val outH = width
                val out = ByteArray(outW * outH + outW * outH / 2)
                var i = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        out[i++] = src[y * width + x]
                    }
                }
                var uvOut = outW * outH
                val uvHeight = height / 2
                for (x in width - 2 downTo 0 step 2) {
                    for (y in 0 until uvHeight) {
                        val srcIx = frameSize + y * width + x
                        out[uvOut++] = src[srcIx]
                        out[uvOut++] = src[srcIx + 1]
                    }
                }
                Triple(out, outW, outH)
            }
            else -> Triple(src, width, height)
        }
    }

    private fun mirrorNv21Horizontal(src: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        if (src.size < frameSize + frameSize / 2) return src
        val out = ByteArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                out[row + (width - 1 - x)] = src[row + x]
            }
        }
        val uvHeight = height / 2
        for (y in 0 until uvHeight) {
            val row = frameSize + y * width
            var x = 0
            while (x < width) {
                val sx = width - 2 - x
                out[row + x] = src[row + sx]
                out[row + x + 1] = src[row + sx + 1]
                x += 2
            }
        }
        return out
    }

    private fun computeDisplayOrientation(): Int {
        val degrees = displayRotationDegrees()
        return if (facingFront) {
            (360 - (sensorOrientation + degrees) % 360) % 360
        } else {
            (sensorOrientation - degrees + 360) % 360
        }
    }

    private fun displayRotationDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun openCamera(): Camera? {
        val n = Camera.getNumberOfCameras()
        var front = -1
        var back = -1
        val info = Camera.CameraInfo()
        for (i in 0 until n) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) front = i
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) back = i
        }
        val preferred = if (preferFront) front else back
        val fallback = if (preferFront) back else front
        val id = when {
            preferred >= 0 -> preferred
            fallback >= 0 -> fallback
            else -> return null
        }
        Camera.getCameraInfo(id, info)
        facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        preferFront = facingFront
        sensorOrientation = info.orientation
        return try {
            Camera.open(id)
        } catch (t: Throwable) {
            Log.e(TAG, "open failed", t)
            onError("Camera.open($id) failed: ${t.message}")
            null
        }
    }

    private fun pickPreviewSize(sizes: List<Camera.Size>): Camera.Size {
        // Prefer 16:9 near 1280x720 — natural phone video look (no weird crop ratios)
        val targetRatio = 16.0 / 9.0
        val band = sizes.filter { it.width * it.height in (640 * 480)..(1920 * 1080) }
        return band
            .minByOrNull { s ->
                val r = s.width.toDouble() / s.height.toDouble()
                abs(r - targetRatio) * 2000.0 + abs(s.width - WalkieProtocol.VIDEO_WIDTH)
            }
            ?: sizes
                .filter { it.width * it.height <= 1280 * 720 }
                .maxByOrNull { it.width * it.height }
            ?: sizes.first()
    }

    companion object {
        private const val TAG = "CameraFrameSource"
    }
}
