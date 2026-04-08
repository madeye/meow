package io.github.madeye.meow.bg

import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.core.MihomoEngine
import io.github.madeye.meow.core.Tun2SocksCore
import io.github.madeye.meow.database.ClashProfile
import timber.log.Timber
import java.io.File

class MihomoInstance(val profile: ClashProfile) {
    val profileName: String get() = profile.name

    private var prevTx: Long = 0
    private var prevRx: Long = 0
    private var lastUpdate: Long = 0

    fun start(configDir: File) {
        val configFile = File(configDir, "config.yaml")
        // Strip sections handled by the app, not mihomo:
        // - subscriptions: refresh is done by the app
        // - dns: DNS is handled by the tun2socks DoH forwarder; mihomo's
        //   own resolver is left disabled so the two paths don't race.
        val yaml = profile.yamlContent
            .replace(Regex("(?m)^subscriptions:.*?(?=^[a-z]|\\Z)", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^dns:.*?(?=^[a-z]|\\Z)", RegexOption.DOT_MATCHES_ALL), "dns:\n  enable: false\n")
            .replace(Regex("(?m)^port:.*\n?"), "")
            .replace(Regex("(?m)^socks-port:.*\n?"), "")
            .replace(Regex("(?m)^mixed-port:.*\n?"), "")
            .let { "mixed-port: 7890\n$it" }
        configFile.writeText(yaml)
        MihomoEngine.nativeSetHomeDir(configDir.absolutePath)
        // The Rust tun2socks side also needs the home dir so its DoH
        // client can read DoH URLs out of config.yaml.
        Tun2SocksCore.nativeSetHomeDir(configDir.absolutePath)
        val result = MihomoEngine.nativeStartEngine("127.0.0.1:9090", "")
        if (result != 0) {
            throw RuntimeException("Failed to start engine: ${MihomoEngine.nativeGetLastError()}")
        }
        Timber.d("MihomoInstance: engine started")
    }

    fun startTun2Socks(vpnService: android.net.VpnService, fd: Int) {
        // Register the VpnService with the Go engine first — its dialer
        // protect hook needs to be live before any outbound proxy socket
        // is created (the hook runs synchronously inside dialer.Control).
        MihomoEngine.nativeSetProtect(vpnService)
        val result = Tun2SocksCore.nativeStartTun2Socks(vpnService, fd, 7890, 1053)
        if (result != 0) {
            throw RuntimeException("Failed to start tun2socks: ${Tun2SocksCore.nativeGetLastError()}")
        }
        Timber.d("MihomoInstance: tun2socks started")
    }

    fun stop() {
        // Shut tun2socks down first so no more packets are flowing into
        // mihomo's mixed listener, then stop the engine, then clear the
        // protect ref.
        Tun2SocksCore.nativeStopTun2Socks()
        MihomoEngine.nativeStopEngine()
        MihomoEngine.nativeSetProtect(null)
        Timber.d("MihomoInstance: engine stopped")
    }

    fun requestTrafficUpdate(): TrafficStats {
        val tx = MihomoEngine.nativeGetUploadTraffic()
        val rx = MihomoEngine.nativeGetDownloadTraffic()
        val now = System.currentTimeMillis()
        val elapsed = if (lastUpdate > 0) now - lastUpdate else 1000L
        val stats = TrafficStats(
            txRate = if (elapsed > 0) (tx - prevTx) * 1000 / elapsed else 0,
            rxRate = if (elapsed > 0) (rx - prevRx) * 1000 / elapsed else 0,
            txTotal = tx,
            rxTotal = rx,
        )
        prevTx = tx
        prevRx = rx
        lastUpdate = now
        return stats
    }
}
