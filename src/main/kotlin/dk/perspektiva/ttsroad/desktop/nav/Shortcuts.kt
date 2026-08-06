package dk.perspektiva.ttsroad.desktop.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** The window-level keyboard actions. Everything else is ordinary focus traversal. */
enum class AppShortcut {
    /** Alt+Left, and the Back control in the header. */
    Back,

    /** F5 / Ctrl+R (Cmd+R on macOS). */
    Refresh,

    /** Escape — closes the top dialog or sheet first, and only navigates if there was none. */
    Dismiss,
}

/**
 * Maps a key press to an app shortcut.
 *
 * Deliberately a pure function over the facts a `KeyEvent` carries, so the whole shortcut table is
 * unit-testable: a Compose `KeyEvent` cannot be constructed without a real toolkit event, and a
 * table encoded inline in a `Modifier.onPreviewKeyEvent` lambda would only be reachable through a
 * UI test on a machine with a display.
 *
 * Key *up* is ignored on purpose: a shortcut that fires on both edges of one press navigates twice.
 */
fun shortcutFor(
    key: Key,
    type: KeyEventType,
    alt: Boolean = false,
    ctrl: Boolean = false,
    meta: Boolean = false,
): AppShortcut? {
    if (type != KeyEventType.KeyDown) return null
    return when {
        key == Key.Escape -> AppShortcut.Dismiss
        key == Key.F5 -> AppShortcut.Refresh
        key == Key.R && (ctrl || meta) -> AppShortcut.Refresh
        key == Key.DirectionLeft && alt -> AppShortcut.Back
        // The dedicated "browser back" key some keyboards and mice send.
        key == Key.Back -> AppShortcut.Back
        else -> null
    }
}

/** What Escape does, given what is open. */
enum class EscapeAction { CloseOverlay, GoBack, None }

/**
 * Escape's precedence rule, as a value.
 *
 * The ordering is the requirement — a dialog closes *before* anything navigates — and expressing
 * it as a pure function is what lets a test assert that an open confirmation swallows the key,
 * rather than that assertion depending on how a platform dialog happens to route key events.
 */
fun escapeAction(hasOpenOverlay: Boolean, canGoBack: Boolean): EscapeAction = when {
    hasOpenOverlay -> EscapeAction.CloseOverlay
    canGoBack -> EscapeAction.GoBack
    else -> EscapeAction.None
}

fun shortcutFor(event: KeyEvent): AppShortcut? = shortcutFor(
    key = event.key,
    type = event.type,
    alt = event.isAltPressed,
    ctrl = event.isCtrlPressed,
    meta = event.isMetaPressed,
)
