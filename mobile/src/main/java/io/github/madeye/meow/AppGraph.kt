package io.github.madeye.meow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppRepository
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.repo.TrafficHistoryRepository
import io.github.madeye.meow.ui.screens.connections.ConnectionsViewModel
import io.github.madeye.meow.ui.screens.home.HomeViewModel
import io.github.madeye.meow.ui.screens.logs.LogsViewModel
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyViewModel
import io.github.madeye.meow.ui.screens.rules.RulesViewModel
import io.github.madeye.meow.ui.screens.settings.SettingsViewModel
import io.github.madeye.meow.ui.screens.subscribe.SubscribeViewModel
import io.github.madeye.meow.ui.screens.traffic.TrafficViewModel
import io.github.madeye.meow.ui.screens.yaml.YamlEditorViewModel
import io.github.madeye.meow.ui.util.AppVersions
import io.github.madeye.meow.vpn.SpeedSampleStore
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency graph.
 *
 * Nine ViewModels over a handful of singletons does not justify Hilt's
 * annotation processing round; this is ~40 lines and reads top to bottom.
 */
object AppGraph {

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api: MeowApi by lazy { MeowApi() }
    val vpn: VpnStateRepository by lazy { VpnStateRepository() }
    val speedSamples: SpeedSampleStore by lazy { SpeedSampleStore(vpn, scope) }
    val profiles: ProfileRepository by lazy { ProfileRepository() }
    val trafficHistory: TrafficHistoryRepository by lazy { TrafficHistoryRepository() }
    val perApp: PerAppRepository by lazy { PerAppRepository() }
    val installedApps: InstalledAppsRepository by lazy { InstalledAppsRepository(appContext) }
    val configValidator: ConfigValidator by lazy { ConfigValidator() }
    val analytics: Analytics by lazy { Analytics(appContext) }
    val appVersions: AppVersions by lazy { AppVersions(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Touching this here starts the speed-sample collector, so the chart has
        // history even if the user never opens the Traffic tab before connecting.
        speedSamples
    }

    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            when (modelClass) {
                HomeViewModel::class.java ->
                    HomeViewModel(vpn, profiles, api, analytics)

                SubscribeViewModel::class.java ->
                    SubscribeViewModel(profiles, configValidator, analytics)

                TrafficViewModel::class.java ->
                    TrafficViewModel(vpn, trafficHistory, speedSamples)

                SettingsViewModel::class.java ->
                    SettingsViewModel(appVersions, vpn)

                PerAppProxyViewModel::class.java ->
                    PerAppProxyViewModel(perApp, installedApps, analytics)

                YamlEditorViewModel::class.java ->
                    YamlEditorViewModel(extras.createSavedStateHandle(), profiles, configValidator, analytics)

                ConnectionsViewModel::class.java -> ConnectionsViewModel(api)
                RulesViewModel::class.java -> RulesViewModel(api)
                LogsViewModel::class.java -> LogsViewModel(api)

                else -> error("unknown ViewModel: ${modelClass.name}")
            } as T
    }
}
