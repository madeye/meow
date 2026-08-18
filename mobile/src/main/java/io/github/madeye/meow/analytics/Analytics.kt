package io.github.madeye.meow.analytics

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent

/**
 * Every analytics event the app emits, in one place.
 *
 * These names were previously scattered through `MainActivity`'s MethodChannel
 * `when` block. They are load-bearing for existing dashboards, so the names and
 * parameters are preserved exactly — renaming one silently blanks a chart.
 */
class Analytics(context: Context) {

    private val firebase = FirebaseAnalytics.getInstance(context)

    fun vpnConnect() = firebase.logEvent("vpn_connect") {}

    fun vpnDisconnect() = firebase.logEvent("vpn_disconnect") {}

    fun vpnStateChange(state: String, profile: String) = firebase.logEvent("vpn_state_change") {
        param("state", state)
        if (profile.isNotEmpty()) param("profile", profile)
    }

    fun subscriptionAdd() = firebase.logEvent("subscription_add") {}

    fun subscriptionEdit() = firebase.logEvent("subscription_edit") {}

    fun subscriptionDelete() = firebase.logEvent("subscription_delete") {}

    fun subscriptionRefresh() = firebase.logEvent("subscription_refresh") {}

    fun subscriptionRefreshAll() = firebase.logEvent("subscription_refresh_all") {}

    fun profileSelect() = firebase.logEvent("profile_select") {}

    fun proxyNodeSelect(proxyName: String) = firebase.logEvent("proxy_node_select") {
        param("proxy_name", proxyName)
    }

    fun profileYamlEdit() = firebase.logEvent("profile_yaml_edit") {}

    fun profileYamlRevert() = firebase.logEvent("profile_yaml_revert") {}

    fun perAppProxySave(mode: String) = firebase.logEvent("per_app_proxy_save") {
        param("mode", mode)
    }

    fun configImport() = firebase.logEvent("config_import") {}

    fun configExport() = firebase.logEvent("config_export") {}
}
