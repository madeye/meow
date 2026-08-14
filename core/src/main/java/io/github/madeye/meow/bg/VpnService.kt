package io.github.madeye.meow.bg

import android.content.Context
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
        private const val EXTRA_BLOCK_QUIC = "block_quic"
        private const val EXTRA_DISABLE_IPV6 = "disable_ipv6"
        private const val EXTRA_PER_APP_MODE = "per_app_mode"
        private const val EXTRA_PER_APP_PACKAGES = "per_app_packages"

        fun startIntent(context: Context): Intent = RuntimeSettings(
            blockQuic = DataStore.blockQuic,
            disableIpv6 = DataStore.disableIpv6,
            perAppMode = DataStore.perAppMode,
            perAppPackages = DataStore.perAppPackages,
        ).toIntent(context)
    }

    private data class RuntimeSettings(
        val blockQuic: Boolean = true,
        val disableIpv6: Boolean = false,
        val perAppMode: String = "proxy",
        val perAppPackages: String = "[]",
    ) {
        fun toIntent(context: Context) = Intent(context, VpnService::class.java).apply {
            putExtra(EXTRA_BLOCK_QUIC, blockQuic)
            putExtra(EXTRA_DISABLE_IPV6, disableIpv6)
            putExtra(EXTRA_PER_APP_MODE, perAppMode)
            putExtra(EXTRA_PER_APP_PACKAGES, perAppPackages)
        }

        companion object {
            fun fromIntent(intent: Intent?): RuntimeSettings? {
                if (intent == null ||
                    !intent.hasExtra(EXTRA_BLOCK_QUIC) ||
                    !intent.hasExtra(EXTRA_DISABLE_IPV6) ||
                    !intent.hasExtra(EXTRA_PER_APP_MODE) ||
                    !intent.hasExtra(EXTRA_PER_APP_PACKAGES)
                ) return null
                return RuntimeSettings(
                    blockQuic = intent.getBooleanExtra(EXTRA_BLOCK_QUIC, true),
                    disableIpv6 = intent.getBooleanExtra(EXTRA_DISABLE_IPV6, false),
                    perAppMode = intent.getStringExtra(EXTRA_PER_APP_MODE) ?: "proxy",
                    perAppPackages = intent.getStringExtra(EXTRA_PER_APP_PACKAGES) ?: "[]",
                )
            }
        }
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = "Reboot required"
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "MihomoVpnService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-vpn")

    private var conn: ParcelFileDescriptor? = null
    @Volatile
    private var active = false
    private var metered = false
    @Volatile
    private var underlyingNetwork: Network? = null
    private var runtimeSettings = RuntimeSettings()

    override fun onCreate() {
        Timber.i("VpnService.onCreate")
        super.onCreate()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override fun onRevoke() = stopRunner()

    override fun killProcesses(scope: CoroutineScope) {
        super.killProcesses(scope)
        active = false
        DefaultNetworkListener.stop()
        conn?.close()
        conn = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VpnService.onStartCommand state=${data.state} startId=$startId")
        if (data.state == BaseService.State.Stopped) {
            runtimeSettings = RuntimeSettings.fromIntent(intent) ?: run {
                Timber.e("VpnService: refusing start without runtime settings snapshot")
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }

    override fun startRunner() {
        startService(runtimeSettings.toIntent(this))
    }

    override suspend fun preInit() {
        if (prepare(this) != null) throw NullConnectionException()
        DefaultNetworkListener.start(this) { network ->
            if (network != underlyingNetwork) {
                underlyingNetwork = network
                // Pass a single-element array for the active network, or an
                // empty array when all underlying connectivity is lost so the
                // platform clears the VPN's transport association instead of
                // keeping a stale network reference.
                if (active) {
                    setUnderlyingNetworks(if (network != null) arrayOf(network) else arrayOf())
                }
                Timber.d("VpnService: underlying network changed -> $network")
            }
        }
    }

    override suspend fun startProcesses() {
        val configDir = File(Core.deviceStorage.noBackupFilesDir, "meow")
        configDir.mkdirs()
        data.mihomoInstance!!.start(
            configDir,
            this,
            runtimeSettings.blockQuic,
            runtimeSettings.disableIpv6,
        )
        startVpn()
    }

    override val isVpnService get() = true

    private fun startVpn() {
        val builder = Builder()
            .setSession("Mihomo VPN")
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
            .addDnsServer(PRIVATE_VLAN4_ROUTER)
            .addRoute("0.0.0.0", 0)

        if (!runtimeSettings.disableIpv6) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            builder.addRoute("::", 0)
        }

        // Per-app VPN routing
        val perAppPackages: Set<String> = try {
            JSONArray(runtimeSettings.perAppPackages).let { arr ->
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
        if (perAppPackages.isNotEmpty()) when (runtimeSettings.perAppMode) {
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

        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        // Capture the underlying network BEFORE establish() — after the VPN
        // is up, ConnectivityManager.getActiveNetwork() may return the VPN
        // network itself, which we must not pass to setUnderlyingNetworks.
        val underlying = underlyingNetwork ?: run {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.activeNetwork?.let { an ->
                cm.getNetworkCapabilities(an)?.let { nc ->
                    if (!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) an else null
                }
            }
        }

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn
        active = true
        // Tell the system which network the VPN sits on top of. Without
        // this, VpnService.protect(fd) knows the bypass mark to apply but
        // the platform's per-network firewall has no associated network for
        // the marked traffic, so packets are silently dropped on Xiaomi /
        // HyperOS builds.  We pass a single best network (prefer WiFi) so
        // the VPN transport label stays clean and Settings shows the
        // correct underlying connection.
        val currentUnderlying = underlyingNetwork ?: underlying
        currentUnderlying?.let { setUnderlyingNetworks(arrayOf(it)) }
        Timber.d("VpnService: setUnderlyingNetworks=$currentUnderlying")
        data.mihomoInstance!!.startTun2Socks(this, conn.fd)
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }
}
