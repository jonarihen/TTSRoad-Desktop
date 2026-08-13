package dk.perspektiva.ttsroad.desktop.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ShortcutsTest {

    @Test
    fun `alt and left is Back`() {
        assertEquals(
            AppShortcut.Back,
            shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown, alt = true),
        )
    }

    @Test
    fun `left on its own seeks rather than navigating back`() {
        // Back has to keep its modifier: an arrow key that leaves the screen would fire every time
        // someone nudged the scrubber from the keyboard.
        assertEquals(
            AppShortcut.SeekBackward,
            shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown),
        )
    }

    @Test
    fun `the dedicated browser back key navigates back`() {
        assertEquals(AppShortcut.Back, shortcutFor(Key.Back, KeyEventType.KeyDown))
    }

    @Test
    fun `F5 and Ctrl-R both refresh`() {
        assertEquals(AppShortcut.Refresh, shortcutFor(Key.F5, KeyEventType.KeyDown))
        assertEquals(AppShortcut.Refresh, shortcutFor(Key.R, KeyEventType.KeyDown, ctrl = true))
    }

    @Test
    fun `Cmd-R refreshes, for the mac build`() {
        assertEquals(AppShortcut.Refresh, shortcutFor(Key.R, KeyEventType.KeyDown, meta = true))
    }

    @Test
    fun `an unmodified R is a letter, not a shortcut`() {
        assertNull(shortcutFor(Key.R, KeyEventType.KeyDown))
    }

    @Test
    fun `escape is its own action`() {
        assertEquals(AppShortcut.Dismiss, shortcutFor(Key.Escape, KeyEventType.KeyDown))
    }

    @Test
    fun `key up is ignored so one press is one action`() {
        assertNull(shortcutFor(Key.F5, KeyEventType.KeyUp))
        assertNull(shortcutFor(Key.Escape, KeyEventType.KeyUp))
        assertNull(shortcutFor(Key.DirectionLeft, KeyEventType.KeyUp, alt = true))
    }

    // --- Transport and navigation shortcuts -----------------------------------------------------

    @Test
    fun `space is play-pause`() {
        assertEquals(AppShortcut.PlayPause, shortcutFor(Key.Spacebar, KeyEventType.KeyDown))
    }

    @Test
    fun `bare arrows seek and ctrl-arrows change chapter`() {
        assertEquals(AppShortcut.SeekForward, shortcutFor(Key.DirectionRight, KeyEventType.KeyDown))
        assertEquals(
            AppShortcut.PreviousChapter,
            shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown, ctrl = true),
        )
        assertEquals(
            AppShortcut.NextChapter,
            shortcutFor(Key.DirectionRight, KeyEventType.KeyDown, ctrl = true),
        )
    }

    @Test
    fun `alt-left stays Back even though ctrl-left is a chapter`() {
        // The two are one keystroke apart, so the ordering inside the matcher is load-bearing.
        assertEquals(AppShortcut.Back, shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown, alt = true))
    }

    @Test
    fun `ctrl-L opens the library and ctrl-comma opens settings`() {
        assertEquals(AppShortcut.OpenLibrary, shortcutFor(Key.L, KeyEventType.KeyDown, ctrl = true))
        assertEquals(AppShortcut.OpenSettings, shortcutFor(Key.Comma, KeyEventType.KeyDown, ctrl = true))
    }

    @Test
    fun `F1 and ctrl-slash both open the shortcut list`() {
        assertEquals(AppShortcut.ShowShortcuts, shortcutFor(Key.F1, KeyEventType.KeyDown))
        assertEquals(AppShortcut.ShowShortcuts, shortcutFor(Key.Slash, KeyEventType.KeyDown, ctrl = true))
    }

    @Test
    fun `the keyboard transport row maps to the same actions`() {
        assertEquals(AppShortcut.PlayPause, shortcutFor(Key.MediaPlayPause, KeyEventType.KeyDown))
        assertEquals(AppShortcut.NextChapter, shortcutFor(Key.MediaNext, KeyEventType.KeyDown))
        assertEquals(AppShortcut.PreviousChapter, shortcutFor(Key.MediaPrevious, KeyEventType.KeyDown))
    }

    // --- Typing safety --------------------------------------------------------------------------

    @Test
    fun `editing keys do not fire while a text field has focus`() {
        // The requirement in one place: a space in the search box types a space.
        assertNull(shortcutFor(Key.Spacebar, KeyEventType.KeyDown, textInputFocused = true))
        assertNull(shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown, textInputFocused = true))
        assertNull(shortcutFor(Key.DirectionRight, KeyEventType.KeyDown, textInputFocused = true))
        // Ctrl+arrow is word navigation in a text field, so it is suppressed too.
        assertNull(
            shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown, ctrl = true, textInputFocused = true),
        )
    }

    @Test
    fun `window shortcuts still work while typing`() {
        assertEquals(
            AppShortcut.Refresh,
            shortcutFor(Key.F5, KeyEventType.KeyDown, textInputFocused = true),
        )
        assertEquals(
            AppShortcut.Dismiss,
            shortcutFor(Key.Escape, KeyEventType.KeyDown, textInputFocused = true),
        )
        assertEquals(
            AppShortcut.OpenLibrary,
            shortcutFor(Key.L, KeyEventType.KeyDown, ctrl = true, textInputFocused = true),
        )
    }

    @Test
    fun `every shortcut declares whether it is safe mid-word`() {
        // Guards the two-handler split in `App`: a new shortcut has to answer this question, and
        // the answer has to match whether the key is one a text field claims.
        val editingKeys = setOf(
            AppShortcut.PlayPause,
            AppShortcut.SeekBackward,
            AppShortcut.SeekForward,
            AppShortcut.PreviousChapter,
            AppShortcut.NextChapter,
        )
        AppShortcut.entries.forEach { shortcut ->
            assertEquals(
                shortcut !in editingKeys,
                shortcut.firesWhileTyping,
                "$shortcut is classified on the wrong side of the typing guard",
            )
        }
    }

    // --- Bookmarks ----------------------------------------------------------------------------

    @Test
    fun `Ctrl-B marks the spot and Ctrl-Shift-B opens the list`() {
        assertEquals(AppShortcut.AddBookmark, shortcutFor(Key.B, KeyEventType.KeyDown, ctrl = true))
        // Checked before the bare mark, so the list shortcut does not also bookmark on its way past.
        assertEquals(
            AppShortcut.OpenBookmarks,
            shortcutFor(Key.B, KeyEventType.KeyDown, ctrl = true, shift = true),
        )
    }

    @Test
    fun `Cmd-B marks the spot, for the mac build`() {
        assertEquals(AppShortcut.AddBookmark, shortcutFor(Key.B, KeyEventType.KeyDown, meta = true))
    }

    @Test
    fun `an unmodified B is a letter, not a shortcut`() {
        assertNull(shortcutFor(Key.B, KeyEventType.KeyDown))
    }

    @Test
    fun `both bookmark shortcuts stay live while typing`() {
        // Checked against the one text field that could plausibly claim Ctrl+B: the library's
        // search box is a plain text field with no bold to toggle, so nothing consumes it.
        assertEquals(
            AppShortcut.AddBookmark,
            shortcutFor(Key.B, KeyEventType.KeyDown, ctrl = true, textInputFocused = true),
        )
        assertEquals(
            AppShortcut.OpenBookmarks,
            shortcutFor(Key.B, KeyEventType.KeyDown, ctrl = true, shift = true, textInputFocused = true),
        )
    }

    @Test
    fun `the help table is not empty and every row is filled in`() {
        assertTrue(ShortcutHelpTable.isNotEmpty())
        assertTrue(ShortcutHelpTable.all { it.keys.isNotBlank() && it.action.isNotBlank() })
    }

    // --- Escape precedence --------------------------------------------------------------------

    @Test
    fun `escape closes an open dialog before it navigates`() {
        assertEquals(
            EscapeAction.CloseOverlay,
            escapeAction(hasOpenOverlay = true, canGoBack = true),
        )
    }

    @Test
    fun `escape navigates back only when nothing is open`() {
        assertEquals(EscapeAction.GoBack, escapeAction(hasOpenOverlay = false, canGoBack = true))
    }

    @Test
    fun `escape at the root with nothing open does nothing`() {
        assertEquals(EscapeAction.None, escapeAction(hasOpenOverlay = false, canGoBack = false))
    }
}
