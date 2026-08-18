package io.github.madeye.meow.vpn

import android.content.Context
import android.content.Intent
import io.github.madeye.meow.aidl.MeowConnection
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turns the AIDL service callbacks into flows the UI can collect.
 *
 * The binding stays activity-scoped, exactly as it was before: binding with
 * `BIND_AUTO_CREATE` from a process-lifetime owner would keep the `:vpn`
 * process alive forever.
 */
class VpnStateRepository(
    private val recorder: DailyTrafficRecorder = DailyTrafficRecorder(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MeowConnection.Callback {

    private val connection = MeowConnection(listenForBandwidth = true)

    private val _state = MutableStateFlow(BaseService.State.Idle)
    val state: StateFlow<BaseService.State> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _profileName = MutableStateFlow("")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    /** Service-reported failures, surfaced once each (snackbar, not state). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun bind(context: Context) = connection.connect(context, this)

    fun unbind(context: Context) = connection.disconnect(context)

    /**
     * Re-reads the binder after a resume. The `:vpn` process can be killed while
     * the app is backgrounded, in which case the last state we saw is stale.
     */
    fun refresh() {
        _state.value = connection.serviceState
    }

    fun requestStop(context: Context) {
        context.sendBroadcast(Intent(Action.CLOSE).setPackage(context.packageName))
    }

    override fun stateChanged(state: BaseService.State, profileName: String, msg: String?) {
        _state.value = state
        if (profileName.isNotEmpty()) _profileName.value = profileName
        if (state == BaseService.State.Connected) recorder.reset()
        if (!msg.isNullOrEmpty()) _messages.tryEmit(msg)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        _traffic.value = stats
        scope.launch { recorder.record(stats) }
    }

    override fun trafficPersisted(profileId: Long) = Unit
}
