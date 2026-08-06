package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FailingCredentialStore
import dk.perspektiva.ttsroad.desktop.security.CredentialStores
import dk.perspektiva.ttsroad.desktop.security.InMemoryCredentialStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The one-time move of a pre-Phase-1 plaintext token out of `session.json`.
 *
 * These are the tests that decide whether a user who upgrades is actually better off. The rule
 * being pinned is that the plaintext is destroyed **either way**: if the keyring accepts it the
 * session survives, and if anything goes wrong the user signs in again. Keeping a working session
 * at the cost of leaving a readable token on disk is never the right trade.
 */
class SessionMigrationTest {

    /** Exactly what an installation from before this phase left behind. */
    private val legacyFile = """
        {
          "serverUrl": "https://ttsroad.example.com/",
          "token": "ttsr_LEGACY_PLAINTEXT_TOKEN",
          "username": "admin",
          "isAdmin": true,
          "serverName": "Perspektiva TTSRoad"
        }
    """.trimIndent()

    @Test
    fun `a plaintext token is moved into the keyring and erased from the file`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        file.writeText(legacyFile)
        val keyring = InMemoryCredentialStore()

        val store = FileSessionStore(file, keyring)

        // The session keeps working — the user is not signed out by the upgrade.
        assertTrue(store.current().isLoggedIn)
        assertEquals("ttsr_LEGACY_PLAINTEXT_TOKEN", store.current().token)
        // …and the secret has moved.
        assertEquals(
            "ttsr_LEGACY_PLAINTEXT_TOKEN",
            keyring.retrieve(CredentialStores.credentialKey("https://ttsroad.example.com/", "admin")),
        )
        val json = file.readText()
        assertFalse(json.contains("ttsr_LEGACY_PLAINTEXT_TOKEN"), "plaintext survived migration: $json")
        assertFalse(json.contains("\"token\""), json)
        assertTrue(json.contains("credentialKey"), json)
    }

    @Test
    fun `migration is one-time - the second start reads the keyring, not the file`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        file.writeText(legacyFile)
        val keyring = InMemoryCredentialStore()
        FileSessionStore(file, keyring)

        val second = FileSessionStore(file, keyring)

        assertTrue(second.current().isLoggedIn)
        assertFalse(file.readText().contains("ttsr_LEGACY_PLAINTEXT_TOKEN"))
    }

    @Test
    fun `a failed migration removes the plaintext and requires a new sign-in`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        file.writeText(legacyFile)

        // The keyring is present but refuses to store — a locked collection, a denied prompt.
        val store = FileSessionStore(file, FailingCredentialStore())

        assertFalse(store.current().isLoggedIn, "a session we cannot protect must not be kept")
        val json = file.readText()
        assertFalse(json.contains("ttsr_LEGACY_PLAINTEXT_TOKEN"), "exposure must be removed anyway: $json")
        // The non-secret hints survive so the user is not left staring at an empty form.
        assertEquals("https://ttsroad.example.com/", store.current().serverUrl)
        assertEquals("admin", store.current().username)
    }

    @Test
    fun `the settings file is owner-only after a write`(@TempDir dir: File) {
        val file = File(dir, "session.json")

        FileSessionStore(file, InMemoryCredentialStore()).save(
            SessionState(serverUrl = "https://x/", token = "ttsr_abc", username = "admin"),
        )

        val path = file.toPath()
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                posix.readAttributes().permissions(),
            )
            return
        }
        // Windows: the DACL is replaced with a single owner entry, so nothing else on the machine
        // can read which server and account this install uses.
        val acl = requireNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)) {
            "neither POSIX permissions nor an ACL are available on this filesystem"
        }
        val owner = acl.owner
        val principals = acl.acl.map { it.principal() }.toSet()
        assertEquals(setOf(owner), principals, "only the owner may appear in the ACL")
    }

    @Test
    fun `a settings write is atomic - an existing file is never truncated in place`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val keyring = InMemoryCredentialStore()
        val store = FileSessionStore(file, keyring)
        store.save(SessionState(serverUrl = "https://first/", token = "ttsr_a", username = "admin"))

        store.save(SessionState(serverUrl = "https://second/", token = "ttsr_b", username = "admin"))

        assertTrue(file.readText().contains("https://second/"))
        // No temp file is left behind by a successful write.
        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "atomic write left temp files: ${leftovers.map { it.name }}")
    }
}
