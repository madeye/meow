package io.github.madeye.meow.core

/**
 * JNI bridge to the Rust tun2socks library (libmihomo_android_ffi.so).
 *
 * This object owns the netstack-smoltcp-based tun2socks layer that reads
 * packets from the Android TUN fd, relays TCP to the SOCKS5 listener
 * exposed by [MihomoEngine] on 127.0.0.1:7890, and intercepts UDP:53 for
 * DoH forwarding. The proxy engine itself (protocol adapters, rules, API
 * controller) lives in [MihomoEngine].
 */
object Tun2SocksCore {
    init {
        System.loadLibrary("mihomo_android_ffi")
        nativeInit()
    }

    external fun nativeInit()

    /**
     * Points the DoH client at the config directory so it can read the
     * configured DoH server list out of `config.yaml`.
     */
    external fun nativeSetHomeDir(dir: String)

    /**
     * Starts the tun2socks loop. [vpnService] is retained for ABI
     * compatibility but is no longer used for socket protection — that
     * responsibility has moved to [MihomoEngine.nativeSetProtect], since
     * all outbound sockets that need protecting are created by the Go
     * engine, not the Rust tun2socks layer (which only deals with
     * loopback sockets).
     */
    external fun nativeStartTun2Socks(
        vpnService: Any,
        fd: Int,
        socksPort: Int,
        dnsPort: Int,
    ): Int

    external fun nativeStopTun2Socks()

    external fun nativeGetLastError(): String
}
