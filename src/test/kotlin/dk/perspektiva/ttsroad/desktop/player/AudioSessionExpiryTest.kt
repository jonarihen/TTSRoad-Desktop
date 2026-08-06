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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
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

    private fun downloadStore(): HttpAudioDownloadStore {
        val repository = object : FakeRepository(serverUrl = server.url("/").toString()) {
            override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader
            override fun resolveUrl(url: String): String =
                if (url.startsWith("http")) url else server.url("/").toString().trimEnd('/') + url
        }
        return HttpAudioDownloadStore(authedClient(sessionStore), repository)
    }

    @Test
    fun `the audio request carries the bearer token from the shared interceptor`() {
        server.enqueue(MockResponse(code = 200, body = "fake-mp3-bytes"))

        val file = downloadStore().download("/audio/a-test-serial/0003.mp3")

        assertEquals("Bearer ttsr_token", server.takeRequest().headers["Authorization"])
        assertTrue(file.length() > 0)
        file.delete()
    }

    @Test
    fun `a 401 on audio is raised as a typed session expiry carrying the server's reason`() {
        server.enqueue(
            MockResponse(code = 401, headers = jsonHeaders, body = ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED),
        )

        val failure = runCatching { downloadStore().download("/audio/a-test-serial/0003.mp3") }.exceptionOrNull()

        val expiry = assertIsSessionExpired(failure)
        assertEquals(SessionEndReason.Revoked, expiry.sessionEnd.reason)
        assertEquals("This device session was revoked. Sign in again.", expiry.sessionEnd.message)
    }

    @Test
    fun `a 500 on audio stays an ordinary IO failure`() {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val failure = runCatching { downloadStore().download("/audio/x.mp3") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure is SessionExpiredException, "a broken server is not a revoked credential")
    }

    @Test
    fun `signed out, the audio path fails fast without a request`() {
        sessionStore.clearToken()

        val failure = runCatching { downloadStore().download("/audio/x.mp3") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, server.requestCount)
    }

    // --- and what the player does with it -------------------------------------------------

    @Test
    fun `a session expiry during playback stops the player and ends the session`() = runBlocking {
        val repository = FakeRepository()
        val end = SessionEnd(SessionEndReason.Expired, "This device session expired. Sign in again.")
        val controller = Mp3PlaybackController(
            repository,
            object : AudioDownloadStore {
                override fun download(url: String): File = throw SessionExpiredException(end)
                override fun release(file: File?) = Unit
            },
            SilentAudioEngine(),
            Dispatchers.Default,
        )

        controller.play(
            ChapterSummary(id = 101, fictionId = 7, title = "Chapter 3", audio = AudioInfo(url = "/audio/x.mp3")),
            null,
        )
        withTimeout(15_000) { controller.state.first { it.error != null } }

        assertEquals("This device session expired. Sign in again.", controller.state.value.error)
        assertFalse(controller.state.value.isPlaying)
        // The repository is what actually drops the token and returns the app to the login screen.
        assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
        controller.release()
    }

    @Test
    fun `an ordinary download failure does not end the session`() = runBlocking {
        val repository = FakeRepository()
        val controller = Mp3PlaybackController(
            repository,
            object : AudioDownloadStore {
                override fun download(url: String): File = throw IOException("Connection reset")
                override fun release(file: File?) = Unit
            },
            SilentAudioEngine(),
            Dispatchers.Default,
        )

        controller.play(
            ChapterSummary(id = 101, fictionId = 7, title = "Chapter 3", audio = AudioInfo(url = "/audio/x.mp3")),
            null,
        )
        withTimeout(15_000) { controller.state.first { it.error != null } }

        assertNull(repository.sessionEnd.value, "a dropped connection must not sign the user out")
        controller.release()
    }

    private fun assertIsSessionExpired(failure: Throwable?): SessionExpiredException {
        assertTrue(failure is SessionExpiredException, "expected a session expiry, got $failure")
        return failure
    }

    /** An engine that never opens a device; the failure paths above never reach it anyway. */
    private class SilentAudioEngine : AudioEngine {
        private val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, 16, 2, 4, 44_100f, false)

        override fun decode(file: File): AudioInputStream =
            AudioInputStream(ByteArrayInputStream(ByteArray(0)), format, 0)

        override fun open(format: AudioFormat): AudioLine = object : AudioLine {
            override val isRunning: Boolean get() = false
            override fun start() = Unit
            override fun stop() = Unit
            override fun flush() = Unit
            override fun drain() = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length
            override fun close() = Unit
        }
    }
}
