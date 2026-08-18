package io.github.madeye.meow.api

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MeowApiLogStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MeowApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = MeowApi(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `logs emits parsed entries and skips malformed frames`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type": "info", "payload": "engine started", "time": "t1"}""")
                    // A junk frame must not kill the stream.
                    webSocket.send("not json at all")
                    webSocket.send("""{"type": "error", "payload": "dial failed", "time": "t2"}""")
                }
            }),
        )

        val entries = withTimeout(10_000) { api.logs().take(2).toList() }

        assertEquals(listOf("info", "error"), entries.map { it.type })
        assertEquals("engine started", entries[0].payload)
        assertEquals("dial failed", entries[1].payload)
    }

    @Test
    fun `logs requests the level query parameter`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type": "debug", "payload": "x", "time": "t"}""")
                }
            }),
        )

        withTimeout(10_000) { api.logs(level = "debug").take(1).toList() }

        val request = server.takeRequest()
        assertEquals("/logs", request.requestUrl!!.encodedPath)
        assertEquals("debug", request.requestUrl!!.queryParameter("level"))
    }

    @Test
    fun `logs reconnects after the socket closes`() = runBlocking {
        repeat(2) { round ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type": "info", "payload": "round-$round", "time": "t"}""")
                        webSocket.close(1000, "done")
                    }
                }),
            )
        }

        // Two frames across two connections: the second only arrives if the
        // stream re-dialled after the first socket closed.
        val entries = withTimeout(20_000) { api.logs().take(2).toList() }

        assertEquals(listOf("round-0", "round-1"), entries.map { it.payload })
    }
}
