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
        // Seed the mihomo home dir with bundled GeoX files so the engine
        // doesn't have to download them on first start.
        copyGeoxAssets(vpnService, configDir)

        val configFile = File(configDir, "config.yaml")
        // Only strip the app-managed `subscriptions:` block. Listener ports,
        // `sniffer:`, and the user `dns:` block are stripped (and the pinned
        // fake-IP DNS block injected) on the Rust side by
        // `engine::strip_and_inject` — see meow-ios for the same pattern.
        val yaml = profile.yamlContent
            .replace(Regex("(?m)^subscriptions:.*?(?=^[a-z]|\\Z)", RegexOption.DOT_MATCHES_ALL), "")
            .let { injectGeoxUrl(it) }
        configFile.writeText(yaml)
        MihomoCore.nativeSetHomeDir(configDir.absolutePath)
        val result = MihomoCore.nativeStartEngine("127.0.0.1:9090", "")
        if (result != 0) {
            throw RuntimeException("Failed to start engine: ${MihomoCore.nativeGetLastError()}")
        }
        Timber.d("MihomoInstance: engine started")
    }

    private fun copyGeoxAssets(context: android.content.Context, configDir: File) {
        // Map: asset name -> target name (meow-rs expects Country.mmdb with capital C)
        val files = listOf(
            "geoip.metadb" to "geoip.metadb",
            "geosite.dat" to "geosite.dat",
            "country.mmdb" to "Country.mmdb",
            "GeoLite2-ASN.mmdb" to "GeoLite2-ASN.mmdb",
        )
        for ((assetName, targetName) in files) {
            val target = File(configDir, targetName)
            if (target.exists() && target.length() > 0) continue
            try {
                context.assets.open("geox/$assetName").use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.d("MihomoInstance: seeded $targetName from assets (${target.length()} bytes)")
            } catch (e: Exception) {
                Timber.w(e, "MihomoInstance: failed to seed $targetName from assets")
            }
        }
    }

    private fun injectGeoxUrl(yaml: String): String {
        if (Regex("(?m)^geox-url:").containsMatchIn(yaml)) return yaml
        val base = "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release"
        val block = buildString {
            appendLine("geox-url:")
            appendLine("  geoip: \"$base/geoip.metadb\"")
            appendLine("  geosite: \"$base/geosite.dat\"")
            appendLine("  mmdb: \"$base/country.mmdb\"")
            appendLine("  asn: \"$base/GeoLite2-ASN.mmdb\"")
        }
        return block + yaml
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
