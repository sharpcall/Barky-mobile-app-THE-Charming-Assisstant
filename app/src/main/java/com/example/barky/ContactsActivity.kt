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
import com.example.barky.net.ContactLabels
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
 * - Title is always a stable device label (Pixel 8 / S20 / S23) — never "Phone 192.168.x.x".
 * - Subtitle shows path + LAN/public IPs.
 * - Host is private LAN only when live HELLO + same Wi‑Fi; else public STUN IP.
 * - LAN HELLO only refreshes ports for a known deviceId — never renames the phone.
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

    override fun onResume() {
        super.onResume()
        // Auto-refresh directory every time Contacts is shown (keep manual Refresh too)
        BarkyPrefs.setFirebaseRegistryEnabled(this, true)
        if (bound && service != null) {
            service?.forceFirebaseRegister()
            loadContacts()
        }
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
        val selfSnap = DeviceIdentity.snapshot(this)
        // Prefer engine STUN; fall back to prefs (last successful walkie STUN)
        val engMap = service?.publicStunMapping()
        val selfLocalIps = NetworkEndpoints.findAllLocalIpv4().toHashSet()
        val selfPublicIp = engMap?.first?.takeIf { it.isNotBlank() }
            ?: BarkyPrefs.lastStunPublicIp(this)
        val selfPublicUdp = when {
            engMap != null && (engMap.second) > 0 -> engMap.second
            else -> BarkyPrefs.lastStunPublicUdp(this)
        }
        val selfNet = NetworkEndpoints.Snapshot(
            localIp = NetworkEndpoints.findLocalIpv4(),
            publicIp = selfPublicIp,
            publicUdpPort = selfPublicUdp,
            onWifi = NetworkEndpoints.isWifi(this),
            wifiSsid = null
        )
        val selfIp = selfNet.localIp
        // Live LAN peers keyed by host for port refresh only — never include self
        val lanByHost = HashMap<String, WalkiePeer>()
        service?.peers?.forEach { p ->
            if (isSelfHost(p.host, p.port, selfLocalIps, selfPublicIp, selfPublicUdp)) return@forEach
            lanByHost[p.host] = p
        }
        Log.i(
            TAG,
            "loadContacts selfId=${selfId.take(14)} locals=$selfLocalIps " +
                "pub=$selfPublicIp:$selfPublicUdp model=${selfSnap.model}"
        )

        if (!FirebasePeerDirectory.isConfigured(this)) {
            // Fallback: LAN only — still never title with raw IP
            val lanRows = lanByHost.values.map { p ->
                val title = ContactLabels.fromPeerName(p.displayName, p.host)
                ContactRow(
                    title = title,
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
                if (!isRealContactDevice(
                        ep, selfId, now, selfLocalIps, selfPublicIp, selfPublicUdp, selfSnap
                    )
                ) {
                    Log.i(TAG, "filter self/fake id=${ep.deviceId.take(14)} name=${ep.displayName}")
                    continue
                }
                // Live HELLO only on the peer's private LAN address
                val liveLan = ep.localIp
                    ?.takeIf { PeerEndpoint.isPrivateIp(it) }
                    ?.let { lanByHost[it] }
                // Same house NAT: both phones often share one public IP (e.g. 142.x).
                val samePublicNat = !selfPublicIp.isNullOrBlank() &&
                    ep.publicIp == selfPublicIp &&
                    ep.publicUdpPort > 0
                val preferLan = selfNet.onWifi && NetworkEndpoints.preferLanPath(
                    selfNet,
                    ep.localIp,
                    ep.wifiHint,
                    liveLanHello = liveLan != null
                )

                val path = pickContactPath(
                    ep = ep,
                    preferLan = preferLan,
                    liveLan = liveLan,
                    samePublicNat = samePublicNat,
                    selfLocalIps = selfLocalIps,
                    selfPublicIp = selfPublicIp,
                    selfPublicUdp = selfPublicUdp
                )
                if (path == null) {
                    Log.w(
                        TAG,
                        "skip ${ep.deviceId.take(12)}: no safe path " +
                            "lan=${ep.localIp} pub=${ep.publicIp}:${ep.publicUdpPort} " +
                            "selfPub=$selfPublicIp:$selfPublicUdp"
                    )
                    continue
                }
                val targetHost = path.host
                val udp = path.udp
                val vport = path.video
                val pathLabel = path.label
                if (isSelfHost(targetHost, udp, selfLocalIps, selfPublicIp, selfPublicUdp)) {
                    Log.w(TAG, "skip self host $targetHost:$udp id=${ep.deviceId.take(12)}")
                    continue
                }

                val age = now - ep.updatedAtMs
                val online = age in 0..TimeUnit.MINUTES.toMillis(5) || liveLan != null
                val title = ContactLabels.fromEndpoint(ep)
                val sub = ContactLabels.pathSubtitle(
                    pathLabel = pathLabel,
                    targetHost = targetHost,
                    localIp = ep.localIp,
                    publicIp = ep.publicIp,
                    udp = udp,
                    videoPort = vport,
                    online = online,
                    ageMs = age,
                    appVersion = ep.appVersion.takeIf { it.isNotBlank() }
                )
                byId[ep.deviceId] = ContactRow(
                    title = title,
                    subtitle = sub,
                    host = targetHost,
                    udpPort = udp,
                    videoPort = vport,
                    deviceId = ep.deviceId
                )
            }

            // Optional: LAN-only peers not in Firebase — stable name, never "Phone IP"
            val coveredHosts = byId.values.map { it.host }.toHashSet()
            for (ep in remote) {
                ep.localIp?.let { coveredHosts.add(it) }
                ep.publicIp?.let { coveredHosts.add(it) }
            }
            // Map remote local IPs → Firebase title for orphan LAN HELLO merge by IP
            val titleByLanIp = HashMap<String, String>()
            for (ep in remote) {
                val t = ContactLabels.fromEndpoint(ep)
                ep.localIp?.let { titleByLanIp[it] = t }
                ep.publicIp?.let { titleByLanIp[it] = t }
            }
            for ((h, p) in lanByHost) {
                if (h in coveredHosts) continue
                if (isSelfHost(h, p.port, selfLocalIps, selfPublicIp, selfPublicUdp)) continue
                val title = titleByLanIp[h]
                    ?: ContactLabels.fromPeerName(p.displayName, p.host)
                // Never invent a contact named like this phone from orphan HELLO
                val selfLabel = ContactLabels.stableTitle(
                    displayName = "${selfSnap.manufacturer} ${selfSnap.model}",
                    phoneModel = selfSnap.model,
                    manufacturer = selfSnap.manufacturer
                )
                if (title.equals(selfLabel, ignoreCase = true) ||
                    title.equals("Pixel 8", true) && selfLabel.contains("Pixel 8", true)
                ) {
                    Log.w(TAG, "skip orphan LAN self-like title=$title host=$h")
                    continue
                }
                byId["lan:$h"] = ContactRow(
                    title = title,
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

    private data class PickedPath(
        val host: String,
        val udp: Int,
        val video: Int,
        val label: String
    )

    /**
     * Choose a host/port that is never our own socket.
     * Same-house NAT (shared public IP) is the main SEE/SCRN self-loop cause.
     */
    private fun pickContactPath(
        ep: PeerEndpoint,
        preferLan: Boolean,
        liveLan: WalkiePeer?,
        samePublicNat: Boolean,
        selfLocalIps: Set<String>,
        selfPublicIp: String?,
        selfPublicUdp: Int
    ): PickedPath? {
        if (preferLan && liveLan != null &&
            !isSelfHost(liveLan.host, liveLan.port, selfLocalIps, selfPublicIp, selfPublicUdp)
        ) {
            return PickedPath(
                liveLan.host,
                liveLan.port,
                if (liveLan.videoPort > 0) liveLan.videoPort else liveLan.port,
                "Wi-Fi"
            )
        }
        if (preferLan && !ep.localIp.isNullOrBlank() && PeerEndpoint.isPrivateIp(ep.localIp!!) &&
            ep.localUdpPort > 0 && ep.localIp !in selfLocalIps
        ) {
            return PickedPath(
                ep.localIp!!,
                ep.localUdpPort,
                if (ep.localVideoPort > 0) ep.localVideoPort else ep.localUdpPort,
                "Wi-Fi"
            )
        }
        // Public path: require peer STUN port. Only hide exact self (same IP AND same mapped port).
        // Bug was: selfPublicUdp<=0 treated every peer on our public IP as self → empty contacts
        // on cellular / same-house NAT.
        if (!ep.publicIp.isNullOrBlank() && ep.publicUdpPort > 0) {
            if (!selfPublicIp.isNullOrBlank() &&
                ep.publicIp == selfPublicIp &&
                selfPublicUdp > 0 &&
                ep.publicUdpPort == selfPublicUdp
            ) {
                return null
            }
            return PickedPath(
                ep.publicIp!!,
                ep.publicUdpPort,
                if (ep.localVideoPort > 0) ep.localVideoPort else ep.publicUdpPort,
                if (samePublicNat) "Same-NAT" else "Internet"
            )
        }
        if (!ep.localIp.isNullOrBlank() && ep.localUdpPort > 0 && ep.localIp !in selfLocalIps) {
            return PickedPath(
                ep.localIp!!,
                ep.localUdpPort,
                if (ep.localVideoPort > 0) ep.localVideoPort else ep.localUdpPort,
                "LAN?"
            )
        }
        return null
    }

    /** True if host:udp is this phone (any NIC or our STUN mapping). */
    private fun isSelfHost(
        host: String,
        udp: Int,
        selfLocalIps: Set<String>,
        selfPublicIp: String?,
        selfPublicUdp: Int
    ): Boolean {
        if (host.isBlank()) return true
        if (host in selfLocalIps) return true
        // Public STUN: same IP alone is not enough (shared CGNAT / home NAT). Need same mapped port.
        if (!selfPublicIp.isNullOrBlank() && host == selfPublicIp) {
            if (selfPublicUdp > 0 && udp > 0 && udp == selfPublicUdp) return true
        }
        return false
    }

    private fun isRealContactDevice(
        ep: PeerEndpoint,
        selfId: String?,
        now: Long,
        selfLocalIps: Set<String>,
        selfPublicIp: String?,
        selfPublicUdp: Int,
        selfSnap: DeviceIdentity.Snapshot
    ): Boolean {
        // Primary: stable deviceId
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
        // Any of our local IPs (Wi‑Fi or cellular CGNAT 25.x)
        if (!ep.localIp.isNullOrBlank() && ep.localIp in selfLocalIps) return false
        // Exact self public endpoint (IP + mapped UDP). Same public IP alone is NOT self.
        if (!selfPublicIp.isNullOrBlank() && ep.publicIp == selfPublicIp &&
            selfPublicUdp > 0 && ep.publicUdpPort == selfPublicUdp
        ) {
            return false
        }
        // Same model + manufacturer only hide when also our LAN or exact STUN port
        val sameModel = ep.phoneModel.isNotBlank() &&
            ep.phoneModel.equals(selfSnap.model, ignoreCase = true)
        val sameMfr = ep.manufacturer.isNotBlank() &&
            ep.manufacturer.equals(selfSnap.manufacturer, ignoreCase = true)
        if (sameModel && sameMfr) {
            if (!ep.localIp.isNullOrBlank() && ep.localIp in selfLocalIps) return false
            if (!selfPublicIp.isNullOrBlank() && ep.publicIp == selfPublicIp &&
                selfPublicUdp > 0 && ep.publicUdpPort == selfPublicUdp
            ) {
                return false
            }
            if (ep.localIp.isNullOrBlank() && ep.publicIp.isNullOrBlank()) return false
        }
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
