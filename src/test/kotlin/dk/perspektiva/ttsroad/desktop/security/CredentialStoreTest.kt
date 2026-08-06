package dk.perspektiva.ttsroad.desktop.security

import dk.perspektiva.ttsroad.desktop.FakeCommandRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Credential-store behaviour, split into what can be proven on any machine (argument shapes, exit
 * codes, selection) and what needs the real platform (the Windows one, which does run here).
 */
class CredentialStoreTest {

    private val token = "ttsr_Zm9vYmFyYmF6cXV1eA"

    // --- The fallback ---------------------------------------------------------------------

    @Test
    fun `the in-memory store round-trips but promises nothing about a restart`() {
        val store = InMemoryCredentialStore()

        store.store("ttsroad/abc", token)

        assertEquals(token, store.retrieve("ttsroad/abc"))
        assertFalse(store.persistsAcrossRestarts, "the UI decides what to warn about from this flag")
        store.delete("ttsroad/abc")
        assertNull(store.retrieve("ttsroad/abc"))
    }

    @Test
    fun `deleting something that is not there is not an error`() {
        InMemoryCredentialStore().delete("ttsroad/never-stored")
    }

    // --- Secret Service (Linux) -----------------------------------------------------------

    @Test
    fun `the Linux store passes the secret on stdin and never in the argument list`() {
        val runner = FakeCommandRunner()
        val store = requireNotNull(SecretServiceCredentialStore.createOrNull(runner))

        store.store("ttsroad/abc", token)

        val (argv, stdin) = runner.invocations.last()
        assertEquals(token, stdin)
        assertTrue(
            argv.none { it.contains(token) },
            "the token would be world-readable in /proc/<pid>/cmdline: $argv",
        )
        assertEquals("secret-tool", argv.first())
        assertEquals("store", argv[1])
        assertTrue(argv.contains("ttsroad/abc"))
    }

    @Test
    fun `a Linux lookup miss is null, not an error`() {
        val runner = FakeCommandRunner().on("lookup", CommandResult(exitCode = 1, stdout = "", stderr = ""))
        val store = requireNotNull(SecretServiceCredentialStore.createOrNull(runner))

        assertNull(store.retrieve("ttsroad/abc"))
    }

    @Test
    fun `a Linux lookup strips the trailing newline some builds add`() {
        val runner = FakeCommandRunner().on("lookup", CommandResult(0, "$token\n", ""))
        val store = requireNotNull(SecretServiceCredentialStore.createOrNull(runner))

        assertEquals(token, store.retrieve("ttsroad/abc"))
    }

    @Test
    fun `a refusal to store is raised, so migration can react instead of silently losing the token`() {
        val runner = FakeCommandRunner().on("store", CommandResult(1, "", "Cannot create item: locked"))
        val store = requireNotNull(SecretServiceCredentialStore.createOrNull(runner))

        val failure = runCatching { store.store("ttsroad/abc", token) }.exceptionOrNull()

        assertTrue(failure is CredentialStoreException)
        assertFalse(failure.message.orEmpty().contains(token), failure.message.orEmpty())
    }

    @Test
    fun `no secret-tool on PATH means no Linux keyring`() {
        val runner = FakeCommandRunner().default(CommandResult.NotExecutable)

        assertNull(SecretServiceCredentialStore.createOrNull(runner))
    }

    @Test
    fun `libsecret installed but no Secret Service daemon means no keyring`() {
        // Exit 1 *with* a diagnostic on stderr is the "no service on the bus" case; exit 1 with a
        // silent stderr is the ordinary "no such secret", which means the keyring is fine.
        val absent = FakeCommandRunner()
            .default(CommandResult(1, "", "Cannot autolaunch D-Bus without X11 \$DISPLAY"))
        val present = FakeCommandRunner().default(CommandResult(1, "", ""))

        assertNull(SecretServiceCredentialStore.createOrNull(absent))
        assertTrue(SecretServiceCredentialStore.createOrNull(present) != null)
    }

    // --- macOS keychain -------------------------------------------------------------------

    @Test
    fun `the macOS store passes the password on stdin and never in the argument list`() {
        val runner = FakeCommandRunner()
        val store = requireNotNull(MacKeychainCredentialStore.createOrNull(runner))

        store.store("ttsroad/abc", token)

        val (argv, stdin) = runner.invocations.last()
        assertTrue(argv.none { it.contains(token) }, "the token would show up in `ps`: $argv")
        // `security` asks for the password twice when -w has no value.
        assertEquals("$token\n$token\n", stdin)
        assertEquals("add-generic-password", argv[1])
        assertTrue(argv.contains("-U"), "without -U a second sign-in fails with errSecDuplicateItem")
    }

    @Test
    fun `exit 44 from security is item-not-found, not a broken keychain`() {
        val runner = FakeCommandRunner().default(CommandResult(44, "", ""))

        val store = requireNotNull(MacKeychainCredentialStore.createOrNull(runner))

        assertNull(store.retrieve("ttsroad/abc"))
    }

    // --- Selection ------------------------------------------------------------------------

    @Test
    fun `an unknown platform with no keyring falls back to session-only, never to a file`() {
        val store = CredentialStores.forCurrentPlatform(
            osName = "SomeOS",
            commandRunner = FakeCommandRunner().default(CommandResult.NotExecutable),
            windowsFactory = { null },
        )

        assertFalse(store.persistsAcrossRestarts)
        assertEquals("session-only", store.id)
    }

    @Test
    fun `each platform reaches for its own native store`() {
        val runner = FakeCommandRunner()
        val fakeWindows = InMemoryCredentialStore(id = "windows-credential-manager")

        assertEquals(
            "windows-credential-manager",
            CredentialStores.forCurrentPlatform("Windows 11", runner, { fakeWindows }).id,
        )
        assertEquals("macos-keychain", CredentialStores.forCurrentPlatform("Mac OS X", runner, { null }).id)
        assertEquals("secret-service", CredentialStores.forCurrentPlatform("Linux", runner, { null }).id)
    }

    // --- Windows, for real ----------------------------------------------------------------

    @Test
    fun `Windows Credential Manager round-trips a token`() {
        assumeTrue(
            System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the Win32 credential API only exists on Windows",
        )
        val store = WindowsCredentialStore.createOrNull()
        assumeTrue(store != null, "the Win32 credential API is not reachable from this runtime")
        requireNotNull(store)
        val key = "ttsroad/test-${System.nanoTime()}"

        try {
            store.store(key, token)

            assertEquals(token, store.retrieve(key))
            assertNull(store.retrieve("$key-does-not-exist"), "a missing entry is null, not an exception")
        } finally {
            store.delete(key)
        }
        assertNull(store.retrieve(key), "delete must actually remove the entry")
    }

    @Test
    fun `Windows Credential Manager overwrites rather than duplicating`() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("win"))
        val store = WindowsCredentialStore.createOrNull()
        assumeTrue(store != null)
        requireNotNull(store)
        val key = "ttsroad/test-${System.nanoTime()}"

        try {
            store.store(key, "first")
            store.store(key, "second")

            assertEquals("second", store.retrieve(key))
        } finally {
            store.delete(key)
        }
    }
}
