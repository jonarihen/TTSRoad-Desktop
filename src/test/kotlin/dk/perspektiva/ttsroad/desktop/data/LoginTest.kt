package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * The login flow end-to-end over a real socket: request shape, and every documented failure the
 * TTSRoad 1.4.0 login endpoint can return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore()
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host · Test OS" },
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

    /**
     * A successful login is now two requests: the login itself, then the forced capability
     * refresh. Both have to be queued or MockWebServer blocks the second one.
     */
    private fun enqueueSuccessfulLogin() {
        enqueue(200, ServerFixtures.LOGIN_SUCCESS)
        enqueue(200, ServerFixtures.CAPABILITIES_1_4_0)
    }

    @Test
    fun `a successful login stores the session and posts the expected body`() = runTest {
        enqueueSuccessfulLogin()

        val result = repository.login(baseUrl(), "  admin  ", "hunter2")

        assertEquals(LoginResult.Success, result)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/login", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"username\":\"admin\""), "username should be trimmed: $body")
        assertTrue(body.contains("\"password\":\"hunter2\""), body)
        assertTrue(body.contains("\"device_name\":\"test-host · Test OS\""), body)
        // totp_code is absent, not null, when no code was supplied.
        assertFalse(body.contains("totp_code"), body)

        val session = sessionStore.current()
        assertTrue(session.isLoggedIn)
        assertEquals("ttsr_Zm9vYmFyYmF6cXV1eA", session.token)
        assertEquals("Bearer ttsr_Zm9vYmFyYmF6cXV1eA", session.authorizationHeader)
        assertEquals("admin", session.username)
        assertTrue(session.isAdmin)
        assertEquals("Perspektiva TTSRoad", session.serverName)
        // The stored server URL is normalized, so every later call hits the same Retrofit instance.
        assertEquals(baseUrl(), session.serverUrl)
    }

    @Test
    fun `a 2FA-required response asks for a code without signing the user in`() = runTest {
        enqueue(401, ServerFixtures.LOGIN_401_TOTP_REQUIRED)

        val result = repository.login(baseUrl(), "admin", "hunter2")

        assertEquals(LoginResult.TotpRequired, result)
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `a wrong 2FA code is also reported as TotpRequired - the server cannot distinguish them`() = runTest {
        // Both "code missing" and "code wrong" are a 401 carrying totp_required, so the UI has to
        // infer "wrong code" from the fact that it already sent one. See LoginStateHolder.
        enqueue(401, ServerFixtures.LOGIN_401_TOTP_INVALID)

        val result = repository.login(baseUrl(), "admin", "hunter2", totpCode = "000000")

        assertEquals(LoginResult.TotpRequired, result)
        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"totp_code\":\"000000\""), body)
    }

    @Test
    fun `a blank 2FA code is not sent to the server`() = runTest {
        enqueueSuccessfulLogin()

        repository.login(baseUrl(), "admin", "hunter2", totpCode = "   ")

        assertFalse(server.takeRequest().bodyText().contains("totp_code"))
    }

    @Test
    fun `a 401 with a plain-string detail surfaces that string`() = runTest {
        enqueue(401, ServerFixtures.LOGIN_401_STRING_DETAIL)

        val result = repository.login(baseUrl(), "admin", "wrong")

        assertIs<LoginResult.Failure>(result)
        assertEquals("Invalid username or password", result.message)
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `a 401 with an object detail surfaces detail-message`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_EXPIRED)

        val result = repository.login(baseUrl(), "admin", "hunter2")

        assertIs<LoginResult.Failure>(result)
        assertEquals("This device session expired. Sign in again.", result.message)
    }

    @Test
    fun `a 429 is a rate limit, not a credential failure, and carries the wait`() = runTest {
        server.enqueue(
            MockResponse(
                code = 429,
                headers = Headers.headersOf("Content-Type", "application/json", "Retry-After", "900"),
                body = ServerFixtures.LOGIN_429_THROTTLED,
            ),
        )

        val result = repository.login(baseUrl(), "admin", "hunter2")

        val limited = assertIs<LoginResult.RateLimited>(result)
        assertEquals(900, limited.retryAfterSeconds)
        assertEquals("Too many failed attempts — try again in 15 minutes", limited.message)
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `a 429 without a Retry-After header falls back to the body's retry_after`() = runTest {
        enqueue(429, ServerFixtures.LOGIN_429_THROTTLED)

        val limited = assertIs<LoginResult.RateLimited>(repository.login(baseUrl(), "admin", "hunter2"))

        assertEquals(900, limited.retryAfterSeconds)
    }

    @Test
    fun `a 429 with neither a header nor a body still reports a rate limit`() = runTest {
        enqueue(429, "")

        val limited = assertIs<LoginResult.RateLimited>(repository.login(baseUrl(), "admin", "hunter2"))

        assertEquals(null, limited.retryAfterSeconds)
        assertEquals("Too many failed attempts", limited.message)
    }

    @Test
    fun `a successful login records the device id, expiry and server version`() = runTest {
        enqueueSuccessfulLogin()

        repository.login(baseUrl(), "admin", "hunter2")

        val session = sessionStore.current()
        assertEquals(42, session.deviceId)
        assertEquals("2026-11-04T09:12:33.123456Z", session.expiresAt)
        assertEquals("1.4.0", session.serverVersion)
    }

    @Test
    fun `a successful login forces a capability refresh before anything is rendered`() = runTest {
        enqueueSuccessfulLogin()

        repository.login(baseUrl(), "admin", "hunter2")

        assertTrue(repository.currentCapabilities.value.readAlong)
        assertTrue(repository.currentCapabilities.value.deviceManagement)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("/api/mobile/capabilities", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a server too old for discovery still signs in, with every optional feature off`() = runTest {
        enqueue(200, ServerFixtures.LOGIN_SUCCESS)
        enqueue(404, """{"detail": "Not Found"}""")

        val result = repository.login(baseUrl(), "admin", "hunter2")

        assertEquals(LoginResult.Success, result)
        assertTrue(sessionStore.current().isLoggedIn, "discovery is a convenience, never a gate on signing in")
        assertEquals(ServerCapabilities.Baseline, repository.currentCapabilities.value)
    }

    @Test
    fun `a discovery outage during login does not fail the login`() = runTest {
        enqueue(200, ServerFixtures.LOGIN_SUCCESS)
        enqueue(500, "boom")

        assertEquals(LoginResult.Success, repository.login(baseUrl(), "admin", "hunter2"))
        assertTrue(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `the login request never carries a stale bearer token`() = runTest {
        // A previous session for the same origin is exactly the case where an interceptor keyed
        // only on "do we have a token" would attach one.
        sessionStore.save(SessionState(serverUrl = baseUrl(), token = "ttsr_stale", username = "old"))
        enqueueSuccessfulLogin()

        repository.login(baseUrl(), "admin", "hunter2")

        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-TtsRoad-No-Auth"], "the marker header must not reach the wire")
    }

    @Test
    fun `an unreachable host produces a readable message with no stack trace`() = runTest {
        val result = repository.login("https://no-such-host.invalid", "admin", "hunter2")

        val failure = assertIs<LoginResult.Failure>(result)
        assertEquals("Cannot reach that server — its address did not resolve", failure.message)
    }

    @Test
    fun `an unparseable error body falls back to the generic message`() = runTest {
        enqueue(500, "<html>502 Bad Gateway</html>")

        val result = repository.login(baseUrl(), "admin", "hunter2")

        assertIs<LoginResult.Failure>(result)
        assertEquals("Invalid username or password", result.message)
    }

    @Test
    fun `a network failure is reported as a failure, not thrown`() = runTest {
        val deadUrl = baseUrl()
        server.close() // nothing is listening on that port any more

        val result = repository.login(deadUrl, "admin", "hunter2")

        assertIs<LoginResult.Failure>(result)
        assertTrue(result.message.isNotBlank(), "a connection failure must carry a message")
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `a URL without a scheme is a user-correctable failure, not a crash`() = runTest {
        val result = repository.login("192.168.1.5:8000", "admin", "hunter2")

        assertIs<LoginResult.Failure>(result)
        assertEquals("Server URL must start with http:// or https://", result.message)
        assertEquals(0, server.requestCount)
    }
}
