package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Local listening history: what "last heard" picks, how the list is bounded, and what a dismissal
 * actually applies to.
 */
class PlaybackHistoryTest {

    private fun snapshot(
        fictionId: Int = 1,
        chapterId: Int = 1,
        positionSeconds: Double = 60.0,
        durationSeconds: Double = 600.0,
        recordedAtMs: Long = 1_000L,
        dismissed: Boolean = false,
    ) = PlaybackSnapshot(
        fictionId = fictionId,
        chapterId = chapterId,
        fictionTitle = "Fiction $fictionId",
        chapterTitle = "Chapter $chapterId",
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        recordedAtMs = recordedAtMs,
        dismissed = dismissed,
    )

    // --- Thinning -------------------------------------------------------------------------------

    @Test
    fun `listening to one chapter leaves one record, at the furthest point reached`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(positionSeconds = 10.0, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(positionSeconds = 300.0, recordedAtMs = 2))

        assertEquals(1, history.size)
        assertEquals(300.0, history.single().positionSeconds)
    }

    @Test
    fun `the list is capped and drops the oldest`() {
        var history = emptyList<PlaybackSnapshot>()
        repeat(PlaybackHistory.MaxEntries + 20) { index ->
            history = PlaybackHistory.record(
                history,
                snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()),
            )
        }

        assertEquals(PlaybackHistory.MaxEntries, history.size)
        // Newest first, and the oldest are the ones gone.
        assertEquals((PlaybackHistory.MaxEntries + 19).toLong(), history.first().recordedAtMs)
        assertTrue(history.none { it.recordedAtMs < 20 })
    }

    @Test
    fun `records are newest first`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 100))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 50))
        assertEquals(listOf(1, 2), history.map { it.chapterId })
    }

    // --- Dismissal ------------------------------------------------------------------------------

    @Test
    fun `a dismissal survives the next progress save for the same chapter`() {
        // Without this the 10-second progress tick would undo a dismissal the moment it was made.
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 7, recordedAtMs = 1))
        history = PlaybackHistory.dismiss(history, "1:7")
        history = PlaybackHistory.record(history, snapshot(chapterId = 7, positionSeconds = 120.0, recordedAtMs = 2))

        assertTrue(history.single().dismissed)
        assertNull(PlaybackHistory.lastHeard(history))
    }

    @Test
    fun `dismissal applies to the snapshot, so a different chapter still appears`() {
        // The requirement, stated as a test: this is not "hide for today".
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 7, recordedAtMs = 1))
        history = PlaybackHistory.dismiss(history, "1:7")
        history = PlaybackHistory.record(history, snapshot(chapterId = 8, recordedAtMs = 2))

        val lastHeard = PlaybackHistory.lastHeard(history)
        assertEquals(8, lastHeard?.chapterId)
    }

    @Test
    fun `dismissing one chapter leaves the others alone`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 2))
        history = PlaybackHistory.dismiss(history, "1:1")

        assertTrue(history.first { it.chapterId == 1 }.dismissed)
        assertFalse(history.first { it.chapterId == 2 }.dismissed)
    }

    // --- Last heard and jump-back ---------------------------------------------------------------

    @Test
    fun `last heard is the most recent resumable snapshot`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 10))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 20))
        assertEquals(2, PlaybackHistory.lastHeard(history)?.chapterId)
    }

    @Test
    fun `a finished chapter is not offered as somewhere to continue`() {
        // At 96% the controller has already marked it played, so "continue" would mean "replay the
        // last few seconds and auto-advance".
        val finished = snapshot(positionSeconds = 599.0, durationSeconds = 600.0)
        assertNull(PlaybackHistory.lastHeard(listOf(finished)))
    }

    @Test
    fun `a chapter with an unknown duration is still offered`() {
        // Progress reads as zero rather than as finished, which is the safe way round.
        val unknown = snapshot(positionSeconds = 30.0, durationSeconds = 0.0)
        assertEquals(unknown.chapterId, PlaybackHistory.lastHeard(listOf(unknown))?.chapterId)
    }

    @Test
    fun `last heard is null when everything is dismissed`() {
        val history = listOf(snapshot(dismissed = true))
        assertNull(PlaybackHistory.lastHeard(history))
    }

    @Test
    fun `jump-back offers one entry per fiction, newest first`() {
        var history = emptyList<PlaybackSnapshot>()
        // Two chapters of one serial, then one of another. The serial must appear once.
        history = PlaybackHistory.record(history, snapshot(fictionId = 1, chapterId = 1, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(fictionId = 1, chapterId = 2, recordedAtMs = 2))
        history = PlaybackHistory.record(history, snapshot(fictionId = 2, chapterId = 9, recordedAtMs = 3))

        val choices = PlaybackHistory.jumpBackChoices(history)
        assertEquals(listOf(2, 1), choices.map { it.fictionId })
        assertEquals(2, choices.first { it.fictionId == 1 }.chapterId)
    }

    @Test
    fun `jump-back is limited and skips dismissed entries`() {
        var history = emptyList<PlaybackSnapshot>()
        repeat(10) { index ->
            history = PlaybackHistory.record(
                history,
                snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()),
            )
        }
        history = PlaybackHistory.dismiss(history, "9:9")

        val choices = PlaybackHistory.jumpBackChoices(history, limit = 3)
        assertEquals(3, choices.size)
        assertTrue(choices.none { it.fictionId == 9 })
    }

    @Test
    fun `progress is bounded to zero and one`() {
        assertEquals(0f, snapshot(positionSeconds = -5.0).progress)
        assertEquals(1f, snapshot(positionSeconds = 9_999.0, durationSeconds = 600.0).progress)
        assertEquals(0f, snapshot(durationSeconds = 0.0).progress)
    }

    // --- The file store -------------------------------------------------------------------------

    @Test
    fun `history survives a restart`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        FilePlaybackHistoryStore(file).record(snapshot(chapterId = 4, recordedAtMs = 99))

        val reloaded = FilePlaybackHistoryStore(file).history.value
        assertEquals(1, reloaded.size)
        assertEquals(4, reloaded.single().chapterId)
    }

    @Test
    fun `a dismissal survives a restart`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        store.record(snapshot(chapterId = 4))
        store.dismiss("1:4")

        assertTrue(FilePlaybackHistoryStore(file).history.value.single().dismissed)
    }

    @Test
    fun `an oversized file from another build is trimmed on the way in`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        repeat(PlaybackHistory.MaxEntries + 30) { index ->
            store.record(snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()))
        }
        assertEquals(PlaybackHistory.MaxEntries, FilePlaybackHistoryStore(file).history.value.size)
    }

    @Test
    fun `a corrupt file degrades to an empty history`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        file.writeText("not json at all")
        assertTrue(FilePlaybackHistoryStore(file).history.value.isEmpty())
    }

    @Test
    fun `the history file holds no server address and no credential`(@TempDir dir: File) {
        // The type has nowhere to put one, which is the real guarantee; this asserts the shape of
        // what actually lands on disk.
        val file = dir.resolve("history.json")
        FilePlaybackHistoryStore(file).record(snapshot())
        val text = file.readText()
        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("bearer", ignoreCase = true))
    }

    @Test
    fun `clearing empties the file`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        store.record(snapshot())
        store.clear()
        assertTrue(store.history.value.isEmpty())
        assertTrue(FilePlaybackHistoryStore(file).history.value.isEmpty())
    }
}
