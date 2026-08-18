package io.github.madeye.meow.ui.screens.traffic

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.repo.TrafficHistoryRepository
import io.github.madeye.meow.repo.TrafficTotals
import io.github.madeye.meow.vpn.SpeedSample
import io.github.madeye.meow.vpn.SpeedSampleStore
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class TrafficUiState(
    val connected: Boolean = false,
    val traffic: TrafficStats = TrafficStats(),
    val today: TrafficTotals = TrafficTotals.Zero,
    val thisMonth: TrafficTotals = TrafficTotals.Zero,
    val history: List<DailyTraffic> = emptyList(),
    val samples: List<SpeedSample> = emptyList(),
    val selectedDay: Int? = null,
)

class TrafficViewModel(
    private val vpn: VpnStateRepository,
    private val history: TrafficHistoryRepository,
    speedSamples: SpeedSampleStore,
) : ViewModel() {

    private val selectedDay = MutableStateFlow<Int?>(null)
    private val today = MutableStateFlow(TrafficTotals.Zero)
    private val thisMonth = MutableStateFlow(TrafficTotals.Zero)

    val uiState: StateFlow<TrafficUiState> = combine(
        vpn.state,
        vpn.traffic,
        history.observeWindow(),
        speedSamples.samples,
        combine(today, thisMonth, selectedDay) { day, month, selected ->
            Triple(day, month, selected)
        },
    ) { state, traffic, window, samples, (day, month, selected) ->
        TrafficUiState(
            connected = state == BaseService.State.Connected,
            traffic = traffic,
            today = day,
            thisMonth = month,
            history = window,
            samples = samples,
            selectedDay = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrafficUiState())

    init {
        // The aggregates are cheap but not free, so they refresh on each new
        // traffic sample rather than being recomputed per frame.
        viewModelScope.launch {
            vpn.traffic.collect { refreshTotals() }
        }
        viewModelScope.launch {
            history.prune()
            refreshTotals()
        }
    }

    fun onSelectDay(index: Int?) {
        selectedDay.value = index
    }

    private suspend fun refreshTotals() {
        today.value = history.today()
        thisMonth.value = history.thisMonth()
    }
}
