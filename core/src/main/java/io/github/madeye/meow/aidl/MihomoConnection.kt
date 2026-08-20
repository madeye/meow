package io.github.madeye.meow.aidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.github.madeye.meow.bg.BaseService
import timber.log.Timber

class MihomoConnection(private var listenForBandwidth: Boolean = false) : ServiceConnection,
    IMihomoServiceCallback.Stub() {

    interface Callback {
        fun stateChanged(state: BaseService.State, profileName: String, msg: String?)
        fun trafficUpdated(profileId: Long, stats: TrafficStats)
        fun trafficPersisted(profileId: Long)
    }

    private var callback: Callback? = null
    private var service: IMihomoService? = null
    private var callbackRegistered = false
    private var bound = false
    // Remembered so onServiceDisconnected can rebind and resume the callback
    // registration (state + traffic) when the :vpn process restarts. Keep the
    // original context: bindService/unbindService track the dispatcher per
    // context, so rebinding with a different one would make disconnect()
    // throw "Service not registered".
    private var bindContext: Context? = null
    private var autoCreateBind = true

    val serviceState: BaseService.State
        get() = try {
            BaseService.State.entries[service?.state ?: 0]
        } catch (_: Exception) {
            BaseService.State.Idle
        }

    @Synchronized
    fun connect(context: Context, callback: Callback, autoCreate: Boolean = true): Boolean {
        this.callback = callback
        // `bound` is cleared in onServiceDisconnected (and disconnect), so this
        // guard both prevents a double bind while a connect is pending and
        // allows a rebind after the :vpn process died.
        if (bound) return true
        // Record the binding context only for an actual bind: it must be the
        // exact context passed to unbindService later.
        bindContext = context
        autoCreateBind = autoCreate
        val intent = Intent(context, io.github.madeye.meow.bg.VpnService::class.java)
            .setAction(io.github.madeye.meow.utils.Action.SERVICE)
        bound = context.bindService(
            intent,
            this,
            if (autoCreate) Context.BIND_AUTO_CREATE else 0,
        )
        return bound
    }

    @Synchronized
    fun disconnect(context: Context) {
        // Drop the rebind context first so a racing onServiceDisconnected
        // (binder thread) cannot rebind after a clean disconnect.
        bindContext = null
        if (bound) {
            unregisterCallback()
            context.unbindService(this)
            bound = false
        }
        callback = null
        service = null
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val service = IMihomoService.Stub.asInterface(binder) ?: return
        this.service = service
        try {
            service.registerCallback(this)
            callbackRegistered = true
            if (listenForBandwidth) service.startListeningForBandwidth(this, 1000)
        } catch (e: RemoteException) {
            Timber.w(e)
        }
        callback?.stateChanged(serviceState, service.profileName ?: "", null)
    }

    @Synchronized
    override fun onServiceDisconnected(name: ComponentName?) {
        callbackRegistered = false
        service = null
        bound = false
        // VPN runs in a separate :vpn process; if it dies (system kill, crash),
        // the UI would otherwise keep showing the last-known state.
        callback?.stateChanged(BaseService.State.Stopped, "", null)
        // Rebind immediately so registerCallback()/startListeningForBandwidth()
        // run against the new process instance as soon as it is up. Without
        // this, `bound` stayed true forever after a process death, the
        // Activity never re-registered, and state + traffic callbacks — and
        // thus all traffic statistics — stayed dead until the Activity was
        // recreated. Serialized with connect()/disconnect() so the rebind
        // cannot race an Activity teardown; disconnect() clears bindContext
        // first, which also makes the rebind a no-op after a clean unbind.
        bindContext?.let { ctx ->
            val intent = Intent(ctx, io.github.madeye.meow.bg.VpnService::class.java)
                .setAction(io.github.madeye.meow.utils.Action.SERVICE)
            bound = ctx.bindService(
                intent,
                this,
                if (autoCreateBind) Context.BIND_AUTO_CREATE else 0,
            )
        }
    }

    private fun unregisterCallback() {
        val service = service ?: return
        if (callbackRegistered) try {
            service.unregisterCallback(this)
        } catch (_: RemoteException) { }
        callbackRegistered = false
    }

    override fun stateChanged(state: Int, profileName: String?, msg: String?) {
        callback?.stateChanged(BaseService.State.entries[state], profileName ?: "", msg)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats?) {
        if (stats != null) callback?.trafficUpdated(profileId, stats)
    }

    override fun trafficPersisted(profileId: Long) {
        callback?.trafficPersisted(profileId)
    }
}
