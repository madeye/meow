package io.github.madeye.meow.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

object DefaultNetworkListener {
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var connectivityManager: ConnectivityManager? = null

    fun start(service: android.net.VpnService, onNetworkChanged: (Network?) -> Unit) {
        val cm = service.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onNetworkChanged(network) }
            override fun onLost(network: Network) { onNetworkChanged(null) }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { onNetworkChanged(network) }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    fun stop(scope: CoroutineScope) {
        try { callback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        catch (e: Exception) { Timber.w(e) }
        callback = null
    }
}
