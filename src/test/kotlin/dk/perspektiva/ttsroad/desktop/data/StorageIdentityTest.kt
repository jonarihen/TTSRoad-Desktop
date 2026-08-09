package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cache keys and the path safety that hangs off them.
 *
 * The acceptance criteria this targets: two servers with overlapping fiction/chapter names cannot
 * share or overwrite content, an address change for the same advertised identity does not force
 * needless duplication, and cleanup cannot escape the owned roots.
 */
class StorageIdentityTest {

    // --- Server identity ------------------------------------------------------------------------

    @Test
    fun `the advertised base url is the identity, so an address change does not duplicate`() {
        // Same server, reached three ways: over the LAN, over a tunnel, and by its public name.
        // All three advertise the same base_url, so all three must resolve to one directory —
        // otherwise moving house re-downloads the entire library.
        val lan = StorageIdentity.serverKey("http://192.168.1.10:8000", "https://ttsroad.example")
        val tunnel = StorageIdentity.serverKey("https://box.tailnet.ts.net", "https://ttsroad.example")
        val public = StorageIdentity.serverKey("https://ttsroad.example", "https://ttsroad.example")

        assertEquals(lan, tunnel)
        assertEquals(lan, public)
    }

    @Test
    fun `without an advertised identity the connect address is used`() {
        // A server too old to advertise base_url still has to store something, and the address is
        // the only stable thing on offer.
        val key = StorageIdentity.serverKey("https://old.example")
        assertEquals(StorageIdentity.KeyLength, key.length)
        assertNotEquals(StorageIdentity.serverKey("https://other.example"), key)
    }

    @Test
    fun `two different servers never share a key`() {
        assertNotEquals(
            StorageIdentity.serverKey("https://a.example", "https://a.example"),
            StorageIdentity.serverKey("https://b.example", "https://b.example"),
        )
    }

    @Test
    fun `an advertised identity beats the address even when the address is shared`() {
        // Two servers behind one reverse proxy, distinguished only by what they advertise.
        assertNotEquals(
            StorageIdentity.serverKey("https://proxy.example", "https://one.internal"),
            StorageIdentity.serverKey("https://proxy.example", "https://two.internal"),
        )
    }

    // --- Origin canonicalisation ----------------------------------------------------------------

    @Test
    fun `case and a trailing slash do not change the identity`() {
        val expected = "https://host.example"
        assertEquals(expected, StorageIdentity.canonicalOrigin("https://host.example"))
        assertEquals(expected, StorageIdentity.canonicalOrigin("https://Host.Example/"))
        assertEquals(expected, StorageIdentity.canonicalOrigin("  https://host.example/  "))
    }

    @Test
    fun `the path is part of the identity, and its case is preserved`() {
        // normalizeBaseUrl keeps whatever path the user configured and Retrofit supports
        // path-based base URLs, so these are two deployments that may hold entirely different
        // libraries under the same chapter ids. Merging them into one download directory would
        // serve one server's audio for the other's chapter.
        assertNotEquals(
            StorageIdentity.serverKey("https://host.example/ttsroad/"),
            StorageIdentity.serverKey("https://host.example/other/"),
        )
        assertNotEquals(
            StorageIdentity.serverKey("https://host.example/TTSRoad/"),
            StorageIdentity.serverKey("https://host.example/ttsroad/"),
        )
        // Host case and a trailing slash are still noise around a path that is not.
        assertEquals(
            StorageIdentity.serverKey("https://HOST.example/TTSRoad"),
            StorageIdentity.serverKey("https://host.example/TTSRoad/"),
        )
    }

    @Test
    fun `the default port is dropped but a custom one is kept`() {
        assertEquals("https://host.example", StorageIdentity.canonicalOrigin("https://host.example:443/"))
        assertEquals("http://host.example", StorageIdentity.canonicalOrigin("http://host.example:80/"))
        assertEquals("http://host.example:8000", StorageIdentity.canonicalOrigin("http://host.example:8000/"))
        // A custom port is a different deployment, so it must not collapse onto the bare host.
        assertNotEquals(
            StorageIdentity.canonicalOrigin("http://host.example"),
            StorageIdentity.canonicalOrigin("http://host.example:8000"),
        )
    }

    @Test
    fun `the scheme is part of the identity`() {
        // http:// and https:// on one host may be two deployments; merging them would mix libraries.
        assertNotEquals(
            StorageIdentity.canonicalOrigin("http://host.example"),
            StorageIdentity.canonicalOrigin("https://host.example"),
        )
    }

    @Test
    fun `credentials in a url never reach the identity`() {
        assertEquals(
            "https://host.example",
            StorageIdentity.canonicalOrigin("https://alice:hunter2@host.example/"),
        )
        // And the same server reached with and without credentials is still one server.
        assertEquals(
            StorageIdentity.serverKey("https://host.example"),
            StorageIdentity.serverKey("https://alice:hunter2@host.example"),
        )
    }

    // --- Account identity -----------------------------------------------------------------------

    @Test
    fun `usernames differing only in case are different accounts`() {
        // The stored name is the server's own spelling, and Django usernames are case-sensitive by
        // default. Two accounts sharing a download directory is a disclosure; one account
        // splitting is a re-download.
        assertNotEquals(StorageIdentity.accountKey("Alice"), StorageIdentity.accountKey("alice"))
        // Surrounding whitespace is still noise.
        assertEquals(StorageIdentity.accountKey(" alice "), StorageIdentity.accountKey("alice"))
    }

    @Test
    fun `two accounts never share a key`() {
        assertNotEquals(StorageIdentity.accountKey("alice"), StorageIdentity.accountKey("bob"))
    }

    @Test
    fun `a signed-out session still has somewhere to put things`() {
        assertEquals(StorageIdentity.AnonymousAccount, StorageIdentity.accountKey(null))
        assertEquals(StorageIdentity.AnonymousAccount, StorageIdentity.accountKey("   "))
    }

    @Test
    fun `one server's two accounts do not share a directory`() {
        val alice = StorageIdentity.of("https://host.example", username = "alice")
        val bob = StorageIdentity.of("https://host.example", username = "bob")

        assertEquals(alice.serverKey, bob.serverKey)
        assertNotEquals(alice.relativePath, bob.relativePath)
    }

    // --- Path safety ----------------------------------------------------------------------------

    @Test
    fun `keys are hex only, so no key can escape its root`() {
        val hostile = listOf(
            "https://../../etc",
            "https://host.example/../../..",
            "https://host/%2e%2e%2f",
            "\\\\server\\share",
            "https://host.example/a b",
        )
        for (url in hostile) {
            val key = StorageIdentity.serverKey(url, null)
            assertTrue(key.all { it in "0123456789abcdef" }, "not hex for $url: $key")
            assertEquals(StorageIdentity.KeyLength, key.length)
        }
    }

    @Test
    fun `the relative path is exactly two safe segments`() {
        val path = StorageIdentity.of("https://host.example", username = "alice").relativePath
        val segments = path.split('/')
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.isNotEmpty() && !it.contains("..") })
    }

    // --- File names -----------------------------------------------------------------------------

    @Test
    fun `a chapter file is named from its id, not from the server`() {
        assertEquals("512.mp3", chapterFileName(512))
        assertEquals("512.mp3.part", partFileName(512))
    }

    @Test
    fun `a content hash changes the name so re-encoded audio replaces the stale file`() {
        val before = chapterFileName(512, "abc123")
        val after = chapterFileName(512, "def456")
        assertNotEquals(before, after)
        assertTrue(before.startsWith("512-"))
        assertTrue(before.endsWith(".mp3"))
    }

    @Test
    fun `a hostile content hash cannot introduce a path separator`() {
        val name = chapterFileName(512, "../../etc/passwd")
        assertFalse(name.contains('/'), name)
        assertFalse(name.contains(".."), name)
        assertFalse(name.contains('\\'), name)
        assertTrue(name.startsWith("512-"), name)
    }

    @Test
    fun `a blank content hash falls back to the plain name`() {
        assertEquals("512.mp3", chapterFileName(512, ""))
        assertEquals("512.mp3", chapterFileName(512, "///"))
    }
}
