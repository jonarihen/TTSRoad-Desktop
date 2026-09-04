package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ShelfEditingTest {

    @Test
    fun `a partial outcome is never reported as done or as failed`() {
        val partial = UnfollowOutcome(removed = listOf(1, 2, 3), failed = listOf(4, 5))
        val report = unfollowReport(partial)

        assertEquals(
            "Unfollowed 3 of 5. 2 could not be removed and are still on your shelf.",
            report,
            "six removals reported as a failure would send somebody to undo work that succeeded",
        )
        assertFalse(partial.isCompleteSuccess)
        assertFalse(partial.isCompleteFailure)
    }

    @Test
    fun `complete success counts what went`() {
        assertEquals("Unfollowed one fiction.", unfollowReport(UnfollowOutcome(removed = listOf(1))))
        assertEquals("Unfollowed 4 fictions.", unfollowReport(UnfollowOutcome(removed = listOf(1, 2, 3, 4))))
        assertTrue(UnfollowOutcome(removed = listOf(1)).isCompleteSuccess)
    }

    @Test
    fun `complete failure says the shelf is unchanged`() {
        val one = unfollowReport(UnfollowOutcome(failed = listOf(1)))
        val many = unfollowReport(UnfollowOutcome(failed = listOf(1, 2)))

        assertEquals("That fiction could not be unfollowed. It is still on your shelf.", one)
        assertEquals("None of the 2 could be unfollowed. They are still on your shelf.", many)
        assertTrue(UnfollowOutcome(failed = listOf(1)).isCompleteFailure)
    }

    @Test
    fun `an outcome with nothing in it is not news`() {
        assertNull(unfollowReport(UnfollowOutcome()))
        assertEquals(0, UnfollowOutcome().attempted)
        assertFalse(UnfollowOutcome().isCompleteSuccess, "nothing attempted is not a success to report")
    }

    @Test
    fun `the confirmation says what unfollowing does not do`() {
        val one = unfollowConfirmation(1)
        val many = unfollowConfirmation(9)

        assertTrue(one.contains("this fiction"))
        assertTrue(many.contains("these 9 fictions"))
        // Following is not an access boundary, so a confirmation that sounded destructive would be
        // teaching people to dismiss confirmations.
        assertTrue(many.contains("Nothing is deleted"))
        assertTrue(many.contains("progress is kept"))
        assertTrue(many.contains("follow them back"))
    }

    @Test
    fun `a selection naming a fiction the shelf no longer holds is pruned`() {
        val shelf = listOf(FictionSummary(id = 1), FictionSummary(id = 2), FictionSummary(id = 3))

        assertEquals(setOf(1, 3), prunedSelection(setOf(1, 3, 99), shelf))
        assertEquals(emptySet(), prunedSelection(setOf(99), shelf))
        assertEquals(emptySet(), prunedSelection(emptySet(), shelf))
        assertEquals(emptySet(), prunedSelection(setOf(1), emptyList()))
    }

    @Test
    fun `the pruned selection follows shelf order, not insertion order`() {
        val shelf = listOf(FictionSummary(id = 5), FictionSummary(id = 1), FictionSummary(id = 9))

        assertContentEquals(
            listOf(5, 1, 9),
            prunedSelection(setOf(9, 1, 5), shelf).toList(),
            "the count and the ticked rows have to agree, which means one order",
        )
    }
}
