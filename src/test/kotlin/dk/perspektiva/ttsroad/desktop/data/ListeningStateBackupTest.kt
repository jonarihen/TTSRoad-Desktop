package dk.perspektiva.ttsroad.desktop.data

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ListeningStateBackupTest {

    @Test
    fun `the filename is dated, because the point is keeping more than one`() {
        assertEquals(
            "ttsroad-listening-2026-09-04.json",
            listeningBackupFileName(LocalDate.of(2026, 9, 4)),
        )
    }

    @Test
    fun `the merge explanation says progress cannot be lost`() {
        // Without this, "import" reads as "overwrite" and the people it reassures never press it.
        assertTrue(ImportMergeExplanation.contains("only moves forward"))
        assertTrue(ImportMergeExplanation.contains("cannot undo"))
    }

    @Test
    fun `skipped-as-older is explained, not just counted`() {
        val lines = listeningImportLines(
            ListeningStateReport(fictionsMatched = 3, playbackSkippedOlder = 7),
        )

        val line = lines.single()
        assertTrue(line.contains("7 positions left alone"))
        assertTrue(
            line.contains("already further ahead"),
            "a restore where every position was ahead looks broken; this is the only explanation",
        )
    }

    @Test
    fun `only non-zero counts are listed`() {
        val lines = listeningImportLines(
            ListeningStateReport(
                fictionsMatched = 2,
                playbackRestored = 4,
                bookmarksRestored = 1,
            ),
        )

        assertEquals(2, lines.size, "a report of eight zeroes is a wall nobody reads")
        assertTrue(lines.any { it == "4 positions restored" })
        assertTrue(lines.any { it == "One bookmark restored" })
    }

    @Test
    fun `missing fictions are named rather than counted`() {
        val lines = listeningImportLines(
            ListeningStateReport(fictionsMatched = 1, fictionsMissing = listOf("Worth the Candle", "Katalepsis")),
        )

        assertEquals(
            "Not on this server: Worth the Candle, Katalepsis",
            lines.single(),
            "on a different server this is normal, and which books did not come across is the point",
        )
    }

    @Test
    fun `a no-op import still says something`() {
        val matched = listeningImportLines(ListeningStateReport(fictionsMatched = 4))

        assertEquals(
            "Nothing to change — this account was already up to date with that backup.",
            matched.single(),
            "silence after pressing a button looks like a failure",
        )
    }

    @Test
    fun `a document matching nothing says so distinctly`() {
        val nothing = listeningImportLines(ListeningStateReport())

        assertEquals("Nothing in that backup matched anything on this server.", nothing.single())
    }

    @Test
    fun `a bookmark limit is reported with its reason`() {
        val lines = listeningImportLines(
            ListeningStateReport(fictionsMatched = 1, bookmarksSkippedFull = 3),
        )

        assertTrue(lines.single().contains("3 bookmarks dropped"))
        assertTrue(lines.single().contains("at its limit"))
    }

    @Test
    fun `singular and plural both read`() {
        assertTrue(
            listeningImportLines(ListeningStateReport(playbackRestored = 1)).single() ==
                "One position restored",
        )
        assertTrue(
            listeningImportLines(ListeningStateReport(chaptersMissing = 2)).single() ==
                "2 chapters not found on this server",
        )
    }
}
