package com.example.barky.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * Always-on NIC / connectivity hook: fires when the default network or local IPv4 changes.
 * Used to push Firebase registration and mid-call path handover immediately (not only on heartbeat).
 */
class NetworkPathMonitor(
    context: Context,
    private val onPathChanged: (signature: String, localIp: String?, onWifi: Boolean) -> Unit
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lastSig = AtomicReference<String?>(null)
    private var registered = false

    private val debounce = Runnable {
        val snap = readSignature()
        val prev = lastSig.getAndSet(snap.signature)
        if (prev == snap.signature) return@Runnable
        Log.i(TAG, "path change prev=$prev → ${snap.signature}")
        onPathChanged(snap.signature, snap.localIp, snap.onWifi)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule("available")
        override fun onLost(network: Network) = schedule("lost")
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            schedule("caps")
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            schedule("link")
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = schedule("blocked")
    }

    fun start() {
        if (registered) return
        registered = true
        lastSig.set(readSignature().signature)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            // Default network changes (Wi‑Fi ↔ cell, new IP on same NIC, captive portal, etc.)
            cm.registerDefaultNetworkCallback(callback)
            Log.i(TAG, "registerDefaultNetworkCallback ok sig=${lastSig.get()}")
        } catch (t: Throwable) {
            Log.w(TAG, "default callback failed, fallback request", t)
            try {
                cm.registerNetworkCallback(req, callback)
            } catch (t2: Throwable) {
                Log.e(TAG, "registerNetworkCallback failed", t2)
                registered = false
            }
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        main.removeCallbacks(debounce)
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
        Log.i(TAG, "stopped")
    }

    /** Force re-evaluate (e.g. after STUN or socket bind). */
    fun forceCheck(reason: String = "force") {
        schedule(reason)
    }

    private fun schedule(reason: String) {
        Log.d(TAG, "schedule reason=$reason")
        main.removeCallbacks(debounce)
        // Brief debounce so link+caps storms collapse into one report
        main.postDelayed(debounce, DEBOUNCE_MS)
    }

    data class Sig(val signature: String, val localIp: String?, val onWifi: Boolean)

    companion object {
        private const val TAG = "NetworkPathMonitor"
        // Fast Wi‑Fi ↔ cellular handover (still collapses burst events)
        private const val DEBOUNCE_MS = 120L

        fun readSignature(context: Context): Sig {
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val onCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val localFromLink = lp?.linkAddresses
                ?.mapNotNull { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { it.hostAddress }
                ?.firstOrNull { !it.startsWith("127.") }
            val local = localFromLink ?: NetworkEndpoints.findLocalIpv4()
            val ifName = lp?.interfaceName ?: "?"
            val sig = buildString {
                append(if (onWifi) "wifi" else if (onCell) "cell" else "other")
                append('|')
                append(local ?: "noip")
                append('|')
                append(ifName)
                append('|')
                append(if (validated) "v" else "u")
            }
            return Sig(sig, local, onWifi)
        }
    }

    private fun readSignature(): Sig = Companion.readSignature(app)
}
