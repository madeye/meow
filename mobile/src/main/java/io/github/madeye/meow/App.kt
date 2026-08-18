package io.github.madeye.meow

import android.app.ActivityManager
import android.app.Application
import androidx.core.content.getSystemService
import io.github.madeye.meow.database.PrivateDatabase
import io.github.madeye.meow.editor.SoraTextMateBootstrap
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Core.init(this)

        // onCreate runs in every process, and the VPN service lives in :vpn.
        // Only the UI process needs the UI graph, the Room warm-up and the
        // editor's TextMate registries; doing that work in :vpn just delays the
        // service's startup, which the system watches with a short timeout.
        if (!isMainProcess()) return

        AppGraph.init(this)
        // Ensure database is created on first launch
        PrivateDatabase.profileDao.getAll()
        // Sora Editor TextMate registries are process-global; populate once.
        SoraTextMateBootstrap.init(this)
    }

    private fun isMainProcess(): Boolean {
        val name = currentProcessName() ?: return true
        return name == packageName
    }

    private fun currentProcessName(): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = android.os.Process.myPid()
        return getSystemService<ActivityManager>()
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }
}
