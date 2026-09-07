package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which seconds of a chapter are an advert, and what a player does with that.
 *
 * The parsing is ordinary; the arithmetic is not. Every way of getting it wrong takes something
 * from a listener — a target computed early clips the last line of a paragraph, a malformed row
 * acted on seeks into the middle of somebody's prose — so the boundaries are pinned here rather
 * than left to the controller test to discover indirectly.
 */
class ChapterSkipsTest {

    /** 100–140s is a mid-chapter plug; 590–600s is a trailing note in a 600s chapter. */
    private val skips = ChapterSkips(
        chapterId = 7,
        segments = listOf(
            ChapterSkipSegment(startMs = 100_000, endMs = 140_000),
            ChapterSkipSegment(startMs = 590_000, endMs = 600_000),
        ),
        durationMs = 600_000,
    )

    @Test
    fun `outside an advert there is nowhere to jump to`() {
        assertNull(skips.targetFor(50_000))
        assertNull(skips.targetFor(160_000))
    }

    @Test
    fun `inside an advert the jump lands where it ends`() {
        assertEquals(140_000L, skips.targetFor(100_000))
        assertEquals(140_000L, skips.targetFor(120_000))
    }

    @Test
    fun `the tolerance is on the near edge only`() {
        // A quarter second short of an advert still means hearing it…
        assertEquals(140_000L, skips.targetFor(99_900))
        // …while a quarter second short of the end of one is a seek that saves nothing and costs a
        // re-buffer to make.
        assertNull(skips.targetFor(139_900))
    }

    @Test
    fun `an advert that runs to the end is the end of the chapter`() {
        val target = skips.targetFor(592_000)

        assertEquals(600_000L, target)
        assertTrue(skips.endsChapter(target!!))
        assertFalse(skips.endsChapter(140_000))
    }

    @Test
    fun `a chapter of unknown length still skips, it just cannot end itself`() {
        val unknown = skips.copy(durationMs = 0)

        assertEquals(140_000L, unknown.targetFor(120_000))
        assertFalse(unknown.endsChapter(600_000))
    }

    @Test
    fun `a malformed segment is dropped rather than guessed at`() {
        val parsed = ChapterSkips.from(
            ChapterSkipsResponse(
                chapterId = 7,
                audioDuration = 600.0,
                segments = listOf(
                    ChapterSkipSegmentWire(startSeconds = 140.0, endSeconds = 100.0),
                    ChapterSkipSegmentWire(startSeconds = -5.0, endSeconds = 10.0),
                    ChapterSkipSegmentWire(startSeconds = 20.0, endSeconds = 20.0),
                    ChapterSkipSegmentWire(startSeconds = 590.0, endSeconds = 600.0),
                    ChapterSkipSegmentWire(startSeconds = 100.0, endSeconds = 140.0),
                ),
            ),
            chapterId = 7,
        )

        // Sorted, because everything downstream takes the first match rather than the best one.
        assertEquals(
            listOf(ChapterSkipSegment(100_000, 140_000), ChapterSkipSegment(590_000, 600_000)),
            parsed.segments,
        )
        assertEquals(600_000L, parsed.durationMs)
    }

    @Test
    fun `a payload with no segments is an ordinary answer`() {
        // What every chapter on a server where nobody has written a rule answers.
        assertTrue(ChapterSkips.from(ChapterSkipsResponse(), chapterId = 7).isEmpty)
        assertTrue(ChapterSkips.None.isEmpty)
    }
}
