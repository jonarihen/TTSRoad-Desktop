package dk.perspektiva.ttsroad.desktop.data

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
    }

    @Test
    fun `a token without a server URL is not a session`() {
        assertFalse(SessionState(serverUrl = "", token = "t").isLoggedIn)
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
    fun `clearToken drops the credential but keeps the server identity`() {
        val store = InMemorySessionStore(
            SessionState(
                serverUrl = "https://x/",
                token = "t",
                username = "admin",
                isAdmin = true,
                serverName = "Perspektiva TTSRoad",
            ),
        )

        store.clearToken()

        val state = store.current()
        assertFalse(state.isLoggedIn)
        assertNull(state.token)
        assertNull(state.username)
        assertFalse(state.isAdmin)
        // Retained on purpose: the settings screen still shows which server this install talks to.
        assertEquals("https://x/", state.serverUrl)
        assertEquals("Perspektiva TTSRoad", state.serverName)
    }

    @Test
    fun `file store round-trips a session through disk`(@TempDir dir: File) {
        val file = File(dir, "nested/session.json")
        val store = FileSessionStore(file)

        store.save(
            SessionState(
                serverUrl = "https://ttsroad.example.com/",
                token = "ttsr_abc",
                username = "admin",
                isAdmin = true,
                serverName = "Perspektiva TTSRoad",
            ),
        )

        assertTrue(file.isFile, "save() should create parent directories")
        val reloaded = FileSessionStore(file).current()
        assertEquals("https://ttsroad.example.com/", reloaded.serverUrl)
        assertEquals("ttsr_abc", reloaded.token)
        assertEquals("admin", reloaded.username)
        assertTrue(reloaded.isAdmin)
        assertEquals("Perspektiva TTSRoad", reloaded.serverName)
        assertTrue(reloaded.isLoggedIn)
    }

    @Test
    fun `file store starts signed out when the file does not exist`(@TempDir dir: File) {
        assertFalse(FileSessionStore(File(dir, "missing.json")).current().isLoggedIn)
    }

    @Test
    fun `a corrupt session file degrades to signed out instead of crashing at startup`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        file.writeText("{ this is not json")

        assertFalse(FileSessionStore(file).current().isLoggedIn)
    }

    @Test
    fun `clearToken is persisted, so a restart stays signed out`(@TempDir dir: File) {
        val file = File(dir, "session.json")
        val store = FileSessionStore(file)
        store.save(SessionState(serverUrl = "https://x/", token = "t", username = "admin"))

        store.clearToken()

        val reloaded = FileSessionStore(file).current()
        assertFalse(reloaded.isLoggedIn)
        assertEquals("https://x/", reloaded.serverUrl)
    }
}
