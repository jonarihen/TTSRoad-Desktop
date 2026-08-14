package dk.perspektiva.ttsroad.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderBehaviourTest {
    @Test
    fun `active content is placed one third down the viewport`() {
        assertEquals(-300, readerAutoScrollOffsetPx(900))
        assertEquals(0, readerAutoScrollOffsetPx(0))
    }

    @Test
    fun `manual scrolling yields permanently until Back to current`() {
        var follow = true
        follow = readerFollowAfter(follow, ReaderFollowEvent.ManualScroll)
        follow = readerFollowAfter(follow, ReaderFollowEvent.PassiveUpdate)
        assertFalse(follow)
        assertTrue(readerFollowAfter(follow, ReaderFollowEvent.BackToCurrent))
        assertTrue(readerFollowAfter(false, ReaderFollowEvent.ChapterChanged))
    }

    @Test
    fun `next chapter prefetch starts at eighty percent`() {
        assertFalse(readerShouldPrefetch(79_999, 100_000))
        assertTrue(readerShouldPrefetch(80_000, 100_000))
        assertFalse(readerShouldPrefetch(1, 0))
    }

    // --- Bookmark anchoring -------------------------------------------------------------------

    @Test
    fun `a mark from the reader is anchored to the sentence, not to the button press`() {
        val anchor = readerBookmarkAnchor(
            isPlayingThisChapter = true,
            timingsMatch = true,
            positionMs = 61_400,
            sentenceStartSeconds = 58.25,
            sentenceText = "The bridge came apart under them.",
        )

        // A listener marking a passage means the passage, not the syllable that happened to be
        // sounding when their hand reached the key.
        assertEquals(58_250L, anchor?.positionMs)
        assertEquals("The bridge came apart under them.", anchor?.label)
    }

    @Test
    fun `between cues it falls back to the playing position`() {
        val anchor = readerBookmarkAnchor(
            isPlayingThisChapter = true,
            timingsMatch = true,
            positionMs = 61_400,
            sentenceStartSeconds = null,
            sentenceText = null,
        )

        assertEquals(61_400L, anchor?.positionMs)
        assertNull(anchor?.label)
    }

    @Test
    fun `reading a chapter that is not playing places no mark at all`() {
        // The same rule that already disables highlighting: with no audio position there is no
        // honest one to record, and a bookmark at 0:00 is worse than none.
        assertNull(
            readerBookmarkAnchor(
                isPlayingThisChapter = false,
                timingsMatch = true,
                positionMs = 0,
                sentenceStartSeconds = 12.0,
                sentenceText = "Whatever this says.",
            ),
        )
        assertNull(
            readerBookmarkAnchor(
                isPlayingThisChapter = true,
                timingsMatch = false,
                positionMs = 61_400,
                sentenceStartSeconds = 12.0,
                sentenceText = "Whatever this says.",
            ),
        )
    }

    @Test
    fun `a long sentence is elided into a label`() {
        val sentence = "word ".repeat(200).trim()

        val anchor = readerBookmarkAnchor(true, true, 0, 0.0, sentence)

        // The ellipsis is the only thing allowed past the limit, and the cut never leaves the
        // trailing space it landed on.
        val label = requireNotNull(anchor?.label)
        assertTrue(label.length <= ReaderBookmarkLabelChars + 1, "was ${label.length}")
        assertTrue(label.endsWith("d…"), label.takeLast(8))
    }

    @Test
    fun `narration line breaks are flattened out of a label`() {
        val anchor = readerBookmarkAnchor(true, true, 0, 0.0, "  The bridge\n  came apart.  ")

        assertEquals("The bridge came apart.", anchor?.label)
    }

    @Test
    fun `a nonsense sentence time is ignored rather than trusted`() {
        // `seekSecondsForOffset` answers from parsed server data; a malformed row must not become
        // a negative or infinite seek.
        assertEquals(61_400L, readerBookmarkAnchor(true, true, 61_400, -3.0, "x")?.positionMs)
        assertEquals(61_400L, readerBookmarkAnchor(true, true, 61_400, Double.NaN, "x")?.positionMs)
    }
}
