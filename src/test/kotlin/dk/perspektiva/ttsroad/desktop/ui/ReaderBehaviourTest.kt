package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import dk.perspektiva.ttsroad.desktop.data.ReadAlongDocument
import dk.perspektiva.ttsroad.desktop.data.ReaderHighlight
import dk.perspektiva.ttsroad.desktop.data.ReaderTheme
import dk.perspektiva.ttsroad.desktop.data.TextSpan
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
    fun `spoken lines stay still inside the comfort band and recenter outside it`() {
        assertNull(readerFollowScrollDelta(200, 240, 900))
        assertNull(readerFollowScrollDelta(560, 600, 900))
        assertEquals(300, readerFollowScrollDelta(600, 640, 900))
        assertEquals(-320, readerFollowScrollDelta(-20, 20, 900))
        assertEquals(4700, readerFollowScrollDelta(5000, 5040, 900))
        assertNull(readerFollowScrollDelta(10, 40, 0))
    }

    @Test
    fun `word styles change only paint and word mode covers the whole word`() {
        ReaderTheme.entries.forEach { theme ->
            val palette = readerPalette(theme)
            listOf(ReaderHighlight.Word, ReaderHighlight.Sentence).forEach { mode ->
                val style = readerWordStyle(mode, palette)
                assertEquals(SpanStyle(), style.copy(color = Color.Unspecified, background = Color.Unspecified))
                assertEquals(palette.accent, style.color)
                assertEquals(if (mode == ReaderHighlight.Word) palette.sentenceBand else Color.Unspecified, style.background)
            }
            val document = ReadAlongDocument(text = "before complete after")
            val paragraph = TextSpan(0, document.text.length)
            val word = TextSpan(7, 15)
            val annotated = readerParagraphText(document, paragraph, paragraph, word, emptyList(), null, ReaderHighlight.Word, palette)
            assertEquals(document.text, annotated.text)
            assertEquals(1, annotated.spanStyles.size)
            val range = annotated.spanStyles.single()
            assertEquals(word.start, range.start)
            assertEquals(word.end, range.end)
            assertEquals(palette.sentenceBand, range.item.background)
            assertNull(range.item.fontWeight)
        }
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

    // --- Distraction-free reading ---------------------------------------------------------------

    @Test
    fun `outside reading mode the chrome is simply always there`() {
        assertTrue(readingModeChromeVisible(readingMode = false, pointerY = 400f, viewportHeightPx = 800f))
        assertTrue(readingModeChromeVisible(readingMode = false, pointerY = null, viewportHeightPx = 800f))
    }

    @Test
    fun `reaching for either edge brings the frame back`() {
        fun visibleAt(y: Float?) =
            readingModeChromeVisible(readingMode = true, pointerY = y, viewportHeightPx = 800f)

        assertTrue(visibleAt(0f), "the very top edge")
        assertTrue(visibleAt(ReadingModeRevealPx))
        assertFalse(visibleAt(ReadingModeRevealPx + 1f))
        assertFalse(visibleAt(400f), "the middle of the page is where reading happens")
        assertTrue(visibleAt(800f - ReadingModeRevealPx))
        assertTrue(visibleAt(800f), "the very bottom edge")
    }

    @Test
    fun `a pointer that never moved or has left the window is not hovering an edge`() {
        assertFalse(readingModeChromeVisible(readingMode = true, pointerY = null, viewportHeightPx = 800f))
    }

    @Test
    fun `an overlay opened by keyboard pins the frame regardless of the pointer`() {
        assertTrue(
            readingModeChromeVisible(
                readingMode = true,
                pointerY = 400f,
                viewportHeightPx = 800f,
                pinned = true,
            ),
            "Ctrl+F must not put a search field under an invisible toolbar",
        )
    }

    @Test
    fun `a viewport that has not been measured yet does not strand the frame on screen`() {
        // Before the first layout pass the height is zero, which would make every coordinate both
        // "within revealPx of the top" and "past height - revealPx" if the rule were written the
        // obvious way round.
        assertFalse(readingModeChromeVisible(readingMode = true, pointerY = 400f, viewportHeightPx = 0f))
    }
}
