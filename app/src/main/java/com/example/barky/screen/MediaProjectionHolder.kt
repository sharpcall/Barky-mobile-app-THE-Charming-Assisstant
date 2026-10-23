package com.example.barky.screen

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a long-lived [MediaProjection] after the user accepts the system dialog once
 * (superadmin “auto screen share”). Later SCRN requests from contacts reuse it
 * **without** another prompt — Android still requires that first consent.
 *
 * Absolute silent capture with zero system UI is not allowed for normal apps.
 */
object MediaProjectionHolder {
    private const val TAG = "MediaProjectionHolder"
    private val main = Handler(Looper.getMainLooper())
    private val held = AtomicReference<MediaProjection?>(null)

    fun set(projection: MediaProjection) {
        held.getAndSet(projection)?.let { old ->
            try {
                old.stop()
            } catch (_: Throwable) {
            }
        }
        try {
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "projection stopped by system")
                        held.compareAndSet(projection, null)
                    }
                },
                main
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerCallback", t)
        }
        held.set(projection)
        Log.i(TAG, "projection ready for auto screen share")
    }

    fun get(): MediaProjection? = held.get()

    fun isReady(): Boolean = held.get() != null

    fun clear() {
        held.getAndSet(null)?.let { p ->
            try {
                p.stop()
            } catch (_: Throwable) {
            }
        }
    }
}
