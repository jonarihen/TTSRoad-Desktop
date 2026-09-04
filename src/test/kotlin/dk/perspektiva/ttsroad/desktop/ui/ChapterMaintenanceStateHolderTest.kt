package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChapterRetryOutcome
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterMaintenanceStateHolderTest {

    private val chapter = ChapterSummary(
        id = 42,
        fictionId = 7,
        title = "Chapter 12",
        status = "error",
        errorMessage = "edge-tts timed out",
    )

    @Test
    fun `a queued retry reports success and re-reads the list`() = runTest {
        val fixture = fixture()
        fixture.repository.retryChapterResult = Result.success(ChapterRetryOutcome.Queued(42))

        fixture.holder.retry(chapter)
        runCurrent()

        assertEquals(listOf(42), fixture.repository.retriedChapters)
        assertEquals("Chapter 12 is queued for conversion again.", fixture.holder.state.value.notice)
        assertNull(fixture.holder.state.value.error)
        assertNull(fixture.holder.state.value.busyChapterId)

        fixture.close()
    }

    @Test
    fun `a 409 is a notice, not an error`() = runTest {
        val fixture = fixture()
        fixture.repository.retryChapterResult = Result.success(ChapterRetryOutcome.AlreadyRunning)

        fixture.holder.retry(chapter)
        runCurrent()

        val notice = fixture.holder.state.value.notice
        assertNotNull(notice)
        assertTrue(notice.contains("already being converted"))
        assertNull(
            fixture.holder.state.value.error,
            "the chapter is either converting or excluded; neither is a thing that failed",
        )

        fixture.close()
    }

    @Test
    fun `a thrown failure becomes an error rather than a notice`() = runTest {
        val fixture = fixture()
        fixture.repository.retryChapterResult = Result.failure(IllegalStateException("boom"))

        fixture.holder.retry(chapter)
        runCurrent()

        assertNotNull(fixture.holder.state.value.error)
        assertNull(fixture.holder.state.value.notice)
        assertNull(fixture.holder.state.value.busyChapterId)

        fixture.close()
    }

    @Test
    fun `excluding reports what the server confirmed, not what was asked`() = runTest {
        val fixture = fixture()
        // The server is the authority on what it stored, so a disagreement is reported as the
        // server's answer rather than as the request's intent.
        fixture.repository.setChapterExcludedResult = Result.success(false)

        fixture.holder.setExcluded(chapter, excluded = true)
        runCurrent()

        assertEquals(listOf(42 to true), fixture.repository.excludedChapters)
        assertEquals(
            "Chapter 12 is back on every account's feed and player.",
            fixture.holder.state.value.notice,
        )

        fixture.close()
    }

    @Test
    fun `a 404 on exclude says the server cannot rather than claiming it worked`() = runTest {
        val fixture = fixture()
        fixture.repository.setChapterExcludedResult = Result.success(null)

        fixture.holder.setExcluded(chapter, excluded = true)
        runCurrent()

        assertEquals("This server cannot exclude a chapter.", fixture.holder.state.value.notice)

        fixture.close()
    }

    @Test
    fun `a 404 on delete is not reported as a deletion`() = runTest {
        val fixture = fixture()
        fixture.repository.deleteChapterResult = Result.success(null)

        fixture.holder.delete(chapter)
        runCurrent()

        assertEquals(listOf(42), fixture.repository.deletedChapters)
        assertEquals(
            "This server cannot delete a chapter.",
            fixture.holder.state.value.notice,
            "a 404 is ambiguous between no-such-endpoint and already-gone; neither is a deletion",
        )

        fixture.close()
    }

    @Test
    fun `a successful delete says so`() = runTest {
        val fixture = fixture()
        fixture.repository.deleteChapterResult = Result.success(true)

        fixture.holder.delete(chapter)
        runCurrent()

        assertEquals("Chapter 12 is deleted.", fixture.holder.state.value.notice)

        fixture.close()
    }

    /**
     * Deliberately on a [StandardTestDispatcher] rather than the unconfined one the rest use.
     *
     * Unconfined runs a launched body eagerly to its first suspension, and the fake never suspends,
     * so the whole request finishes inside the call to `retry` and there is no in-flight window to
     * press twice inside. A queuing dispatcher is what reproduces the real one: the body waits, and
     * the second press arrives while the first has only been *started*.
     *
     * That gap is the bug this pins. Marking busy inside the coroutine — the obvious place — leaves
     * the guard reading state nothing has written yet, and both presses go through.
     */
    @Test
    fun `a second press while one is in flight is refused`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = fixture(dispatcher)
        fixture.repository.retryChapterResult = Result.success(ChapterRetryOutcome.Queued(42))

        fixture.holder.retry(chapter)
        assertEquals(
            42,
            fixture.holder.state.value.busyChapterId,
            "busy has to be visible before the body runs, or the guard has nothing to read",
        )

        fixture.holder.retry(chapter.copy(id = 43))
        runCurrent()

        assertEquals(
            listOf(42),
            fixture.repository.retriedChapters,
            "a second press while one is in flight would race two writes against one shared row",
        )

        fixture.close()
    }

    @Test
    fun `dismissing clears both the notice and the error`() = runTest {
        val fixture = fixture()
        fixture.repository.retryChapterResult = Result.failure(IllegalStateException("boom"))
        fixture.holder.retry(chapter)
        runCurrent()

        fixture.holder.dismissNotice()

        assertNull(fixture.holder.state.value.error)
        assertNull(fixture.holder.state.value.notice)

        fixture.close()
    }

    // --- fixture ----------------------------------------------------------------------------------

    private class Fixture(
        val repository: FakeRepository,
        val cache: LibraryCache,
        val holder: ChapterMaintenanceStateHolder,
    ) {
        fun close() {
            holder.clear()
            cache.close()
        }
    }

    private fun TestScope.fixture(
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): Fixture {
        val repository = FakeRepository()
        val cache = LibraryCache(repository, dispatcher)
        return Fixture(repository, cache, ChapterMaintenanceStateHolder(repository, cache, dispatcher))
    }
}
