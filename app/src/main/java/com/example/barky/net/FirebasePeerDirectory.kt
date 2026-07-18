package com.example.barky.net

import android.content.Context
import android.util.Log
import com.example.barky.BarkyPrefs
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Free-tier Firebase **Firestore REST** directory (no media relay).
 *
 * Requires [com.example.barky.R.string.firebase_project_id] + API key in resources,
 * filled from your Firebase project (see docs/FIREBASE_SETUP.md).
 * If not configured, all calls no-op and log once.
 *
 * Uses REST so the app builds **without** `google-services.json` until you add it.
 * When you add the JSON, you can later switch to the official SDK; the document shape stays the same.
 */
object FirebasePeerDirectory {
    private const val TAG = "FirebasePeerDirectory"
    private const val COLLECTION = "devices"
    private val io = Executors.newSingleThreadExecutor()
    private val warned = AtomicBoolean(false)

    data class Config(val projectId: String, val apiKey: String)

    fun isConfigured(context: Context): Boolean {
        val c = config(context) ?: return false
        return c.projectId.isNotBlank() &&
            !c.projectId.startsWith("YOUR_") &&
            c.apiKey.isNotBlank() &&
            !c.apiKey.startsWith("YOUR_")
    }

    /**
     * Upsert this device’s endpoints (heartbeat). Call from walkie service on a timer
     * and on network changes.
     */
    fun registerAsync(
        context: Context,
        localUdpPort: Int,
        localVideoPort: Int,
        appVersion: String,
        lat: Double? = null,
        lon: Double? = null,
        walkieSocket: java.net.DatagramSocket? = null
    ) {
        if (!isConfigured(context)) {
            if (warned.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Firebase not configured — copy google-services values into " +
                        "res/values/firebase_config.xml (see docs/FIREBASE_SETUP.md)"
                )
            }
            return
        }
        io.execute {
            try {
                registerBlocking(context, localUdpPort, localVideoPort, appVersion, lat, lon, walkieSocket)
            } catch (t: Throwable) {
                Log.w(TAG, "register failed", t)
            }
        }
    }

    fun lookupByDeviceIdAsync(context: Context, deviceId: String, cb: (PeerEndpoint?) -> Unit) {
        if (!isConfigured(context)) {
            cb(null)
            return
        }
        io.execute {
            try {
                cb(lookupByDeviceId(context, deviceId))
            } catch (t: Throwable) {
                Log.w(TAG, "lookup $deviceId", t)
                cb(null)
            }
        }
    }

    fun lookupByPhoneAsync(context: Context, phone: String, cb: (List<PeerEndpoint>) -> Unit) {
        if (!isConfigured(context) || phone.isBlank() || phone == "NoPhoneNumber") {
            cb(emptyList())
            return
        }
        io.execute {
            try {
                cb(lookupByPhone(context, phone))
            } catch (t: Throwable) {
                Log.w(TAG, "lookup phone", t)
                cb(emptyList())
            }
        }
    }

    /** List registered Barky phones (for Contacts page). Excludes self when [selfDeviceId] set. */
    fun listAllAsync(context: Context, selfDeviceId: String?, cb: (List<PeerEndpoint>) -> Unit) {
        if (!isConfigured(context)) {
            cb(emptyList())
            return
        }
        io.execute {
            try {
                val all = listAll(context)
                val filtered = if (selfDeviceId.isNullOrBlank()) all
                else all.filter { it.deviceId != selfDeviceId }
                // Fresh first
                cb(filtered.sortedByDescending { it.updatedAtMs })
            } catch (t: Throwable) {
                Log.w(TAG, "listAll", t)
                cb(emptyList())
            }
        }
    }

    private fun listAll(context: Context): List<PeerEndpoint> {
        val cfg = config(context) ?: return emptyList()
        // Page through collection (pageSize max 300 on REST)
        val out = ArrayList<PeerEndpoint>()
        var pageToken: String? = null
        do {
            val base =
                "https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents/" +
                    "$COLLECTION?pageSize=100&key=${cfg.apiKey}"
            val url = if (pageToken.isNullOrBlank()) base
            else base + "&pageToken=" + java.net.URLEncoder.encode(pageToken, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { BufferedReader(InputStreamReader(it)).readText() }
                .orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "listAll HTTP $code $text")
                break
            }
            val root = JSONObject(text)
            val docs = root.optJSONArray("documents")
            if (docs != null) {
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    parseDocument(doc)?.let { out.add(it) }
                }
            }
            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (!pageToken.isNullOrBlank())
        Log.i(TAG, "listAll count=${out.size}")
        return out
    }

    private fun config(context: Context): Config? {
        return try {
            val res = context.resources
            val pkg = context.packageName
            val pid = res.getIdentifier("firebase_project_id", "string", pkg)
            val key = res.getIdentifier("firebase_api_key", "string", pkg)
            if (pid == 0 || key == 0) return null
            Config(res.getString(pid).trim(), res.getString(key).trim())
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerBlocking(
        context: Context,
        localUdpPort: Int,
        localVideoPort: Int,
        appVersion: String,
        lat: Double?,
        lon: Double?,
        walkieSocket: java.net.DatagramSocket?
    ) {
        val cfg = config(context) ?: return
        val idSnap = DeviceIdentity.snapshot(context, lat, lon)
        val net = NetworkEndpoints.snapshot(context, walkieSocket)
        val body = JSONObject()
            .put("fields", JSONObject()
                .put("deviceId", stringField(idSnap.deviceId))
                .put("phoneNumber", stringField(idSnap.phoneNumber))
                .put("displayName", stringField("${idSnap.manufacturer} ${idSnap.model}"))
                .put("androidId", stringField(idSnap.androidId))
                .put("localIp", stringField(net.localIp ?: ""))
                .put("localUdpPort", intField(localUdpPort))
                .put("localVideoPort", intField(localVideoPort))
                .put("publicIp", stringField(net.publicIp ?: ""))
                .put("publicUdpPort", intField(net.publicUdpPort))
                .put("wifiSsid", stringField(net.wifiSsid ?: ""))
                .put("onWifi", boolField(net.onWifi))
                .put("appVersion", stringField(appVersion))
                .put("updatedAtMs", intField(System.currentTimeMillis()))
                .apply {
                    if (lat != null) put("lat", doubleField(lat))
                    if (lon != null) put("lon", doubleField(lon))
                }
            )
        val url =
            "https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents/" +
                "$COLLECTION/${idSnap.deviceId}?key=${cfg.apiKey}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 12_000
            readTimeout = 12_000
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        if (code in 200..299) {
            BarkyPrefs.prefs(context).edit()
                .putString("last_firebase_device_id", idSnap.deviceId)
                .putLong("last_firebase_register_ms", System.currentTimeMillis())
                .apply()
            Log.i(
                TAG,
                "registered id=${idSnap.deviceId} local=${net.localIp}:$localUdpPort " +
                    "public=${net.publicIp}:${net.publicUdpPort} wifi=${net.onWifi}"
            )
        } else {
            Log.w(TAG, "register HTTP $code $resp")
        }
        conn.disconnect()
    }

    private fun lookupByDeviceId(context: Context, deviceId: String): PeerEndpoint? {
        val cfg = config(context) ?: return null
        val url =
            "https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents/" +
                "$COLLECTION/$deviceId?key=${cfg.apiKey}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { BufferedReader(InputStreamReader(it)).readText() }
            .orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "lookup HTTP $code $text")
            return null
        }
        return parseDocument(JSONObject(text))
    }

    private fun lookupByPhone(context: Context, phone: String): List<PeerEndpoint> {
        // Structured query (requires API key + open rules or OAuth). Simple: list not available on REST free without query.
        // For free bring-up we document phone lookup via client-side filter after knowing deviceId.
        // Optional runQuery endpoint:
        val cfg = config(context) ?: return emptyList()
        val query = JSONObject()
            .put(
                "structuredQuery",
                JSONObject()
                    .put("from", org.json.JSONArray().put(JSONObject().put("collectionId", COLLECTION)))
                    .put(
                        "where",
                        JSONObject().put(
                            "fieldFilter",
                            JSONObject()
                                .put("field", JSONObject().put("fieldPath", "phoneNumber"))
                                .put("op", "EQUAL")
                                .put("value", stringField(phone))
                        )
                    )
                    .put("limit", 10)
            )
        val url =
            "https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents:runQuery?key=${cfg.apiKey}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 12_000
            readTimeout = 12_000
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(query.toString()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { BufferedReader(InputStreamReader(it)).readText() }
            .orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "phone query HTTP $code $text")
            return emptyList()
        }
        // runQuery returns a JSON array of {document:{...}}
        val out = ArrayList<PeerEndpoint>()
        try {
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val doc = o.optJSONObject("document") ?: continue
                parseDocument(doc)?.let { out.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse phone query", t)
        }
        return out
    }

    private fun parseDocument(doc: JSONObject): PeerEndpoint? {
        val fields = doc.optJSONObject("fields") ?: return null
        fun s(name: String): String = fields.optJSONObject(name)?.optString("stringValue").orEmpty()
        fun i(name: String): Long =
            fields.optJSONObject(name)?.optString("integerValue")?.toLongOrNull()
                ?: fields.optJSONObject(name)?.optDouble("doubleValue")?.toLong()
                ?: 0L
        fun d(name: String): Double? =
            fields.optJSONObject(name)?.optDouble("doubleValue")?.takeIf { !it.isNaN() }
        val id = s("deviceId").ifBlank { return null }
        return PeerEndpoint(
            deviceId = id,
            phoneNumber = s("phoneNumber").ifBlank { "NoPhoneNumber" },
            displayName = s("displayName"),
            localIp = s("localIp").ifBlank { null },
            localUdpPort = i("localUdpPort").toInt(),
            localVideoPort = i("localVideoPort").toInt(),
            publicIp = s("publicIp").ifBlank { null },
            publicUdpPort = i("publicUdpPort").toInt(),
            wifiHint = s("wifiSsid").ifBlank { null },
            lat = d("lat"),
            lon = d("lon"),
            updatedAtMs = i("updatedAtMs"),
            appVersion = s("appVersion")
        )
    }

    private fun stringField(v: String) = JSONObject().put("stringValue", v)
    private fun intField(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun intField(v: Int) = intField(v.toLong())
    private fun doubleField(v: Double) = JSONObject().put("doubleValue", v)
    private fun boolField(v: Boolean) = JSONObject().put("booleanValue", v)
}
