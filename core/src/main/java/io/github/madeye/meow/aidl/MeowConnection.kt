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

    val serviceState: BaseService.State
        get() = try {
            BaseService.State.entries[service?.state ?: 0]
        } catch (_: Exception) {
            BaseService.State.Idle
        }

    fun connect(context: Context, callback: Callback) {
        this.callback = callback
        val intent = Intent(context, io.github.madeye.meow.bg.VpnService::class.java)
            .setAction(io.github.madeye.meow.utils.Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        context.unbindService(this)
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

    override fun onServiceDisconnected(name: ComponentName?) {
        callbackRegistered = false
        service = null
        // VPN runs in a separate :vpn process; if it dies (system kill, crash),
        // the UI would otherwise keep showing the last-known state.
        callback?.stateChanged(BaseService.State.Stopped, "", null)
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
