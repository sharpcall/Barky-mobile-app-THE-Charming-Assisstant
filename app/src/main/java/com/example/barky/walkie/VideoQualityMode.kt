package com.example.barky.walkie

/**
 * Encapsulates video and screen-mirroring quality presets for SEE (remote camera)
 * and SCRN (screen mirroring). Designed as a self-contained beta abstraction so it
 * can be modified, moved to Settings, or automated based on network conditions.
 *
 * Modes:
 * 1. NO_COMPRESSION: Maximum definition (1080p / 720p native, 95% JPEG, 5.0 Mbps AVC)
 * 2. STANDARD: Balanced quality & fluid frame rate (720p / 480p, 75% JPEG, 1.8 Mbps AVC)
 * 3. LOW: Data saver for weak cellular/congested networks (360p / 480p, 50% JPEG, 500 kbps AVC)
 */
enum class VideoQualityMode(
    val key: String,
    val displayLabel: String,
    val buttonText: String,
    val estimatedBandwidthDesc: String,
    val seeMaxEdge: Int,
    val seeJpegQuality: Int,
    val seeMaxBytesTcp: Int,
    val seeIntervalMs: Long,
    val seeAvcWidth: Int,
    val seeAvcHeight: Int,
    val seeAvcBitRate: Int,
    val seeAvcFrameRate: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenJpegQuality: Int,
    val screenAvcBitRate: Int,
    val screenIntervalMs: Long
) {
    /**
     * No Compression / Maximum Definition mode:
     * - Highest resolution possible (up to 1080p upright).
     * - Minimal JPEG compression artifacts (quality 95).
     * - High-bitrate 5.0 Mbps H.264 AVC stream @ 30 fps.
     * - Bandwidth: ~4.5 - 6.0 Mbps on AVC (~2.0 - 2.7 GB / hour); ~12 - 18 Mbps on MJPEG (~5.5 - 8.0 GB / hour).
     */
    NO_COMPRESSION(
        key = "no_compression",
        displayLabel = "HD (No Compression)",
        buttonText = "Quality: HD",
        estimatedBandwidthDesc = "4–6 Mbps (~2.2 GB/h)",
        seeMaxEdge = 1080,
        seeJpegQuality = 95,
        seeMaxBytesTcp = 400_000,
        seeIntervalMs = 33L, // ~30 fps
        seeAvcWidth = 720,
        seeAvcHeight = 1280,
        seeAvcBitRate = 5_000_000,
        seeAvcFrameRate = 30,
        screenWidth = 1080,
        screenHeight = 1920,
        screenJpegQuality = 95,
        screenAvcBitRate = 5_000_000,
        screenIntervalMs = 150L // faster a11y capture rate
    ),

    /**
     * Standard Balanced quality mode:
     * - Native 720p / 480p resolution.
     * - Balanced 75-78% JPEG quality.
     * - 1.8 Mbps H.264 AVC stream @ 24 fps.
     * - Bandwidth: ~1.5 - 2.0 Mbps on AVC (~675 - 900 MB / hour); ~3.5 - 5.0 Mbps on MJPEG (~1.5 - 2.2 GB / hour).
     */
    STANDARD(
        key = "standard",
        displayLabel = "Standard Quality",
        buttonText = "Quality: Standard",
        estimatedBandwidthDesc = "1.5–2 Mbps (~800 MB/h)",
        seeMaxEdge = 720,
        seeJpegQuality = 78,
        seeMaxBytesTcp = 180_000,
        seeIntervalMs = 33L, // ~30 fps
        seeAvcWidth = 480,
        seeAvcHeight = 640,
        seeAvcBitRate = 1_800_000,
        seeAvcFrameRate = 24,
        screenWidth = 720,
        screenHeight = 1280,
        screenJpegQuality = 72,
        screenAvcBitRate = 1_800_000,
        screenIntervalMs = 240L
    ),

    /**
     * Low Data-Saver mode:
     * - Compact 360p / 480p resolution.
     * - 50% JPEG quality for small payload size.
     * - 500 kbps H.264 AVC stream @ 15 fps.
     * - Bandwidth: ~400 - 600 kbps on AVC (~180 - 270 MB / hour); ~1.0 - 1.5 Mbps on MJPEG (~450 - 675 MB / hour).
     */
    LOW(
        key = "low",
        displayLabel = "Low (Data Saver)",
        buttonText = "Quality: Low",
        estimatedBandwidthDesc = "400–600 kbps (~220 MB/h)",
        seeMaxEdge = 480,
        seeJpegQuality = 50,
        seeMaxBytesTcp = 80_000,
        seeIntervalMs = 66L, // ~15 fps
        seeAvcWidth = 360,
        seeAvcHeight = 480,
        seeAvcBitRate = 500_000,
        seeAvcFrameRate = 15,
        screenWidth = 480,
        screenHeight = 854,
        screenJpegQuality = 45,
        screenAvcBitRate = 500_000,
        screenIntervalMs = 400L
    );

    /** Returns the next quality mode in a 3-way cycle: NO_COMPRESSION -> STANDARD -> LOW -> NO_COMPRESSION. */
    fun next(): VideoQualityMode = when (this) {
        NO_COMPRESSION -> STANDARD
        STANDARD -> LOW
        LOW -> NO_COMPRESSION
    }

    /** Helper to generate a CameraFrameSource CaptureConfig for SEE based on this mode. */
    fun toCaptureConfig(): CaptureConfig = CaptureConfig(
        minIntervalMs = seeIntervalMs,
        maxEdge = seeMaxEdge,
        jpegQuality = seeJpegQuality,
        maxBytesTcp = seeMaxBytesTcp
    )

    companion object {
        fun fromKey(key: String?): VideoQualityMode =
            values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STANDARD
    }
}
