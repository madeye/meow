package io.github.madeye.meow.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.util.AppVersions
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val appVersion: String = "",
    val engineVersion: String = "",
    /** The engine-backed screens can only load while the tunnel is up. */
    val engineOnline: Boolean = false,
)

class SettingsViewModel(
    private val versions: AppVersions,
    vpn: VpnStateRepository,
) : ViewModel() {

    private val versionPair = MutableStateFlow("" to "")

    val uiState: StateFlow<SettingsUiState> = combine(
        versionPair,
        vpn.state.map { it == BaseService.State.Connected },
    ) { (app, engine), online ->
        SettingsUiState(appVersion = app, engineVersion = engine, engineOnline = online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { versionPair.value = versions.read() }
    }
}
