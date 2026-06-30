package io.github.madeye.meow.subscription

import io.github.madeye.meow.database.ClashProfile
import io.github.madeye.meow.database.PrivateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object SubscriptionService {
    suspend fun fetchSubscription(profile: ClashProfile): ClashProfile = withContext(Dispatchers.IO) {
        val url = URL(profile.url)
        val connection = url.openConnection()
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "clash.meta/1.0")
        val yaml = connection.inputStream.bufferedReader().readText()
        profile.copy(yamlContent = yaml, yamlBackup = yaml, lastUpdated = System.currentTimeMillis())
    }

    suspend fun addSubscription(name: String, url: String): ClashProfile = withContext(Dispatchers.IO) {
        val profile = ClashProfile(name = name, url = url)
        val fetched = fetchSubscription(profile)
        val id = PrivateDatabase.profileDao.insert(fetched)
        fetched.copy(id = id)
    }

    /// Create a profile from a YAML string the user imported from a file. It
    /// has no source URL, so refresh-from-URL skips it (see [refreshAll]).
    suspend fun addLocal(name: String, yamlContent: String): ClashProfile = withContext(Dispatchers.IO) {
        val profile = ClashProfile(
            name = name,
            url = "",
            yamlContent = yamlContent,
            yamlBackup = yamlContent,
            lastUpdated = System.currentTimeMillis(),
        )
        val id = PrivateDatabase.profileDao.insert(profile)
        profile.copy(id = id)
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val profiles = PrivateDatabase.profileDao.getAll().filter { it.url.isNotEmpty() }
        for (profile in profiles) {
            try {
                val updated = fetchSubscription(profile)
                PrivateDatabase.profileDao.update(updated)
            } catch (_: Exception) { }
        }
    }
}
