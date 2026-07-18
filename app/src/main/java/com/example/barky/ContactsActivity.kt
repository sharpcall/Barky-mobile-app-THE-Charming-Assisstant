package com.example.barky

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
 * Contacts = Barky phones from Firebase directory + live LAN peers.
 * Tap SEE / LISTEN / CALL (HOLD) after selecting path (LAN if private, else public IP).
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
        toolbar.setNavigationIconTint(getColor(R.color.barky_on_primary))

        status = findViewById(R.id.contacts_status)
        list = findViewById(R.id.contacts_list)
        refresh = findViewById(R.id.contacts_refresh)
        adapter = Adapter(
            rows,
            onListen = { row -> act(row, Act.LISTEN) },
            onSee = { row -> act(row, Act.SEE) },
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

    private enum class Act { LISTEN, SEE, CALL }

    private fun act(row: ContactRow, act: Act) {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, R.string.contacts_service_wait, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = svc.adoptDirectoryPeer(row.title, row.host, row.udpPort, row.videoPort)
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
            Act.CALL -> {
                // HOLD/talk — user can hold mic on main screen; start path ready
                Toast.makeText(this, getString(R.string.contacts_selected_call, row.title), Toast.LENGTH_LONG).show()
            }
        }
        // Return to main for video/mic UI
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun loadContacts() {
        status.setText(R.string.contacts_loading)
        val selfId = DeviceIdentity.deviceId(this)
        val selfNet = NetworkEndpoints.snapshot(this)
        val merged = LinkedHashMap<String, ContactRow>()

        // 1) Live LAN peers from walkie HELLO/NSD
        service?.peers?.forEach { p: WalkiePeer ->
            val key = "lan:${p.host}:${p.port}"
            merged[key] = ContactRow(
                title = p.displayName.ifBlank { p.host },
                subtitle = getString(
                    R.string.contacts_row_lan,
                    p.host,
                    p.port,
                    p.videoPort
                ),
                host = p.host,
                udpPort = p.port,
                videoPort = p.videoPort,
                source = "LAN",
                deviceId = null
            )
        }

        // 2) Firebase directory (all registered Barky phones)
        if (!FirebasePeerDirectory.isConfigured(this)) {
            runOnUiThread {
                finishMerge(merged, selfNet, warning = getString(R.string.contacts_firebase_off))
            }
            return
        }
        if (!BarkyPrefs.isFirebaseRegistryEnabled(this)) {
            runOnUiThread {
                finishMerge(merged, selfNet, warning = getString(R.string.contacts_registry_disabled))
            }
            return
        }

        FirebasePeerDirectory.listAllAsync(this, selfId) { remote ->
            val now = System.currentTimeMillis()
            for (ep in remote) {
                val age = now - ep.updatedAtMs
                val online = age in 0..TimeUnit.MINUTES.toMillis(5)
                val preferLan = NetworkEndpoints.preferLanPath(
                    selfNet,
                    ep.localIp,
                    ep.wifiHint
                )
                val host = ep.preferredHost(preferLan) ?: continue
                val udp = ep.preferredUdpPort(preferLan)
                val vport = if (preferLan && ep.localVideoPort > 0) ep.localVideoPort
                else if (ep.localVideoPort > 0) ep.localVideoPort
                else udp
                val path = if (preferLan) "Wi‑Fi" else "Internet"
                val phone = ep.phoneNumber
                val ageMin = (age / 60_000L).coerceAtLeast(0)
                val sub = getString(
                    R.string.contacts_row_remote,
                    path,
                    host,
                    udp,
                    phone,
                    if (online) getString(R.string.contacts_online) else getString(R.string.contacts_stale, ageMin)
                )
                // Prefer Firebase row key by deviceId so 3 phones all show even if LAN also listed
                val key = "fb:${ep.deviceId}"
                // Skip exact LAN duplicate host if already present and same ports
                val lanDup = merged.values.any { it.host == host && it.udpPort == udp && it.source == "LAN" }
                if (!lanDup) {
                    merged[key] = ContactRow(
                        title = ep.displayName.ifBlank { ep.deviceId.take(12) },
                        subtitle = sub,
                        host = host,
                        udpPort = udp,
                        videoPort = vport,
                        source = "Firebase",
                        deviceId = ep.deviceId
                    )
                } else {
                    // Annotate LAN row with Firebase name if match
                    val lanKey = merged.entries.firstOrNull {
                        it.value.host == host && it.value.udpPort == udp
                    }?.key
                    if (lanKey != null) {
                        val old = merged[lanKey]!!
                        merged[lanKey] = old.copy(
                            title = ep.displayName.ifBlank { old.title },
                            subtitle = old.subtitle + " · " + getString(R.string.contacts_also_registered)
                        )
                    }
                }
            }
            runOnUiThread {
                finishMerge(merged, selfNet, warning = null)
            }
        }
    }

    private fun finishMerge(
        merged: Map<String, ContactRow>,
        selfNet: NetworkEndpoints.Snapshot,
        warning: String?
    ) {
        rows.clear()
        rows.addAll(merged.values.sortedBy { it.title.lowercase() })
        adapter.notifyDataSetChanged()
        val base = getString(
            R.string.contacts_status_fmt,
            rows.size,
            selfNet.localIp ?: "—",
            selfNet.publicIp ?: "—"
        )
        status.text = if (warning != null) "$base\n$warning" else base
    }

    private class Adapter(
        private val data: List<ContactRow>,
        private val onListen: (ContactRow) -> Unit,
        private val onSee: (ContactRow) -> Unit,
        private val onCall: (ContactRow) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.contact_name)
            val detail: TextView = v.findViewById(R.id.contact_detail)
            val listen: MaterialButton = v.findViewById(R.id.contact_listen)
            val see: MaterialButton = v.findViewById(R.id.contact_see)
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
            holder.call.setOnClickListener { onCall(row) }
        }
    }
}
