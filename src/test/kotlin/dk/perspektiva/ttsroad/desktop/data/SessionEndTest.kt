package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The structured 401 body, in both shapes FastAPI can produce.
 *
 * `detail` is either a bare string (`{"detail": "Not authenticated"}`) or an object
 * (`{"detail": {"message": …, "reason": …}}`), and both come back from the same endpoints, so a
 * parser that handles one is a parser that crashes on the other.
 */
class SessionEndTest {

    @Test
    fun `an expired token is recognised and the server's own wording is kept`() {
        val end = parseSessionEnd(ServerFixtures.UNAUTHORIZED_TOKEN_EXPIRED)

        assertEquals(SessionEndReason.Expired, end.reason)
        assertEquals("This device session expired. Sign in again.", end.message)
    }

    @Test
    fun `a revoked token is recognised`() {
        assertEquals(SessionEndReason.Revoked, parseSessionEnd(ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED).reason)
    }

    @Test
    fun `an invalid token is recognised`() {
        assertEquals(SessionEndReason.Invalid, parseSessionEnd(ServerFixtures.UNAUTHORIZED_TOKEN_INVALID).reason)
    }

    @Test
    fun `a bare-string detail is still readable`() {
        val end = parseSessionEnd(ServerFixtures.UNAUTHORIZED_NOT_AUTHENTICATED)

        assertEquals(SessionEndReason.Unknown, end.reason)
        assertEquals("Not authenticated", end.message)
    }

    @Test
    fun `every unreadable body still yields a usable explanation`() {
        val bodies = listOf(
            """{"detail":{"reason":"teapot"}}""",
            """{"detail":{}}""",
            "{}",
            "",
            "not json at all",
            null,
        )

        bodies.forEach { body ->
            val end = parseSessionEnd(body)
            assertEquals(SessionEndReason.Unknown, end.reason, "body: $body")
            assertTrue(end.message.isNotBlank(), "a sign-out with no explanation is worse than a wrong one: $body")
        }
    }

    @Test
    fun `the reason match is case and whitespace tolerant`() {
        assertEquals(
            SessionEndReason.Expired,
            parseSessionEnd("""{"detail":{"reason":"  TOKEN_EXPIRED  "}}""").reason,
        )
    }

    @Test
    fun `a server message that quotes the credential is redacted before it is shown`() {
        val end = parseSessionEnd(
            """{"detail":{"message":"Token ttsr_SECRETVALUE is invalid","reason":"invalid_token"}}""",
        )

        assertTrue(end.message.contains("***"), end.message)
        assertTrue(!end.message.contains("ttsr_SECRETVALUE"), end.message)
    }

    // --- detailMessage --------------------------------------------------------------------

    @Test
    fun `detailMessage reads both shapes and gives up quietly on neither`() {
        assertEquals("Invalid username or password", detailMessage(ServerFixtures.LOGIN_401_STRING_DETAIL))
        assertEquals("Two-factor authentication required", detailMessage(ServerFixtures.LOGIN_401_TOTP_REQUIRED))
        assertNull(detailMessage("""{"detail": 42}"""))
        assertNull(detailMessage("<html>502</html>"))
        assertNull(detailMessage(null))
    }

    // --- 429 ------------------------------------------------------------------------------

    @Test
    fun `the Retry-After header wins over the body`() {
        val throttle = parseLoginThrottle(ServerFixtures.LOGIN_429_THROTTLED, retryAfterHeader = "60")

        assertEquals(60, throttle.retryAfterSeconds)
        assertEquals("Too many failed attempts — try again in a minute", throttle.displayMessage)
    }

    @Test
    fun `a proxy that strips Retry-After falls back to the body`() {
        assertEquals(900, parseLoginThrottle(ServerFixtures.LOGIN_429_THROTTLED, null).retryAfterSeconds)
    }

    @Test
    fun `an HTTP-date Retry-After is ignored rather than guessed at`() {
        val throttle = parseLoginThrottle("""{"detail":{"message":"Slow down"}}""", "Wed, 21 Oct 2026 07:28:00 GMT")

        assertNull(throttle.retryAfterSeconds)
        assertEquals("Slow down", throttle.displayMessage)
    }

    @Test
    fun `the wait is phrased in units a human uses`() {
        assertEquals("a moment", formatRetryAfter(0))
        assertEquals("30 seconds", formatRetryAfter(30))
        assertEquals("a minute", formatRetryAfter(90))
        assertEquals("15 minutes", formatRetryAfter(900))
        assertEquals("an hour", formatRetryAfter(3_600))
        assertEquals("2 hours", formatRetryAfter(7_200))
    }
}
