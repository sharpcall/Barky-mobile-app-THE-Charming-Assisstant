package com.example.barky

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.barky.walkie.WalkieTalkieService

/**
 * Ensures the walkie service is brought up with the process and re-scheduled after crashes.
 */
class BarkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught in ${thread.name} — scheduling walkie restart", error)
            scheduleServiceRestart(this, delayMs = 800L)
            previous?.uncaughtException(thread, error)
        }
        // Bring walkie up whenever our process is created (boot, crash respawn, user open)
        try {
            WalkieTalkieService.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Auto-start walkie on process create failed", t)
            scheduleServiceRestart(this, delayMs = 2_000L)
        }
    }

    companion object {
        private const val TAG = "BarkyApp"

        fun scheduleServiceRestart(context: Context, delayMs: Long = 1_000L) {
            try {
                val app = context.applicationContext
                val intent = Intent(app, WalkieTalkieService::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getForegroundService(app, 99, intent, flags)
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(200L),
                    pi
                )
                Log.i(TAG, "Scheduled walkie restart in ${delayMs}ms")
            } catch (t: Throwable) {
                Log.w(TAG, "scheduleServiceRestart failed", t)
            }
        }
    }
}
