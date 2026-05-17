package io.github.madeye.meow.bg

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.madeye.meow.Core
import io.github.madeye.meow.net.DefaultNetworkListener
import io.github.madeye.meow.preference.DataStore
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(), BaseService.Interface {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = "Reboot required"
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "MihomoVpnService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-vpn")

    private var conn: ParcelFileDescriptor? = null
    private var active = false
    private var metered = false
    @Volatile
    private var underlyingNetwork: Network? = null

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override fun onRevoke() = stopRunner()

    override fun killProcesses(scope: CoroutineScope) {
        super.killProcesses(scope)
        active = false
        scope.launch { DefaultNetworkListener.stop(this) }
        conn?.close()
        conn = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override suspend fun preInit() {
        if (prepare(this) != null) throw NullConnectionException()
        DefaultNetworkListener.start(this) { underlyingNetwork = it }
    }

    override suspend fun startProcesses() {
        val configDir = File(Core.deviceStorage.noBackupFilesDir, "mihomo")
        configDir.mkdirs()
        data.mihomoInstance!!.start(configDir, this)
        startVpn()
    }

    override val isVpnService get() = true

    private fun startVpn() {
        val builder = Builder()
            .setSession("Mihomo VPN")
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
            .addDnsServer(PRIVATE_VLAN4_ROUTER)
            .addAddress(PRIVATE_VLAN6_CLIENT, 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        // Per-app VPN routing
        val perAppPackages: Set<String> = try {
            JSONArray(DataStore.perAppPackages).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (_: Exception) { emptySet() }

        // Note: we deliberately do NOT add the meow package to
        // `addDisallowedApplication` here. The engine and tun2socks run in
        // the `:vpn` process and rely on `VpnService.protect(fd)` (called
        // from the patched mihomo-proxy connect hook and the mihomo-dns
        // SocketFactory) to bypass the TUN on a per-socket basis. Excluding
        // the whole app's uid would also exempt traffic users may want to
        // intercept (e.g. a built-in browser preview) and would shadow the
        // protect path the rest of the stack is designed around.
        if (perAppPackages.isNotEmpty()) when (DataStore.perAppMode) {
            "proxy" -> {
                // Only selected apps go through VPN.
                for (pkg in perAppPackages) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (_: PackageManager.NameNotFoundException) { }
                }
            }
            else -> {
                // "bypass" — all apps except selected go through VPN.
                for (pkg in perAppPackages) {
                    try { builder.addDisallowedApplication(pkg) }
                    catch (_: PackageManager.NameNotFoundException) { }
                }
            }
        }

        active = true
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn
        // Tell the system which networks the VPN sits on top of. Without
        // this, `VpnService.protect(fd)` knows the bypass mark to apply but
        // the platform's per-network firewall has no associated network for
        // the marked traffic, so packets are silently dropped on Xiaomi /
        // HyperOS builds. Prefer the listener's tracked default network;
        // fall back to ConnectivityManager.getActiveNetwork() so we still
        // have an underlying network on the first establish (the listener's
        // first onAvailable can race the establish call).
        val underlying = underlyingNetwork ?: run {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.activeNetwork
        }
        underlying?.let { setUnderlyingNetworks(arrayOf(it)) }
        Timber.d("VpnService: setUnderlyingNetworks=$underlying")
        data.mihomoInstance!!.startTun2Socks(this, conn.fd)
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }
}
