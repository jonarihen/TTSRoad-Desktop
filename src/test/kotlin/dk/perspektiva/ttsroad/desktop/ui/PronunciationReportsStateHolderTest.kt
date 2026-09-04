package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.PronunciationReport
import dk.perspektiva.ttsroad.desktop.data.ReportOutcome
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PronunciationReportsStateHolderTest {

    private val stored = PronunciationReport(id = 9, chapterId = 42, word = "Erin")

    @Test
    fun `the position is frozen when the form opens, not when it is sent`() = runTest {
        val repository = FakeRepository(
            createPronunciationReportResult = Result.success(ReportOutcome.Filed(stored)),
        )
        val holder = holder(repository)

        holder.open(chapterId = 42, chapterTitle = "Chapter 12", positionMs = 61_000)
        // Typing takes time; the useful timestamp is where the word actually was.
        holder.setWord("Erin")
        holder.setNote("Should rhyme with 'Karen'.")
        holder.send()
        runCurrent()

        val sent = repository.filedReports.single()
        assertEquals(61.0, sent.positionSeconds)
        assertEquals(42, sent.chapterId)
        assertEquals("Erin", sent.word)

        holder.clear()
    }

    @Test
    fun `a filed report closes the form and joins the list without a second request`() = runTest {
        val repository = FakeRepository(
            createPronunciationReportResult = Result.success(ReportOutcome.Filed(stored)),
        )
        val holder = holder(repository)
        holder.open(42, "Chapter 12", 0)
        holder.setWord("Erin")

        holder.send()
        runCurrent()

        assertNull(holder.state.value.draft)
        assertEquals(listOf(9), holder.state.value.reports.map { it.id })
        assertNotNull(holder.state.value.notice)
        assertFalse(holder.state.value.busy)

        holder.clear()
    }

    @Test
    fun `a full queue shows the server's own sentence`() = runTest {
        val message = "You already have 500 open pronunciation reports. Resolve or delete some before adding more."
        val repository = FakeRepository(
            createPronunciationReportResult = Result.success(ReportOutcome.AtCapacity(message)),
        )
        val holder = holder(repository)
        holder.open(42, "Chapter 12", 0)
        holder.setWord("Erin")

        holder.send()
        runCurrent()

        assertEquals(
            message,
            holder.state.value.error,
            "the number in it is the actionable part and can differ per deployment",
        )
        assertNotNull(holder.state.value.draft, "the typing is kept so it can be retried after clearing some")

        holder.clear()
    }

    @Test
    fun `an empty draft sends nothing`() = runTest {
        val repository = FakeRepository()
        val holder = holder(repository)
        holder.open(42, "Chapter 12", 0)

        holder.send()
        runCurrent()

        assertTrue(repository.filedReports.isEmpty())
        assertFalse(holder.state.value.draft!!.canSend)

        holder.clear()
    }

    @Test
    fun `a chapter that is not loaded cannot open the form`() = runTest {
        val holder = holder(FakeRepository())

        holder.open(chapterId = 0, chapterTitle = "", positionMs = 0)

        assertNull(holder.state.value.draft)

        holder.clear()
    }

    @Test
    fun `deleting removes the row only when the server confirmed it`() = runTest {
        val repository = FakeRepository(
            pronunciationReportsResult = Result.success(listOf(stored)),
            deletePronunciationReportResult = Result.success(true),
        )
        val holder = holder(repository)
        holder.ensureLoaded()
        runCurrent()

        holder.delete(stored)
        runCurrent()

        assertEquals(listOf(9), repository.deletedReports)
        assertTrue(holder.state.value.reports.isEmpty())

        holder.clear()
    }

    @Test
    fun `a 404 on delete keeps the row rather than guessing`() = runTest {
        val repository = FakeRepository(
            pronunciationReportsResult = Result.success(listOf(stored)),
            deletePronunciationReportResult = Result.success(false),
        )
        val holder = holder(repository)
        holder.ensureLoaded()
        runCurrent()

        holder.delete(stored)
        runCurrent()

        assertEquals(
            1,
            holder.state.value.reports.size,
            "a 404 is ambiguous between no-such-endpoint and already-gone",
        )
        assertNotNull(holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `an unsupported server says so rather than failing silently`() = runTest {
        val repository = FakeRepository(
            createPronunciationReportResult = Result.success(ReportOutcome.Unsupported),
        )
        val holder = holder(repository)
        holder.open(42, "Chapter 12", 0)
        holder.setWord("Erin")

        holder.send()
        runCurrent()

        assertEquals("This server does not take pronunciation reports.", holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `the list loads once`() = runTest {
        val repository = FakeRepository(pronunciationReportsResult = Result.success(listOf(stored)))
        val holder = holder(repository)

        holder.ensureLoaded()
        runCurrent()
        holder.ensureLoaded()
        runCurrent()

        assertEquals(1, holder.state.value.reports.size)

        holder.clear()
    }

    private fun TestScope.holder(repository: FakeRepository) =
        PronunciationReportsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
}
