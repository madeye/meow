package io.github.madeye.meow.api

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Client for the embedded engine's Clash-compatible controller API.
 *
 * The engine runs in-process (in `:vpn`) and binds its listener on
 * `127.0.0.1:9090` — see `MeowInstance.start`. [baseUrl] is a constructor
 * parameter rather than a hardcoded constant purely so tests can point it at a
 * `MockWebServer`, and so the iOS-style hardening (random port + minted secret)
 * stays a one-line change later.
 *
 * Ported from `flutter_module/lib/services/meow_api.dart`.
 */
class MeowApi(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = MeowJson,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:9090"
        const val DELAY_TEST_URL = "http://www.gstatic.com/generate_204"

        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Never route loopback through a system proxy — installing proxies
            // is this app's entire job, and honouring one here would loop.
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Keeps the /logs socket from being reaped while idle.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    // -------------------------------------------------------------------------
    // Proxies
    // -------------------------------------------------------------------------

    suspend fun proxies(): ProxiesResult {
        val body = get("/proxies", operation = "proxies")
        return decode("proxies") { ProxiesResult.parse(json.parseToJsonElement(body).jsonObject) }
    }

    suspend fun selectProxy(group: String, name: String) {
        val payload = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name))),
        )
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("proxies").addPathSegment(group).build())
            .put(payload.toRequestBody(JSON_MEDIA))
            .build()
        execute(request, "selectProxy", okCodes = setOf(200, 204))
    }

    suspend fun testProxyDelay(
        name: String,
        url: String = DELAY_TEST_URL,
        timeoutMs: Int = 5_000,
    ): Int {
        val requestUrl = baseUrl.newBuilder()
            .addPathSegment("proxies").addPathSegment(name).addPathSegment("delay")
            .addQueryParameter("url", url)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        val body = execute(Request.Builder().url(requestUrl).build(), "testProxyDelay")
        return decode("testProxyDelay") {
            json.parseToJsonElement(body).jsonObject["delay"]?.jsonPrimitive?.intOrNull ?: 0
        }
    }

    /**
     * Probes every member of a group. The default timeout is far longer than the
     * client's read timeout, so this call gets its own timeout budget.
     */
    suspend fun testGroupDelay(
        group: String,
        url: String = DELAY_TEST_URL,
        timeoutMs: Int = 60_000,
    ): Map<String, Int> {
        val requestUrl = baseUrl.newBuilder()
            .addPathSegment("group").addPathSegment(group).addPathSegment("delay")
            .addQueryParameter("url", url)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        val scoped = client.newBuilder()
            .callTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .build()
        val body = execute(Request.Builder().url(requestUrl).build(), "testGroupDelay", client = scoped)
        // Success is a flat name -> delay map; failures come back as
        // {"message": "..."}, so non-numeric values are dropped rather than
        // blowing up the whole result.
        return decode("testGroupDelay") {
            json.parseToJsonElement(body).jsonObject.mapNotNull { (key, value) ->
                value.jsonPrimitive.intOrNull?.let { key to it }
            }.toMap()
        }
    }

    // -------------------------------------------------------------------------
    // Rules / connections / configs
    // -------------------------------------------------------------------------

    suspend fun rules(): List<Rule> {
        val body = get("/rules", operation = "rules")
        return decode("rules") { json.decodeFromString(RulesResponse.serializer(), body).rules }
    }

    suspend fun connections(): ConnectionsSnapshot {
        val body = get("/connections", operation = "connections")
        return decode("connections") {
            json.decodeFromString(ConnectionsSnapshot.serializer(), body)
        }
    }

    suspend fun closeConnection(id: String) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("connections").addPathSegment(id).build())
            .delete()
            .build()
        execute(request, "closeConnection", okCodes = setOf(200, 204))
    }

    suspend fun closeAllConnections() {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("connections").build())
            .delete()
            .build()
        execute(request, "closeAllConnections", okCodes = setOf(200, 204))
    }

    suspend fun configs(): RuntimeConfig {
        val body = get("/configs", operation = "configs")
        return decode("configs") { json.decodeFromString(RuntimeConfig.serializer(), body) }
    }

    suspend fun patchConfigs(patch: JsonObject) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("configs").build())
            .patch(json.encodeToString(JsonObject.serializer(), patch).toRequestBody(JSON_MEDIA))
            .build()
        execute(request, "patchConfigs", okCodes = setOf(200, 204))
    }

    // -------------------------------------------------------------------------
    // Streams
    // -------------------------------------------------------------------------

    /**
     * Live engine logs.
     *
     * This replaces the old `getLogs` JNI call, which could never have worked
     * from the UI process: the Rust ring buffer it drains is a per-process
     * `static`, and the engine lives in `:vpn`. The WebSocket crosses the
     * process boundary over loopback, so it sees the real log stream.
     */
    fun logs(level: String = "info"): Flow<LogEntry> {
        val url = baseUrl.newBuilder()
            .scheme(if (baseUrl.isHttps) "https" else "http")
            .addPathSegment("logs")
            .addQueryParameter("level", level)
            .build()
        return streamJsonLines(url) { json.decodeFromString(LogEntry.serializer(), it) }
    }

    private fun <T> streamJsonLines(url: HttpUrl, parse: (String) -> T): Flow<T> {
        // Reset the backoff whenever a connection actually delivers something,
        // so a long-lived stream that drops doesn't inherit the previous ramp.
        val attemptOffset = java.util.concurrent.atomic.AtomicInteger(0)
        return callbackFlow {
            val request = Request.Builder().url(url).build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        attemptOffset.set(0)
                        // A single malformed frame must not tear down the stream.
                        val parsed = try {
                            parse(text)
                        } catch (e: Exception) {
                            return
                        }
                        trySend(parsed)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        close(t)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // OkHttp does not answer a peer close frame for us; without
                        // this the socket lingers and onClosed never arrives.
                        webSocket.close(code, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // A clean close still means "reconnect": the engine
                        // drops the socket on restart, and the stream is
                        // supposed to outlive that. Completing normally here
                        // would end the flow for good, since retryWhen below
                        // only sees exceptions.
                        close(StreamClosed(code, reason))
                    }
                },
            )
            awaitClose { socket.cancel() }
        }.retryWhen { cause, _ ->
            if (cause is kotlinx.coroutines.CancellationException) return@retryWhen false
            val attempt = attemptOffset.getAndIncrement().coerceAtMost(6)
            // 500ms doubling to a 30s ceiling — same ramp as the Dart client.
            delay((500L shl attempt).coerceAtMost(30_000L))
            true
        }
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private suspend fun get(path: String, operation: String): String {
        val url = baseUrl.newBuilder()
            .addPathSegments(path.trimStart('/'))
            .build()
        return execute(Request.Builder().url(url).build(), operation)
    }

    private suspend fun execute(
        request: Request,
        operation: String,
        okCodes: Set<Int> = setOf(200),
        client: OkHttpClient = this.client,
    ): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw MeowApiException.Unreachable(e)
        }
        response.use {
            if (it.code !in okCodes) throw MeowApiException.Http(operation, it.code)
            it.body?.string().orEmpty()
        }
    }

    private inline fun <T> decode(operation: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw MeowApiException.Decode(operation, e)
        }
}

/**
 * Lenient by design: the engine is the source of truth and adds fields between
 * releases, so unknown keys must never fail a response the UI could have used.
 */
val MeowJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Signals that the peer hung up, so the stream re-dials rather than ending. */
private class StreamClosed(code: Int, reason: String) :
    IOException("websocket closed ($code${if (reason.isEmpty()) "" else ": $reason"})")

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
