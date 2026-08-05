package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
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

/** Authenticated endpoints: request shape, auth header, and error propagation. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTest {
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
                serverName = "Perspektiva TTSRoad",
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

    @Test
    fun `library sends the bearer header and decodes the payload`() = runTest {
        enqueue(200, ServerFixtures.LIBRARY)

        val library = repository.library()

        assertEquals(1, library.fictions.size)
        assertEquals(1, library.continueListening.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/library", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    @Test
    fun `chapters passes the fiction id in the path and playable_only in the query`() = runTest {
        enqueue(200, ServerFixtures.CHAPTERS)

        val response = repository.chapters(fictionId = 7, playableOnly = true)

        assertEquals(2, response.chapters.size)
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/7/chapters", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("playable_only"))
        assertEquals("false", request.url.queryParameter("include_excluded"))
    }

    @Test
    fun `markPlayed posts a chapter-id array even for a single chapter`() = runTest {
        enqueue(200, ServerFixtures.MARK_OK)

        val response = repository.markPlayed(listOf(101), played = true)

        assertEquals(1, response.count)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/playback/mark", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"chapter_ids\":[101]"), body)
        assertTrue(body.contains("\"played\":true"), body)
    }

    @Test
    fun `saveProgress clamps a negative position to zero`() = runTest {
        enqueue(200, ServerFixtures.PROGRESS_SAVED)

        repository.saveProgress(fictionId = 7, chapterId = 101, positionSeconds = -12.0, isPlayed = false)

        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"position_seconds\":0.0"), body)
        assertTrue(body.contains("\"fiction_id\":7"), body)
        assertTrue(body.contains("\"is_played\":false"), body)
    }

    @Test
    fun `saveProgress is a no-op when signed out, so a stray progress tick cannot crash playback`() = runTest {
        sessionStore.clearToken()

        val response = repository.saveProgress(7, 101, 12.0, isPlayed = false)

        assertNull(response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 401 on an authenticated call signs the user out and explains why`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_EXPIRED)

        assertThrows<HttpException> { repository.library() }

        assertEquals(1, sessionStore.clearTokenCalls)
        assertFalse(sessionStore.current().isLoggedIn)
        assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
        assertEquals("This device session expired. Sign in again.", repository.sessionEnd.value?.message)
        // The server hints survive so the login screen can prefill rather than starting blank.
        assertEquals("admin", sessionStore.current().username)
        assertTrue(sessionStore.current().serverUrl.isNotBlank())
    }

    @Test
    fun `a rejected token is not retried`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED)

        assertThrows<HttpException> { repository.library() }

        assertEquals(1, server.requestCount, "a token the server refuses can never work on a retry")
    }

    @Test
    fun `a 500 is a server problem, not a credential problem, and does not sign the user out`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        assertThrows<HttpException> { repository.library() }

        assertEquals(0, sessionStore.clearTokenCalls)
        assertTrue(sessionStore.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a genuine network outage does not sign the user out`() = runTest {
        // Nothing is listening any more: this is exactly the case that must NOT be read as
        // "the server rejected our token".
        server.close()

        assertThrows<java.io.IOException> { repository.library() }

        assertEquals(0, sessionStore.clearTokenCalls)
        assertTrue(sessionStore.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a 401 on a progress save ends the session too`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_INVALID)

        assertThrows<HttpException> { repository.saveProgress(7, 101, 12.0, isPlayed = false) }

        assertEquals(SessionEndReason.Invalid, repository.sessionEnd.value?.reason)
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `an authenticated call while signed out fails fast without hitting the network`() = runTest {
        sessionStore.clearToken()

        assertThrows<IllegalArgumentException> { repository.library() }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logout revokes server-side then clears the local token`() = runTest {
        enqueue(200, """{"status": "ok", "revoked": true}""")

        repository.logout()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/logout", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `logout still signs out locally when the server call fails`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        repository.logout()

        assertFalse(sessionStore.current().isLoggedIn)
        assertEquals(1, sessionStore.clearTokenCalls)
    }

    @Test
    fun `authHeaderValue is what the audio download path attaches`() = runTest {
        assertEquals("Bearer ttsr_token", repository.authHeaderValue())
        sessionStore.clearToken()
        assertNull(repository.authHeaderValue())
    }
}
