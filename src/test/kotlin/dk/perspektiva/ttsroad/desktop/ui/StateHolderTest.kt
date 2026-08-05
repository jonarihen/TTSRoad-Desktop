package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The state holders exist so this file can exist: the same load/submit logic used to live inside
 * `LaunchedEffect` blocks and could only be reached through the Compose runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateHolderTest {

    // --- LibraryStateHolder --------------------------------------------------------------

    @Test
    fun `the library loads once when the holder is created`() = runTest {
        val repository = FakeRepository(
            libraryResult = Result.success(LibraryResponse(fictions = listOf(FictionSummary(id = 7, title = "A")))),
        )

        val holder = LibraryStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        val state = holder.state.value
        assertIs<Load.Ok<LibraryResponse>>(state)
        assertEquals(1, state.value.fictions.size)
        assertEquals(1, repository.libraryCalls)
        holder.clear()
    }

    @Test
    fun `a library failure becomes an error state carrying the exception message`() = runTest {
        val repository = FakeRepository(libraryResult = Result.failure(IllegalStateException("no route to host")))

        val holder = LibraryStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        val state = holder.state.value
        assertIs<Load.Err>(state)
        assertEquals("no route to host", state.message)
        holder.clear()
    }

    @Test
    fun `a failure with no message falls back to a readable one`() = runTest {
        val repository = FakeRepository(libraryResult = Result.failure(RuntimeException()))

        val holder = LibraryStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals("Could not load library", assertIs<Load.Err>(holder.state.value).message)
        holder.clear()
    }

    @Test
    fun `refresh re-fetches`() = runTest {
        val repository = FakeRepository()
        val holder = LibraryStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        holder.refresh()
        runCurrent()

        assertEquals(2, repository.libraryCalls)
        holder.clear()
    }

    @Test
    fun `clearing the holder cancels its scope so a later refresh does nothing`() = runTest {
        val repository = FakeRepository()
        val holder = LibraryStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(1, repository.libraryCalls)

        holder.clear()
        holder.refresh()
        runCurrent()

        assertEquals(1, repository.libraryCalls)
    }

    // --- FictionDetailStateHolder --------------------------------------------------------

    @Test
    fun `the chapter list loads for the requested fiction`() = runTest {
        val response = ChaptersResponse(
            fiction = FictionSummary(id = 7, title = "A Test Serial"),
            total = 1,
            chapters = listOf(ChapterSummary(id = 101, title = "Chapter 1")),
        )
        val repository = FakeRepository(chaptersResult = Result.success(response))

        val holder = FictionDetailStateHolder(repository, fictionId = 7, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(1, assertIs<Load.Ok<ChaptersResponse>>(holder.state.value).value.chapters.size)
        assertNull(holder.actionError.value)
        holder.clear()
    }

    @Test
    fun `marking a chapter played sends the mark and then refetches the list`() = runTest {
        val repository = FakeRepository()
        val holder = FictionDetailStateHolder(repository, fictionId = 7, UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(1, repository.chaptersCalls)

        holder.setPlayed(chapterId = 101, played = true)
        runCurrent()

        assertEquals(listOf(listOf(101) to true), repository.markedPlayed)
        // The server stays the authority on what actually changed, so we refetch rather than patch.
        assertEquals(2, repository.chaptersCalls)
        assertNull(holder.actionError.value)
        holder.clear()
    }

    @Test
    fun `a failed mark surfaces an action error and leaves the loaded list alone`() = runTest {
        val repository = object : FakeRepository() {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean) =
                throw IllegalStateException("server said no")
        }
        val holder = FictionDetailStateHolder(repository, fictionId = 7, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        holder.setPlayed(chapterId = 101, played = true)
        runCurrent()

        assertEquals("server said no", holder.actionError.value)
        assertIs<Load.Ok<ChaptersResponse>>(holder.state.value)
        holder.clear()
    }

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
