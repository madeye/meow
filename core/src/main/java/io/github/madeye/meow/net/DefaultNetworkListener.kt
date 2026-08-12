package io.github.madeye.meow.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import timber.log.Timber

object DefaultNetworkListener {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    @Volatile
    private var lastNetwork: Network? = null

    fun start(service: android.net.VpnService, onNetworkChanged: (Network?) -> Unit) {
        callback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        val cm = service.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastNetwork = network
                onNetworkChanged(network)
            }

            override fun onLost(network: Network) {
                if (network == lastNetwork) {
                    lastNetwork = null
                    onNetworkChanged(null)
                }
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                lastNetwork = network
                onNetworkChanged(network)
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
                }
            }
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    fun stop() {
        try { callback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        catch (e: Exception) { Timber.w(e) }
        callback = null
        lastNetwork = null
    }
}
