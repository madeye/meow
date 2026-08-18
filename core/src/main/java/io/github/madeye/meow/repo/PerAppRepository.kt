package io.github.madeye.meow.repo

import io.github.madeye.meow.preference.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Which apps the tunnel applies to. */
enum class PerAppMode(val key: String) {
    /** Only the selected packages are routed through the tunnel. */
    Proxy("proxy"),

    /** Everything except the selected packages is routed. */
    Bypass("bypass"),
    ;

    companion object {
        fun from(key: String?): PerAppMode =
            entries.firstOrNull { it.key == key } ?: Proxy
    }
}

data class PerAppConfig(
    val mode: PerAppMode = PerAppMode.Proxy,
    val packages: Set<String> = emptySet(),
)

/**
 * Per-app proxy selection, stored as a JSON array in SharedPreferences.
 * `VpnService` reads the same two keys when building the TUN interface, so the
 * storage format is fixed.
 */
class PerAppRepository(private val json: Json = Json) {

    suspend fun load(): PerAppConfig = withContext(Dispatchers.IO) {
        val packages = try {
            json.decodeFromString(ListSerializer(String.serializer()), DataStore.perAppPackages)
        } catch (e: Exception) {
            emptyList()
        }
        PerAppConfig(
            mode = PerAppMode.from(DataStore.perAppMode),
            packages = packages.toSet(),
        )
    }

    suspend fun save(config: PerAppConfig) = withContext(Dispatchers.IO) {
        DataStore.perAppMode = config.mode.key
        DataStore.perAppPackages =
            json.encodeToString(ListSerializer(String.serializer()), config.packages.toList())
    }
}
