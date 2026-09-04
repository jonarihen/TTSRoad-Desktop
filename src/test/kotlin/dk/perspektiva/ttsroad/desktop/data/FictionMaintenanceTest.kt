package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FictionMaintenanceTest {

    @Test
    fun `poll is offered to a non-admin and the rest are not`() {
        val capable = ServerCapabilities(fictionMaintenance = true)

        assertEquals(
            listOf(FictionMaintenanceAction.Poll),
            fictionMaintenanceActions(capable, isAdmin = false),
            "the server leaves polling open on purpose; the other four it does not",
        )
        assertEquals(
            FictionMaintenanceAction.entries,
            fictionMaintenanceActions(capable, isAdmin = true),
        )
        assertTrue(fictionMaintenanceActions(ServerCapabilities.Baseline, isAdmin = true).isEmpty())
    }

    @Test
    fun `only re-narration asks a question`() {
        val confirming = FictionMaintenanceAction.entries.filter { it.confirms }

        assertEquals(
            listOf(FictionMaintenanceAction.ReconvertAll),
            confirming,
            "a prompt before each cheap action is what teaches people to click through the real one",
        )
    }

    @Test
    fun `the re-narration question names the number`() {
        val many = reconvertConfirmation(doneChapters = 412)

        assertTrue(many.contains("412 converted chapters"))
        assertTrue(many.contains("412 conversions"))
        assertTrue(many.contains("existing audio is gone"))
        assertTrue(reconvertConfirmation(1).contains("The one converted chapter"))
        assertTrue(reconvertConfirmation(0).contains("Nothing is converted yet"))
    }

    @Test
    fun `a poll reports which branch it took`() {
        assertEquals(
            "Re-read the whole chapter list from the source.",
            message(FictionMaintenanceAction.Poll, MaintenanceResponse(fullIngest = true)),
        )
        assertEquals(
            "Checked the source — re-read the last 25 chapters.",
            message(FictionMaintenanceAction.Poll, MaintenanceResponse(partialSync = 25)),
        )
        assertEquals(
            "Checked the source for new chapters.",
            message(FictionMaintenanceAction.Poll, MaintenanceResponse()),
        )
    }

    @Test
    fun `a count of zero is reported rather than hidden`() {
        // "status: ok" is the same answer for four hundred files and none. Silence would be
        // indistinguishable from a control that did nothing.
        assertEquals(
            "No files needed rewriting.",
            message(FictionMaintenanceAction.Retag, MaintenanceResponse(fileCount = 0)),
        )
        assertEquals(
            "No failed chapters to retry.",
            message(FictionMaintenanceAction.RetryFailed, MaintenanceResponse(resetCount = 0)),
        )
        assertEquals(
            "The filter excluded nothing new.",
            message(FictionMaintenanceAction.ApplyFilter, MaintenanceResponse(excludedCount = 0)),
        )
    }

    @Test
    fun `counts are singular and plural`() {
        assertEquals(
            "Rewrote the tags on one file.",
            message(FictionMaintenanceAction.Retag, MaintenanceResponse(fileCount = 1)),
        )
        assertEquals(
            "Rewrote the tags on 12 files.",
            message(FictionMaintenanceAction.Retag, MaintenanceResponse(fileCount = 12)),
        )
        assertEquals(
            "Queued one failed chapter again.",
            message(FictionMaintenanceAction.RetryFailed, MaintenanceResponse(resetCount = 1)),
        )
    }

    @Test
    fun `no filter configured is reported as itself, not as excluding nothing`() {
        val response = MaintenanceResponse(excludedCount = 0, detail = "No chapter filter is set.")

        assertEquals(
            "No chapter filter is set.",
            message(FictionMaintenanceAction.ApplyFilter, response),
            "there being no rule to run is a different answer from the rule matching nothing",
        )
    }

    @Test
    fun `re-narration warns that it will take a while`() {
        val message = message(FictionMaintenanceAction.ReconvertAll, MaintenanceResponse(resetCount = 400))

        assertTrue(message.contains("400 chapters"))
        assertTrue(message.contains("take a while"))
    }

    @Test
    fun `every action carries its own sentence`() {
        FictionMaintenanceAction.entries.forEach { action ->
            assertTrue(action.subtitle.isNotBlank(), "${action.name} needs a subtitle to be a row")
            assertTrue(action.title.isNotBlank())
        }
    }

    @Test
    fun `retag says it does not re-narrate`() {
        // The distinction that matters: it rewrites files, it does not spend TTS.
        assertTrue(FictionMaintenanceAction.Retag.subtitle.contains("No re-narration"))
        assertFalse(FictionMaintenanceAction.Retag.confirms)
    }

    @Test
    fun `the filter row says it never un-excludes`() {
        assertTrue(FictionMaintenanceAction.ApplyFilter.subtitle.contains("Never un-excludes"))
    }

    private fun message(action: FictionMaintenanceAction, response: MaintenanceResponse) =
        fictionMaintenanceMessage(action, response)
}
