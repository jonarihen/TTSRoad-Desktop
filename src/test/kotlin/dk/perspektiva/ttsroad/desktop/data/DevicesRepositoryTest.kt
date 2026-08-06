package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
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
import org.junit.jupiter.api.assertThrows
import retrofit2.HttpException

/**
 * `/api/mobile/me` and the three device-management endpoints, on the wire.
 *
 * The distinctions being pinned here are the ones a UI cannot recover from if the repository gets
 * them wrong: 404 means "this server has no such endpoint" and must NOT sign anyone out, 401 still
 * must, and 500 is neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "ttsr_token",
                username = "admin",
                isAdmin = true,
                deviceId = 42,
            ),
        )
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse(code = code, headers = jsonHeaders, body = body))
    }

    // --- me ----------------------------------------------------------------------------------

    @Test
    fun `me returns the account the server currently sees`() = runTest {
        enqueue(200, ServerFixtures.ME)

        val user = repository.currentUser()

        assertEquals("admin", user?.username)
        assertEquals(true, user?.isAdmin)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/me", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    // --- listing -----------------------------------------------------------------------------

    @Test
    fun `devices decodes every row and sends the bearer header`() = runTest {
        enqueue(200, ServerFixtures.DEVICES)

        val devices = repository.devices()

        assertEquals(listOf(42, 43, 44), devices?.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/devices", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    @Test
    fun `an empty list means no sessions, not an unsupported server`() = runTest {
        enqueue(200, """{"api_version": 1, "devices": []}""")

        val devices = repository.devices()

        assertEquals(emptyList(), devices)
        assertFalse(devices == null, "an empty list must stay distinguishable from null")
    }

    @Test
    fun `a 404 means the server has no device API and does not sign the user out`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        val devices = repository.devices()

        assertNull(devices)
        assertEquals(0, sessionStore.clearTokenCalls)
        assertTrue(sessionStore.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a 401 on the devices call still ends the session`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED)

        assertThrows<HttpException> { repository.devices() }

        assertEquals(1, sessionStore.clearTokenCalls)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
    }

    @Test
    fun `a 500 propagates rather than being mistaken for an old server`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        assertThrows<HttpException> { repository.devices() }

        assertEquals(0, sessionStore.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    // --- revoking ----------------------------------------------------------------------------

    @Test
    fun `revokeDevice deletes the token id in the path`() = runTest {
        enqueue(200, ServerFixtures.DEVICE_REVOKED)

        assertTrue(repository.revokeDevice(43))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/devices/43", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    @Test
    fun `a 404 on a revoke is reported as false, not thrown, and keeps the session`() = runTest {
        // The server sends this both for "no such endpoint" and for "that session is already
        // gone"; the repository deliberately does not guess which, it just reports the 404.
        enqueue(404, ServerFixtures.DEVICE_NOT_FOUND)

        assertFalse(repository.revokeDevice(999))

        assertEquals(0, sessionStore.clearTokenCalls)
        assertTrue(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `revokeOtherDevices posts to the revoke-others route`() = runTest {
        enqueue(200, ServerFixtures.DEVICES_REVOKED_OTHERS)

        assertTrue(repository.revokeOtherDevices())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/devices/revoke-others", request.url.encodedPath)
    }

    @Test
    fun `a 404 on revoke-others is an unsupported server, not a failure`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        assertFalse(repository.revokeOtherDevices())

        assertEquals(0, sessionStore.clearTokenCalls)
    }

    @Test
    fun `a 401 while revoking ends the session — the token, not the endpoint, is the problem`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_EXPIRED)

        assertThrows<HttpException> { repository.revokeDevice(43) }

        assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `a device call while signed out never reaches the network`() = runTest {
        sessionStore.clearToken()

        assertThrows<IllegalArgumentException> { repository.devices() }

        assertEquals(0, server.requestCount)
    }
}
