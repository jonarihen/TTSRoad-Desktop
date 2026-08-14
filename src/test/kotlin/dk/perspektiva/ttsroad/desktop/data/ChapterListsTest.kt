package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChapterListsTest {

    private val ready = ChapterSummary(
        id = 101,
        fictionId = 7,
        title = "Ready",
        audio = AudioInfo(url = "/audio/x/0001.mp3"),
        audioDuration = 600.0,
    )
    private val played = ChapterSummary(
        id = 102,
        fictionId = 7,
        title = "Played",
        audio = AudioInfo(url = "/audio/x/0002.mp3"),
        playback = PlaybackInfo(isPlayed = true),
    )
    private val converting = ChapterSummary(id = 103, fictionId = 7, title = "Converting", status = "processing")

    // --- Filters -------------------------------------------------------------------------------

    @Test
    fun `All keeps everything, including chapters with no audio yet`() {
        val all = listOf(ready, played, converting)
        assertEquals(all, all.chapterView(ChapterFilter.All))
    }

    @Test
    fun `Unplayed drops what the server says is finished`() {
        assertEquals(
            listOf(ready, converting),
            listOf(ready, played, converting).chapterView(ChapterFilter.Unplayed),
        )
    }

    @Test
    fun `Ready means there is audio, not that the status string says done`() {
        // `status` and the `playable` flag both exist, but only `audio` proves the player can open
        // something — a chapter can report done with no audio object attached.
        val doneWithoutAudio = ChapterSummary(id = 104, status = "done", playable = true)

        assertEquals(
            listOf(ready, played),
            listOf(ready, played, converting, doneWithoutAudio).chapterView(ChapterFilter.Ready),
        )
    }

    // --- withPlayed ----------------------------------------------------------------------------

    @Test
    fun `marking played sets the flag and the position the server will report`() {
        val patched = listOf(ready).withPlayed(listOf(101), played = true).first().playback

        assertNotNull(patched)
        assertTrue(patched.isPlayed)
        assertEquals(600.0, patched.positionSeconds)
    }

    @Test
    fun `un-marking clears the position, as the server does`() {
        val patched = listOf(played).withPlayed(listOf(102), played = false).first().playback

        assertNotNull(patched)
        assertFalse(patched.isPlayed)
        assertEquals(0.0, patched.positionSeconds)
    }

    @Test
    fun `rows that were not marked keep their identity`() {
        // Not cosmetic: Compose skips an item whose input is the same instance, so this is the
        // difference between redrawing one row and redrawing five hundred.
        val list = listOf(ready, played, converting)

        val patched = list.withPlayed(listOf(101), played = true)

        assertSame(list[1], patched[1])
        assertSame(list[2], patched[2])
    }

    @Test
    fun `an empty id set returns the very same list`() {
        val list = listOf(ready, played)
        assertSame(list, list.withPlayed(emptyList(), played = true))
    }

    // --- Lazy keys -----------------------------------------------------------------------------

    @Test
    fun `chapter keys are stable across a refresh that returns the same rows`() {
        val first = chapterKeys(listOf(ready, played, converting))
        val second = chapterKeys(listOf(ready.copy(title = "Ready (renamed)"), played, converting))

        assertEquals(first, second)
    }

    @Test
    fun `duplicate chapter ids still produce unique keys`() {
        // The library's two shelves are different server payloads whose ids can repeat, and a row
        // that carries no id at all resolves to 0 — a duplicate key is a hard crash in a lazy list.
        val idless = ChapterSummary(title = "No id")
        val keys = chapterKeys(listOf(ready, ready, idless, idless))

        assertEquals(keys.size, keys.toSet().size, "keys were $keys")
        assertEquals("7:101", keys.first(), "the first occurrence keeps the plain, stable key")
    }

    @Test
    fun `the same chapter under two fictions does not collide`() {
        val elsewhere = ready.copy(fictionId = 9)

        val keys = chapterKeys(listOf(ready, elsewhere))

        assertEquals(listOf("7:101", "9:101"), keys)
    }

    @Test
    fun `fiction keys are unique even when a payload decodes to id zero`() {
        val malformed = FictionSummary()
        val keys = fictionKeys(listOf(FictionSummary(id = 7), malformed, malformed))

        assertEquals(keys.size, keys.toSet().size, "keys were $keys")
        assertEquals("fiction:7", keys.first())
    }

    // --- Chapter id / number fallbacks ---------------------------------------------------------

    @Test
    fun `a library-shelf row resolves its ids from the flat payload shape`() {
        // `continue_listening` carries chapter_id/chapter_title/fiction_id and no `id` at all.
        val shelfRow = ChapterSummary(
            apiChapterId = 101,
            fictionId = 7,
            chapterTitle = "Chapter 3 — The Descent",
            fictionTitle = "A Test Serial",
            fictionAuthor = "Someone",
            chapterNumber = 3,
        )

        assertEquals(101, shelfRow.resolvedChapterId)
        assertEquals(7, shelfRow.resolvedFictionId)
        assertEquals("Chapter 3 — The Descent", shelfRow.resolvedTitle)
        assertEquals("A Test Serial", shelfRow.resolvedFictionTitle)
        assertEquals("Someone", shelfRow.resolvedAuthor)
    }

    @Test
    fun `a nested fiction object wins over the flat fields`() {
        val nested = ChapterSummary(
            id = 101,
            apiChapterId = 999,
            fiction = FictionSummary(id = 9, title = "Nested", author = "Nested author"),
            fictionId = 0,
            fictionTitle = "Flat",
            fictionAuthor = "Flat author",
        )

        assertEquals(101, nested.resolvedChapterId, "a real `id` beats the flat `chapter_id`")
        assertEquals(9, nested.resolvedFictionId)
        assertEquals("Nested", nested.resolvedFictionTitle)
        assertEquals("Nested author", nested.resolvedAuthor)
    }

    @Test
    fun `an id-less row resolves to zero rather than throwing`() {
        // Every field on the model is defaulted, so a malformed payload has to degrade, not crash.
        assertEquals(0, ChapterSummary().resolvedChapterId)
        assertEquals(0, ChapterSummary().resolvedFictionId)
        assertNull(ChapterSummary().resolvedDisplayNumber)
    }

    @Test
    fun `display_number is preferred, chapter_number is the fallback`() {
        assertEquals(3.0, ChapterSummary(displayNumber = 3.0, chapterNumber = 41).resolvedDisplayNumber)
        assertEquals(41.0, ChapterSummary(chapterNumber = 41).resolvedDisplayNumber)
    }

    // --- Sorting -------------------------------------------------------------------------------

    private fun numbered(n: Int) = ChapterSummary(id = 1000 + n, fictionId = 7, title = "C$n", displayNumber = n.toDouble())

    @Test
    fun `oldest-first is the server order, newest-first is its exact reverse`() {
        val list = listOf(numbered(1), numbered(2), numbered(3))

        assertEquals(listOf(1.0, 2.0, 3.0), list.sortedByDisplayNumber(ascending = true).map { it.displayNumber })
        assertEquals(listOf(3.0, 2.0, 1.0), list.sortedByDisplayNumber(ascending = false).map { it.displayNumber })
    }

    @Test
    fun `an out-of-order payload is still sorted by number, not by position`() {
        val list = listOf(numbered(3), numbered(1), numbered(2))

        assertEquals(listOf(1.0, 2.0, 3.0), list.sortedByDisplayNumber(ascending = true).map { it.displayNumber })
    }

    @Test
    fun `unnumbered chapters stay at the end in both directions`() {
        // Excluded chapters carry a null display_number; floating them to the top of a
        // newest-first list would put the least identifiable rows where the newest chapter goes.
        val unnumbered = ChapterSummary(id = 900, fictionId = 7, title = "No number")
        val list = listOf(numbered(1), unnumbered, numbered(2))

        assertEquals("No number", list.sortedByDisplayNumber(ascending = true).last().title)
        assertEquals("No number", list.sortedByDisplayNumber(ascending = false).last().title)
    }

    @Test
    fun `sorting never mutates or drops anything`() {
        val list = listOf(numbered(2), numbered(1))
        val sorted = list.sortedByDisplayNumber(ascending = false)

        assertEquals(list.size, sorted.size)
        assertEquals(list.toSet(), sorted.toSet())
        assertEquals(listOf(numbered(2), numbered(1)), list, "the receiver is untouched")
    }

    @Test
    fun `filter and sort compose into one view`() {
        val list = listOf(numbered(1), played.copy(displayNumber = 2.0), numbered(3))

        val view = list.chapterView(ChapterListOptions(ChapterFilter.Unplayed, ChapterSort.Newest))

        assertEquals(listOf(3.0, 1.0), view.map { it.displayNumber })
    }

    @Test
    fun `the playback order ignores however the screen is currently sorted`() {
        val newestFirst = listOf(numbered(3), numbered(2), numbered(1))

        assertEquals(listOf(1.0, 2.0, 3.0), newestFirst.playbackOrder().map { it.displayNumber })
    }

    // --- Bulk id selection ---------------------------------------------------------------------

    @Test
    fun `mark-all-previous is exclusive and independent of the current view order`() {
        val newestFirst = listOf(numbered(4), numbered(3), numbered(2), numbered(1))

        val before = newestFirst.chaptersBefore(chapterId = 1003).allChapterIds()

        assertEquals(listOf(1001, 1002), before, "chapters 1 and 2, in reading order")
    }

    @Test
    fun `the first chapter and an unknown id both select nothing`() {
        val list = listOf(numbered(1), numbered(2))

        assertEquals(emptyList(), list.chaptersBefore(1001).allChapterIds())
        assertEquals(emptyList(), list.chaptersBefore(999_999).allChapterIds())
        assertEquals(emptyList(), list.chaptersBefore(0).allChapterIds())
    }

    @Test
    fun `bulk ids skip rows that are already in the target state`() {
        // Re-marking a finished chapter would reset position_seconds to the full duration for no
        // reason, and an empty result is how the UI knows the action would change nothing.
        val list = listOf(ready, played, converting)

        assertEquals(listOf(101, 103), list.markableIds(played = true))
        assertEquals(listOf(102), list.markableIds(played = false))
        assertEquals(emptyList(), listOf(played).markableIds(played = true))
    }

    @Test
    fun `ids that failed to decode are never sent to the server`() {
        val idless = ChapterSummary(title = "No id")

        assertEquals(listOf(101), listOf(ready, idless).allChapterIds())
        assertEquals(listOf(101), listOf(ready, idless).markableIds(played = true))
    }

    // --- Current-index mapping ------------------------------------------------------------------

    @Test
    fun `the current chapter is located in the view actually on screen`() {
        val view = listOf(numbered(3), numbered(2), numbered(1))

        assertEquals(1, view.indexOfChapter(1002))
        assertEquals(-1, view.indexOfChapter(9999), "a chapter the filter hid is not on screen")
        assertEquals(-1, view.indexOfChapter(null))
        assertEquals(-1, view.indexOfChapter(0), "an id-less row must not match the first row")
    }

    // --- Status legibility ----------------------------------------------------------------------

    @Test
    fun `a ready chapter needs no status chip`() {
        assertEquals(ChapterAvailability.Ready, ready.availability())
        assertNull(ready.statusLabel())
    }

    @Test
    fun `excluded outranks everything else, including having audio`() {
        val excluded = ready.copy(excluded = true)

        assertEquals(ChapterAvailability.Excluded, excluded.availability())
        assertEquals("Excluded", excluded.statusLabel())
    }

    @Test
    fun `a failure is legible without leaking the server's own error text`() {
        val failed = ChapterSummary(
            id = 105,
            status = "error",
            errorMessage = "Traceback: /srv/ttsroad/app/services/tts.py line 214 -- edge-tts refused",
        )

        assertEquals(ChapterAvailability.Failed, failed.availability())
        assertEquals("Failed", failed.statusLabel())
        assertFalse(failed.statusLabel().orEmpty().contains("ttsroad"), "no server paths on screen")
    }

    @Test
    fun `conversion progress is shown when the server reports it`() {
        assertEquals("Converting 41%", converting.copy(ttsProgress = 41).statusLabel())
        assertEquals("Converting", converting.statusLabel())
        // 100% with no audio yet is still just "converting" — the row is not playable either way.
        assertEquals("Converting", converting.copy(ttsProgress = 100).statusLabel())
    }

    @Test
    fun `anything else is simply queued`() {
        val pending = ChapterSummary(id = 106, status = "pending")

        assertEquals(ChapterAvailability.Queued, pending.availability())
        assertEquals("Queued", pending.statusLabel())
    }

    // --- Rollback -------------------------------------------------------------------------------

    @Test
    fun `a rollback restores the exact progress a row had, not zero`() {
        // The specific bug this prevents: a failed "mark played" on a chapter the user was 6:52
        // into must not leave it at 0:00 just because un-marking is what the server does.
        val inProgress = ready.copy(playback = PlaybackInfo(positionSeconds = 412.5, isPlayed = false))
        val list = listOf(inProgress)
        val snapshot = list.playbackSnapshot(listOf(101))

        val optimistic = list.withPlayed(listOf(101), played = true)
        val rolledBack = optimistic.withRestoredPlayback(snapshot)

        assertTrue(optimistic.first().isPlayed)
        assertEquals(412.5, rolledBack.first().playback?.positionSeconds)
        assertFalse(rolledBack.first().isPlayed)
    }

    @Test
    fun `restoring a row that had no playback object at all puts the null back`() {
        val list = listOf(ready)
        val snapshot = list.playbackSnapshot(listOf(101))

        val rolledBack = list.withPlayed(listOf(101), played = true).withRestoredPlayback(snapshot)

        assertNull(rolledBack.first().playback)
    }

    @Test
    fun `rows outside the snapshot keep their identity through a rollback`() {
        val list = listOf(ready, played, converting)

        val rolledBack = list.withRestoredPlayback(list.playbackSnapshot(listOf(101)))

        assertSame(list[1], rolledBack[1])
        assertSame(list[2], rolledBack[2])
    }

    // --- Listening totals ------------------------------------------------------------------------

    private fun playable(id: Int, duration: Double, playback: PlaybackInfo? = null) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = "Chapter $id",
        audio = AudioInfo(url = "/audio/x/$id.mp3"),
        audioDuration = duration,
        playback = playback,
    )

    @Test
    fun `totals count only chapters the player could actually open`() {
        // `converting` has no audio: it is not listening time yet, and counting it would make
        // "n of m played" unreachable while the fiction is still being produced.
        val totals = listOf(playable(1, 600.0), playable(2, 600.0), converting).listeningTotals()

        assertEquals(2, totals.listenable)
        assertEquals(1200.0, totals.remainingSeconds)
    }

    @Test
    fun `an untouched chapter contributes its whole duration`() {
        assertEquals(600.0, listOf(playable(1, 600.0)).listeningTotals().remainingSeconds)
    }

    @Test
    fun `the server's remaining_seconds wins over duration minus position`() {
        val chapter = playable(1, 600.0, PlaybackInfo(positionSeconds = 100.0, remainingSeconds = 480.0))

        assertEquals(480.0, listOf(chapter).listeningTotals().remainingSeconds)
    }

    @Test
    fun `a payload with a position but no remainder falls back to the subtraction`() {
        val chapter = playable(1, 600.0, PlaybackInfo(positionSeconds = 100.0))

        assertEquals(500.0, listOf(chapter).listeningTotals().remainingSeconds)
    }

    @Test
    fun `a duration that shrank under a saved position cannot contribute negative time`() {
        val chapter = playable(1, 60.0, PlaybackInfo(positionSeconds = 900.0))

        assertEquals(0.0, listOf(chapter).listeningTotals().remainingSeconds)
    }

    @Test
    fun `a finished chapter is zero regardless of what its remainder says`() {
        // A stale `remaining_seconds` from before the mark must not keep counting down the total.
        val finished = playable(1, 600.0, PlaybackInfo(isPlayed = true, remainingSeconds = 600.0))
        val totals = listOf(finished, playable(2, 600.0)).listeningTotals()

        assertEquals(1, totals.played)
        assertEquals(1, totals.unplayed)
        assertEquals(600.0, totals.remainingSeconds)
    }

    @Test
    fun `an optimistic mark moves the totals with the checkmarks`() {
        val list = listOf(playable(1, 600.0), playable(2, 600.0))

        val after = list.withPlayed(listOf(1, 2), played = true).listeningTotals()

        assertEquals(2, after.played)
        assertEquals(0, after.unplayed)
        assertEquals(0.0, after.remainingSeconds)
    }

    @Test
    fun `a rollback restores the totals a failed mark had claimed`() {
        val list = listOf(playable(1, 600.0, PlaybackInfo(positionSeconds = 120.0)), playable(2, 600.0))
        val snapshot = list.playbackSnapshot(listOf(1, 2))

        val restored = list.withPlayed(listOf(1, 2), played = true)
            .withRestoredPlayback(snapshot)
            .listeningTotals()

        assertEquals(0, restored.played)
        assertEquals(1080.0, restored.remainingSeconds)
    }

    @Test
    fun `a fiction with nothing playable reports empty rather than zero of zero`() {
        assertTrue(listOf(converting).listeningTotals().isEmpty)
        assertFalse(listOf(ready).listeningTotals().isEmpty)
    }

    @Test
    fun `a listening span reads in hours and minutes, not as a timestamp`() {
        assertEquals("54h 38m", formatListeningSpan(54 * 3600 + 38 * 60 + 12.0))
        assertEquals("3h", formatListeningSpan(3 * 3600.0))
        assertEquals("38m", formatListeningSpan(38 * 60.0))
    }

    @Test
    fun `an unfinished chapter is never announced as zero minutes left`() {
        assertEquals("1m", formatListeningSpan(4.0))
        assertEquals("0m", formatListeningSpan(0.0))
        assertEquals("0m", formatListeningSpan(-30.0))
    }
}
