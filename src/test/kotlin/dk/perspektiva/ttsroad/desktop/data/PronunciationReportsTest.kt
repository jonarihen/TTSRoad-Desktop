package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PronunciationReportsTest {

    @Test
    fun `a note with no word is still a valid report`() {
        assertNull(
            pronunciationReportProblem(word = "", note = "The narrator garbles the surname."),
            "'the third paragraph sounds wrong' says something even without a single word",
        )
        assertNull(pronunciationReportProblem(word = "Erin", note = ""))
    }

    @Test
    fun `neither is refused`() {
        val problem = pronunciationReportProblem(word = "  ", note = "")

        assertNotNull(problem)
        assertTrue(problem.contains("word that sounded wrong"))
    }

    @Test
    fun `the server's ceilings are checked before sending`() {
        val longWord = "a".repeat(MaxReportedWordChars + 1)
        val longNote = "a".repeat(MaxReportNoteChars + 1)

        assertTrue(pronunciationReportProblem(longWord, "")!!.contains("200"))
        assertTrue(pronunciationReportProblem("Erin", longNote)!!.contains("2000"))
        assertNull(pronunciationReportProblem("a".repeat(MaxReportedWordChars), ""))
    }

    @Test
    fun `the chapter is what identifies the report`() {
        // The server derives the fiction from the chapter rather than trusting a client, which is
        // why the request type has no fiction field for a caller to get wrong.
        val request = pronunciationReportRequest(chapterId = 42, positionMs = 61_000, word = "Erin", note = "")

        assertNotNull(request)
        assertEquals(42, request.chapterId)
        assertEquals("Erin", request.word)
    }

    @Test
    fun `position is converted to seconds`() {
        val request = pronunciationReportRequest(chapterId = 1, positionMs = 61_500, word = "x", note = "")

        assertEquals(61.5, request!!.positionSeconds)
    }

    @Test
    fun `a negative position is floored rather than sent`() {
        val request = pronunciationReportRequest(chapterId = 1, positionMs = -5_000, word = "x", note = "")

        assertEquals(0.0, request!!.positionSeconds)
    }

    @Test
    fun `blank fields are sent as absent, not as empty strings`() {
        val request = pronunciationReportRequest(chapterId = 1, positionMs = 0, word = "  Erin  ", note = "   ")

        assertEquals("Erin", request!!.word)
        assertNull(request.note, "an empty string would store a blank note rather than none")
    }

    @Test
    fun `an invalid draft produces no request at all`() {
        assertNull(pronunciationReportRequest(chapterId = 1, positionMs = 0, word = "", note = ""))
    }

    @Test
    fun `a position of zero is not shown as the very start`() {
        assertNull(formatReportPosition(0.0), "an unset position is not a timestamp")
        assertNull(formatReportPosition(-1.0))
        assertEquals("1:01", formatReportPosition(61.0))
        assertEquals("41:07", formatReportPosition(2467.0))
        assertEquals("1:00:00", formatReportPosition(3600.0))
        assertEquals("2:03:04", formatReportPosition(7384.0))
    }

    @Test
    fun `the location prefers the chapter title over its number`() {
        val titled = PronunciationReport(chapterTitle = "The Inn", chapterNumber = 12, positionSeconds = 61.0)
        val numbered = PronunciationReport(chapterNumber = 12, positionSeconds = 61.0)

        assertEquals("The Inn  ·  1:01", pronunciationReportLocation(titled))
        assertEquals("Chapter 12  ·  1:01", pronunciationReportLocation(numbered))
    }

    @Test
    fun `a report with nothing to place still reads`() {
        assertEquals("Unknown position", pronunciationReportLocation(PronunciationReport()))
    }
}
