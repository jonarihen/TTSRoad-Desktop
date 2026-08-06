package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The state holders exist so this file can exist: the same submit logic used to live inside
 * `LaunchedEffect` blocks and could only be reached through the Compose runtime.
 *
 * Library and chapter loading moved out of a per-screen holder and into
 * [dk.perspektiva.ttsroad.desktop.data.LibraryCache] in the navigation phase; those tests live in
 * `LibraryCacheTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateHolderTest {

    // --- LoginStateHolder ----------------------------------------------------------------

    @Test
    fun `a successful login leaves no error and stops the busy spinner`() = runTest {
        val repository = FakeRepository(loginResult = LoginResult.Success)
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()

        assertFalse(holder.state.value.busy)
        assertNull(holder.state.value.error)
        assertFalse(holder.state.value.twoFactor)
        assertEquals(1, repository.loginCalls)
        holder.clear()
    }

    @Test
    fun `the first TotpRequired reveals the code field without showing an error`() = runTest {
        val repository = FakeRepository(loginResult = LoginResult.TotpRequired)
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()

        assertTrue(holder.state.value.twoFactor)
        assertNull(holder.state.value.error)
        // No code was entered yet, so none was sent.
        assertNull(repository.lastLoginTotp)
        holder.clear()
    }

    @Test
    fun `a second TotpRequired after a code was entered is reported as an invalid code`() = runTest {
        val repository = FakeRepository(loginResult = LoginResult.TotpRequired)
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()
        holder.submit("https://x", "admin", "hunter2", "000000")
        runCurrent()

        assertTrue(holder.state.value.twoFactor)
        assertEquals("Invalid authentication code", holder.state.value.error)
        assertEquals("000000", repository.lastLoginTotp)
        holder.clear()
    }

    @Test
    fun `a failure message is shown verbatim`() = runTest {
        val repository = FakeRepository(loginResult = LoginResult.Failure("Too many failed attempts"))
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()

        assertEquals("Too many failed attempts", holder.state.value.error)
        assertFalse(holder.state.value.busy)
        holder.clear()
    }

    @Test
    fun `a rate limit is surfaced with its wait and blocks further attempts`() = runTest {
        val repository = FakeRepository(
            loginResult = LoginResult.RateLimited("Too many failed attempts — try again in 15 minutes", 900),
        )
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()

        assertEquals("Too many failed attempts — try again in 15 minutes", holder.state.value.error)
        // The button reads this: leaving it enabled lets the user extend their own lockout.
        assertEquals(900, holder.state.value.retryAfterSeconds)
        assertEquals(1, repository.loginCalls)
        // …and while it is set, a retry is refused rather than sent.
        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()
        assertEquals(1, repository.loginCalls, "a locked-out form must not send another attempt")
        holder.clear()
    }

    @Test
    fun `the rate limit clears itself once the server's wait has passed`() = runTest {
        // Without this the form is disabled by a field only submit() clears, and submit() is the
        // control that field disables — a 429 would lock the user out until they restart the app.
        val repository = FakeRepository(loginResult = LoginResult.RateLimited("Too many failed attempts", 30))
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()
        assertEquals(30, holder.state.value.retryAfterSeconds)

        advanceTimeBy(31_000)
        runCurrent()

        assertNull(holder.state.value.retryAfterSeconds, "the form must become usable again on its own")
        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()
        assertEquals(2, repository.loginCalls)
        holder.clear()
    }

    @Test
    fun `a discovered server is shown before any credential is sent`() = runTest {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(serverName = "Perspektiva TTSRoad", serverVersion = "1.4.0"),
        )
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler), probeDebounceMillis = 600)

        holder.serverUrlChanged("https://ttsroad.example.com")
        advanceTimeBy(700)
        runCurrent()

        assertEquals("1.4.0", holder.state.value.discovered?.serverVersion)
        assertEquals(0, repository.loginCalls, "discovery must never need a password")
        holder.clear()
    }

    @Test
    fun `typing does not probe on every keystroke`() = runTest {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(serverName = "X", serverVersion = "1.4.0"),
        )
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler), probeDebounceMillis = 600)

        "https://ttsroad.example.com".forEachIndexed { index, _ ->
            holder.serverUrlChanged("https://ttsroad.example.com".take(index + 1))
            advanceTimeBy(50)
        }
        advanceTimeBy(700)
        runCurrent()

        assertEquals(listOf("https://ttsroad.example.com"), repository.capabilityProbes)
        holder.clear()
    }

    @Test
    fun `a server that does not answer discovery shows nothing rather than an error`() = runTest {
        // Baseline is what both "too old for the endpoint" and "unreachable" resolve to.
        val repository = FakeRepository(capabilitiesResult = ServerCapabilities.Baseline)
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler), probeDebounceMillis = 1)

        holder.serverUrlChanged("https://old.example.com")
        advanceTimeBy(50)
        runCurrent()

        assertNull(holder.state.value.discovered)
        assertNull(holder.state.value.error, "a half-typed address is not a failure")
        holder.clear()
    }

    @Test
    fun `clearing the server field forgets the previously discovered server`() = runTest {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(serverName = "X", serverVersion = "1.4.0"),
        )
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler), probeDebounceMillis = 1)
        holder.serverUrlChanged("https://x")
        advanceTimeBy(50)
        runCurrent()
        assertNotNull(holder.state.value.discovered)

        holder.serverUrlChanged("")
        runCurrent()

        assertNull(holder.state.value.discovered, "stale identification of a server you are no longer typing")
        holder.clear()
    }

    @Test
    fun `staying in 2FA mode after a failure keeps the code field visible`() = runTest {
        val repository = FakeRepository(loginResult = LoginResult.TotpRequired)
        val holder = LoginStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.submit("https://x", "admin", "hunter2", "")
        runCurrent()

        repository.loginResult = LoginResult.Failure("Invalid username or password")
        holder.submit("https://x", "admin", "hunter2", "000000")
        runCurrent()

        assertTrue(holder.state.value.twoFactor)
        assertEquals("Invalid username or password", holder.state.value.error)
        holder.clear()
    }
}
