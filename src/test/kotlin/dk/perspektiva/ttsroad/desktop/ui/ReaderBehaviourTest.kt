package dk.perspektiva.ttsroad.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
