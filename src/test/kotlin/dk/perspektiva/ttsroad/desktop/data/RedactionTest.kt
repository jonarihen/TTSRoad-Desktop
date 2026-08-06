package dk.perspektiva.ttsroad.desktop.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Redaction is the last line before a secret becomes text a user can copy into an issue, so these
 * tests are written as "the token must not appear", not "the output equals this string".
 */
class RedactionTest {

    private val token = "ttsr_Zm9vYmFyYmF6cXV1eA"

    private fun assertScrubbed(input: String, vararg secrets: String) {
        val output = redactSecrets(input)
        secrets.forEach { assertFalse(output.contains(it), "leaked \"$it\" in: $output") }
    }

    @Test
    fun `a bearer token is removed wherever it appears`() {
        assertScrubbed("failed to download with $token", token)
        assertScrubbed("""{"token":"$token"}""", token)
        assertScrubbed("GET /audio/x.mp3?token=$token", token)
    }

    @Test
    fun `an Authorization header is removed even when the token is not a TTSRoad one`() {
        assertScrubbed("Authorization: Bearer abc.def.ghi", "abc.def.ghi")
        assertScrubbed("authorization=opaque-value", "opaque-value")
        assertScrubbed("""{"Authorization": "Bearer abc.def.ghi"}""", "abc.def.ghi")
    }

    @Test
    fun `secrets in query strings are removed`() {
        assertScrubbed("https://x/api?password=hunter2&user=admin", "hunter2")
        assertScrubbed("https://x/feed?feed_token=abc123", "abc123")
        assertScrubbed("https://x/login?totp_code=000000", "000000")
        // The non-secret part of the URL is worth keeping — that is what makes a report useful.
        assertTrue(redactSecrets("https://x/api?password=hunter2&user=admin").contains("user=admin"))
    }

    @Test
    fun `credentials in a JSON body are removed`() {
        assertScrubbed("""{"username":"admin","password":"hunter2"}""", "hunter2")
        assertScrubbed("""{"totp_code": "123456"}""", "123456")
        assertTrue(redactSecrets("""{"username":"admin","password":"hunter2"}""").contains("admin"))
    }

    @Test
    fun `a URL with inline credentials is scrubbed`() {
        assertScrubbed("https://admin:hunter2@ttsroad.example.com/api", "hunter2")
    }

    @Test
    fun `redaction is null-safe and leaves innocent text alone`() {
        assertEquals("", redactSecrets(null))
        assertEquals("", redactSecrets(""))
        assertEquals("could not load library", redactSecrets("could not load library"))
    }

    // --- describeNetworkFailure -----------------------------------------------------------

    @Test
    fun `transport failures become one sentence, with no stack trace and no class names`() {
        val cases = mapOf(
            UnknownHostException("ttsroad.example.com") to "Cannot reach that server — its address did not resolve",
            SSLHandshakeException("PKIX path building failed: unable to find valid certification path") to
                "The server's TLS certificate was not accepted",
            SocketTimeoutException("timeout") to "The server did not respond in time",
            ConnectException("Connection refused: connect") to "Could not connect to the server",
        )

        cases.forEach { (error, expected) ->
            val described = describeNetworkFailure(error)
            assertEquals(expected, described)
            assertFalse(described.contains("Exception"), described)
            assertFalse(described.contains("\n"), "a stack trace must never reach the UI: $described")
        }
    }

    @Test
    fun `an unrecognised failure keeps its message but not its secrets`() {
        val described = describeNetworkFailure(IOException("failed with Authorization: Bearer $token"))

        assertFalse(described.contains(token), described)
        assertTrue(described.contains("failed with"), described)
    }

    @Test
    fun `a failure with no message at all still says something`() {
        assertTrue(describeNetworkFailure(IllegalStateException()).isNotBlank())
    }

    @Test
    fun `a screen falls back to its own wording only when there is nothing to say`() {
        assertEquals("Could not load library", userFacingMessage(RuntimeException(), "Could not load library"))
        assertEquals(
            "The server did not respond in time",
            userFacingMessage(SocketTimeoutException("timeout"), "Could not load library"),
        )
        assertEquals("no route to host", userFacingMessage(IllegalStateException("no route to host"), "x"))
        // …and whatever it says, it is redacted first.
        assertFalse(userFacingMessage(IllegalStateException("bad $token"), "x").contains(token))
    }

    // --- AppLog ---------------------------------------------------------------------------

    @Test
    fun `every log line goes through redaction`() {
        val lines = mutableListOf<String>()
        val previous = AppLog.sink
        AppLog.sink = { lines += it }
        try {
            AppLog.warn("could not store $token", IOException("Authorization: Bearer $token"))
        } finally {
            AppLog.sink = previous
        }

        assertEquals(1, lines.size)
        assertFalse(lines.single().contains(token), lines.single())
    }
}
