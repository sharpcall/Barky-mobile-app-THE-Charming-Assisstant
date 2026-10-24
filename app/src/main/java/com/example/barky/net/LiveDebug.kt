package com.example.barky.net

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-demand live debug telemetry stream.
 *
 * Collects timestamped actions, network states, and packet events on the phone
 * and streams them to Firestore `live_debug_stream/{deviceId}` so developers
 * and CLI agents can live-monitor phone actions remotely in real time.
 */
object LiveDebug {
    private const val TAG = "BarkyLiveDebug"
    private val io = Executors.newSingleThreadExecutor()
    private val buffer = ConcurrentLinkedQueue<String>()
    private const val MAX_BUFFER = 60
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var lastFlushMs = 0L
    private val flushPending = AtomicBoolean(false)
    @Volatile
    private var cachedContext: Context? = null
    @Volatile
    private var activeSessionTag: String = "IDLE"
    @Volatile
    private var selectedPeerInfo: String = "None"

    /**
     * Cache the application context on startup.
     */
    fun init(context: Context) {
        cachedContext = context.applicationContext
    }

    /**
     * Update current high-level session state (e.g. "SEE_REQUESTING", "SEE_SERVING", "IDLE").
     */
    fun setSession(session: String) {
        activeSessionTag = session
    }

    /**
     * Update the currently selected peer label.
     */
    fun setSelectedPeer(peer: String) {
        selectedPeerInfo = peer
    }

    /**
     * Log a live debug event.
     * Writes to Logcat, saves to the rolling memory buffer, and flushes to Firestore
     * so that `scripts/live-monitor.mjs` displays the phone's live actions in real time.
     */
    fun emit(tag: String, msg: String, context: Context? = null) {
        val ctx = context?.applicationContext ?: cachedContext
        val timeStr = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$timeStr] [$tag] $msg"
        Log.i(TAG, entry)
        buffer.add(entry)
        while (buffer.size > MAX_BUFFER) {
            buffer.poll()
        }
        if (ctx != null) {
            scheduleFlush(ctx)
        }
    }

    /**
     * Schedule a flush to Firestore with brief debouncing to combine high-frequency bursts.
     */
    private fun scheduleFlush(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFlushMs > 750L) {
            flushAsync(context)
        } else if (flushPending.compareAndSet(false, true)) {
            io.execute {
                try {
                    Thread.sleep(750L)
                } catch (_: InterruptedException) {
                }
                flushPending.set(false)
                flushBlocking(context)
            }
        }
    }

    /**
     * Trigger asynchronous upload of current debug logs to Firestore.
     */
    fun flushAsync(context: Context) {
        io.execute { flushBlocking(context) }
    }

    /**
     * Upload the debug log snapshot to Firestore `live_debug_stream/{deviceId}`.
     */
    private fun flushBlocking(context: Context) {
        lastFlushMs = SystemClock.uptimeMillis()
        if (!FirebasePeerDirectory.isConfigured(context)) return
        try {
            val deviceId = DeviceIdentity.deviceId(context)
            val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            val onWifi = NetworkEndpoints.isWifi(context)
            val localIp = NetworkEndpoints.findLocalIpv4().orEmpty()
            val netStr = if (onWifi) "Wi-Fi ($localIp)" else "Cellular ($localIp)"
            val logs = buffer.toList()
            val (pid, apiKey, dbId) = getFirebaseConfig(context) ?: return
            val docUrl = "https://firestore.googleapis.com/v1/projects/$pid/databases/$dbId/documents/live_debug_stream/$deviceId?key=$apiKey"

            val jsonLogs = JSONArray()
            for (line in logs) {
                jsonLogs.put(JSONObject().put("stringValue", line))
            }

            val fields = JSONObject()
                .put("deviceId", JSONObject().put("stringValue", deviceId))
                .put("deviceModel", JSONObject().put("stringValue", model))
                .put("network", JSONObject().put("stringValue", netStr))
                .put("activeSession", JSONObject().put("stringValue", activeSessionTag))
                .put("selectedPeer", JSONObject().put("stringValue", selectedPeerInfo))
                .put("lastAction", JSONObject().put("stringValue", logs.lastOrNull() ?: "None"))
                .put("updatedAtMs", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
                .put("recentLogs", JSONObject().put("arrayValue", JSONObject().put("values", jsonLogs)))

            val body = JSONObject().put("fields", fields)
            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "flush live_debug_stream HTTP $code")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "flush live_debug_stream failed: ${t.message}")
        }
    }

    private fun getFirebaseConfig(context: Context): Triple<String, String, String>? {
        return try {
            val res = context.resources
            val pkg = context.packageName
            val pid = res.getIdentifier("firebase_project_id", "string", pkg)
            val key = res.getIdentifier("firebase_api_key", "string", pkg)
            if (pid == 0 || key == 0) return null
            val dbId = res.getIdentifier("firebase_database_id", "string", pkg)
            val databaseId = if (dbId != 0) res.getString(dbId).trim().ifBlank { "barkydefault" } else "barkydefault"
            Triple(res.getString(pid).trim(), res.getString(key).trim(), databaseId)
        } catch (_: Throwable) {
            null
        }
    }
}
