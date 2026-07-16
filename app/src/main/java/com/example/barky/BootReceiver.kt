package com.example.barky

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.barky.walkie.WalkieTalkieService

/**
 * Starts the walkie foreground service on boot / package replace.
 *
 * [Intent.ACTION_LOCKED_BOOT_COMPLETED] can fire before the user enters PIN
 * (Direct Boot). Full camera/mic may only work after unlock, but the service
 * is already alive and will keep advertising on Wi‑Fi when network is ready.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Boot/start event: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                try {
                    WalkieTalkieService.start(context.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start walkie on $action", t)
                    BarkyApp.scheduleServiceRestart(context, delayMs = 3_000L)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BarkyBoot"
    }
}
