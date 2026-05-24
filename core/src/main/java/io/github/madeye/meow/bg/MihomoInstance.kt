package io.github.madeye.meow.bg

import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.core.MihomoCore
import io.github.madeye.meow.database.ClashProfile
import timber.log.Timber
import java.io.File

class MihomoInstance(val profile: ClashProfile) {
    val profileName: String get() = profile.name

    private var prevTx: Long = 0
    private var prevRx: Long = 0
    private var lastUpdate: Long = 0

    fun start(configDir: File, vpnService: android.net.VpnService) {
        // Seed the engine home dir with bundled GeoX databases so meow-rs
        // never has to reach the network on first start — the upstream
        // pre-VPN fetch in meow-config goes via raw github.com which is
        // unreliable on censored / metered links. APK assets are populated
        // at build time by the `:core:downloadGeoxAssets` Gradle task.
        copyGeoxAssets(vpnService, configDir)

        val configFile = File(configDir, "config.yaml")
        // Only strip the app-managed `subscriptions:` block. Listener ports,
        // `sniffer:`, and the user `dns:` block are stripped (and the pinned
        // fake-IP DNS block injected) on the Rust side by
        // `engine::strip_and_inject` — see meow-ios for the same pattern.
        val yaml = profile.yamlContent
            .replace(Regex("(?m)^subscriptions:.*?(?=^[a-z]|\\Z)", RegexOption.DOT_MATCHES_ALL), "")
        configFile.writeText(yaml)
        MihomoCore.nativeSetHomeDir(configDir.absolutePath)
        val result = MihomoCore.nativeStartEngine("127.0.0.1:9090", "")
        if (result != 0) {
            throw RuntimeException("Failed to start engine: ${MihomoCore.nativeGetLastError()}")
        }
        Timber.d("MihomoInstance: engine started")
    }

    // Copy bundled GeoX databases from APK assets into the engine home dir.
    // Skips files that already exist (cached from a prior start). Asset
    // names follow the upstream release naming; targets follow meow-rs's
    // discovery names (`Country.mmdb` with capital C, see
    // meow_config::default_geoip_path).
    private fun copyGeoxAssets(context: android.content.Context, configDir: File) {
        val files = listOf(
            "country.mmdb" to "Country.mmdb",
            "GeoLite2-ASN.mmdb" to "GeoLite2-ASN.mmdb",
        )
        for ((assetName, targetName) in files) {
            val target = File(configDir, targetName)
            if (target.exists() && target.length() > 0) continue
            try {
                context.assets.open("geox/$assetName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.d("MihomoInstance: seeded $targetName from assets (${target.length()} bytes)")
            } catch (e: Exception) {
                Timber.w(e, "MihomoInstance: failed to seed $targetName from assets")
            }
        }
    }

    fun startTun2Socks(vpnService: android.net.VpnService, fd: Int) {
        val result = MihomoCore.nativeStartTun2Socks(vpnService, fd, 1053)
        if (result != 0) {
            throw RuntimeException("Failed to start tun2socks: ${MihomoCore.nativeGetLastError()}")
        }
        Timber.d("MihomoInstance: tun2socks started")
    }

    fun stop() {
        MihomoCore.nativeStopEngine()
        Timber.d("MihomoInstance: engine stopped")
    }

    fun requestTrafficUpdate(): TrafficStats {
        val tx = MihomoCore.nativeGetUploadTraffic()
        val rx = MihomoCore.nativeGetDownloadTraffic()
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
