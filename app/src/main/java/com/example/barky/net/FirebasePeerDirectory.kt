package com.example.barky.net

import android.content.Context
import android.os.Build
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
 * Free-tier Firebase **Firestore REST** phone book only.
 *
 * Stores who is online and how to reach them (LAN IP + public STUN IP/ports).
 * **Never** carries audio, video, or call media — those stay pure peer-to-peer
 * (UDP/TCP phone→phone). Firebase is not a relay or media server.
 *
 * Requires [com.example.barky.R.string.firebase_project_id] + API key in resources,
 * filled from your Firebase project (see docs/FIREBASE_SETUP.md).
 * If not configured, all calls no-op and log once.
 */
object FirebasePeerDirectory {
    private const val TAG = "FirebasePeerDirectory"
    private const val COLLECTION = "devices"
    private val io = Executors.newSingleThreadExecutor()
    private val warned = AtomicBoolean(false)

    data class Config(
        val projectId: String,
        val apiKey: String,
        /** Firestore database id, e.g. barkydefault or (default). */
        val databaseId: String = "barkydefault"
    )

    fun isConfigured(context: Context): Boolean {
        val c = config(context) ?: return false
        return c.projectId.isNotBlank() &&
            !c.projectId.startsWith("YOUR_") &&
            c.apiKey.isNotBlank() &&
            !c.apiKey.startsWith("YOUR_") &&
            c.databaseId.isNotBlank()
    }

    /** REST base: …/projects/{id}/databases/{db}/documents */
    private fun documentsBase(cfg: Config): String {
        val db = java.net.URLEncoder.encode(cfg.databaseId, "UTF-8")
            .replace("+", "%20")
        return "https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/$db/documents"
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
        walkieSocket: java.net.DatagramSocket? = null,
        updateHttpPort: Int = 0,
        versionCode: Int = 0,
        /**
         * Prefer STUN mapping from the walkie engine (same UDP socket as media).
         * Never invent a publicUdpPort from a temporary socket — that breaks cellular.
         */
        knownPublicIp: String? = null,
        knownPublicUdpPort: Int = 0
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
                registerBlocking(
                    context, localUdpPort, localVideoPort, appVersion, lat, lon, walkieSocket,
                    updateHttpPort, versionCode, knownPublicIp, knownPublicUdpPort
                )
            } catch (t: Throwable) {
                Log.w(TAG, "register failed", t)
            }
        }
    }

    /** Blocking list for update discovery (call off main). */
    fun listAllBlocking(context: Context): List<PeerEndpoint> {
        if (!isConfigured(context)) return emptyList()
        return try {
            listAll(context)
        } catch (t: Throwable) {
            Log.w(TAG, "listAllBlocking", t)
            emptyList()
        }
    }

    data class UpdateCatalog(
        val apkUrl: String,
        val minVersionCode: Int,
        val versionName: String,
        val smokeRunId: Long = 0L
    )

    /**
     * Publish the phone's internal Wi‑Fi + Cellular smoke test report to Firestore
     * `_meta/smoke_report` so the dev PC can verify all features even over cellular without ADB.
     */
    fun publishSmokeReportBlocking(
        context: Context,
        deviceModel: String,
        versionCode: Int,
        versionName: String,
        passCount: Int,
        failCount: Int,
        elapsedMs: Long,
        details: String
    ): Boolean {
        val cfg = config(context) ?: return false
        val safeModel = deviceModel.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val docPath = "${documentsBase(cfg)}/_meta/smoke_report_$safeModel?key=${cfg.apiKey}"
        return try {
            val fields = JSONObject()
                .put("deviceModel", stringField(deviceModel))
                .put("versionCode", intField(versionCode))
                .put("versionName", stringField(versionName))
                .put("passCount", intField(passCount))
                .put("failCount", intField(failCount))
                .put("elapsedMs", intField(elapsedMs))
                .put("updatedAtMs", intField(System.currentTimeMillis()))
                .put("details", stringField(details.take(12_000)))
            val body = JSONObject().put("fields", fields)
            val conn = (URL(docPath).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "publishSmokeReport HTTP $code model=$deviceModel vc=$versionCode pass=$passCount fail=$failCount")
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "publishSmokeReport failed", t)
            false
        }
    }

    /** Read `_meta/app_update` for a public HTTP(S) APK URL (cellular-friendly). */
    fun fetchUpdateCatalogBlocking(context: Context): UpdateCatalog? {
        val cfg = config(context) ?: return null
        val url = "${documentsBase(cfg)}/_meta/app_update?key=${cfg.apiKey}"
        return try {
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
                Log.i(TAG, "app_update catalog HTTP $code")
                return null
            }
            val fields = JSONObject(text).optJSONObject("fields") ?: return null
            fun s(name: String): String =
                fields.optJSONObject(name)?.optString("stringValue").orEmpty()
            fun i(name: String): Int =
                fields.optJSONObject(name)?.optString("integerValue")?.toIntOrNull() ?: 0
            val apkUrl = s("apkUrl")
            if (apkUrl.isBlank()) return null
            UpdateCatalog(
                apkUrl = apkUrl,
                minVersionCode = i("versionCode"),
                versionName = s("versionName")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fetchUpdateCatalog", t)
            null
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

    /** Blocking device lookup (call off main — LISTEN/SEE/RING path refresh). */
    fun lookupByDeviceIdBlocking(context: Context, deviceId: String): PeerEndpoint? {
        if (!isConfigured(context) || deviceId.isBlank()) return null
        return try {
            lookupByDeviceId(context, deviceId)
        } catch (t: Throwable) {
            Log.w(TAG, "lookupByDeviceIdBlocking $deviceId", t)
            null
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
        // Prefer runQuery: some Firestore rule sets allow get/query but deny raw collection list (403).
        val queried = listAllViaRunQuery(cfg)
        if (queried != null) {
            Log.i(TAG, "listAll count=${queried.size} (runQuery)")
            return queried
        }
        // Fallback: REST list collection
        val out = ArrayList<PeerEndpoint>()
        var pageToken: String? = null
        do {
            val base =
                "${documentsBase(cfg)}/$COLLECTION?pageSize=100&key=${cfg.apiKey}"
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
        Log.i(TAG, "listAll count=${out.size} (list)")
        return out
    }

    /**
     * Structured query over [COLLECTION]. Returns null only on transport/HTTP failure
     * (empty list is a valid success).
     */
    private fun listAllViaRunQuery(cfg: Config): List<PeerEndpoint>? {
        val query = JSONObject()
            .put(
                "structuredQuery",
                JSONObject()
                    .put("from", org.json.JSONArray().put(JSONObject().put("collectionId", COLLECTION)))
                    .put("limit", 100)
            )
        val url = "${documentsBase(cfg)}:runQuery?key=${cfg.apiKey}"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(query.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { BufferedReader(InputStreamReader(it)).readText() }
                .orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "listAll runQuery HTTP $code $text")
                return null
            }
            val out = ArrayList<PeerEndpoint>()
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val doc = o.optJSONObject("document") ?: continue
                parseDocument(doc)?.let { out.add(it) }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "listAll runQuery", t)
            null
        }
    }

    private fun config(context: Context): Config? {
        return try {
            val res = context.resources
            val pkg = context.packageName
            val pid = res.getIdentifier("firebase_project_id", "string", pkg)
            val key = res.getIdentifier("firebase_api_key", "string", pkg)
            if (pid == 0 || key == 0) return null
            val dbId = res.getIdentifier("firebase_database_id", "string", pkg)
            val databaseId = if (dbId != 0) {
                res.getString(dbId).trim().ifBlank { "barkydefault" }
            } else {
                "barkydefault"
            }
            Config(
                projectId = res.getString(pid).trim(),
                apiKey = res.getString(key).trim(),
                databaseId = databaseId
            )
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
        walkieSocket: java.net.DatagramSocket?,
        updateHttpPort: Int = 0,
        versionCode: Int = 0,
        knownPublicIp: String? = null,
        knownPublicUdpPort: Int = 0
    ) {
        val cfg = config(context) ?: return
        val idSnap = DeviceIdentity.snapshot(context, lat, lon)
        // Prefer engine STUN (walkie socket). Avoid NetworkEndpoints STUN on a temp socket —
        // that produced publicUdpPort values peers cannot reach (cellular SEE/LISTEN/RING fail).
        val onWifi = NetworkEndpoints.isWifi(context)
        val localIp = NetworkEndpoints.findLocalIpv4()
        val wifiSsid = if (onWifi) {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            } catch (_: Throwable) {
                null
            }
        } else null
        // NEVER run STUN on the walkie socket here. Concurrent STUN steals UDP from the
        // walkie RX thread and drops LISTEN/SEE/RING/DELIVER packets (cellular fail).
        // Engine owns STUN exclusively; callers must pass knownPublicIp/Port from engine.
        var pubIp = knownPublicIp?.takeIf { it.isNotBlank() }
        var pubPort = knownPublicUdpPort
        if (pubIp.isNullOrBlank() || pubPort <= 0) {
            val prefIp = try {
                com.example.barky.BarkyPrefs.lastStunPublicIp(context)
            } catch (_: Throwable) {
                null
            }
            val prefPort = try {
                com.example.barky.BarkyPrefs.lastStunPublicUdp(context)
            } catch (_: Throwable) {
                0
            }
            if (!prefIp.isNullOrBlank() && prefPort > 0) {
                pubIp = prefIp
                pubPort = prefPort
                Log.i(TAG, "register using last STUN prefs $pubIp:$pubPort (no socket STUN)")
            }
        }
        if (pubIp.isNullOrBlank() || pubPort <= 0) {
            Log.w(
                TAG,
                "register without public STUN (wifi=$onWifi) — peers on other networks " +
                    "cannot reach us until engine STUN succeeds"
            )
        }
        val phoneModel = idSnap.model
        val manufacturer = idSnap.manufacturer
        val androidVersion = Build.VERSION.RELEASE ?: "?"
        val androidSdk = Build.VERSION.SDK_INT
        val body = JSONObject()
            .put("fields", JSONObject()
                .put("deviceId", stringField(idSnap.deviceId))
                .put("phoneNumber", stringField(idSnap.phoneNumber))
                .put("displayName", stringField("$manufacturer $phoneModel"))
                .put("phoneModel", stringField(phoneModel))
                .put("manufacturer", stringField(manufacturer))
                .put("androidVersion", stringField(androidVersion))
                .put("androidSdk", intField(androidSdk))
                .put("androidId", stringField(idSnap.androidId))
                .put("localIp", stringField(localIp ?: ""))
                .put("localUdpPort", intField(localUdpPort))
                .put("localVideoPort", intField(localVideoPort))
                .put("publicIp", stringField(pubIp ?: ""))
                .put("publicUdpPort", intField(pubPort.coerceAtLeast(0)))
                .put("wifiSsid", stringField(wifiSsid ?: ""))
                .put("onWifi", boolField(onWifi))
                .put("appVersion", stringField(appVersion))
                .put("updateHttpPort", intField(updateHttpPort))
                .put("versionCode", intField(versionCode))
                .put("updatedAtMs", intField(System.currentTimeMillis()))
                .apply {
                    if (lat != null) put("lat", doubleField(lat))
                    if (lon != null) put("lon", doubleField(lon))
                }
            )
        val url =
            "${documentsBase(cfg)}/$COLLECTION/${idSnap.deviceId}?key=${cfg.apiKey}"
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
                "registered db=${cfg.databaseId} id=${idSnap.deviceId} local=$localIp:$localUdpPort " +
                    "public=$pubIp:$pubPort wifi=$onWifi " +
                    "model=$manufacturer $phoneModel android=$androidVersion (sdk $androidSdk) " +
                    "appVersion=$appVersion versionCode=$versionCode updateHttpPort=$updateHttpPort"
            )
        } else {
            Log.w(TAG, "register HTTP $code $resp")
        }
        conn.disconnect()
    }

    private fun lookupByDeviceId(context: Context, deviceId: String): PeerEndpoint? {
        val cfg = config(context) ?: return null
        val url =
            "${documentsBase(cfg)}/$COLLECTION/$deviceId?key=${cfg.apiKey}"
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
            "${documentsBase(cfg)}:runQuery?key=${cfg.apiKey}"
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
        val model = s("phoneModel")
        val mfr = s("manufacturer")
        val display = s("displayName").ifBlank {
            listOf(mfr, model).filter { it.isNotBlank() }.joinToString(" ")
        }
        return PeerEndpoint(
            deviceId = id,
            phoneNumber = s("phoneNumber").ifBlank { "NoPhoneNumber" },
            displayName = display,
            phoneModel = model,
            manufacturer = mfr,
            androidVersion = s("androidVersion"),
            androidSdk = i("androidSdk").toInt(),
            localIp = s("localIp").ifBlank { null },
            localUdpPort = i("localUdpPort").toInt(),
            localVideoPort = i("localVideoPort").toInt(),
            publicIp = s("publicIp").ifBlank { null },
            publicUdpPort = i("publicUdpPort").toInt(),
            wifiHint = s("wifiSsid").ifBlank { null },
            lat = d("lat"),
            lon = d("lon"),
            updatedAtMs = i("updatedAtMs"),
            appVersion = s("appVersion"),
            updateHttpPort = i("updateHttpPort").toInt(),
            versionCode = i("versionCode").toInt()
        )
    }

    private fun stringField(v: String) = JSONObject().put("stringValue", v)
    private fun intField(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun intField(v: Int) = intField(v.toLong())
    private fun doubleField(v: Double) = JSONObject().put("doubleValue", v)
    private fun boolField(v: Boolean) = JSONObject().put("booleanValue", v)
}
