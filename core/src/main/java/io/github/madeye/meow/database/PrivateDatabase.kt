package io.github.madeye.meow.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.madeye.meow.Core

@Database(entities = [ClashProfile::class, DailyTraffic::class], version = 4)
abstract class PrivateDatabase : RoomDatabase() {
    companion object {
        private val instance by lazy {
            // The database was named mihomo.db before the app-wide
            // mihomo→meow rename; move an existing file (and its WAL/SHM
            // sidecars) so installed users keep their profiles.
            val legacy = Core.deviceStorage.getDatabasePath("mihomo.db")
            val current = Core.deviceStorage.getDatabasePath("meow.db")
            if (legacy.exists() && !current.exists()) {
                current.parentFile?.mkdirs()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val from = java.io.File(legacy.path + suffix)
                    if (from.exists()) from.renameTo(java.io.File(current.path + suffix))
                }
            }
            Room.databaseBuilder(Core.deviceStorage, PrivateDatabase::class.java, "meow.db")
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
        }

        val profileDao get() = instance.profileDao()
        val dailyTrafficDao get() = instance.dailyTrafficDao()
    }

    abstract fun profileDao(): ProfileDao
    abstract fun dailyTrafficDao(): DailyTrafficDao
}
