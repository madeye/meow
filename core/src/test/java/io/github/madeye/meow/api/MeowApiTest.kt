package io.github.madeye.meow.api

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeowApiTest {

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

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    // -------------------------------------------------------------------------
    // /proxies — the heterogeneous map, discriminated by `type` value
    // -------------------------------------------------------------------------

    @Test
    fun `proxies splits groups from leaf nodes`() = runTest {
        enqueue(
            """
            {"proxies": {
              "Proxy":     {"type": "Selector", "now": "Tokyo 01", "all": ["Tokyo 01", "SG 02"]},
              "Tokyo 01":  {"type": "Shadowsocks", "history": [{"time": "2026-08-17T10:00:00Z", "delay": 76}]},
              "SG 02":     {"type": "Trojan", "history": []}
            }}
            """.trimIndent(),
        )

        val result = api.proxies()

        assertEquals(setOf("Proxy"), result.groups.keys)
        assertEquals(setOf("Tokyo 01", "SG 02"), result.proxies.keys)
        assertEquals("Tokyo 01", result.groups.getValue("Proxy").now)
        assertEquals(listOf("Tokyo 01", "SG 02"), result.groups.getValue("Proxy").all)
        assertEquals(76, result.proxies.getValue("Tokyo 01").latestDelay)
        assertEquals(0, result.proxies.getValue("SG 02").latestDelay)
    }

    @Test
    fun `selectableGroups hides GLOBAL and sorts case-insensitively`() = runTest {
        enqueue(
            """
            {"proxies": {
              "zeta":   {"type": "Selector", "now": "a", "all": []},
              "Alpha":  {"type": "URLTest",  "now": "b", "all": []},
              "GLOBAL": {"type": "Selector", "now": "c", "all": []}
            }}
            """.trimIndent(),
        )

        val names = api.proxies().selectableGroups.map { it.name }

        assertEquals(listOf("Alpha", "zeta"), names)
    }

    @Test
    fun `proxy history accepts both Go string time and Rust SystemTime`() = runTest {
        enqueue(
            """
            {"proxies": {
              "go":   {"type": "Shadowsocks", "history": [{"time": "2026-08-17T10:00:00Z", "delay": 10}]},
              "rust": {"type": "Shadowsocks", "history": [{"time": {"secs_since_epoch": 1786960800, "nanos_since_epoch": 0}, "delay": 20}]}
            }}
            """.trimIndent(),
        )

        val result = api.proxies()

        // 2026-08-17T10:00:00Z in both encodings.
        assertEquals(1786960800000L, result.proxies.getValue("go").history.single().timeMillis)
        assertEquals(1786960800000L, result.proxies.getValue("rust").history.single().timeMillis)
    }

    // -------------------------------------------------------------------------
    // Delay probes
    // -------------------------------------------------------------------------

    @Test
    fun `testProxyDelay sends url and timeout and reads the delay`() = runTest {
        enqueue("""{"delay": 142}""")

        val delay = api.testProxyDelay("Tokyo 01", timeoutMs = 5000)

        assertEquals(142, delay)
        val request = server.takeRequest()
        assertEquals("/proxies/Tokyo%2001/delay", request.requestUrl!!.encodedPath)
        assertEquals(MeowApi.DELAY_TEST_URL, request.requestUrl!!.queryParameter("url"))
        assertEquals("5000", request.requestUrl!!.queryParameter("timeout"))
    }

    @Test
    fun `testGroupDelay drops non-numeric entries from an error body`() = runTest {
        enqueue("""{"message": "group not found"}""")

        assertTrue(api.testGroupDelay("Nope").isEmpty())
    }

    @Test
    fun `testGroupDelay returns the name to delay map`() = runTest {
        enqueue("""{"Tokyo 01": 76, "SG 02": 121}""")

        assertEquals(mapOf("Tokyo 01" to 76, "SG 02" to 121), api.testGroupDelay("Proxy"))
    }

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    @Test
    fun `selectProxy PUTs the node name and accepts 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.selectProxy("Proxy", "Tokyo 01")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/proxies/Proxy", request.requestUrl!!.encodedPath)
        assertEquals("""{"name":"Tokyo 01"}""", request.body.readUtf8())
    }

    @Test
    fun `closeConnection DELETEs the id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.closeConnection("abc-123")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/connections/abc-123", request.requestUrl!!.encodedPath)
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    @Test
    fun `rules unwraps the rules array`() = runTest {
        enqueue("""{"rules": [{"type": "DOMAIN", "payload": "example.com", "proxy": "DIRECT"}]}""")

        val rules = api.rules()

        assertEquals(1, rules.size)
        assertEquals("DOMAIN", rules[0].type)
        assertEquals("DIRECT", rules[0].proxy)
    }

    @Test
    fun `configs maps kebab-case engine keys`() = runTest {
        enqueue("""{"mode": "global", "allow-lan": true, "log-level": "debug", "mixed-port": 7891}""")

        val config = api.configs()

        assertEquals("global", config.mode)
        assertTrue(config.allowLan)
        assertEquals("debug", config.logLevel)
        assertEquals(7891, config.mixedPort)
    }

    @Test
    fun `unknown fields do not break decoding`() = runTest {
        enqueue("""{"downloadTotal": 5, "uploadTotal": 3, "connections": [], "somethingNew": 1}""")

        val snapshot = api.connections()

        assertEquals(5L, snapshot.downloadTotal)
        assertEquals(3L, snapshot.uploadTotal)
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    fun `non-success status raises Http with the operation name`() = runTest {
        enqueue("nope", code = 404)

        val error = assertThrows(MeowApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { api.rules() }
        }
        assertEquals("rules", error.operation)
        assertEquals(404, error.code)
    }

    @Test
    fun `a dead engine raises Unreachable rather than a raw IOException`() = runTest {
        val deadPort = server.port
        server.shutdown()
        val offline = MeowApi(baseUrl = "http://127.0.0.1:$deadPort".toHttpUrl())

        assertThrows(MeowApiException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { offline.rules() }
        }
    }

    @Test
    fun `malformed json raises Decode`() = runTest {
        enqueue("this is not json")

        assertThrows(MeowApiException.Decode::class.java) {
            kotlinx.coroutines.runBlocking { api.proxies() }
        }
    }

    @Test
    fun `empty history yields a null-safe latest delay`() = runTest {
        enqueue("""{"proxies": {"a": {"type": "Shadowsocks"}}}""")

        val proxy = api.proxies().proxies.getValue("a")

        assertEquals(0, proxy.latestDelay)
        assertNull(proxy.history.firstOrNull())
    }
}
