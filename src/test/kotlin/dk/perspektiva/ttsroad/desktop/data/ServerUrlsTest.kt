package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ServerUrlsTest {

    // --- normalizeBaseUrl ---------------------------------------------------------------

    @Test
    fun `appends the trailing slash Retrofit requires`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com"))
    }

    @Test
    fun `an existing trailing slash is not doubled`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com/"))
    }

    @Test
    fun `several trailing slashes collapse to one`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com///"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("  https://ttsroad.example.com  "))
    }

    @Test
    fun `a port and a path prefix survive normalization`() {
        assertEquals("http://192.168.1.5:8000/tts/", normalizeBaseUrl("http://192.168.1.5:8000/tts"))
    }

    @Test
    fun `a missing scheme is rejected with the message the login screen renders`() {
        val error = assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("192.168.1.5:8000") }
        assertEquals("Server URL must start with http:// or https://", error.message)
    }

    @Test
    fun `a bare hostname is rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("ttsroad.example.com") }
    }

    @Test
    fun `a non-http scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("ftp://ttsroad.example.com") }
    }

    @Test
    fun `an empty string is rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("") }
    }

    // --- resolveAgainstServer ----------------------------------------------------------

    @Test
    fun `a relative audio path is resolved against the server`() {
        assertEquals(
            "https://ttsroad.example.com/audio/a-test-serial/0003.mp3",
            resolveAgainstServer("https://ttsroad.example.com/", "/audio/a-test-serial/0003.mp3"),
        )
    }

    @Test
    fun `an absolute URL on the server's own origin is left alone`() {
        val url = "https://ttsroad.example.com/audio/a-test-serial/0003.mp3"
        assertEquals(url, resolveAgainstServer("https://ttsroad.example.com/", url))
    }

    @Test
    fun `an external cover origin is never rewritten onto the TTSRoad host`() {
        // Covers commonly come straight from Royal Road's CDN. Rewriting them would 404.
        val cdn = "https://cdn.royalroadcdn.com/public/covers/12345.jpg"
        assertEquals(cdn, resolveAgainstServer("https://ttsroad.example.com/", cdn))
    }

    @Test
    fun `an external plain-http cover origin is left alone too`() {
        val cdn = "http://images.example.net/cover.jpg"
        assertEquals(cdn, resolveAgainstServer("https://ttsroad.example.com/", cdn))
    }

    @Test
    fun `scheme detection is case-insensitive`() {
        val cdn = "HTTPS://CDN.EXAMPLE.COM/cover.jpg"
        assertEquals(cdn, resolveAgainstServer("https://ttsroad.example.com/", cdn))
    }

    @Test
    fun `a relative path without a leading slash still produces one separator`() {
        assertEquals(
            "https://ttsroad.example.com/cover/x.jpg",
            resolveAgainstServer("https://ttsroad.example.com/", "cover/x.jpg"),
        )
    }

    @Test
    fun `resolution works against a server hosted under a path prefix`() {
        assertEquals(
            "https://example.com/tts/audio/x.mp3",
            resolveAgainstServer("https://example.com/tts/", "/audio/x.mp3"),
        )
    }

    @Test
    fun `the repository resolves relative URLs against the stored session server`() {
        val store = InMemorySessionStore(SessionState(serverUrl = "https://ttsroad.example.com/", token = "t"))
        val repo = RetrofitTtsRoadRepository(store, okhttp3.OkHttpClient())
        assertEquals(
            "https://ttsroad.example.com/cover/a.jpg",
            repo.resolveUrl("/cover/a.jpg"),
        )
        assertEquals(
            "https://cdn.royalroadcdn.com/c.jpg",
            repo.resolveUrl("https://cdn.royalroadcdn.com/c.jpg"),
        )
    }
}
