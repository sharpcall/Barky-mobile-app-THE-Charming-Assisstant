package com.example.barky.walkie.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.example.barky.walkie.WalkieProtocol
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opus speech codec via Android [MediaCodec] (same family WhatsApp uses for voice).
 * Falls back when no encoder/decoder is present (rare on API 26+).
 */
class OpusAudioCodec {
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private val encReady = AtomicBoolean(false)
    private val decReady = AtomicBoolean(false)
    private var ptsUs = 0L

    fun startEncoder(): Boolean {
        if (encReady.get()) return true
        return try {
            val mime = MediaFormat.MIMETYPE_AUDIO_OPUS
            if (!hasCodec(mime, encoder = true)) {
                Log.w(TAG, "no Opus encoder")
                return false
            }
            val fmt = MediaFormat.createAudioFormat(mime, WalkieProtocol.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, WalkieProtocol.BYTES_PER_FRAME * 4)
                if (Build.VERSION.SDK_INT >= 29) {
                    setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                }
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            encReady.set(true)
            Log.i(TAG, "Opus encoder OK ${WalkieProtocol.SAMPLE_RATE}Hz mono ${BIT_RATE}bps")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Opus encoder start failed", t)
            releaseEncoder()
            false
        }
    }

    fun startDecoder(): Boolean {
        if (decReady.get()) return true
        return try {
            val mime = MediaFormat.MIMETYPE_AUDIO_OPUS
            if (!hasCodec(mime, encoder = false)) {
                Log.w(TAG, "no Opus decoder")
                return false
            }
            val fmt = MediaFormat.createAudioFormat(mime, WalkieProtocol.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                // Minimal CSD for raw Opus packets (empty identification often works with c2)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            decoder = codec
            decReady.set(true)
            Log.i(TAG, "Opus decoder OK")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Opus decoder start failed", t)
            releaseDecoder()
            false
        }
    }

    fun isEncoderReady(): Boolean = encReady.get()
    fun isDecoderReady(): Boolean = decReady.get()

    /** Encode one 40ms PCM frame → one Opus packet (or null). */
    fun encode(pcm: ByteArray, length: Int): ByteArray? {
        val codec = encoder ?: return null
        if (!encReady.get() || length < 2) return null
        return try {
            val inIx = codec.dequeueInputBuffer(2_000)
            if (inIx < 0) return null
            val inBuf = codec.getInputBuffer(inIx) ?: return null
            inBuf.clear()
            val n = minOf(length, inBuf.capacity())
            inBuf.put(pcm, 0, n)
            codec.queueInputBuffer(inIx, 0, n, ptsUs, 0)
            ptsUs += WalkieProtocol.FRAME_MS * 1000L
            drainEncoder()
        } catch (t: Throwable) {
            Log.w(TAG, "encode", t)
            null
        }
    }

    private fun drainEncoder(): ByteArray? {
        val codec = encoder ?: return null
        val info = MediaCodec.BufferInfo()
        val outIx = codec.dequeueOutputBuffer(info, 2_000)
        if (outIx == MediaCodec.INFO_TRY_AGAIN_LATER) return null
        if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.i(TAG, "enc format ${codec.outputFormat}")
            return drainEncoder()
        }
        if (outIx < 0) return null
        return try {
            val outBuf = codec.getOutputBuffer(outIx) ?: return null
            val bytes = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            outBuf.get(bytes)
            codec.releaseOutputBuffer(outIx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // config — skip for stream
                return drainEncoder()
            }
            bytes
        } catch (t: Throwable) {
            try {
                codec.releaseOutputBuffer(outIx, false)
            } catch (_: Throwable) {
            }
            null
        }
    }

    fun decode(opus: ByteArray): ByteArray? {
        val codec = decoder ?: return null
        if (!decReady.get() || opus.isEmpty()) return null
        return try {
            val inIx = codec.dequeueInputBuffer(2_000)
            if (inIx < 0) return null
            val inBuf = codec.getInputBuffer(inIx) ?: return null
            inBuf.clear()
            inBuf.put(opus)
            codec.queueInputBuffer(inIx, 0, opus.size, ptsUs, 0)
            ptsUs += WalkieProtocol.FRAME_MS * 1000L
            drainDecoder()
        } catch (t: Throwable) {
            Log.w(TAG, "decode", t)
            null
        }
    }

    private fun drainDecoder(): ByteArray? {
        val codec = decoder ?: return null
        val info = MediaCodec.BufferInfo()
        val outIx = codec.dequeueOutputBuffer(info, 2_000)
        if (outIx == MediaCodec.INFO_TRY_AGAIN_LATER) return null
        if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return drainDecoder()
        if (outIx < 0) return null
        return try {
            val outBuf = codec.getOutputBuffer(outIx) ?: return null
            val bytes = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            outBuf.get(bytes)
            codec.releaseOutputBuffer(outIx, false)
            bytes
        } catch (t: Throwable) {
            try {
                codec.releaseOutputBuffer(outIx, false)
            } catch (_: Throwable) {
            }
            null
        }
    }

    fun release() {
        releaseEncoder()
        releaseDecoder()
    }

    private fun releaseEncoder() {
        encReady.set(false)
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        encoder = null
    }

    private fun releaseDecoder() {
        decReady.set(false)
        try {
            decoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            decoder?.release()
        } catch (_: Throwable) {
        }
        decoder = null
    }

    companion object {
        private const val TAG = "OpusAudioCodec"
        /** Speech-optimized bitrate (WhatsApp-class). */
        const val BIT_RATE = 32_000

        fun hasCodec(mime: String, encoder: Boolean): Boolean {
            return try {
                val list = MediaCodecList(MediaCodecList.ALL_CODECS)
                list.codecInfos.any { info ->
                    if (encoder != info.isEncoder) return@any false
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun isAvailable(): Boolean =
            hasCodec(MediaFormat.MIMETYPE_AUDIO_OPUS, encoder = true) &&
                hasCodec(MediaFormat.MIMETYPE_AUDIO_OPUS, encoder = false)
    }
}
