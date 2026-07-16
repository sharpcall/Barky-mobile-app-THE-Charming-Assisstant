package com.example.barky.walkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.barky.BarkyPrefs
import com.example.barky.MainActivity
import com.example.barky.R

/**
 * Foreground service that keeps NSD + UDP video walkie alive.
 */
class WalkieTalkieService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }

    private val binder = LocalBinder()
    private var engine: WalkieTalkieEngine? = null
    /** True only when the user/app explicitly requested stop — skip auto-respawn. */
    private var intentionalStop = false

    @Volatile var statusText: String = "Starting…"
        private set
    @Volatile var peers: List<WalkiePeer> = emptyList()
        private set
    @Volatile var isTalking: Boolean = false
        private set
    @Volatile var isReceiving: Boolean = false
        private set
    @Volatile var isChannelBusy: Boolean = false
        private set
    @Volatile var isWakeupRinging: Boolean = false
        private set
    @Volatile var isRemoteWakeupActive: Boolean = false
        private set
    @Volatile var isReplaying: Boolean = false
        private set
    @Volatile var hasLastMessage: Boolean = false
        private set
    @Volatile var lastVideoDebug: String = "Video: idle (codec=MJPEG / platform JPEG)"
        private set
    @Volatile var internetInviteCode: String? = null
        private set

    private val uiListeners = mutableSetOf<UiListener>()

    interface UiListener {
        fun onStatus(message: String)
        fun onPeersChanged(peers: List<WalkiePeer>)
        fun onTalkingChanged(talking: Boolean)
        fun onReceivingChanged(receiving: Boolean)
        fun onChannelBusyChanged(busy: Boolean)
        fun onVideoFrame(bitmap: Bitmap, live: Boolean)
        fun onLastMessageChanged(hasMessage: Boolean)
        fun onWakeupReceived(from: String)
        fun onWakeupRingingChanged(ringing: Boolean)
        fun onRemoteWakeupActive(active: Boolean)
        fun onReplayStateChanged(replaying: Boolean)
        fun onError(message: String)
        fun onVideoDebug(message: String)
        fun onInternetInviteChanged(inviteCode: String?)
    }

    fun addListener(l: UiListener) {
        uiListeners.add(l)
        l.onStatus(statusText)
        l.onPeersChanged(peers)
        l.onTalkingChanged(isTalking)
        l.onReceivingChanged(isReceiving)
        l.onChannelBusyChanged(isChannelBusy)
        l.onLastMessageChanged(hasLastMessage)
        l.onWakeupRingingChanged(isWakeupRinging)
        l.onRemoteWakeupActive(isRemoteWakeupActive)
        l.onReplayStateChanged(isReplaying)
        l.onVideoDebug(lastVideoDebug)
        l.onInternetInviteChanged(internetInviteCode)
    }

    fun removeListener(l: UiListener) {
        uiListeners.remove(l)
    }

    fun selectPeer(peer: WalkiePeer?) = engine?.selectPeer(peer)
    fun startTalking() = engine?.startTalking()
    fun stopTalking() = engine?.stopTalking()
    fun sendWakeup() = engine?.sendWakeup()
    fun stopWakeupRinging() = engine?.stopWakeupRinging()
    fun replayLastMessage() = engine?.replayLastMessage()
    fun stopReplay() = engine?.stopReplay()
    fun applyNetworkMode() = engine?.applyNetworkMode()
    fun connectViaInvite(code: String): Boolean = engine?.connectViaInvite(code) == true
    fun getMyInviteCode(): String? = engine?.getMyInviteCode() ?: internetInviteCode
    fun switchCamera(): Boolean = engine?.switchCamera() == true
    fun isCameraFrontPreferred(): Boolean = engine?.isCameraFrontPreferred()
        ?: com.example.barky.BarkyPrefs.isCameraFront(this)
    fun startLocalPreview() = engine?.startLocalPreview()
    fun stopLocalPreview() = engine?.stopLocalPreview()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startAsForeground("Video walkie ready on Wi‑Fi")
        engine = WalkieTalkieEngine(this, object : WalkieTalkieEngine.Listener {
            override fun onStatus(message: String) {
                statusText = message
                updateNotification(message)
                uiListeners.forEach { it.onStatus(message) }
            }

            override fun onPeersChanged(newPeers: List<WalkiePeer>) {
                peers = newPeers
                uiListeners.forEach { it.onPeersChanged(newPeers) }
            }

            override fun onTalkingChanged(talking: Boolean) {
                isTalking = talking
                uiListeners.forEach { it.onTalkingChanged(talking) }
            }

            override fun onReceivingChanged(receiving: Boolean) {
                isReceiving = receiving
                uiListeners.forEach { it.onReceivingChanged(receiving) }
            }

            override fun onChannelBusyChanged(busy: Boolean) {
                isChannelBusy = busy
                uiListeners.forEach { it.onChannelBusyChanged(busy) }
            }

            override fun onVideoFrame(bitmap: Bitmap, live: Boolean) {
                uiListeners.forEach { it.onVideoFrame(bitmap, live) }
            }

            override fun onLastMessageChanged(hasMessage: Boolean) {
                hasLastMessage = hasMessage
                uiListeners.forEach { it.onLastMessageChanged(hasMessage) }
            }

            override fun onWakeupReceived(from: String) {
                showWakeupNotification(from)
                uiListeners.forEach { it.onWakeupReceived(from) }
            }

            override fun onWakeupRingingChanged(ringing: Boolean) {
                isWakeupRinging = ringing
                if (!ringing) {
                    getSystemService(NotificationManager::class.java)?.cancel(WAKEUP_NOTIF_ID)
                }
                uiListeners.forEach { it.onWakeupRingingChanged(ringing) }
            }

            override fun onRemoteWakeupActive(active: Boolean) {
                isRemoteWakeupActive = active
                uiListeners.forEach { it.onRemoteWakeupActive(active) }
            }

            override fun onReplayStateChanged(replaying: Boolean) {
                isReplaying = replaying
                uiListeners.forEach { it.onReplayStateChanged(replaying) }
            }

            override fun onError(message: String) {
                statusText = message
                updateNotification(message)
                uiListeners.forEach { it.onError(message) }
            }

            override fun onVideoDebug(message: String) {
                lastVideoDebug = message
                uiListeners.forEach { it.onVideoDebug(message) }
            }

            override fun onInternetInviteChanged(inviteCode: String?) {
                internetInviteCode = inviteCode
                uiListeners.forEach { it.onInternetInviteChanged(inviteCode) }
            }
        })
        engine?.start()
        Log.i(TAG, "WalkieTalkieService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalStop = true
                stopSelf()
                return START_NOT_STICKY
            }
            // adb automation (no UI coordinates needed):
            // am start-foreground-service -n com.example.barky/.walkie.WalkieTalkieService -a com.example.barky.TEST_TALK_START
            ACTION_TEST_TALK_START -> {
                Log.i(TAG, "TEST_TALK_START via adb")
                engine?.startTalking()
            }
            ACTION_TEST_TALK_STOP -> {
                Log.i(TAG, "TEST_TALK_STOP via adb")
                engine?.stopTalking()
            }
            ACTION_TEST_REPLAY -> {
                Log.i(TAG, "TEST_REPLAY via adb")
                engine?.replayLastMessage()
            }
            ACTION_TEST_WAKEUP -> {
                Log.i(TAG, "TEST_WAKEUP via adb")
                engine?.sendWakeup()
            }
            ACTION_SET_BEST_QUALITY -> {
                val on = intent.getBooleanExtra(EXTRA_BEST_QUALITY, true)
                BarkyPrefs.setBestQualityVideo(this, on)
                Log.i(TAG, "SET_BEST_QUALITY=$on via adb")
            }
            ACTION_APPLY_NETWORK_MODE -> {
                Log.i(TAG, "APPLY_NETWORK_MODE via intent")
                engine?.applyNetworkMode()
            }
            ACTION_CONNECT_INVITE -> {
                val code = intent.getStringExtra(EXTRA_INVITE_CODE).orEmpty()
                val ok = engine?.connectViaInvite(code) == true
                Log.i(TAG, "CONNECT_INVITE ok=$ok codeLen=${code.length}")
            }
        }
        // START_STICKY: system restarts service after crash / low-memory kill
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiped away from recents — keep walkie alive
        if (!intentionalStop) {
            Log.i(TAG, "Task removed — restarting walkie service")
            com.example.barky.BarkyApp.scheduleServiceRestart(this, delayMs = 500L)
        }
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        uiListeners.clear()
        super.onDestroy()
        Log.i(TAG, "WalkieTalkieService destroyed")
        if (!intentionalStop) {
            com.example.barky.BarkyApp.scheduleServiceRestart(this, delayMs = 1_000L)
        }
        intentionalStop = false
    }

    private fun startAsForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } catch (_: Throwable) {
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_WALKIE)
            .setContentTitle(getString(R.string.walkie_notif_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showWakeupNotification(from: String) {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_WAKEUP, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_WAKEUP)
            .setContentTitle(getString(R.string.walkie_wakeup_title))
            .setContentText(getString(R.string.walkie_wakeup_body, from))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(WAKEUP_NOTIF_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WALKIE,
                getString(R.string.walkie_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKEUP,
                getString(R.string.walkie_wakeup_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.walkie_wakeup_channel_desc)
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    companion object {
        private const val TAG = "WalkieTalkieService"
        private const val NOTIF_ID = 42
        private const val WAKEUP_NOTIF_ID = 43
        private const val CHANNEL_WALKIE = "barky_walkie"
        private const val CHANNEL_WAKEUP = "barky_wakeup"
        const val ACTION_STOP = "com.example.barky.walkie.STOP"
        const val ACTION_TEST_TALK_START = "com.example.barky.TEST_TALK_START"
        const val ACTION_TEST_TALK_STOP = "com.example.barky.TEST_TALK_STOP"
        const val ACTION_TEST_REPLAY = "com.example.barky.TEST_REPLAY"
        const val ACTION_TEST_WAKEUP = "com.example.barky.TEST_WAKEUP"
        const val ACTION_SET_BEST_QUALITY = "com.example.barky.SET_BEST_QUALITY"
        const val ACTION_APPLY_NETWORK_MODE = "com.example.barky.APPLY_NETWORK_MODE"
        const val ACTION_CONNECT_INVITE = "com.example.barky.CONNECT_INVITE"
        const val EXTRA_BEST_QUALITY = "best"
        const val EXTRA_INVITE_CODE = "invite_code"
        const val EXTRA_WAKEUP = "extra_wakeup"

        fun start(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun applyNetworkMode(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java)
                .setAction(ACTION_APPLY_NETWORK_MODE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkieTalkieService::class.java))
        }

        fun testTalkStart(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java).setAction(ACTION_TEST_TALK_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun testTalkStop(context: Context) {
            val i = Intent(context, WalkieTalkieService::class.java).setAction(ACTION_TEST_TALK_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
