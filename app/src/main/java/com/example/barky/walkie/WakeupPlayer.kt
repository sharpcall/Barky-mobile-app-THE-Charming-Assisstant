package com.example.barky.walkie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays a loud alarm-style wakeup for ~10 seconds at max alarm volume.
 * Call [stop] to silence early (e.g. Stop Ringing button).
 */
class WakeupPlayer(
    private val context: Context,
    private val onRingingChanged: ((Boolean) -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var previousAlarmVolume: Int = -1
    @Volatile
    var isRinging: Boolean = false
        private set

    fun start() {
        if (isRinging) return
        isRinging = true
        notifyRinging(true)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            vibrate()

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }

            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tg ->
                fun beepLoop(remaining: Int) {
                    if (!isRinging || remaining <= 0) return
                    tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700)
                    handler.postDelayed({ beepLoop(remaining - 1) }, 900)
                }
                beepLoop(12)
            }

            handler.postDelayed({ stop() }, WAKEUP_MS)
            Log.i(TAG, "Wakeup started")
        } catch (t: Throwable) {
            Log.e(TAG, "Wakeup failed", t)
            stop()
        }
    }

    fun stop() {
        if (!isRinging && mediaPlayer == null && tone == null) return
        isRinging = false
        handler.removeCallbacksAndMessages(null)
        cancelVibrate()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null
        try {
            if (previousAlarmVolume >= 0) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            }
        } catch (_: Exception) {
        }
        previousAlarmVolume = -1
        notifyRinging(false)
        Log.i(TAG, "Wakeup stopped")
    }

    private fun notifyRinging(ringing: Boolean) {
        handler.post { onRingingChanged?.invoke(ringing) }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate() {
        try {
            val pattern = longArrayOf(0, 400, 200, 400, 200, 600, 200, 400)
            val v = vibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibrate failed", t)
        }
    }

    private fun cancelVibrate() {
        try {
            vibrator()?.cancel()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "WakeupPlayer"
        private const val WAKEUP_MS = 12_000L
    }
}
