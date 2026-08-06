package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The breakpoints and the small pure helpers the screens read. Declared once and asserted here so
 * "narrow" means the same thing in Settings, in the player and in the library.
 */
class AdaptiveLayoutTest {

    @Test
    fun `the supported minimum window width is Compact`() {
        assertTrue(windowSizeClassFor(MinWindowWidth).isCompact)
    }

    @Test
    fun `the breakpoints are half-open, so a width belongs to exactly one class`() {
        assertEquals(WindowSizeClass.Compact, windowSizeClassFor(CompactWidthMax - 1.dp))
        assertEquals(WindowSizeClass.Medium, windowSizeClassFor(CompactWidthMax))
        assertEquals(WindowSizeClass.Medium, windowSizeClassFor(MediumWidthMax - 1.dp))
        assertEquals(WindowSizeClass.Expanded, windowSizeClassFor(MediumWidthMax))
    }

    @Test
    fun `an ultrawide window is Expanded, where content is capped rather than stretched`() {
        assertEquals(WindowSizeClass.Expanded, windowSizeClassFor(3440.dp))
        assertFalse(windowSizeClassFor(3440.dp).isCompact)
    }

    // --- Library search ------------------------------------------------------------------------

    private val fictions = listOf(
        FictionSummary(id = 1, title = "A Test Serial", author = "Someone", tags = listOf("LitRPG")),
        FictionSummary(id = 2, title = "Another Story", author = "Writer Person", tags = listOf("Progression")),
    )

    @Test
    fun `search matches title, author and tags, case-insensitively`() {
        assertEquals(listOf(1), filterFictions(fictions, "test serial").map { it.id })
        assertEquals(listOf(2), filterFictions(fictions, "WRITER").map { it.id })
        assertEquals(listOf(1), filterFictions(fictions, "litrpg").map { it.id })
    }

    @Test
    fun `a blank query is not a filter`() {
        assertEquals(fictions, filterFictions(fictions, "   "))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(listOf(1), filterFictions(fictions, "  serial  ").map { it.id })
    }

    // --- "how old is this" ---------------------------------------------------------------------

    @Test
    fun `content that has never loaded is not described as recent`() {
        assertEquals("an earlier session", formatLastUpdated(null, nowMillis = 1_000_000))
    }

    @Test
    fun `age is reported in the coarsest unit that still says something`() {
        val now = 10_000_000L
        assertEquals("just now", formatLastUpdated(now - 30_000, now))
        assertEquals("1 minute ago", formatLastUpdated(now - 60_000, now))
        assertEquals("5 minutes ago", formatLastUpdated(now - 5 * 60_000, now))
        assertEquals("1 hour ago", formatLastUpdated(now - 60 * 60_000, now))
        assertEquals("3 hours ago", formatLastUpdated(now - 3 * 60 * 60_000, now))
        assertEquals("2 days ago", formatLastUpdated(now - 48L * 60 * 60_000, now))
    }

    @Test
    fun `a timestamp from the future reads as just now rather than as a negative age`() {
        assertEquals("just now", formatLastUpdated(2_000_000, nowMillis = 1_000_000))
    }
}
