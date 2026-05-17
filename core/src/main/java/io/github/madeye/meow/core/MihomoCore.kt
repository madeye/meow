package io.github.madeye.meow.core

object MihomoCore {
    init {
        System.loadLibrary("mihomo_android_ffi")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeSetHomeDir(dir: String)
    external fun nativeStartEngine(addr: String, secret: String): Int
    external fun nativeStopEngine()
    external fun nativeStartTun2Socks(vpnService: Any, fd: Int, dnsPort: Int): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeGetUploadTraffic(): Long
    external fun nativeGetDownloadTraffic(): Long
    external fun nativeValidateConfig(yaml: String): Int
    external fun nativeGetLastError(): String
    external fun nativeVersion(): String
    external fun nativeTestDirectTcp(host: String, port: Int): String
    external fun nativeTestDnsResolver(dnsAddr: String): String
}
