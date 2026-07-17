package com.example.barky.walkie

/**
 * Audio/control: UDP. Video: TCP preferred (Motion JPEG), UDP fallback.
 *
 * **Codec:** Motion JPEG (MJPEG) — each frame is a standard JPEG still.
 * Encoder: Android `Bitmap.compress(JPEG)` / `YuvImage.compressToJpeg` (platform libjpeg).
 * No third-party native codec library.
 */
object WalkieProtocol {
    const val SERVICE_TYPE = "_barkytalk._udp."
    const val SERVICE_NAME_PREFIX = "Barky-"
    const val ATTR_VIDEO_PORT = "vport"

    const val TYPE_AUDIO: Byte = 0x01
    const val TYPE_WAKEUP: Byte = 0x02
    const val TYPE_PING: Byte = 0x03
    const val TYPE_PONG: Byte = 0x04
    const val TYPE_TALK_END: Byte = 0x05
    const val TYPE_VIDEO: Byte = 0x06
    const val TYPE_WAKEUP_STOP: Byte = 0x07
    const val TYPE_HELLO: Byte = 0x08
    /** Half-duplex: peer started transmitting — lock local PTT.
     * Optional 2nd byte: AUDIO_MODE_SPEAKER (0) or AUDIO_MODE_HEADSET (1). */
    const val TYPE_TALK_START: Byte = 0x09
    /** Request peer to stream mic continuously (LISTEN). */
    const val TYPE_LISTEN_REQ: Byte = 0x0A
    const val TYPE_LISTEN_STOP: Byte = 0x0B
    /** Request peer to stream camera + mic continuously (SEE). */
    const val TYPE_SEE_REQ: Byte = 0x0C
    const val TYPE_SEE_STOP: Byte = 0x0D
    /**
     * GPS share: [type][lat:double LE][lon:double LE][timeMs:long LE]
     * 1 + 8 + 8 + 8 = 25 bytes.
     */
    const val TYPE_GPS: Byte = 0x0E
    /** Continuous monitor audio (LISTEN/SEE) — play live, do not store as message. */
    const val TYPE_MONITOR_AUDIO: Byte = 0x0F
    /** Continuous monitor JPEG part (same layout as TYPE_VIDEO) — live only. */
    const val TYPE_MONITOR_VIDEO: Byte = 0x10

    const val AUDIO_MODE_SPEAKER: Byte = 0
    const val AUDIO_MODE_HEADSET: Byte = 1
    const val GPS_PAYLOAD_BYTES = 1 + 8 + 8 + 8
    const val GPS_TRAIL_HOURS = 5

    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 40
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2

    const val VIDEO_CHUNK = 1100
    const val VIDEO_HEADER = 1 + 2 + 1 + 1
    const val MAX_PACKET = VIDEO_HEADER + VIDEO_CHUNK + 64

    /**
     * Target ~20–25 fps when encode keeps up.
     * LAN TCP MJPEG: sharp stills via platform JPEG (no 3rd-party codec).
     */
    /** Slightly steadier pace for live encode→display (less flash than over-encoding). */
    const val VIDEO_MIN_INTERVAL_MS = 50L
    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720
    /** Longer edge of upright frame sent over TCP (clear on phone screens). */
    const val VIDEO_OUT_MAX_EDGE = 1280
    const val VIDEO_JPEG_QUALITY_START = 90
    const val VIDEO_JPEG_MAX_BYTES_TCP = 280_000
    const val VIDEO_JPEG_MAX_BYTES_UDP = 1000
    /** Only one last message kept in RAM; cap frames so memory stays bounded. */
    const val MAX_STORED_FRAMES = 90

    /**
     * BEST quality (record-then-send): aim ~30 fps while capturing, higher JPEG budget,
     * more frames allowed in the single in-RAM clip.
     */
    const val BEST_VIDEO_MIN_INTERVAL_MS = 33L
    const val BEST_VIDEO_JPEG_QUALITY = 92
    const val BEST_VIDEO_JPEG_MAX_BYTES = 350_000
    const val BEST_MAX_STORED_FRAMES = 300

    /**
     * BEST mode: one atomic TCP payload (audio + all frames) so nothing is cut by
     * early TALK_END / idle finalize. Magic is not a valid JPEG length.
     * Layout after magic int:
     *   audioLen:int, audio:bytes, frameCount:int,
     *   repeat: timeOffsetMs:long, jpegLen:int, jpeg:bytes
     */
    const val TCP_BUNDLE_MAGIC = 0x424B5354 // "BKST"
}
