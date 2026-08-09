package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.PlaybackInfo
import dk.perspektiva.ttsroad.desktop.download.DownloadEntry
import dk.perspektiva.ttsroad.desktop.download.DownloadState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mapping between the download index and what a chapter row draws.
 *
 * Pure, so the decisions — which are mostly about telling apart states that look similar and behave
 * completely differently — are asserted without a display.
 */
class ChapterDownloadsBindingTest {

    private fun entry(
        chapterId: Int,
        state: DownloadState,
        bytes: Long = 0,
        total: Long = 0,
        failure: String? = null,
    ) = DownloadEntry(
        chapterId = chapterId,
        fictionId = 7,
        fictionTitle = "A Serial",
        chapterTitle = "Chapter $chapterId",
        state = state,
        bytesDownloaded = bytes,
        totalBytes = total,
        fileName = "$chapterId.mp3",
        failureMessage = failure,
        updatedAtMs = chapterId.toLong(),
    )

    private fun chapter(id: Int, played: Boolean = false, hasAudio: Boolean = true) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = "Chapter $id",
        displayNumber = id.toDouble(),
        playable = true,
        audio = if (hasAudio) AudioInfo(url = "/audio/s/$id.mp3") else null,
        playback = PlaybackInfo(positionSeconds = 0.0, isPlayed = played),
    )

    // --- State mapping --------------------------------------------------------------------------

    @Test
    fun `no storage means the control is absent, not merely empty`() {
        // Unavailable draws nothing at all; a greyed-out button that can never be pressed would be
        // worse than no button.
        assertEquals(ChapterDownloadState.Unavailable, chapterDownloadUi(null, available = false).state)
        assertEquals(
            ChapterDownloadState.Unavailable,
            chapterDownloadUi(entry(1, DownloadState.Downloaded), available = false).state,
        )
    }

    @Test
    fun `a chapter with no row is offered a download`() {
        // The distinction that matters: "no entry" is not "unavailable", it is "not yet".
        assertEquals(ChapterDownloadState.NotDownloaded, chapterDownloadUi(null, available = true).state)
    }

    @Test
    fun `a chapter without audio has no dead download action`() {
        val ui = chapterDownloadUi(chapter(1, hasAudio = false), entry = null, storageAvailable = true)

        assertEquals(ChapterDownloadState.Unavailable, ui.state)
    }

    @Test
    fun `each index state maps to the control that state affords`() {
        fun stateOf(s: DownloadState) = chapterDownloadUi(entry(1, s), available = true).state

        assertEquals(ChapterDownloadState.Queued, stateOf(DownloadState.Queued))
        assertEquals(ChapterDownloadState.Downloading, stateOf(DownloadState.Downloading))
        assertEquals(ChapterDownloadState.Downloaded, stateOf(DownloadState.Downloaded))
        assertEquals(ChapterDownloadState.Failed, stateOf(DownloadState.Failed))
        assertEquals(ChapterDownloadState.Removing, stateOf(DownloadState.Removing))
        assertEquals(ChapterDownloadState.NotDownloaded, stateOf(DownloadState.None))
    }

    @Test
    fun `progress is shown when the size is known and withheld when it is not`() {
        val known = chapterDownloadUi(entry(1, DownloadState.Downloading, bytes = 50, total = 200), true)
        assertEquals(0.25f, known.progress)

        // A determinate bar filling from a total this app invented would be a lie.
        val unknown = chapterDownloadUi(entry(1, DownloadState.Downloading, bytes = 50, total = 0), true)
        assertNull(unknown.progress)
    }

    @Test
    fun `a failure carries its reason so the row can say what went wrong`() {
        val ui = chapterDownloadUi(entry(1, DownloadState.Failed, failure = "The disk is full"), true)
        assertEquals("The disk is full", ui.failureMessage)
    }

    // --- "Download next N" ----------------------------------------------------------------------

    @Test
    fun `the next batch starts at the first unplayed chapter, not at the top`() {
        val chapters = (1..30).map { chapter(it, played = it <= 4) }

        val next = chaptersToDownloadNext(chapters, emptyList(), count = 10)

        assertEquals((5..14).toList(), next.map { it.resolvedChapterId })
    }

    @Test
    fun `the batch is in reading order however the screen is sorted`() {
        // A listener who flipped the list to newest-first still means "the next ten I will hear".
        val chapters = (1..30).map { chapter(it) }.reversed()

        val next = chaptersToDownloadNext(chapters, emptyList(), count = 5)

        assertEquals(listOf(1, 2, 3, 4, 5), next.map { it.resolvedChapterId })
    }

    @Test
    fun `chapters already downloaded or in flight are skipped so the batch is N new ones`() {
        val chapters = (1..30).map { chapter(it) }
        val entries = listOf(
            entry(1, DownloadState.Downloaded),
            entry(2, DownloadState.Queued),
            entry(3, DownloadState.Downloading),
        )

        val next = chaptersToDownloadNext(chapters, entries, count = 10)

        assertEquals((4..13).toList(), next.map { it.resolvedChapterId })
    }

    @Test
    fun `chapters with no audio are never queued`() {
        val chapters = listOf(chapter(1, hasAudio = false), chapter(2), chapter(3, hasAudio = false), chapter(4))

        val next = chaptersToDownloadNext(chapters, emptyList(), count = 10)

        assertEquals(listOf(2, 4), next.map { it.resolvedChapterId })
    }

    @Test
    fun `a fully played serial offers the batch from the start rather than nothing`() {
        val chapters = (1..5).map { chapter(it, played = true) }

        val next = chaptersToDownloadNext(chapters, emptyList(), count = 10)

        assertEquals((1..5).toList(), next.map { it.resolvedChapterId })
    }

    @Test
    fun `an empty serial asks for nothing`() {
        assertTrue(chaptersToDownloadNext(emptyList(), emptyList()).isEmpty())
    }
}
