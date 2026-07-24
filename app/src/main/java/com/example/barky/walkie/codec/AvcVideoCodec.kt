package com.example.barky.walkie.codec

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * H.264/AVC via [MediaCodec] hardware encoder/decoder (WhatsApp-class video).
 * Input: NV21 from Camera1. Output: Annex-B access units (SPS/PPS + slices).
 */
class AvcVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = DEFAULT_BITRATE,
    private val frameRate: Int = FRAME_RATE,
    private val iFrameIntervalSec: Int = 1
) {
    private var codec: MediaCodec? = null
    private val ready = AtomicBoolean(false)
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var ptsUs = 0L
    private var spsPps: ByteArray? = null
    private val fps = frameRate.coerceIn(10, 30)

    fun start(): Boolean {
        if (ready.get()) return true
        return try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            if (!hasEncoder(mime)) {
                Log.w(TAG, "no AVC encoder")
                return false
            }
            colorFormat = pickColorFormat(mime) ?: return false
            val w = width and 0x7FFFFFFE
            val h = height and 0x7FFFFFFE
            val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2)
            }
            val c = MediaCodec.createEncoderByType(mime)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            ready.set(true)
            Log.i(TAG, "AVC encoder ${w}x$h br=$bitRate color=$colorFormat")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "AVC encoder start", t)
            release()
            false
        }
    }

    fun isReady(): Boolean = ready.get()

    /**
     * Encode one NV21 frame (sensor orientation). Returns 0..n access units (with SPS/PPS on keyframes).
     */
    fun encodeNv21(nv21: ByteArray, srcW: Int, srcH: Int): List<ByteArray> {
        val c = codec ?: return emptyList()
        if (!ready.get()) return emptyList()
        return try {
            val yuv = nv21ToEncoderColor(nv21, srcW, srcH, width and 0x7FFFFFFE, height and 0x7FFFFFFE)
                ?: return emptyList()
            val inIx = c.dequeueInputBuffer(5_000)
            if (inIx < 0) return emptyList()
            val inBuf = c.getInputBuffer(inIx) ?: return emptyList()
            inBuf.clear()
            inBuf.put(yuv)
            c.queueInputBuffer(inIx, 0, yuv.size, ptsUs, 0)
            ptsUs += 1_000_000L / fps
            drainEncoder()
        } catch (t: Throwable) {
            Log.w(TAG, "encodeNv21", t)
            emptyList()
        }
    }

    /** Encode one ARGB_8888 bitmap (screen capture path). */
    fun encodeBitmap(bmp: android.graphics.Bitmap): List<ByteArray> {
        if (!ready.get()) return emptyList()
        val w = width and 0x7FFFFFFE
        val h = height and 0x7FFFFFFE
        val scaled = if (bmp.width != w || bmp.height != h) {
            android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        val nv21 = argbBitmapToNv21(scaled, w, h)
        if (scaled !== bmp) scaled.recycle()
        return encodeNv21(nv21, w, h)
    }

    private fun argbBitmapToNv21(bmp: android.graphics.Bitmap, w: Int, h: Int): ByteArray {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h
        var index = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[index++]
                val r = c shr 16 and 0xff
                val g = c shr 8 and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    private fun drainEncoder(): List<ByteArray> {
        val c = codec ?: return emptyList()
        val out = ArrayList<ByteArray>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIx = c.dequeueOutputBuffer(info, 2_000)
            when {
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "enc out format ${c.outputFormat}")
                    continue
                }
                outIx >= 0 -> {
                    try {
                        val buf = c.getOutputBuffer(outIx) ?: continue
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(bytes)
                        c.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            spsPps = bytes
                            out.add(bytes)
                        } else {
                            // Prepend SPS/PPS on IDR if needed
                            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val cfg = spsPps
                            if (isKey && cfg != null && !startsWithAnnexB(bytes)) {
                                out.add(cfg + bytes)
                            } else {
                                out.add(bytes)
                            }
                        }
                    } catch (t: Throwable) {
                        try {
                            c.releaseOutputBuffer(outIx, false)
                        } catch (_: Throwable) {
                        }
                    }
                }
                else -> break
            }
        }
        return out
    }

    fun release() {
        ready.set(false)
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        spsPps = null
    }

    private fun nv21ToEncoderColor(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): ByteArray? {
        // Scale via JPEG is expensive; simple nearest for speed when sizes match-ish
        val scaled = if (srcW != dstW || srcH != dstH) {
            scaleNv21(nv21, srcW, srcH, dstW, dstH)
        } else nv21
        return when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar ->
                nv21ToNv12(scaled, dstW, dstH)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ->
                nv21ToI420(scaled, dstW, dstH)
            else -> nv21ToNv12(scaled, dstW, dstH)
        }
    }

    companion object {
        private const val TAG = "AvcVideoEncoder"
        /** Camera SEE/HOLD default. Screen share uses higher via constructor bitRate + 30fps config. */
        const val FRAME_RATE = 24
        const val DEFAULT_BITRATE = 2_500_000
        const val SCREEN_BITRATE = 4_000_000
        const val SCREEN_FRAME_RATE = 30

        fun hasEncoder(mime: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean {
            return try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun isAvailable(): Boolean = hasEncoder()

        private fun pickColorFormat(mime: String): Int? {
            try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    if (!info.supportedTypes.any { it.equals(mime, true) }) continue
                    val caps = info.getCapabilitiesForType(mime)
                    val preferred = intArrayOf(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    )
                    for (p in preferred) {
                        if (caps.colorFormats.contains(p)) return p
                    }
                    if (caps.colorFormats.isNotEmpty()) return caps.colorFormats[0]
                }
            } catch (_: Throwable) {
            }
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }

        private fun startsWithAnnexB(b: ByteArray): Boolean =
            b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() &&
                ((b[2] == 0.toByte() && b[3] == 1.toByte()) || b[2] == 1.toByte())

        private fun nv21ToNv12(nv21: ByteArray, w: Int, h: Int): ByteArray {
            val ySize = w * h
            val out = ByteArray(ySize + ySize / 2)
            System.arraycopy(nv21, 0, out, 0, ySize)
            // NV21 VU interleaved → NV12 UV interleaved
            var i = ySize
            while (i < nv21.size - 1) {
                out[i] = nv21[i + 1] // U
                out[i + 1] = nv21[i] // V
                i += 2
            }
            return out
        }

        private fun nv21ToI420(nv21: ByteArray, w: Int, h: Int): ByteArray {
            val ySize = w * h
            val cSize = ySize / 4
            val out = ByteArray(ySize + ySize / 2)
            System.arraycopy(nv21, 0, out, 0, ySize)
            var u = ySize
            var v = ySize + cSize
            var i = ySize
            while (i < nv21.size - 1 && u < ySize + cSize && v < out.size) {
                out[v++] = nv21[i]     // V
                out[u++] = nv21[i + 1] // U
                i += 2
            }
            return out
        }

        private fun scaleNv21(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
            val out = ByteArray(dw * dh + dw * dh / 2)
            for (y in 0 until dh) {
                val sy = (y * sh / dh).coerceIn(0, sh - 1)
                for (x in 0 until dw) {
                    val sx = (x * sw / dw).coerceIn(0, sw - 1)
                    out[y * dw + x] = src[sy * sw + sx]
                }
            }
            val ySize = dw * dh
            val srcY = sw * sh
            for (y in 0 until dh / 2) {
                val sy = (y * 2 * sh / dh).coerceIn(0, sh - 2)
                for (x in 0 until dw / 2) {
                    val sx = (x * 2 * sw / dw).coerceIn(0, sw - 2) and 0x7FFFFFFE
                    val srcOff = srcY + (sy / 2) * sw + sx
                    val dstOff = ySize + y * dw + x * 2
                    if (srcOff + 1 < src.size && dstOff + 1 < out.size) {
                        out[dstOff] = src[srcOff]
                        out[dstOff + 1] = src[srcOff + 1]
                    }
                }
            }
            return out
        }
    }
}

/**
 * Decode H.264 access units to [Bitmap] for UI.
 * Samsung hardware decoders often emit awkward YUV_420_888 plane layouts that look like
 * grey/green TV static when converted poorly — prefer Google software decoder on Samsung.
 */
class AvcVideoDecoder {
    private var codec: MediaCodec? = null
    private val ready = AtomicBoolean(false)
    private var width = 640
    private var height = 480
    private var ptsUs = 0L
    private var loggedPlanes = false

    fun start(w: Int = 640, h: Int = 480): Boolean {
        if (ready.get()) return true
        width = w and 0x7FFFFFFE
        height = h and 0x7FFFFFFE
        return try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 2)
                // Prefer flexible YUV so getOutputImage is supported on all OEMs
                try {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                } catch (_: Throwable) {
                }
            }
            val c = createPreferredDecoder(mime)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            ready.set(true)
            loggedPlanes = false
            Log.i(TAG, "AVC decoder ${width}x$height name=${c.name}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "AVC decoder start", t)
            release()
            false
        }
    }

    fun isReady(): Boolean = ready.get()

    fun decodeToBitmap(accessUnit: ByteArray): Bitmap? {
        val c = codec ?: return null
        if (!ready.get() || accessUnit.isEmpty()) return null
        return try {
            val inIx = c.dequeueInputBuffer(5_000)
            if (inIx < 0) return null
            val inBuf = c.getInputBuffer(inIx) ?: return null
            inBuf.clear()
            if (accessUnit.size > inBuf.remaining()) {
                Log.w(TAG, "AU too large ${accessUnit.size} > ${inBuf.remaining()}")
                return null
            }
            inBuf.put(accessUnit)
            c.queueInputBuffer(inIx, 0, accessUnit.size, ptsUs, 0)
            ptsUs += 1_000_000L / 20
            drainToBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "decode", t)
            null
        }
    }

    private fun drainToBitmap(): Bitmap? {
        val c = codec ?: return null
        val info = MediaCodec.BufferInfo()
        // Drain a few times — first buffers may be format change / codec config
        repeat(4) {
            val outIx = c.dequeueOutputBuffer(info, 8_000)
            when {
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "decoder format $f")
                }
                outIx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* legacy */ }
                outIx >= 0 -> {
                    return try {
                        if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            c.releaseOutputBuffer(outIx, false)
                            null
                        } else {
                            val image = c.getOutputImage(outIx)
                            val bmp = if (image != null) {
                                imageToBitmap(image).also { image.close() }
                            } else {
                                null
                            }
                            c.releaseOutputBuffer(outIx, false)
                            bmp
                        }
                    } catch (t: Throwable) {
                        try {
                            c.releaseOutputBuffer(outIx, false)
                        } catch (_: Throwable) {
                        }
                        Log.w(TAG, "drain image", t)
                        null
                    }
                }
                else -> return null
            }
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            if (!loggedPlanes) {
                loggedPlanes = true
                val p = image.planes
                Log.i(
                    TAG,
                    "YUV planes crop=${image.cropRect} size=${image.width}x${image.height} " +
                        "yStride=${p[0].rowStride}/${p[0].pixelStride} " +
                        "uStride=${p[1].rowStride}/${p[1].pixelStride} " +
                        "vStride=${p[2].rowStride}/${p[2].pixelStride}"
                )
            }
            // Prefer absolute-index ARGB (most reliable on Samsung when NV21 pack fails)
            yuv420ToArgbBitmap(image)
                ?: run {
                    val nv21 = yuv420888ToNv21(image) ?: return null
                    val w = image.cropRect.width().coerceAtLeast(1)
                    val h = image.cropRect.height().coerceAtLeast(1)
                    val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
                    val baos = ByteArrayOutputStream(w * h / 2)
                    if (!yuv.compressToJpeg(Rect(0, 0, w, h), 90, baos)) return null
                    android.graphics.BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
                }
        } catch (t: Throwable) {
            Log.w(TAG, "imageToBitmap", t)
            null
        }
    }

    /** Absolute-index YUV_420_888 → ARGB_8888 (no ByteBuffer position side-effects). */
    private fun yuv420ToArgbBitmap(image: Image): Bitmap? {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride.coerceAtLeast(1)
        val uRow = uP.rowStride
        val uPix = uP.pixelStride.coerceAtLeast(1)
        val vRow = vP.rowStride
        val vPix = vP.pixelStride.coerceAtLeast(1)
        // Plane buffers may start at non-zero position
        val yBase = yBuf.position()
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        val argb = IntArray(w * h)
        var i = 0
        for (row in 0 until h) {
            val yRowStart = yBase + (crop.top + row) * yRow + crop.left * yPix
            val uvRow = (crop.top + row) shr 1
            val uRowStart = uBase + uvRow * uRow
            val vRowStart = vBase + uvRow * vRow
            for (col in 0 until w) {
                val yi = yRowStart + col * yPix
                if (yi < 0 || yi >= yBuf.limit()) {
                    argb[i++] = 0xff000000.toInt()
                    continue
                }
                val y = (yBuf.get(yi).toInt() and 0xff)
                val uvCol = (crop.left + col) shr 1
                val ui = uRowStart + uvCol * uPix
                val vi = vRowStart + uvCol * vPix
                val u = if (ui in 0 until uBuf.limit()) (uBuf.get(ui).toInt() and 0xff) - 128 else 0
                val v = if (vi in 0 until vBuf.limit()) (vBuf.get(vi).toInt() and 0xff) - 128 else 0
                // BT.601 limited-range-ish fast convert
                var r = y + ((v * 1436) shr 10)
                var g = y - ((u * 352 + v * 731) shr 10)
                var b = y + ((u * 1815) shr 10)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                argb[i++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray? {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride.coerceAtLeast(1)
        val uRow = uP.rowStride
        val uPix = uP.pixelStride.coerceAtLeast(1)
        val vRow = vP.rowStride
        val vPix = vP.pixelStride.coerceAtLeast(1)
        val yBase = yBuf.position()
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        var o = 0
        for (row in 0 until height) {
            val rowStart = yBase + (crop.top + row) * yRow + crop.left * yPix
            for (col in 0 until width) {
                val idx = rowStart + col * yPix
                out[o++] = if (idx in 0 until yBuf.limit()) yBuf.get(idx) else 0
            }
        }
        // NV21 = VU interleaved
        o = ySize
        for (row in 0 until height / 2) {
            val uRowStart = uBase + ((crop.top shr 1) + row) * uRow + (crop.left shr 1) * uPix
            val vRowStart = vBase + ((crop.top shr 1) + row) * vRow + (crop.left shr 1) * vPix
            for (col in 0 until width / 2) {
                val vi = vRowStart + col * vPix
                val ui = uRowStart + col * uPix
                out[o++] = if (vi in 0 until vBuf.limit()) vBuf.get(vi) else 0
                out[o++] = if (ui in 0 until uBuf.limit()) uBuf.get(ui) else 0
            }
        }
        return out
    }

    fun release() {
        ready.set(false)
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        loggedPlanes = false
    }

    companion object {
        private const val TAG = "AvcVideoDecoder"

        fun isAvailable(): Boolean {
            return try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
                }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Samsung OEM decoders often produce plane layouts that break naive YUV→RGB.
         * Prefer Google software decoder on Samsung; fall back to default type.
         */
        private fun createPreferredDecoder(mime: String): MediaCodec {
            val samsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            if (samsung) {
                val prefer = listOf(
                    "c2.android.avc.decoder",
                    "OMX.google.h264.decoder"
                )
                for (name in prefer) {
                    try {
                        val c = MediaCodec.createByCodecName(name)
                        Log.i(TAG, "using software decoder $name")
                        return c
                    } catch (_: Throwable) {
                    }
                }
                // Any google/android software decoder that supports AVC
                try {
                    val list = MediaCodecList(MediaCodecList.ALL_CODECS)
                    for (info in list.codecInfos) {
                        if (info.isEncoder) continue
                        val n = info.name.lowercase()
                        if ((n.contains("google") || n.contains("android") || n.contains("c2.android")) &&
                            info.supportedTypes.any { it.equals(mime, true) }
                        ) {
                            Log.i(TAG, "using decoder ${info.name}")
                            return MediaCodec.createByCodecName(info.name)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            return MediaCodec.createDecoderByType(mime)
        }
    }
}
