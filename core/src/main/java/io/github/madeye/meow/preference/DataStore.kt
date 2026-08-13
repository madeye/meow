package io.github.madeye.meow.preference

import androidx.preference.PreferenceManager
import io.github.madeye.meow.Core

object DataStore {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(Core.deviceStorage)

    var serviceMode: String
        get() = prefs.getString("serviceMode", "vpn") ?: "vpn"
        set(value) = prefs.edit().putString("serviceMode", value).apply()

    var portProxy: Int
        get() = prefs.getInt("portProxy", 7890)
        set(value) = prefs.edit().putInt("portProxy", value).apply()

    var portLocalDns: Int
        get() = prefs.getInt("portLocalDns", 1053)
        set(value) = prefs.edit().putInt("portLocalDns", value).apply()

    var perAppMode: String
        get() = prefs.getString("perAppMode", "proxy") ?: "proxy"
        set(value) = prefs.edit().putString("perAppMode", value).apply()

    var perAppPackages: String
        get() = prefs.getString("perAppPackages", "[]") ?: "[]"
        set(value) = prefs.edit().putString("perAppPackages", value).apply()

    val blockQuic: Boolean
        get() = prefs.getBoolean("blockQuic", true)

    val disableIpv6: Boolean
        get() = prefs.getBoolean("disableIpv6", false)

    fun setNetworkPrefs(blockQuic: Boolean, disableIpv6: Boolean): Boolean =
        prefs.edit()
            .putBoolean("blockQuic", blockQuic)
            .putBoolean("disableIpv6", disableIpv6)
            .commit()
}
