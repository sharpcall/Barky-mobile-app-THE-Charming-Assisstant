package com.example.barky.walkie

/**
 * One received (or completed) one-way video message for live buffer / replay.
 */
data class WalkieVideoFrame(
    val timeOffsetMs: Long,
    val jpeg: ByteArray
)

data class WalkieMessage(
    val audioPcm: ByteArray,
    val frames: List<WalkieVideoFrame>,
    val durationMs: Long,
    val fromHost: String
) {
    val hasContent: Boolean
        get() = audioPcm.isNotEmpty() || frames.isNotEmpty()
}
