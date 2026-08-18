package io.github.madeye.meow

import android.app.Application
import io.github.madeye.meow.database.PrivateDatabase
import io.github.madeye.meow.editor.SoraTextMateBootstrap
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Core.init(this)
        AppGraph.init(this)
        // Ensure database is created on first launch
        PrivateDatabase.profileDao.getAll()
        // Sora Editor TextMate registries are process-global; populate once.
        SoraTextMateBootstrap.init(this)
    }
}
