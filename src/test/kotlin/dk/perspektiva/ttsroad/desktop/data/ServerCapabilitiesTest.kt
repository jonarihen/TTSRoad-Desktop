package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing rules for `/api/mobile/capabilities`.
 *
 * The endpoint is additive by contract, so the interesting cases are all about a server this build
 * has never met: unknown keys, values of the wrong type, missing sections. Every one of them has
 * to land on "off" rather than on an exception, because the alternative is a client that refuses
 * to start against a newer server.
 */
class ServerCapabilitiesTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun parse(json: String): ServerCapabilities =
        ServerCapabilities.from(
            requireNotNull(moshi.adapter(CapabilitiesResponse::class.java).fromJson(json)),
        )

    @Test
    fun `a 1_4_0 server advertises read-along and device management and nothing else`() {
        val capabilities = parse(ServerFixtures.CAPABILITIES_1_4_0)

        assertEquals("Perspektiva TTSRoad", capabilities.serverName)
        assertEquals("1.4.0", capabilities.serverVersion)
        assertEquals("https://ttsroad.example.com", capabilities.serverBaseUrl)
        assertTrue(capabilities.readAlong)
        assertTrue(capabilities.deviceManagement)
        assertFalse(capabilities.search)
        assertFalse(capabilities.bookmarks)
        assertFalse(capabilities.deltaSync)
        assertFalse(capabilities.audioContentHash)
        // False even though bulk marking demonstrably works: the flag tracks a named route, and
        // inferring it from observed behaviour is how a client ends up calling a 404.
        assertFalse(capabilities.batchProgress)
        // Absent from that server's payload entirely, which must read as off rather than as unknown.
        assertFalse(capabilities.queue)
        assertEquals(200, capabilities.maxChaptersPerPage)
    }

    @Test
    fun `the queue capability is read from its own flag`() {
        val capabilities = parse(
            """{"api_version": 1, "server": {"name": "X", "version": "1.5.0"}, "capabilities": {"queue": true}}""",
        )

        assertTrue(capabilities.queue)
    }

    @Test
    fun `a non-boolean queue flag is off`() {
        val capabilities = parse(
            """{"api_version": 1, "server": {"name": "X", "version": "1.5.0"}, "capabilities": {"queue": "yes"}}""",
        )

        assertFalse(capabilities.queue, "only a literal true may light up a surface")
    }

    @Test
    fun `unknown keys are ignored and non-boolean values are treated as off`() {
        val capabilities = parse(ServerFixtures.CAPABILITIES_WITH_UNKNOWN_KEYS)

        assertTrue(capabilities.readAlong, "a literal true still enables the feature")
        assertFalse(capabilities.search, "\"partial\" is not true")
        assertFalse(capabilities.bookmarks, "1 is not true")
        assertFalse(capabilities.deviceManagement, "null is not true")
        // `time_travel` and `max_bookmarks` are simply dropped; nothing throws.
        assertNull(capabilities.maxChaptersPerPage, "a non-numeric limit is absent, not zero")
        assertEquals("2.9.0", capabilities.serverVersion)
    }

    @Test
    fun `api_version is never a proxy for a feature`() {
        val capabilities = parse("""{"api_version": 9, "server": {"name": "X", "version": "9.9"}}""")

        assertEquals(9, capabilities.apiVersion)
        assertFalse(capabilities.readAlong)
        assertFalse(capabilities.deviceManagement)
        assertNull(capabilities.maxChaptersPerPage)
    }

    @Test
    fun `the baseline has everything off and is not marked as discovered`() {
        val baseline = ServerCapabilities.Baseline

        assertFalse(baseline.readAlong)
        assertFalse(baseline.deviceManagement)
        assertFalse(baseline.queue)
        assertNull(baseline.serverVersion)
        assertNull(baseline.maxChaptersPerPage)
        assertFalse(baseline.isDiscovered, "the login screen must not claim to have found a server")
    }

    @Test
    fun `an empty body parses to the baseline rather than failing`() {
        val capabilities = parse("{}")

        assertEquals(ServerCapabilities.Baseline, capabilities)
    }
}
