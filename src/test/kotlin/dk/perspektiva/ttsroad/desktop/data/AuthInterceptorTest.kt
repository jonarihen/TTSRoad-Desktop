package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The single place a bearer token is attached.
 *
 * The origin rule is the one that matters most: covers, audio and API calls all share one client,
 * and cover URLs routinely point at a third-party CDN. A "do we have a token?" interceptor would
 * hand the user's TTSRoad credential to Royal Road's image host on every library screen.
 */
class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun clientFor(session: SessionState) = OkHttpClient.Builder()
        .addInterceptor(TtsRoadAuthInterceptor { session.bearerCredentials })
        .build()

    private fun get(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()) {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().close()
    }

    private fun signedIn() = SessionState(
        serverUrl = server.url("/").toString(),
        token = "ttsr_token",
        username = "admin",
    )

    @Test
    fun `a request to the signed-in server carries the bearer token`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(clientFor(signedIn()), server.url("/api/mobile/library").toString())

        assertEquals("Bearer ttsr_token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an audio request on the same origin carries the token`() {
        server.enqueue(MockResponse(code = 200, body = "mp3"))

        get(clientFor(signedIn()), server.url("/audio/a-test-serial/0003.mp3").toString())

        assertEquals("Bearer ttsr_token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a cover image on another origin is fetched without the token`() {
        // Two servers, one client — exactly the Coil situation.
        val cdn = MockWebServer()
        cdn.start()
        try {
            cdn.enqueue(MockResponse(code = 200, body = "jpeg"))

            get(clientFor(signedIn()), cdn.url("/covers/12345.jpg").toString())

            assertNull(
                cdn.takeRequest().headers["Authorization"],
                "the session credential must never leave the server it belongs to",
            )
        } finally {
            cdn.close()
        }
    }

    @Test
    fun `a different scheme on the same host is a different origin`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val httpsSession = signedIn().copy(
            serverUrl = server.url("/").newBuilder().scheme("https").build().toString(),
        )

        get(clientFor(httpsSession), server.url("/api/mobile/library").toString())

        assertNull(server.takeRequest().headers["Authorization"], "http:// is not https://")
    }

    @Test
    fun `a different port on the same host is a different origin`() {
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(MockResponse(code = 200, body = "ok"))

            get(clientFor(signedIn()), other.url("/api/mobile/library").toString())

            assertNull(other.takeRequest().headers["Authorization"])
        } finally {
            other.close()
        }
    }

    @Test
    fun `the no-auth marker suppresses the token and is stripped from the request`() {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        get(clientFor(signedIn()), server.url("/api/mobile/capabilities").toString(), mapOf(NoAuthHeader to "1"))

        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers[NoAuthHeader], "an internal marker must not reach the server")
    }

    @Test
    fun `nothing is attached while signed out`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(clientFor(SessionState()), server.url("/api/mobile/library").toString())

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an explicit Authorization header on the call is not overwritten`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(
            clientFor(signedIn()),
            server.url("/api/mobile/library").toString(),
            mapOf("Authorization" to "Bearer explicit"),
        )

        assertEquals("Bearer explicit", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `origin comparison treats a default port as equal to an explicit one`() {
        assertEquals(true, isSameOrigin("https://ttsroad.example.com/", "https://ttsroad.example.com:443/x".toHttpUrl()))
        assertEquals(true, isSameOrigin("https://TTSRoad.Example.com/", "https://ttsroad.example.com/x".toHttpUrl()))
        assertEquals(false, isSameOrigin("https://ttsroad.example.com/", "https://evil.example.com/x".toHttpUrl()))
        assertEquals(false, isSameOrigin("", "https://ttsroad.example.com/x".toHttpUrl()))
        // A subdomain is not the same origin, however tempting it looks.
        assertEquals(
            false,
            isSameOrigin("https://ttsroad.example.com/", "https://cdn.ttsroad.example.com/x".toHttpUrl()),
        )
    }
}
