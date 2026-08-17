package io.github.madeye.meow.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.content.ContextCompat
import io.github.madeye.meow.Core
import io.github.madeye.meow.aidl.IMeowService
import io.github.madeye.meow.aidl.IMeowServiceCallback
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.*
import timber.log.Timber

object BaseService {
    enum class State(val canStop: Boolean = false) {
        Idle,
        Connecting(true),
        Connected(true),
        Stopping,
        Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var meowInstance: MeowInstance? = null
        var notification: ServiceNotification? = null
        var closeReceiverRegistered = false
        val binder = Binder(this)
        var connectingJob: Job? = null

        val closeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SHUTDOWN -> {}
                    Action.RELOAD -> service.forceLoad()
                    else -> service.stopRunner()
                }
            }
        }

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            binder.stateChanged(s, msg)
            state = s
        }
    }

    class Binder(private var data: Data? = null) : IMeowService.Stub(), CoroutineScope, AutoCloseable {
        private val callbacks = RemoteCallbackList<IMeowServiceCallback>()
        private val bandwidthListeners = mutableMapOf<IBinder, Long>()
        override val coroutineContext = Dispatchers.Main.immediate + Job()
        private var looper: Job? = null

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.meowInstance?.profileName ?: "Idle"

        override fun registerCallback(cb: IMeowServiceCallback) { callbacks.register(cb) }

        private fun broadcast(work: (IMeowServiceCallback) -> Unit) {
            val count = callbacks.beginBroadcast()
            try {
                repeat(count) {
                    try { work(callbacks.getBroadcastItem(it)) }
                    catch (_: RemoteException) {}
                    catch (e: Exception) { Timber.w(e) }
                }
            } finally { callbacks.finishBroadcast() }
        }

        private suspend fun loop() {
            while (true) {
                delay(bandwidthListeners.values.minOrNull() ?: return)
                val instance = data?.meowInstance ?: continue
                if (data?.state != State.Connected || bandwidthListeners.isEmpty()) continue
                val stats = instance.requestTrafficUpdate()
                broadcast { item ->
                    if (bandwidthListeners.contains(item.asBinder())) {
                        item.trafficUpdated(0, stats)
                    }
                }
            }
        }

        override fun startListeningForBandwidth(cb: IMeowServiceCallback, timeout: Long) {
            launch {
                if (bandwidthListeners.isEmpty() && bandwidthListeners.put(cb.asBinder(), timeout) == null) {
                    looper = launch { loop() }
                }
            }
        }

        override fun stopListeningForBandwidth(cb: IMeowServiceCallback) {
            launch {
                if (bandwidthListeners.remove(cb.asBinder()) != null && bandwidthListeners.isEmpty()) {
                    looper?.cancel()
                    looper = null
                }
            }
        }

        override fun unregisterCallback(cb: IMeowServiceCallback) {
            stopListeningForBandwidth(cb)
            callbacks.unregister(cb)
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun forceLoad() {
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Timber.w("Illegal state $s when invoking use")
            }
        }

        val isVpnService get() = false

        suspend fun startProcesses()

        fun startRunner() {
            this as Context
            startService(Intent(this, javaClass))
        }

        fun killProcesses(scope: CoroutineScope) {
            data.meowInstance?.stop()
            data.meowInstance = null
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            if (data.state == State.Stopping) return
            data.changeState(State.Stopping)
            GlobalScope.launch(Dispatchers.Main.immediate) {
                data.connectingJob?.cancelAndJoin()
                this@Interface as Service
                coroutineScope {
                    killProcesses(this)
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.closeReceiver)
                        data.closeReceiverRegistered = false
                    }
                    data.notification?.destroy()
                    data.notification = null
                }
                data.changeState(State.Stopped, msg)
                if (restart) startRunner() else stopSelf()
            }
        }

        suspend fun preInit() {}

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY

            val profile = Core.currentProfile
            this as Context
            if (profile == null) {
                data.notification = createNotification("")
                stopRunner(false, "No profile selected")
                return Service.START_NOT_STICKY
            }

            data.meowInstance = MeowInstance(profile)

            if (!data.closeReceiverRegistered) {
                ContextCompat.registerReceiver(this, data.closeReceiver, IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                data.closeReceiverRegistered = true
            }

            data.notification = createNotification(profile.name)
            data.changeState(State.Connecting)
            data.connectingJob = GlobalScope.launch(Dispatchers.Main.immediate) {
                try {
                    preInit()
                    startProcesses()
                    data.changeState(State.Connected)
                } catch (_: CancellationException) {
                } catch (exc: Throwable) {
                    Timber.w(exc)
                    stopRunner(false, "Service failed: ${exc.localizedMessage}")
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }
}
