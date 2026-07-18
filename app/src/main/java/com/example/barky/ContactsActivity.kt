package com.example.barky

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.barky.net.DeviceIdentity
import com.example.barky.net.FirebasePeerDirectory
import com.example.barky.net.NetworkEndpoints
import com.example.barky.net.PeerEndpoint
import com.example.barky.walkie.WalkiePeer
import com.example.barky.walkie.WalkieTalkieService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit

/**
 * Contacts = real Barky phones only.
 *
 * Rules:
 * - One row per **deviceId** (fallback: host IP). Never key only by public IP —
 *   home NAT often shares one public address across all phones.
 * - Prefer private LAN IP when both sides are on the same subnet.
 * - Seed/template Firebase docs never shown.
 * - Friendly titles (model names), not NSD junk like "Pixel8-2050-v45851".
 */
class ContactsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var refresh: MaterialButton
    private var service: WalkieTalkieService? = null
    private var bound = false
    private val rows = ArrayList<ContactRow>()
    private lateinit var adapter: Adapter

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WalkieTalkieService.LocalBinder).getService()
            bound = true
            loadContacts()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    data class ContactRow(
        val title: String,
        val subtitle: String,
        val host: String,
        val udpPort: Int,
        val videoPort: Int,
        val source: String,
        val deviceId: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setNavigationIconTint(getColor(R.color.barky_on_surface))

        status = findViewById(R.id.contacts_status)
        list = findViewById(R.id.contacts_list)
        refresh = findViewById(R.id.contacts_refresh)
        adapter = Adapter(
            rows,
            onListen = { row -> act(row, Act.LISTEN) },
            onSee = { row -> act(row, Act.SEE) },
            onScrn = { row -> act(row, Act.SCRN) },
            onCall = { row -> act(row, Act.CALL) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        refresh.setOnClickListener { loadContacts() }
        WalkieTalkieService.start(this)
        bindService(Intent(this, WalkieTalkieService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            try {
                unbindService(conn)
            } catch (_: Exception) {
            }
            bound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private enum class Act { LISTEN, SEE, SCRN, CALL }

    private fun act(row: ContactRow, act: Act) {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, R.string.contacts_service_wait, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = svc.adoptDirectoryPeer(
            row.title,
            row.host,
            row.udpPort,
            row.videoPort,
            row.deviceId
        )
        if (!ok) {
            Toast.makeText(this, R.string.contacts_select_fail, Toast.LENGTH_SHORT).show()
            return
        }
        when (act) {
            Act.LISTEN -> {
                svc.startListenRemote()
                Toast.makeText(this, getString(R.string.contacts_started_listen, row.title), Toast.LENGTH_SHORT).show()
            }
            Act.SEE -> {
                svc.startSeeRemote()
                Toast.makeText(this, getString(R.string.contacts_started_see, row.title), Toast.LENGTH_SHORT).show()
            }
            Act.SCRN -> {
                svc.startScreenRemote()
                Toast.makeText(this, getString(R.string.contacts_started_scrn, row.title), Toast.LENGTH_SHORT).show()
            }
            Act.CALL -> {
                Toast.makeText(this, getString(R.string.contacts_selected_call, row.title), Toast.LENGTH_LONG).show()
            }
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun loadContacts() {
        status.setText(R.string.contacts_loading)
        val selfId = DeviceIdentity.deviceId(this)
        val selfNet = NetworkEndpoints.snapshot(this)
        val selfIp = selfNet.localIp
        // Key = deviceId if known, else "host:$ip" — never public-IP-only
        val byKey = LinkedHashMap<String, ContactRow>()
        val hostClaimed = HashSet<String>()

        fun keyOf(deviceId: String?, host: String): String {
            val id = deviceId?.takeIf { it.isNotBlank() && !it.startsWith("_") }
            return id ?: "host:$host"
        }

        fun isUglyTitle(t: String): Boolean {
            if (t.isBlank()) return true
            if (t.startsWith("udp-") || t.startsWith("path-") || t.startsWith("inet-")) return true
            if (t.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return true
            // NSD: Pixel8-2050-v45851
            if (t.contains(Regex("-v\\d{2,5}$"))) return true
            if (t.matches(Regex("^[A-Za-z0-9]+-\\d{4}$"))) return true
            return false
        }

        fun humanTitle(raw: String, host: String, fallbackModel: String = ""): String {
            var s = raw.trim()
                .removePrefix("Barky-")
                .removePrefix("barky-")
            s = s.replace(Regex("-v\\d{2,5}$"), "")
            // Drop random 4-digit NSD suffix (Pixel8-2050 → Pixel8)
            s = s.replace(Regex("-\\d{4}$"), "")
            if (isUglyTitle(s) || s.isBlank()) {
                if (fallbackModel.isNotBlank()) return fallbackModel
                return host
            }
            return s
        }

        fun pickBetter(a: ContactRow, b: ContactRow): ContactRow {
            val preferB =
                (b.deviceId != null && a.deviceId == null) ||
                    (b.source == "Firebase" && a.source != "Firebase") ||
                    (isUglyTitle(a.title) && !isUglyTitle(b.title)) ||
                    (PeerEndpoint.isPrivateIp(b.host) && !PeerEndpoint.isPrivateIp(a.host))
            val prefer = if (preferB) b else a
            val other = if (prefer === b) a else b
            val host = when {
                PeerEndpoint.isPrivateIp(prefer.host) -> prefer.host
                PeerEndpoint.isPrivateIp(other.host) -> other.host
                else -> prefer.host
            }
            val title = when {
                !isUglyTitle(prefer.title) -> prefer.title
                !isUglyTitle(other.title) -> other.title
                else -> prefer.title
            }
            return prefer.copy(
                title = title,
                host = host,
                udpPort = if (host == prefer.host) prefer.udpPort else other.udpPort,
                videoPort = if (host == prefer.host) prefer.videoPort else other.videoPort,
                deviceId = prefer.deviceId ?: other.deviceId,
                subtitle = prefer.subtitle
            )
        }

        fun put(row: ContactRow) {
            if (row.host.isBlank()) return
            if (selfIp != null && row.host == selfIp) return
            // Never use shared public NAT IP alone as contact address when private exists later
            val k = keyOf(row.deviceId, row.host)
            val existing = byKey[k]
            if (existing == null) {
                // Also block same host under different keys
                val hostDup = byKey.entries.find { it.value.host == row.host && it.key != k }
                if (hostDup != null) {
                    // Merge into hostDup preferring better name/deviceId
                    val better = pickBetter(hostDup.value, row)
                    byKey.remove(hostDup.key)
                    byKey[keyOf(better.deviceId, better.host)] = better
                    hostClaimed.add(better.host)
                    return
                }
                byKey[k] = row
                hostClaimed.add(row.host)
                return
            }
            byKey[k] = pickBetter(existing, row)
            hostClaimed.add(byKey[k]!!.host)
        }

        // 1) Firebase first (canonical names + deviceIds)
        fun mergeLanAfterFirebase() {
            service?.peers?.forEach { p: WalkiePeer ->
                if (selfIp != null && p.host == selfIp) return@forEach
                if (hostClaimed.contains(p.host)) return@forEach
                // Skip pure public IP ghosts when we already have private-LAN contacts
                if (!PeerEndpoint.isPrivateIp(p.host) && byKey.values.any { PeerEndpoint.isPrivateIp(it.host) }) {
                    return@forEach
                }
                val title = humanTitle(p.displayName, p.host)
                put(
                    ContactRow(
                        title = title,
                        subtitle = getString(R.string.contacts_row_lan, p.host, p.port, p.videoPort),
                        host = p.host,
                        udpPort = p.port,
                        videoPort = p.videoPort,
                        source = "LAN",
                        deviceId = null
                    )
                )
            }
        }

        if (!FirebasePeerDirectory.isConfigured(this)) {
            mergeLanAfterFirebase()
            finishUi(byKey, selfNet, getString(R.string.contacts_firebase_off))
            return
        }
        if (!BarkyPrefs.isFirebaseRegistryEnabled(this)) {
            mergeLanAfterFirebase()
            finishUi(byKey, selfNet, getString(R.string.contacts_registry_disabled))
            return
        }

        FirebasePeerDirectory.listAllAsync(this, selfId) { remote ->
            val now = System.currentTimeMillis()
            for (ep in remote) {
                if (!isRealContactDevice(ep, selfId, now, selfIp)) continue
                // Always prefer LAN host when peer has a private local IP and we do too
                val preferLan = preferLanStrict(selfNet, ep.localIp)
                val host = when {
                    preferLan && !ep.localIp.isNullOrBlank() -> ep.localIp
                    !ep.localIp.isNullOrBlank() && PeerEndpoint.isPrivateIp(ep.localIp) &&
                        selfIp != null && PeerEndpoint.isPrivateIp(selfIp) -> ep.localIp
                    !ep.publicIp.isNullOrBlank() -> ep.publicIp
                    !ep.localIp.isNullOrBlank() -> ep.localIp
                    else -> null
                } ?: continue
                if (selfIp != null && host == selfIp) continue
                val udp = if (host == ep.localIp && ep.localUdpPort > 0) {
                    ep.localUdpPort
                } else if (ep.publicUdpPort > 0 && host == ep.publicIp) {
                    ep.publicUdpPort
                } else {
                    ep.localUdpPort.takeIf { it > 0 } ?: ep.publicUdpPort
                }
                if (udp <= 0) continue
                val vport = when {
                    ep.localVideoPort > 0 -> ep.localVideoPort
                    else -> udp
                }
                val path = if (PeerEndpoint.isPrivateIp(host)) "Wi-Fi" else "Internet"
                val age = now - ep.updatedAtMs
                val online = age in 0..TimeUnit.MINUTES.toMillis(5)
                val model = listOf(ep.manufacturer, ep.phoneModel)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                val title = humanTitle(
                    ep.displayName.ifBlank { model },
                    host,
                    fallbackModel = model.ifBlank { ep.deviceId.take(10) }
                )
                val sub = buildString {
                    append(path).append(' ').append(host).append(" UDP ").append(udp)
                    if (online) append(" · online") else append(" · last seen ").append(age / 60_000L).append("m")
                    if (ep.appVersion.isNotBlank()) append(" · ").append(ep.appVersion)
                }
                put(
                    ContactRow(
                        title = title,
                        subtitle = sub,
                        host = host,
                        udpPort = udp,
                        videoPort = vport,
                        source = "Firebase",
                        deviceId = ep.deviceId
                    )
                )
            }
            mergeLanAfterFirebase()
            runOnUiThread { finishUi(byKey, selfNet, null) }
        }
    }

    /**
     * Prefer LAN when both sides report private IPs on same /16 (home/office).
     * Stricter than generic path so we never collapse multiple phones onto one public IP.
     */
    private fun preferLanStrict(self: NetworkEndpoints.Snapshot, peerLocalIp: String?): Boolean {
        val a = self.localIp
        val b = peerLocalIp
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (!PeerEndpoint.isPrivateIp(a) || !PeerEndpoint.isPrivateIp(b)) return false
        val aParts = a.split(".")
        val bParts = b.split(".")
        if (aParts.size == 4 && bParts.size == 4) {
            // same /16 is enough for typical home 192.168.x
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1]) return true
        }
        return NetworkEndpoints.preferLanPath(self, peerLocalIp, null)
    }

    private fun finishUi(
        byKey: Map<String, ContactRow>,
        selfNet: NetworkEndpoints.Snapshot,
        warning: String?
    ) {
        rows.clear()
        rows.addAll(byKey.values.sortedBy { it.title.lowercase() })
        adapter.notifyDataSetChanged()
        val base = getString(
            R.string.contacts_status_fmt,
            rows.size,
            selfNet.localIp ?: "-",
            selfNet.publicIp ?: "-"
        )
        status.text = if (warning != null) "$base\n$warning" else base
        rows.forEach { r ->
            Log.i(
                TAG,
                "CONTACT ${r.title} host=${r.host} udp=${r.udpPort} v=${r.videoPort} " +
                    "src=${r.source} id=${r.deviceId?.take(12)}"
            )
        }
        Log.i(TAG, "contacts_ready count=${rows.size} self=${selfNet.localIp}")
    }

    private fun isRealContactDevice(
        ep: PeerEndpoint,
        selfId: String?,
        now: Long,
        selfIp: String?
    ): Boolean {
        if (!selfId.isNullOrBlank() && ep.deviceId == selfId) return false
        val id = ep.deviceId
        if (id.isBlank() || id.startsWith("_")) return false
        if (id.contains("example", true) || id.contains("probe", true) || id.contains("keytest", true)) {
            return false
        }
        val name = ep.displayName
        if (name.contains("Template", true) || name.contains("Probe", true) ||
            name.contains("Example", true)
        ) {
            return false
        }
        if (ep.appVersion.equals("seed", true)) return false
        if (ep.localIp.isNullOrBlank() && ep.publicIp.isNullOrBlank()) return false
        if (selfIp != null && (ep.localIp == selfIp)) return false
        // Stale > 2 hours — drop
        if (ep.updatedAtMs > 0L && now - ep.updatedAtMs > TimeUnit.HOURS.toMillis(2)) return false
        return true
    }

    private class Adapter(
        private val data: List<ContactRow>,
        private val onListen: (ContactRow) -> Unit,
        private val onSee: (ContactRow) -> Unit,
        private val onScrn: (ContactRow) -> Unit,
        private val onCall: (ContactRow) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.contact_name)
            val detail: TextView = v.findViewById(R.id.contact_detail)
            val listen: MaterialButton = v.findViewById(R.id.contact_listen)
            val see: MaterialButton = v.findViewById(R.id.contact_see)
            val scrn: MaterialButton = v.findViewById(R.id.contact_scrn)
            val call: MaterialButton = v.findViewById(R.id.contact_call)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            holder.name.text = row.title
            holder.detail.text = row.subtitle
            holder.listen.setOnClickListener { onListen(row) }
            holder.see.setOnClickListener { onSee(row) }
            holder.scrn.setOnClickListener { onScrn(row) }
            holder.call.setOnClickListener { onCall(row) }
        }
    }

    companion object {
        private const val TAG = "ContactsActivity"
    }
}
