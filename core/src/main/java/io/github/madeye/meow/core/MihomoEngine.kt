package io.github.madeye.meow.core

/**
 * JNI bridge to the Go-backed mihomo engine (libmihomo.so).
 *
 * This is the proxy engine half of the native stack: config parsing,
 * tunnel/listener lifecycle, traffic stats, diagnostics. The other half —
 * the Rust tun2socks layer that reads the TUN fd and relays to this
 * engine's SOCKS listener on 127.0.0.1:7890 — lives in [Tun2SocksCore].
 *
 * The two libraries load independently; JNI name mangling keeps their
 * symbol spaces disjoint because each class has its own
 * `Java_io_github_madeye_meow_core_<ClassName>_*` prefix.
 */
object MihomoEngine {
    init {
        System.loadLibrary("mihomo")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeSetHomeDir(dir: String)
    external fun nativeStartEngine(addr: String, secret: String): Int
    external fun nativeStopEngine()

    /**
     * Registers the VpnService used by mihomo's dialer protect hook.
     * Pass `null` to clear the reference (e.g. on stop). Safe to call
     * multiple times; the previous GlobalRef is released each time.
     */
    external fun nativeSetProtect(vpnService: Any?)

    external fun nativeIsRunning(): Boolean
    external fun nativeGetUploadTraffic(): Long
    external fun nativeGetDownloadTraffic(): Long
    external fun nativeValidateConfig(yaml: String): Int
    external fun nativeGetLastError(): String
    external fun nativeVersion(): String
    external fun nativeTestDirectTcp(host: String, port: Int): String
    external fun nativeTestProxyHttp(url: String): String
    external fun nativeTestDnsResolver(dnsAddr: String): String
}
