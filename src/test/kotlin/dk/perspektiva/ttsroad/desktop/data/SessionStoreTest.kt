package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.security.CredentialStores
import dk.perspektiva.ttsroad.desktop.security.InMemoryCredentialStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Session state transitions: signed out -> signed in -> signed out, and what survives each hop. */
class SessionStoreTest {

    @Test
    fun `a fresh session is signed out and has no authorization header`() {
        val state = SessionState()
        assertFalse(state.isLoggedIn)
        assertNull(state.authorizationHeader)
        assertNull(state.bearerCredentials)
    }

    @Test
    fun `a token without a server URL is not a session`() {
        assertFalse(SessionState(serverUrl = "", token = "t").isLoggedIn)
        // …and it produces no credential either, so the interceptor has no origin to match against.
        assertNull(SessionState(serverUrl = "", token = "t").bearerCredentials)
    }

    @Test
    fun `a server URL without a token is not a session`() {
        assertFalse(SessionState(serverUrl = "https://x/", token = null).isLoggedIn)
    }

    @Test
    fun `a blank token is treated as no token`() {
        val state = SessionState(serverUrl = "https://x/", token = "   ")
        assertFalse(state.isLoggedIn)
        assertNull(state.authorizationHeader)
    }

    @Test
    fun `signing in publishes the new state on the flow`() {
        val store = InMemorySessionStore()
        assertFalse(store.session.value.isLoggedIn)

        store.save(SessionState(serverUrl = "https://x/", token = "t", username = "admin", isAdmin = true))

        assertTrue(store.session.value.isLoggedIn)
        assertEquals("Bearer t", store.session.value.authorizationHeader)
        assertEquals("admin", store.session.value.username)
    }

    @Test
    fun `clearToken drops the credential and every claim, but keeps the non-secret hints`() {
        val store = InMemorySessionStore(
            SessionState(
                serverUrl = "https://x/",
                token = "t",
                username = "admin",
                isAdmin = true,
                serverName = "Perspektiva TTSRoad",
                serverVersion = "1.4.0",
                deviceId = 42,
                expiresAt = "2026-11-04T09:12:33Z",
            ),
        )

        store.clearToken()

        val state = store.current()
        assertFalse(state.isLoggedIn)
        assertNull(state.token)
        // Claims about a session that no longer exists.
        assertFalse(state.isAdmin)
        assertNull(state.deviceId)
        assertNull(state.expiresAt)
        // Retained on purpose: the login screen prefills from these and Settings still shows the server.
        assertEquals("https://x/", state.serverUrl)
        assertEquals("Perspektiva TTSRoad", state.serverName)
        assertEquals("1.4.0", state.serverVersion)
        assertEquals("admin", state.username)
    }

    // --- FileSessionStore: what is on disk vs what is in the keyring -----------------------

    private fun signedIn(serverUrl: String = "https://ttsroad.example.com/") = SessionState(
        serverUrl = serverUrl,
        token = "ttsr_abc",
        username = "admin",
        isAdmin = true,
        serverName = "Perspektiva TTSRoad",
        serverVersion = "1.4.0",
        deviceId = 42,
    )

    @Test
    fun `the token goes to the keyring and never to the settings file`(@TempDir dir: File) {
        val file = File(dir, "nested/session.json")
        val keyring = InMemoryCredentialStore()

        FileSessionStore(file, keyring).save(signedIn())

        assertTrue(file.isFile, "save() should create parent directories")
        val json = file.readText()
        assertFalse(json.contains("ttsr_abc"), "the bearer token must not be on disk: $json")
        assertFalse(json.contains("\"token\""), json)
        // What IS on disk: the server/user hints and the identifier of the keyring entry.
        assertTrue(json.contains("https://ttsroad.example.com/"), json)
        assertTrue(json.contains("credentialKey"), json)
        assertEquals(
            "ttsr_abc",
            keyring.retrieve(CredentialStores.credentialKey("https://ttsroad.example.com/", "admin")),
        )
    }

    @Test
    fun `a keyring-backed session is restored on the next start`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val keyring = InMemoryCredentialStore()
        FileSessionStore(file, keyring).save(signedIn())

        val reloaded = FileSessionStore(file, keyring).current()

        assertTrue(reloaded.isLoggedIn)
        assertEquals("ttsr_abc", reloaded.token)
        assertEquals("admin", reloaded.username)
        assertEquals("Perspektiva TTSRoad", reloaded.serverName)
        assertEquals("1.4.0", reloaded.serverVersion)
    }

    @Test
    fun `a session with nowhere to keep the credential does not survive a restart`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        // A *fresh* in-memory store is what "the keyring went away" looks like from the next
        // process's point of view; the whole point is that the token was never written down.
        FileSessionStore(file, InMemoryCredentialStore()).save(signedIn())

        val reloaded = FileSessionStore(file, InMemoryCredentialStore())

        assertFalse(reloaded.current().isLoggedIn)
        assertFalse(reloaded.persistsCredentials)
        // The non-secret hints still come back, so the login form is prefilled.
        assertEquals("https://ttsroad.example.com/", reloaded.current().serverUrl)
        assertEquals("admin", reloaded.current().username)
    }

    @Test
    fun `file store starts signed out when the file does not exist`(@TempDir dir: File) {
        assertFalse(FileSessionStore(File(dir, "missing.json"), InMemoryCredentialStore()).current().isLoggedIn)
    }

    @Test
    fun `a corrupt session file degrades to signed out instead of crashing at startup`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        file.writeText("{ this is not json")

        assertFalse(FileSessionStore(file, InMemoryCredentialStore()).current().isLoggedIn)
    }

    @Test
    fun `clearToken removes the keyring entry, so a restart stays signed out`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val keyring = InMemoryCredentialStore()
        val store = FileSessionStore(file, keyring)
        store.save(signedIn())
        val key = CredentialStores.credentialKey("https://ttsroad.example.com/", "admin")

        store.clearToken()

        assertNull(keyring.retrieve(key), "signing out must destroy the credential, not orphan it")
        val reloaded = FileSessionStore(file, keyring).current()
        assertFalse(reloaded.isLoggedIn)
        assertEquals("https://ttsroad.example.com/", reloaded.serverUrl)
    }

    @Test
    fun `signing in as someone else does not leave the old token live in the keyring`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val keyring = InMemoryCredentialStore()
        val store = FileSessionStore(file, keyring)
        store.save(signedIn())
        val firstKey = CredentialStores.credentialKey("https://ttsroad.example.com/", "admin")

        store.save(signedIn().copy(username = "operator", token = "ttsr_other"))

        assertNull(keyring.retrieve(firstKey), "the replaced credential must be destroyed, not orphaned")
        assertEquals(
            "ttsr_other",
            keyring.retrieve(CredentialStores.credentialKey("https://ttsroad.example.com/", "operator")),
        )
    }

    @Test
    fun `switching servers does not leave the previous server's token behind`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val keyring = InMemoryCredentialStore()
        val store = FileSessionStore(file, keyring)
        store.save(signedIn(serverUrl = "https://first.example.com/"))
        val firstKey = CredentialStores.credentialKey("https://first.example.com/", "admin")

        store.save(signedIn(serverUrl = "https://second.example.com/"))

        assertNull(keyring.retrieve(firstKey))
    }

    // --- Where the settings file lives ----------------------------------------------------

    private fun dirFor(os: String, env: Map<String, String> = emptyMap()) =
        FileSessionStore.configDir(os, "/home/u", { env[it] }).path.replace('\\', '/')

    @Test
    fun `Linux honours XDG_CONFIG_HOME and falls back to the spec's default`() {
        assertEquals("/xdg/TTSRoad", dirFor("Linux", mapOf("XDG_CONFIG_HOME" to "/xdg")))
        assertEquals("/home/u/.config/TTSRoad", dirFor("Linux"))
        assertEquals("/home/u/.config/TTSRoad", dirFor("Linux", mapOf("XDG_CONFIG_HOME" to "")))
        // The XDG spec says a relative value is invalid and must be ignored, not resolved.
        assertEquals("/home/u/.config/TTSRoad", dirFor("Linux", mapOf("XDG_CONFIG_HOME" to "relative/path")))
    }

    @Test
    fun `Windows and macOS use their own conventions`() {
        assertEquals("C:/Users/u/AppData/Roaming/TTSRoad", dirFor("Windows 11", mapOf("APPDATA" to "C:/Users/u/AppData/Roaming")))
        assertEquals("/home/u/AppData/Roaming/TTSRoad", dirFor("Windows 11"))
        assertEquals("/home/u/Library/Application Support/TTSRoad", dirFor("Mac OS X"))
    }

    @Test
    fun `the credential key is stable for a server plus user and different for anyone else`() {
        val key = CredentialStores.credentialKey("https://x/", "admin")

        assertEquals(key, CredentialStores.credentialKey("https://x/", "admin"))
        assertEquals(key, CredentialStores.credentialKey("https://x", "admin"), "trailing slash is not identity")
        assertFalse(key == CredentialStores.credentialKey("https://x/", "someone-else"))
        assertFalse(key == CredentialStores.credentialKey("https://y/", "admin"))
        // It identifies an entry; it must not be derived from anything secret.
        assertTrue(key.startsWith("ttsroad/"))
    }

    /**
     * The separator between the URL and the username is a NUL, which cannot occur in either
     * half — so no pair of (server, user) can collide by straddling it.
     *
     * Pinned as a test because the separator was originally a *raw* NUL byte in the source file,
     * which made git classify `CredentialStore.kt` as binary and show no diff for it. Rewriting it
     * as an escape is only safe if the derived key is unchanged, and this is what proves it: these
     * digests are of `"https://x" + NUL + "admin"`, computed independently of the implementation.
     */
    @Test
    fun `the credential key separator keeps neighbouring pairs apart`() {
        assertEquals(
            "ttsroad/" + sha256Hex("https://x\u0000admin").take(32),
            CredentialStores.credentialKey("https://x/", "admin"),
        )
        // Without a separator both of these would hash "https://xadmin".
        assertFalse(
            CredentialStores.credentialKey("https://x", "admin") ==
                CredentialStores.credentialKey("https://xadmin", ""),
        )
        // A missing username is not the same as an empty one only if it hashes the same — it does,
        // deliberately, because `orEmpty()` is the documented behaviour.
        assertEquals(
            CredentialStores.credentialKey("https://x", null),
            CredentialStores.credentialKey("https://x", ""),
        )
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
