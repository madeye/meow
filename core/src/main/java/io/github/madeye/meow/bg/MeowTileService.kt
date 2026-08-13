package io.github.madeye.meow.bg

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService as BaseTileService
import androidx.annotation.RequiresApi
import io.github.madeye.meow.aidl.MihomoConnection
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.core.R
import io.github.madeye.meow.utils.Action

@RequiresApi(Build.VERSION_CODES.N)
class MeowTileService : BaseTileService(), MihomoConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy { Icon.createWithResource(this, R.drawable.ic_service_active) }

    private val connection = MihomoConnection()

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    override fun stateChanged(state: BaseService.State, profileName: String, msg: String?) {
        updateTile(state, profileName)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {}

    override fun trafficPersisted(profileId: Long) {}

    private fun updateTile(serviceState: BaseService.State, profileName: String) {
        val running = serviceState == BaseService.State.Connecting ||
            serviceState == BaseService.State.Connected ||
            serviceState == BaseService.State.Stopping

        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            icon = when (serviceState) {
                BaseService.State.Connected -> iconConnected
                BaseService.State.Connecting,
                BaseService.State.Stopping -> iconBusy
                else -> iconIdle
            }
            label = if (serviceState == BaseService.State.Connected && profileName.isNotEmpty())
                profileName else getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        val tile = qsTile ?: return
        when (tile.state) {
            Tile.STATE_INACTIVE -> startVpn()
            Tile.STATE_ACTIVE -> sendBroadcast(Intent(Action.CLOSE).setPackage(packageName))
        }
    }

    private fun startVpn() {
        if (android.net.VpnService.prepare(this) != null) {
            packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { startActivity(it) }
            return
        }
        val intent = VpnService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
