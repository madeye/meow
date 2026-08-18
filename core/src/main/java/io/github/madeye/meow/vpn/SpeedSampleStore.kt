package io.github.madeye.meow.vpn

import io.github.madeye.meow.bg.BaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpeedSample(val timeMillis: Long, val txRate: Long, val rxRate: Long)

/**
 * Rolling window of transfer rates behind the live speed chart.
 *
 * Deliberately process-scoped rather than owned by the Traffic screen's
 * ViewModel: the chart would otherwise reset to empty every time the user
 * visits another tab. meow-ios solves the same problem with
 * `UtilityTrafficChartStore`.
 */
class SpeedSampleStore(
    private val repository: VpnStateRepository,
    scope: CoroutineScope,
    private val capacity: Int = CAPACITY,
) {
    private val _samples = MutableStateFlow<List<SpeedSample>>(emptyList())
    val samples: StateFlow<List<SpeedSample>> = _samples.asStateFlow()

    init {
        scope.launch {
            repository.traffic.collect { stats ->
                _samples.value = (
                    _samples.value + SpeedSample(
                        timeMillis = System.currentTimeMillis(),
                        txRate = stats.txRate,
                        rxRate = stats.rxRate,
                    )
                    ).takeLast(capacity)
            }
        }
        scope.launch {
            // A new session starts a new chart; stale samples from the previous
            // connection would show as a false spike at the left edge.
            repository.state.collect { state ->
                if (state == BaseService.State.Connected) _samples.value = emptyList()
            }
        }
    }

    companion object {
        const val CAPACITY = 60
    }
}
