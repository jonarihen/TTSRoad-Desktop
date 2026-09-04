package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChapterMaintenanceTest {

    @Test
    fun `only a failed chapter is worth retrying`() {
        assertTrue(ChapterSummary(status = "error").canRetry())
        assertTrue(
            ChapterSummary(errorMessage = "edge-tts timed out").canRetry(),
            "an error message is a failure even when the status has not caught up",
        )
        assertFalse(
            ChapterSummary(status = "processing").canRetry(),
            "a converting chapter is already doing what retry asks; the server answers 409",
        )
        assertFalse(ChapterSummary(status = "done", audio = AudioInfo(url = "https://x/1.mp3")).canRetry())
        assertFalse(ChapterSummary(status = "pending").canRetry())
    }

    @Test
    fun `an excluded chapter is not offered a retry even when it also failed`() {
        val excluded = ChapterSummary(status = "error", errorMessage = "boom", excluded = true)

        assertEquals(ChapterAvailability.Excluded, excluded.availability())
        assertFalse(
            excluded.canRetry(),
            "the server answers 409 for an excluded chapter; the way back is to un-exclude it",
        )
    }

    @Test
    fun `a 409 says which of the two things it is`() {
        val message = chapterRetryMessage(ChapterRetryOutcome.AlreadyRunning, "Chapter 12")

        // "Could not retry" would point at neither, and the two have different next actions: one is
        // waiting, the other is undoing an exclusion.
        assertTrue(message!!.contains("already being converted"))
        assertTrue(message.contains("excluded"))
        assertFalse(message.contains("failed"))
    }

    @Test
    fun `a queued retry names the chapter`() {
        assertEquals(
            "Chapter 12 is queued for conversion again.",
            chapterRetryMessage(ChapterRetryOutcome.Queued(5), "Chapter 12"),
        )
    }

    @Test
    fun `an unsupported server says so rather than reporting a failure`() {
        assertEquals(
            "This server cannot retry a chapter.",
            chapterRetryMessage(ChapterRetryOutcome.Unsupported, "Chapter 12"),
        )
    }

    @Test
    fun `exclusion is described as affecting everyone, both ways`() {
        val off = chapterExcludeMessage("Chapter 3", excluded = true)
        val on = chapterExcludeMessage("Chapter 3", excluded = false)

        assertTrue(off.contains("every account"))
        assertTrue(on.contains("every account"))
        assertTrue(off.contains("excluded"))
    }

    @Test
    fun `the delete confirmation names what everybody loses`() {
        val body = chapterDeleteConfirmation("Chapter 3")

        assertTrue(body.contains("Every account"))
        assertTrue(body.contains("podcast feed"))
        assertTrue(body.contains("progress"))
        assertTrue(body.contains("cannot be undone"))
    }

    @Test
    fun `the exclude confirmation says the audio is kept and retry stops working`() {
        val excluding = chapterExcludeConfirmation("Chapter 3", excluding = true)

        assertTrue(excluding.contains("audio is kept"))
        assertTrue(
            excluding.contains("cannot be retried"),
            "the server refuses a retry on an excluded chapter, so the two controls interact",
        )
        assertTrue(chapterExcludeConfirmation("Chapter 3", excluding = false).contains("back on"))
    }

    @Test
    fun `the destructive pair needs the capability and the admin flag`() {
        val capable = ServerCapabilities(chapterMaintenance = true)

        assertTrue(canMaintainChapters(capable, isAdmin = true))
        assertFalse(canMaintainChapters(capable, isAdmin = false))
        assertFalse(canMaintainChapters(ServerCapabilities.Baseline, isAdmin = true))
    }
}
