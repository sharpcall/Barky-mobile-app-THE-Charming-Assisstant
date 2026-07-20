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
 * Contacts = one row per Firebase deviceId.
 *
 * - Title is always manufacturer + model from Firebase (never raw LAN/NSD junk).
 * - Host is private LAN IP when on same Wi‑Fi subnet, else public STUN IP.
 * - LAN HELLO only refreshes ports for a known deviceId — never creates nameless rows.
 * - Seeds/templates excluded.
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
        val deviceId: String
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
            onSelect = { row -> act(row, Act.SELECT) }
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

    private enum class Act { LISTEN, SEE, SCRN, SELECT }

    private fun act(row: ContactRow, act: Act) {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, R.string.contacts_service_wait, Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(
            TAG,
            "ACT $act title=${row.title} host=${row.host} udp=${row.udpPort} " +
                "video=${row.videoPort} id=${row.deviceId.take(14)}"
        )
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
            Act.SELECT -> {
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
        // Live LAN peers keyed by host for port refresh only
        val lanByHost = HashMap<String, WalkiePeer>()
        service?.peers?.forEach { p ->
            if (selfIp == null || p.host != selfIp) lanByHost[p.host] = p
        }

        fun prettyTitle(ep: PeerEndpoint): String {
            val model = ep.phoneModel.trim()
            val mfr = ep.manufacturer.trim()
            val fromFields = when {
                model.isNotBlank() && mfr.isNotBlank() -> {
                    // Avoid "samsung SM-S918W" doubling — prefer model alone if it starts with SM-
                    if (model.startsWith("SM-", ignoreCase = true) ||
                        model.startsWith("Pixel", ignoreCase = true)
                    ) {
                        if (model.startsWith("Pixel", ignoreCase = true)) model
                        else "$mfr $model".replace(Regex("(?i)samsung\\s+samsung"), "samsung")
                    } else {
                        "$mfr $model"
                    }
                }
                model.isNotBlank() -> model
                mfr.isNotBlank() -> mfr
                else -> ep.displayName.trim()
            }.trim()
            val cleaned = fromFields
                .replace(Regex("(?i)^google\\s+"), "")
                .ifBlank { ep.displayName.ifBlank { ep.deviceId.take(10) } }
            // Friendly Galaxy labels
            return when {
                cleaned.contains("SM-S918", true) -> "Galaxy S23 Ultra"
                cleaned.contains("SM-G981", true) -> "Galaxy S20"
                cleaned.contains("Pixel", true) -> cleaned.replace(Regex("(?i)google\\s*"), "").trim()
                    .ifBlank { "Pixel" }
                else -> cleaned
            }
        }

        if (!FirebasePeerDirectory.isConfigured(this)) {
            // Fallback: LAN only with host titles
            val lanRows = lanByHost.values.map { p ->
                ContactRow(
                    title = "Phone ${p.host}",
                    subtitle = getString(R.string.contacts_row_lan, p.host, p.port, p.videoPort),
                    host = p.host,
                    udpPort = p.port,
                    videoPort = p.videoPort,
                    deviceId = "lan:${p.host}"
                )
            }.sortedBy { it.title.lowercase() }
            finishUi(lanRows, selfNet, getString(R.string.contacts_firebase_off))
            return
        }

        BarkyPrefs.setFirebaseRegistryEnabled(this, true)
        FirebasePeerDirectory.listAllAsync(this, selfId) { remote ->
            val now = System.currentTimeMillis()
            val byId = LinkedHashMap<String, ContactRow>()
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

                // Prefer live HELLO ports when we can reach them on LAN
                val live = listOfNotNull(ep.localIp, ep.publicIp, host)
                    .firstNotNullOfOrNull { lanByHost[it] }

                val udp = when {
                    live != null && live.port > 0 -> live.port
                    preferLan && ep.localUdpPort > 0 -> ep.localUdpPort
                    ep.publicUdpPort > 0 && host == ep.publicIp -> ep.publicUdpPort
                    ep.localUdpPort > 0 -> ep.localUdpPort
                    else -> ep.publicUdpPort
                }
                if (udp <= 0) continue
                val vport = when {
                    live != null && live.videoPort > 0 -> live.videoPort
                    ep.localVideoPort > 0 -> ep.localVideoPort
                    else -> udp
                }
                // Always use LAN host for targeting when live HELLO exists on private IP
                val targetHost = when {
                    live != null && PeerEndpoint.isPrivateIp(live.host) -> live.host
                    else -> host
                }
                val path = if (PeerEndpoint.isPrivateIp(targetHost)) "Wi-Fi" else "Internet"
                val age = now - ep.updatedAtMs
                val online = age in 0..TimeUnit.MINUTES.toMillis(5) || live != null
                val title = prettyTitle(ep)
                val sub = buildString {
                    append(path).append(' ').append(targetHost)
                    append(" · UDP ").append(udp)
                    append(" · video ").append(vport)
                    if (online) append(" · online") else append(" · last ").append(age / 60_000L).append("m")
                    if (ep.appVersion.isNotBlank()) append(" · ").append(ep.appVersion)
                }
                byId[ep.deviceId] = ContactRow(
                    title = title,
                    subtitle = sub,
                    host = targetHost,
                    udpPort = udp,
                    videoPort = vport,
                    deviceId = ep.deviceId
                )
            }

            // Optional: LAN-only peers not in Firebase (rare) — label by host only, no mix-up
            val coveredHosts = byId.values.map { it.host }.toHashSet()
            // Also cover Firebase local/public IPs so we don't re-add
            for (ep in remote) {
                ep.localIp?.let { coveredHosts.add(it) }
                ep.publicIp?.let { coveredHosts.add(it) }
            }
            for ((h, p) in lanByHost) {
                if (h in coveredHosts) continue
                if (selfIp != null && h == selfIp) continue
                byId["lan:$h"] = ContactRow(
                    title = "Phone $h",
                    subtitle = getString(R.string.contacts_row_lan, h, p.port, p.videoPort),
                    host = h,
                    udpPort = p.port,
                    videoPort = p.videoPort,
                    deviceId = "lan:$h"
                )
            }

            val listRows = byId.values.sortedBy { it.title.lowercase() }
            runOnUiThread { finishUi(listRows, selfNet, null) }
        }
    }

    private fun preferLanStrict(self: NetworkEndpoints.Snapshot, peerLocalIp: String?): Boolean {
        val a = self.localIp
        val b = peerLocalIp
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (!PeerEndpoint.isPrivateIp(a) || !PeerEndpoint.isPrivateIp(b)) return false
        val aParts = a.split(".")
        val bParts = b.split(".")
        if (aParts.size == 4 && bParts.size == 4) {
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1] && aParts[2] == bParts[2]) {
                return true
            }
            if (aParts[0] == bParts[0] && aParts[1] == bParts[1]) return true
        }
        return self.onWifi
    }

    private fun finishUi(
        listRows: List<ContactRow>,
        selfNet: NetworkEndpoints.Snapshot,
        warning: String?
    ) {
        rows.clear()
        rows.addAll(listRows)
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
                "CONTACT title=${r.title} host=${r.host} udp=${r.udpPort} v=${r.videoPort} " +
                    "id=${r.deviceId.take(16)}"
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
        private val onSelect: (ContactRow) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.contact_name)
            val detail: TextView = v.findViewById(R.id.contact_detail)
            val listen: MaterialButton = v.findViewById(R.id.contact_listen)
            val see: MaterialButton = v.findViewById(R.id.contact_see)
            val scrn: MaterialButton = v.findViewById(R.id.contact_scrn)
            val select: MaterialButton = v.findViewById(R.id.contact_call)
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
            holder.select.setOnClickListener { onSelect(row) }
        }
    }

    companion object {
        private const val TAG = "ContactsActivity"
    }
}
