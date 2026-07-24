package com.example.barky.walkie

import android.content.Context
import android.util.Log
import com.example.barky.BarkyPrefs
import com.example.barky.walkie.codec.AvcVideoEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SEE feature — remote live camera (request + capture/encode).
 *
 * **File name:** `See.kt`
 * **Full path:** `app/src/main/java/com/example/barky/walkie/See.kt`
 *
 * Contains:
 * - [See] — control plane: SEE_REQ / SEE_STOP multi-path bursts (public + LAN)
 * - [SeeCameraSession] — camera open + H.264/MJPEG encode for the serve path
 *
 * Path selection lives in [PeerReach]; UDP/TCP send stays in [WalkieTalkieEngine]
 * (shared socket with STUN / LISTEN / RING).
 */
object See {
    private const val TAG = "See"
    const val FILE_NAME = "See.kt"

    /**
     * Burst SEE_REQ to every target (public first when off Wi‑Fi).
     */
    fun sendReqBurst(
        targets: List<WalkiePeer>,
        replyPort: Int,
        stillWanted: () -> Boolean,
        sendControl: (peer: WalkiePeer, type: Byte, replyPort: Int) -> Unit,
        holePunch: (peer: WalkiePeer, rounds: Int) -> Unit,
        sendHello: (peer: WalkiePeer) -> Unit,
        sendPathUpdate: (peer: WalkiePeer) -> Unit = {}
    ) {
        if (targets.isEmpty()) {
            Log.w(TAG, "sendReqBurst empty targets ($FILE_NAME)")
            return
        }
        Log.i(
            TAG,
            "SEE_REQ burst targets=${targets.joinToString { "${it.host}:${it.port}" }} " +
                "reply=$replyPort file=$FILE_NAME"
        )
        for (t in targets) {
            if (!stillWanted()) return
            try {
                sendHello(t)
                sendPathUpdate(t)
                val public = !com.example.barky.net.PeerEndpoint.isPrivateIp(t.host)
                if (public) {
                    holePunch(t, 18)
                } else {
                    Thread.sleep(50)
                }
                val ports = linkedSetOf(t.port)
                for (port in ports.filter { it > 0 }) {
                    if (!stillWanted()) return
                    sendControl(t.copy(port = port), WalkieProtocol.TYPE_SEE_REQ, replyPort)
                    Thread.sleep(35)
                }
            } catch (ex: Throwable) {
                Log.w(TAG, "SEE_REQ target ${t.host}: ${ex.message}")
            }
        }
        Thread.sleep(320)
        if (!stillWanted()) return
        for (t in targets) {
            if (!stillWanted()) return
            try {
                sendControl(t, WalkieProtocol.TYPE_SEE_REQ, replyPort)
                if (!com.example.barky.net.PeerEndpoint.isPrivateIp(t.host)) {
                    holePunch(t, 8)
                }
                Thread.sleep(40)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "SEE_REQ burst done file=$FILE_NAME")
    }

    fun sendStop(
        targets: List<WalkiePeer>,
        sendControl: (peer: WalkiePeer, type: Byte) -> Unit
    ) {
        for (t in targets) {
            try {
                sendControl(t, WalkieProtocol.TYPE_SEE_STOP)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "SEE_STOP sent n=${targets.size} file=$FILE_NAME")
    }
}

/**
 * SEE camera capture + optional H.264 encode (serve side).
 *
 * Lives in **`See.kt`** next to the control plane so the whole SEE feature is one module.
 */
class SeeCameraSession(
    private val context: Context,
    private val isActive: () -> Boolean,
    private val isTcpConnected: () -> Boolean,
    private val useAvc: Boolean,
    /** Optional UI TextureView surface so local self-PiP shows while serving SEE. */
    private val externalSurfaceTexture: () -> android.graphics.SurfaceTexture? = { null },
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

    fun cameraSource(): CameraFrameSource? = camera

    fun switchCamera(): Boolean {
        val cam = camera ?: return false
        cam.switchCamera()
        return true
    }

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "SeeCameraSession already started")
            return false
        }
        frameCount.set(0)
        val preferFront = BarkyPrefs.isCameraFront(context)
        var enc: AvcVideoEncoder? = null
        if (useAvc) {
            // Free platform H.264 — portrait 480×640 matches upright NV21 after camera rotation
            // (was 640×480 landscape → remote SEE looked tilted 90° on phones held upright).
            enc = AvcVideoEncoder(
                width = 480,
                height = 640,
                bitRate = 1_800_000,
                frameRate = 24,
                iFrameIntervalSec = 1
            ).also { it.start() }
            if (enc.isReady() != true) {
                enc.release()
                enc = null
                Log.w(TAG, "AVC encoder not ready — falling back to MJPEG")
            } else {
                Log.i(TAG, "SEE camera codec=H.264/AVC 480x640@24 1.8Mbps upright file=${See.FILE_NAME}")
            }
        } else {
            Log.i(TAG, "SEE camera codec=MJPEG file=${See.FILE_NAME}")
        }
        encoder = enc

        // AVC uses full Live capture; JPEG SEE path optimised for low latency (~30 fps)
        val captureConfig = if (enc != null && enc.isReady()) {
            CaptureConfig.Live
        } else {
            CaptureConfig.SeeLive
        }
        val cam = if (enc != null && enc.isReady()) {
            val readyEnc = enc
            CameraFrameSource(
                context = context,
                compactForUdp = { !isTcpConnected() },
                config = captureConfig,
                preferFrontCamera = preferFront,
                externalSurfaceTexture = externalSurfaceTexture,
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
                config = captureConfig,
                preferFrontCamera = preferFront,
                externalSurfaceTexture = externalSurfaceTexture,
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
        Log.i(TAG, "SeeCameraSession started front=$preferFront avc=${enc != null} file=${See.FILE_NAME}")
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
