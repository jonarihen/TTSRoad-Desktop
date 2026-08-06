package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.BuildInfo
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionState
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The About pane's copyable diagnostics block.
 *
 * The assertion that matters is the negative one: this text exists to be pasted into a public bug
 * report, so a bearer token reaching it would be a credential leak with a one-click delivery
 * mechanism.
 */
class DiagnosticsTest {

    private val session = SessionState(
        serverUrl = "https://ttsroad.example.com/",
        token = "ttsr_Zm9vYmFyYmF6cXV1eA",
        username = "admin",
        isAdmin = true,
        serverName = "Perspektiva TTSRoad",
        serverVersion = "1.4.0",
        deviceId = 42,
    )

    @Test
    fun `diagnostics never carry the bearer token`() {
        val text = buildDiagnostics(
            session = session,
            capabilities = ServerCapabilities(serverVersion = "1.4.0", deviceManagement = true),
            credentialStoreName = "Windows Credential Manager",
            persistsCredentials = true,
        )

        assertFalse(text.contains("ttsr_Zm9vYmFyYmF6cXV1eA"), text)
        assertFalse(text.contains("Bearer", ignoreCase = true), text)
    }

    @Test
    fun `diagnostics name the things a bug report needs`() {
        val text = buildDiagnostics(
            session = session,
            capabilities = ServerCapabilities(serverVersion = "1.4.0", deviceManagement = true),
            credentialStoreName = "Windows Credential Manager",
            persistsCredentials = true,
        )

        assertTrue(text.contains(BuildInfo.VERSION), text)
        assertTrue(text.contains("https://ttsroad.example.com/"), text)
        assertTrue(text.contains("Windows Credential Manager"), text)
        assertTrue(text.contains("Device management"), text)
        assertTrue(text.contains("Session id: 42"), text)
    }

    @Test
    fun `a session-only credential store says so`() {
        val text = buildDiagnostics(
            session = session,
            capabilities = ServerCapabilities.Baseline,
            credentialStoreName = "In memory",
            persistsCredentials = false,
        )

        assertTrue(text.contains("In memory (session only)"), text)
        assertTrue(text.contains("Not advertised by this server"), text)
    }
}
