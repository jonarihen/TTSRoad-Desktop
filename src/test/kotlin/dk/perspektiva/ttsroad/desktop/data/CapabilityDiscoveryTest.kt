package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Discovery over the wire: caching, the TTL, what a 404 means, what a transient failure means, and
 * the one rule that makes it safe to run against a URL the user is still typing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityDiscoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")
    private var now: Long = 1_000_000L

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore()
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { now },
            deviceNameProvider = { "test-host" },
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun baseUrl(): String = server.url("/").toString()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse(code = code, headers = jsonHeaders, body = body))
    }

    @Test
    fun `discovery is unauthenticated and the marker header never reaches the wire`() = runTest {
        // A stale session for the very origin being probed: without the no-auth marker this is
        // exactly when a previous server's token would be handed to whatever is listening.
        sessionStore.save(SessionState(serverUrl = baseUrl(), token = "ttsr_stale_from_another_server"))
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)

        repository.capabilities(baseUrl())

        val request = server.takeRequest()
        assertEquals("/api/mobile/capabilities", request.url.encodedPath)
        assertNull(request.headers["Authorization"], "a stale token must never be offered to a typed URL")
        assertNull(request.headers[NoAuthHeader], "the marker is internal and must be stripped")
    }

    @Test
    fun `a second call inside the TTL is served from memory`() = runTest {
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)

        repository.capabilities(baseUrl())
        now += TimeUnit.HOURS.toMillis(5)
        val second = repository.capabilities(baseUrl())

        assertTrue(second.readAlong)
        assertEquals(1, server.requestCount, "discovery must not be a per-screen cost")
    }

    @Test
    fun `the cache expires after six hours`() = runTest {
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        repository.capabilities(baseUrl())

        now += TimeUnit.HOURS.toMillis(6) + 1
        enqueue(200, ServerFixtures.CAPABILITIES_WITH_UNKNOWN_KEYS)
        val refreshed = repository.capabilities(baseUrl())

        assertEquals(2, server.requestCount)
        assertEquals("2.9.0", refreshed.serverVersion, "a server upgraded under a long-running app is noticed")
    }

    @Test
    fun `forceRefresh bypasses a still-valid cache`() = runTest {
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        repository.capabilities(baseUrl())

        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        repository.capabilities(baseUrl(), forceRefresh = true)

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a trailing slash difference hits the same cache entry`() = runTest {
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)

        repository.capabilities(baseUrl())
        repository.capabilities(baseUrl().trimEnd('/'))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 404 means baseline, and it is remembered so the endpoint is not re-asked`() = runTest {
        enqueue(404, """{"detail": "Not Found"}""")

        val first = repository.capabilities(baseUrl())
        val second = repository.capabilities(baseUrl())

        assertEquals(ServerCapabilities.Baseline, first)
        assertEquals(ServerCapabilities.Baseline, second)
        assertEquals(1, server.requestCount, "an old server will not grow the endpoint under us")
    }

    @Test
    fun `a transient failure keeps the last known answer instead of downgrading`() = runTest {
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        assertTrue(repository.capabilities(baseUrl()).readAlong)

        now += TimeUnit.HOURS.toMillis(7)
        enqueue(500, """{"detail": "boom"}""")
        val afterOutage = repository.capabilities(baseUrl())

        assertTrue(afterOutage.readAlong, "one bad response is not evidence the server lost a feature")
    }

    @Test
    fun `an unreachable server resolves to baseline without throwing`() = runTest {
        val dead = baseUrl()
        server.close()

        assertEquals(ServerCapabilities.Baseline, repository.capabilities(dead))
    }

    @Test
    fun `an unparseable base URL resolves to baseline without a request`() = runTest {
        assertEquals(ServerCapabilities.Baseline, repository.capabilities("not a url"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a garbage body resolves to baseline rather than failing the caller`() = runTest {
        enqueue(200, "<html>this is the /setup page</html>")

        assertEquals(ServerCapabilities.Baseline, repository.capabilities(baseUrl()))
    }

    @Test
    fun `refreshCurrentCapabilities does nothing at all while signed out`() = runTest {
        assertEquals(ServerCapabilities.Baseline, repository.refreshCurrentCapabilities())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signing out forgets the discovered capabilities`() = runTest {
        sessionStore.save(SessionState(serverUrl = baseUrl(), token = "ttsr_t", username = "admin"))
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        assertTrue(repository.refreshCurrentCapabilities().readAlong)

        server.enqueue(MockResponse(code = 200, headers = jsonHeaders, body = """{"status":"ok"}"""))
        repository.logout()

        assertFalse(repository.currentCapabilities.value.readAlong, "the next server may not have it")
        // …and the cache entry is gone, so the next sign-in genuinely re-asks.
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        repository.capabilities(baseUrl())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an expired session forgets the discovered capabilities too`() = runTest {
        sessionStore.save(SessionState(serverUrl = baseUrl(), token = "ttsr_t", username = "admin"))
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
        repository.refreshCurrentCapabilities()

        repository.endSession(SessionEnd(SessionEndReason.Revoked, "revoked"))

        assertFalse(repository.currentCapabilities.value.readAlong)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
    }
}
