package io.github.madeye.meow.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

object DefaultNetworkListener {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private val networks = CopyOnWriteArraySet<Network>()

    fun start(service: android.net.VpnService, onNetworkChanged: (Network?) -> Unit) {
        callback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        networks.clear()
        val cm = service.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks.add(network)
                onNetworkChanged(bestNetwork())
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                // Validation state flips after onAvailable (e.g. a WiFi that
                // later passes/fails internet validation), so re-rank here to
                // keep bestNetwork accurate.  The caller de-duplicates via
                // `network != underlyingNetwork`, so this never fires a
                // redundant setUnderlyingNetworks for an unchanged pick.
                onNetworkChanged(bestNetwork())
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                onNetworkChanged(bestNetwork())
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    private fun bestNetwork(): Network? {
        val cm = connectivityManager ?: return null
        // Score each tracked network and pick the highest.  A network must be
        // VALIDATED to outrank others, so a WiFi that is up (associated, has
        // INTERNET capability) but has no real upstream — captive portal,
        // dead AP, or validation still pending — cannot shadow a working
        // cellular connection.  Within a validation tier, prefer WiFi then
        // Ethernet then any other transport, keeping the VPN transport label
        // clean so Settings shows the correct underlying connection.
        fun score(nc: NetworkCapabilities?): Int {
            if (nc == null) return -1
            var s = 0
            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 1000
            when {
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> s += 100
                nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> s += 50
            }
            return s
        }
        return networks
            .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .maxByOrNull { (_, nc) -> score(nc) }
            ?.first
    }

    fun stop() {
        try { callback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        catch (e: Exception) { Timber.w(e) }
        callback = null
        networks.clear()
    }
}
