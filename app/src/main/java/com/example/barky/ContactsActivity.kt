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
 * Contacts = one row per real Barky **device** (deviceId).
 * Never list the same phone twice (LAN IP + public IP).
 * Firebase registry is force-enabled; seeds never shown.
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
            // Always keep directory registration on while Contacts is used
            BarkyPrefs.setFirebaseRegistryEnabled(this@ContactsActivity, true)
            service?.forceFirebaseRegister()
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
        val deviceId: String?,
        val localIp: String? = null,
        val publicIp: String? = null
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

        refresh.setOnClickListener {
            BarkyPrefs.setFirebaseRegistryEnabled(this, true)
            service?.forceFirebaseRegister()
            loadContacts()
        }
        BarkyPrefs.setFirebaseRegistryEnabled(this, true)
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

        fun isUglyTitle(t: String): Boolean {
            if (t.isBlank()) return true
            if (t.startsWith("udp-") || t.startsWith("path-") || t.startsWith("inet-")) return true
            if (t.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return true
            if (t.contains(Regex("-v\\d{2,5}$"))) return true
            if (t.matches(Regex("^[A-Za-z0-9]+-\\d{4}$"))) return true
            return false
        }

        fun humanTitle(raw: String, host: String, fallbackModel: String = ""): String {
            var s = raw.trim()
                .removePrefix("Barky-")
                .removePrefix("barky-")
            s = s.replace(Regex("-v\\d{2,5}$"), "")
            s = s.replace(Regex("-\\d{4}$"), "")
            if (isUglyTitle(s) || s.isBlank()) {
                if (fallbackModel.isNotBlank()) return fallbackModel
                return host
            }
            return s
        }

        fun normalizeTitleKey(t: String): String =
            t.lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .replace("google", "")
                .replace("samsung", "")

        // Canonical map: deviceId → row (LAN-only temp keys use "host:…")
        val byId = LinkedHashMap<String, ContactRow>()
        // localIp / publicIp → deviceId for merge
        val ipToId = HashMap<String, String>()

        fun mergeRows(a: ContactRow, b: ContactRow, net: NetworkEndpoints.Snapshot): ContactRow {
            val deviceId = a.deviceId ?: b.deviceId
            val title = when {
                !isUglyTitle(a.title) && a.source == "Firebase" -> a.title
                !isUglyTitle(b.title) && b.source == "Firebase" -> b.title
                !isUglyTitle(a.title) -> a.title
                !isUglyTitle(b.title) -> b.title
                else -> a.title
            }
            val localIp = a.localIp ?: b.localIp
            val publicIp = a.publicIp ?: b.publicIp
            // Reachability: LAN if we and peer share private subnet, else public
            val preferLan = preferLanStrict(net, localIp)
            val host = when {
                preferLan && !localIp.isNullOrBlank() -> localIp
                !preferLan && !publicIp.isNullOrBlank() -> publicIp
                PeerEndpoint.isPrivateIp(a.host) && preferLan -> a.host
                PeerEndpoint.isPrivateIp(b.host) && preferLan -> b.host
                !PeerEndpoint.isPrivateIp(a.host) && !preferLan -> a.host
                !PeerEndpoint.isPrivateIp(b.host) && !preferLan -> b.host
                PeerEndpoint.isPrivateIp(a.host) -> a.host
                PeerEndpoint.isPrivateIp(b.host) -> b.host
                else -> a.host
            }
            val udp = when {
                host == localIp && a.localIp == localIp -> a.udpPort
                host == localIp && b.localIp == localIp -> b.udpPort
                host == a.host -> a.udpPort
                host == b.host -> b.udpPort
                else -> a.udpPort.takeIf { it > 0 } ?: b.udpPort
            }
            val vport = when {
                a.videoPort > 0 && a.videoPort != a.udpPort -> a.videoPort
                b.videoPort > 0 && b.videoPort != b.udpPort -> b.videoPort
                a.videoPort > 0 -> a.videoPort
                else -> b.videoPort
            }
            val path = if (PeerEndpoint.isPrivateIp(host)) "Wi-Fi" else "Internet"
            val sub = buildString {
                append(path).append(' ').append(host).append(" UDP ").append(udp)
                if (!localIp.isNullOrBlank() && !publicIp.isNullOrBlank() && localIp != publicIp) {
                    append(" · also ").append(if (host == localIp) publicIp else localIp)
                }
                // keep version hint from either subtitle
                val ver = listOf(a.subtitle, b.subtitle).firstNotNullOfOrNull { s ->
                    Regex("·\\s*([\\d.]+[\\w-]*)\\s*$").find(s)?.groupValues?.getOrNull(1)
                }
                if (ver != null) append(" · ").append(ver)
            }
            return ContactRow(
                title = title,
                subtitle = sub,
                host = host,
                udpPort = udp,
                videoPort = if (vport > 0) vport else udp,
                source = if (a.source == "Firebase" || b.source == "Firebase") "Firebase" else "LAN",
                deviceId = deviceId,
                localIp = localIp,
                publicIp = publicIp
            )
        }

        fun store(row: ContactRow) {
            if (row.host.isBlank()) return
            if (selfIp != null && row.host == selfIp) return
            val id = row.deviceId?.takeIf { it.isNotBlank() && !it.startsWith("_") }
            val key = id ?: "host:${row.host}"

            val existingKey = when {
                byId.containsKey(key) -> key
                id != null && byId.containsKey(id) -> id
                row.localIp != null && ipToId[row.localIp] != null -> ipToId[row.localIp]!!
                row.publicIp != null && ipToId[row.publicIp] != null -> ipToId[row.publicIp]!!
                ipToId[row.host] != null -> ipToId[row.host]!!
                else -> {
                    val nt = normalizeTitleKey(row.title)
                    if (nt.length >= 4) {
                        byId.entries.firstOrNull { normalizeTitleKey(it.value.title) == nt }?.key
                    } else null
                }
            }

            val mergeKey = existingKey ?: key
            val prev = byId[mergeKey]
            val merged = if (prev == null) row else mergeRows(prev, row, selfNet)
            val finalKey = merged.deviceId?.takeIf { it.isNotBlank() } ?: mergeKey
            if (finalKey != mergeKey) byId.remove(mergeKey)
            byId[finalKey] = merged
            merged.localIp?.let { ipToId[it] = finalKey }
            merged.publicIp?.let { ipToId[it] = finalKey }
            ipToId[merged.host] = finalKey
        }

        fun addLan() {
            service?.peers?.forEach { p: WalkiePeer ->
                if (selfIp != null && p.host == selfIp) return@forEach
                // If this IP already mapped to a device, skip creating a new row
                if (ipToId.containsKey(p.host)) {
                    // Optionally refresh ports on existing
                    val k = ipToId[p.host]!!
                    val prev = byId[k] ?: return@forEach
                    if (prev.host == p.host && (p.port > 0 || p.videoPort > 0)) {
                        byId[k] = prev.copy(
                            udpPort = p.port.takeIf { it > 0 } ?: prev.udpPort,
                            videoPort = p.videoPort.takeIf { it > 0 } ?: prev.videoPort
                        )
                    }
                    return@forEach
                }
                val title = humanTitle(p.displayName, p.host)
                store(
                    ContactRow(
                        title = title,
                        subtitle = getString(R.string.contacts_row_lan, p.host, p.port, p.videoPort),
                        host = p.host,
                        udpPort = p.port,
                        videoPort = p.videoPort,
                        source = "LAN",
                        deviceId = null,
                        localIp = p.host.takeIf { PeerEndpoint.isPrivateIp(it) },
                        publicIp = p.host.takeUnless { PeerEndpoint.isPrivateIp(it) }
                    )
                )
            }
        }

        if (!FirebasePeerDirectory.isConfigured(this)) {
            addLan()
            finishUi(byId, selfNet, getString(R.string.contacts_firebase_off))
            return
        }

        // Force registry for directory
        BarkyPrefs.setFirebaseRegistryEnabled(this, true)

        FirebasePeerDirectory.listAllAsync(this, selfId) { remote ->
            val now = System.currentTimeMillis()
            for (ep in remote) {
                if (!isRealContactDevice(ep, selfId, now, selfIp)) continue
                val preferLan = preferLanStrict(selfNet, ep.localIp)
                val host = when {
                    preferLan && !ep.localIp.isNullOrBlank() -> ep.localIp!!
                    !preferLan && !ep.publicIp.isNullOrBlank() -> ep.publicIp!!
                    !ep.localIp.isNullOrBlank() -> ep.localIp!!
                    !ep.publicIp.isNullOrBlank() -> ep.publicIp!!
                    else -> continue
                }
                if (selfIp != null && host == selfIp) continue
                val udp = when {
                    host == ep.localIp && ep.localUdpPort > 0 -> ep.localUdpPort
                    host == ep.publicIp && ep.publicUdpPort > 0 -> ep.publicUdpPort
                    ep.localUdpPort > 0 -> ep.localUdpPort
                    else -> ep.publicUdpPort
                }
                if (udp <= 0) continue
                val vport = ep.localVideoPort.takeIf { it > 0 } ?: udp
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
                store(
                    ContactRow(
                        title = title,
                        subtitle = sub,
                        host = host,
                        udpPort = udp,
                        videoPort = vport,
                        source = "Firebase",
                        deviceId = ep.deviceId,
                        localIp = ep.localIp,
                        publicIp = ep.publicIp
                    )
                )
            }
            addLan()
            // Final pass: collapse any remaining title-key duplicates
            val collapsed = LinkedHashMap<String, ContactRow>()
            for (r in byId.values) {
                val tk = r.deviceId?.takeIf { it.isNotBlank() }
                    ?: "t:${normalizeTitleKey(r.title).ifBlank { r.host }}"
                val prev = collapsed[tk]
                collapsed[tk] = if (prev == null) r else mergeRows(prev, r, selfNet)
            }
            runOnUiThread { finishUi(collapsed, selfNet, null) }
        }
    }

    private fun preferLanStrict(self: NetworkEndpoints.Snapshot, peerLocalIp: String?): Boolean {
        val a = self.localIp
        val b = peerLocalIp
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (!PeerEndpoint.isPrivateIp(a) || !PeerEndpoint.isPrivateIp(b)) return false
        // Same /16 or both private and self on Wi‑Fi
        val aParts = a.split(".")
        val bParts = b.split(".")
        if (aParts.size == 4 && bParts.size == 4) {
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1]) return true
        }
        return self.onWifi && NetworkEndpoints.preferLanPath(self, peerLocalIp, null)
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
                    "src=${r.source} id=${r.deviceId?.take(12)} local=${r.localIp} pub=${r.publicIp}"
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
        if (selfIp != null && ep.localIp == selfIp) return false
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
