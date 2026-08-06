package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.SessionEndReason
import dk.perspektiva.ttsroad.desktop.data.SessionState
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A 401 on `/audio/…` is the same event as a 401 on an API call.
 *
 * It is easy to get wrong because the audio path does not go through Retrofit: without a typed
 * failure it arrives as a generic IOException, the player shows "HTTP 401" forever, and the user
 * stays "signed in" to a server that has already revoked them.
 */
class AudioSessionExpiryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "ttsr_token", username = "admin"),
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun mediaSource(url: String): HttpMediaSource {
        val repository = object : FakeRepository(serverUrl = server.url("/").toString()) {
            override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader
            override fun resolveUrl(url: String): String =
                if (url.startsWith("http")) url else server.url("/").toString().trimEnd('/') + url
        }
        return HttpMediaSource(authedClient(sessionStore), repository, url)
    }

    /** Opens the source and drains it, which is what an engine does. */
    private fun readFully(url: String): ByteArray = mediaSource(url).open().use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = stream.read(buffer, 0, buffer.size)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    @Test
    fun `the audio request carries the bearer token from the shared interceptor`() {
        server.enqueue(MockResponse(code = 200, body = "fake-mp3-bytes"))

        val bytes = readFully("/audio/a-test-serial/0003.mp3")

        assertEquals("Bearer ttsr_token", server.takeRequest().headers["Authorization"])
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `the opening request is ranged, so seekability is known before the user seeks`() {
        server.enqueue(MockResponse(code = 200, body = "fake-mp3-bytes"))

        readFully("/audio/x.mp3")

        // Asking for bytes=0- costs nothing and is the only way to learn, up front, whether this
        // server can satisfy a later seek without re-reading the chapter from the start.
        assertEquals("bytes=0-", server.takeRequest().headers["Range"])
    }

    @Test
    fun `a server without range support reports itself unseekable rather than seeking wrongly`() {
        server.enqueue(MockResponse(code = 200, body = "fake-mp3-bytes"))

        mediaSource("/audio/x.mp3").open().use { stream ->
            assertFalse(stream.isSeekable, "a 200 is the server declining the range")
            assertFalse(stream.seek(4), "and seeking must then fail rather than silently restart")
        }
    }

    @Test
    fun `a 401 on audio is raised as a typed session expiry carrying the server's reason`() {
        server.enqueue(
            MockResponse(code = 401, headers = jsonHeaders, body = ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED),
        )

        val failure = runCatching { readFully("/audio/a-test-serial/0003.mp3") }.exceptionOrNull()

        val expiry = assertIsSessionExpired(failure)
        assertEquals(SessionEndReason.Revoked, expiry.sessionEnd.reason)
        assertEquals("This device session was revoked. Sign in again.", expiry.sessionEnd.message)
    }

    @Test
    fun `a 500 on audio stays an ordinary IO failure`() {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val failure = runCatching { readFully("/audio/x.mp3") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure is SessionExpiredException, "a broken server is not a revoked credential")
    }

    @Test
    fun `signed out, the audio path fails fast without a request`() {
        sessionStore.clearToken()

        val failure = runCatching { readFully("/audio/x.mp3") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, server.requestCount)
    }

    // --- and what the player does with it -------------------------------------------------

    private fun controllerFor(repository: FakeRepository, engine: FakePlaybackEngine) =
        QueuePlaybackController(
            repository = repository,
            sources = FakeMediaSourceFactory(),
            engine = engine,
            ioDispatcher = Dispatchers.Default,
            // No ladder: these assert which door a failure goes through, not how long it waits.
            retryDelaysMs = emptyList(),
        )

    private val chapter = ChapterSummary(
        id = 101,
        fictionId = 7,
        title = "Chapter 3",
        audio = AudioInfo(url = "/audio/x.mp3"),
    )

    @Test
    fun `a session expiry during playback stops the player and ends the session`() = runBlocking {
        val repository = FakeRepository()
        val end = SessionEnd(SessionEndReason.Expired, "This device session expired. Sign in again.")
        val engine = FakePlaybackEngine().apply { prepareFailure = SessionExpiredException(end) }
        val controller = controllerFor(repository, engine)

        controller.play(chapter, null)
        withTimeout(15_000) { controller.state.first { it.error != null } }

        assertEquals("This device session expired. Sign in again.", controller.state.value.error)
        assertFalse(controller.state.value.isPlaying)
        assertFalse(
            controller.state.value.canRetry,
            "a revoked credential is not something a Retry button can fix",
        )
        // The repository is what actually drops the token and returns the app to the login screen.
        assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
        controller.release()
    }

    @Test
    fun `an ordinary download failure does not end the session`() = runBlocking {
        val repository = FakeRepository()
        val engine = FakePlaybackEngine().apply { prepareFailure = IOException("Connection reset") }
        val controller = controllerFor(repository, engine)

        controller.play(chapter, null)
        withTimeout(15_000) { controller.state.first { it.error != null } }

        assertNull(repository.sessionEnd.value, "a dropped connection must not sign the user out")
        assertTrue(controller.state.value.canRetry, "but it is worth offering another attempt")
        controller.release()
    }

    private fun assertIsSessionExpired(failure: Throwable?): SessionExpiredException {
        assertTrue(failure is SessionExpiredException, "expected a session expiry, got $failure")
        return failure
    }
}
