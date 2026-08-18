package io.github.madeye.meow.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class ProxyNodeUi(
    val name: String,
    val type: String,
    val delayMs: Int?,
    val selected: Boolean,
)

@Immutable
data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
)

@Immutable
data class HomeUiState(
    val state: BaseService.State = BaseService.State.Idle,
    val profileName: String = "",
    val hasProfile: Boolean = false,
    val traffic: TrafficStats = TrafficStats(),
    val groups: List<ProxyGroupUi> = emptyList(),
    val expandedGroup: String? = null,
    val testingGroup: String? = null,
) {
    val isConnected: Boolean get() = state == BaseService.State.Connected

    /** The switch is inert mid-transition, matching the old Flutter behaviour. */
    val isBusy: Boolean
        get() = state == BaseService.State.Connecting || state == BaseService.State.Stopping
}

class HomeViewModel(
    private val vpn: VpnStateRepository,
    private val profiles: ProfileRepository,
    private val api: MeowApi,
    private val analytics: Analytics,
) : ViewModel() {

    private val groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    private val expanded = MutableStateFlow<String?>(null)
    private val testing = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        vpn.state,
        vpn.traffic,
        profiles.observeSelected(),
        groups,
        combine(expanded, testing) { expandedGroup, testingGroup -> expandedGroup to testingGroup },
    ) { state, traffic, profile, groupList, (expandedGroup, testingGroup) ->
        HomeUiState(
            state = state,
            profileName = profile?.name.orEmpty(),
            hasProfile = profile != null,
            traffic = traffic,
            groups = groupList,
            expandedGroup = expandedGroup,
            testingGroup = testingGroup,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Groups only exist while the engine is up, and they change when the
        // user switches profile — so reload on every state transition.
        viewModelScope.launch {
            vpn.state.collect { state ->
                if (state == BaseService.State.Connected) loadGroups() else groups.value = emptyList()
            }
        }
    }

    /** The `:vpn` process may have been killed while backgrounded. */
    fun onResume() {
        vpn.refresh()
        if (vpn.state.value == BaseService.State.Connected) loadGroups()
    }

    fun onToggleExpanded(group: String) {
        expanded.value = if (expanded.value == group) null else group
    }

    fun onConnectRequested() = analytics.vpnConnect()

    fun onDisconnect(context: android.content.Context) {
        analytics.vpnDisconnect()
        vpn.requestStop(context)
    }

    fun onSelectNode(group: String, node: String) {
        viewModelScope.launch {
            try {
                api.selectProxy(group, node)
                analytics.proxyNodeSelect(node)
                // Persist the pick so it can be replayed on the next connect.
                profiles.getSelected()?.let { profiles.saveSelectedProxy(it.id, node) }
                loadGroups()
            } catch (e: MeowApiException) {
                // The engine is the source of truth; a failed select simply
                // leaves the previous node in place on the next refresh.
                loadGroups()
            }
        }
    }

    fun onTestGroup(group: String) {
        viewModelScope.launch {
            testing.value = group
            try {
                api.testGroupDelay(group)
            } catch (e: MeowApiException) {
                // Individual probes can time out; the reload below still shows
                // whatever latencies did come back.
            } finally {
                testing.value = null
                loadGroups()
            }
        }
    }

    private fun loadGroups() {
        viewModelScope.launch {
            groups.value = try {
                val result = api.proxies()
                result.selectableGroups.map { group ->
                    ProxyGroupUi(
                        name = group.name,
                        type = group.type,
                        now = group.now,
                        nodes = group.all.map { nodeName ->
                            val node = result.proxies[nodeName]
                            ProxyNodeUi(
                                name = nodeName,
                                type = node?.type.orEmpty(),
                                delayMs = node?.latestDelay?.takeIf { it > 0 },
                                selected = nodeName == group.now,
                            )
                        },
                    )
                }
            } catch (e: MeowApiException) {
                // An empty group list and a failed fetch look identical on
                // screen, so the reason has to reach logcat or it is invisible.
                Timber.w(e, "loading proxy groups failed")
                emptyList()
            }
        }
    }
}
