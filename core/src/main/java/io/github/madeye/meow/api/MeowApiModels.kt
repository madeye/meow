package io.github.madeye.meow.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Group types as reported by the engine's `/proxies` endpoint. */
internal val GROUP_TYPES = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay")

/**
 * One latency probe.
 *
 * `time` arrives in two different shapes depending on which engine serves it:
 * a plain ISO string (meow-go) or a Rust `SystemTime`
 * (`{"secs_since_epoch": N, "nanos_since_epoch": N}`). Both are normalised to
 * epoch millis here so callers never see the difference.
 */
data class ProxyHistory(
    val timeMillis: Long,
    val delay: Int,
)

data class Proxy(
    val name: String,
    val type: String,
    val history: List<ProxyHistory>,
) {
    /** Delay of the most recent probe; 0 when never tested or timed out. */
    val latestDelay: Int get() = history.lastOrNull()?.delay ?: 0
}

data class ProxyGroup(
    val name: String,
    val type: String,
    /** Currently selected member. */
    val now: String,
    val all: List<String>,
    val history: List<ProxyHistory>,
)

/** Parsed `GET /proxies`, split into selector groups and leaf proxies. */
data class ProxiesResult(
    val groups: Map<String, ProxyGroup>,
    val proxies: Map<String, Proxy>,
) {
    /**
     * User-selectable groups, sorted case-insensitively by name.
     *
     * Mirrors meow-ios's `ProxyGroupModel.build`: hides the top-level `GLOBAL`
     * aggregator, and imposes an order because the engine's `/proxies` map has
     * none (Go and Rust both iterate maps non-deterministically, so an
     * unsorted list reshuffles on every refresh).
     */
    val selectableGroups: List<ProxyGroup>
        get() = groups.values
            .filter { it.name != "GLOBAL" && it.type in GROUP_TYPES }
            .sortedBy { it.name.lowercase() }

    companion object {
        fun parse(root: JsonObject): ProxiesResult {
            val raw = root["proxies"]?.asObjectOrNull() ?: JsonObject(emptyMap())
            val groups = mutableMapOf<String, ProxyGroup>()
            val proxies = mutableMapOf<String, Proxy>()
            for ((name, element) in raw) {
                val data = element.asObjectOrNull() ?: continue
                val type = data.string("type")
                if (type in GROUP_TYPES) {
                    groups[name] = ProxyGroup(
                        name = name,
                        type = type,
                        now = data.string("now"),
                        all = data.stringList("all"),
                        history = data.historyList(),
                    )
                } else {
                    proxies[name] = Proxy(
                        name = name,
                        type = type,
                        history = data.historyList(),
                    )
                }
            }
            return ProxiesResult(groups, proxies)
        }
    }
}

@Serializable
data class Rule(
    val type: String = "",
    val payload: String = "",
    val proxy: String = "",
)

@Serializable
internal data class RulesResponse(val rules: List<Rule> = emptyList())

@Serializable
data class ConnectionMeta(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val dnsMode: String = "",
    val processName: String = "",
    val uid: Int = 0,
)

@Serializable
data class Connection(
    val id: String = "",
    val metadata: ConnectionMeta = ConnectionMeta(),
    val upload: Long = 0,
    val download: Long = 0,
    /** ISO-8601 timestamp; the UI derives elapsed time from it. */
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
)

@Serializable
data class ConnectionsSnapshot(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<Connection> = emptyList(),
)

@Serializable
data class LogEntry(
    /** INFO | WARN | ERROR | DEBUG | SILENT */
    val type: String = "",
    val payload: String = "",
    val time: String = "",
)

@Serializable
data class RuntimeConfig(
    /** rule | global | direct */
    val mode: String = "rule",
    val ipv6: Boolean = false,
    @SerialName("allow-lan") val allowLan: Boolean = false,
    @SerialName("log-level") val logLevel: String = "info",
    @SerialName("mixed-port") val mixedPort: Int = 7890,
    @SerialName("external-controller") val externalController: String = "",
)

// -----------------------------------------------------------------------------
// JSON helpers — the /proxies payload is a heterogeneous map discriminated by a
// field *value* rather than a key, so it is parsed by hand rather than by
// generated serializers.
// -----------------------------------------------------------------------------

private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun JsonObject.string(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

private fun JsonObject.stringList(key: String): List<String> =
    runCatching {
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content }
    }.getOrNull().orEmpty()

private fun JsonObject.historyList(): List<ProxyHistory> =
    runCatching {
        (this["history"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { entry -> entry.asObjectOrNull()?.toHistory() }
    }.getOrNull().orEmpty()

private fun JsonObject.toHistory(): ProxyHistory {
    val delay = runCatching { this["delay"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: 0
    val time = this["time"]
    val millis = when {
        time == null -> 0L
        // Rust SystemTime struct.
        time.asObjectOrNull() != null -> {
            val secs = time.asObjectOrNull()
                ?.get("secs_since_epoch")?.jsonPrimitive?.longOrNull ?: 0L
            secs * 1000
        }
        // Go ISO-8601 string. Kept as 0 when unparseable; only ordering uses it.
        else -> runCatching {
            java.time.Instant.parse(time.jsonPrimitive.content).toEpochMilli()
        }.getOrDefault(0L)
    }
    return ProxyHistory(timeMillis = millis, delay = delay)
}
