package dk.perspektiva.ttsroad.desktop.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `left on its own is not Back`() {
        // Otherwise the arrow key would navigate out of a screen while a slider or a text field is
        // being driven from the keyboard.
        assertNull(shortcutFor(Key.DirectionLeft, KeyEventType.KeyDown))
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
