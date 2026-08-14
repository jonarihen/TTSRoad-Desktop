package dk.perspektiva.ttsroad.desktop.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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

    /** Space. */
    PlayPause,

    /** Left — back by the configured skip interval. */
    SeekBackward,

    /** Right — forward by the configured skip interval. */
    SeekForward,

    /** Ctrl+Left. */
    PreviousChapter,

    /** Ctrl+Right. */
    NextChapter,

    /** Ctrl+L. */
    OpenLibrary,

    /** Ctrl+comma — the platform convention for preferences. */
    OpenSettings,

    /**
     * Ctrl+B — mark the spot that is playing.
     *
     * Safe mid-word, and checked against the one text field that could plausibly claim it: the
     * library's search box is a plain `OutlinedTextField`, which has no bold to toggle. So this is
     * a combination no text field takes, and it stays live while typing like Ctrl+L and F5.
     */
    AddBookmark,

    /** Ctrl+Shift+B — the list of them. */
    OpenBookmarks,

    /**
     * F11 — distraction-free reading.
     *
     * Only the reader acts on it; everywhere else it is a key that does nothing, which is a better
     * answer than a mode that appears on a screen with no text in it. F11 is the platform habit for
     * "get the frame out of the way", and no text field claims it.
     */
    ToggleReadingMode,

    /** F1 or Ctrl+slash. */
    ShowShortcuts,
    ;

    /**
     * Whether this shortcut may fire while a text field has focus.
     *
     * The distinction is the whole requirement: Space, the arrows and Ctrl+arrow are all *text
     * editing* keys, and a search box that pauses the audiobook instead of typing a space is
     * broken in a way that is hard to even describe as a bug report. The rest are combinations no
     * text field claims, and they stay live so F5 still refreshes from inside the search box.
     *
     * `App` enforces this structurally as well — the two groups are installed on the preview and
     * the ordinary key handler respectively — but the classification is here so it can be asserted
     * without a focused text field and a real toolkit event.
     */
    val firesWhileTyping: Boolean
        get() = when (this) {
            Back, Refresh, Dismiss, OpenLibrary, OpenSettings, ShowShortcuts,
            AddBookmark, OpenBookmarks, ToggleReadingMode,
            -> true
            PlayPause, SeekBackward, SeekForward, PreviousChapter, NextChapter -> false
        }
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
 *
 * [textInputFocused] suppresses the editing-key shortcuts; see [AppShortcut.firesWhileTyping].
 */
fun shortcutFor(
    key: Key,
    type: KeyEventType,
    alt: Boolean = false,
    ctrl: Boolean = false,
    meta: Boolean = false,
    shift: Boolean = false,
    textInputFocused: Boolean = false,
): AppShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val match = match(key, alt, ctrl, meta, shift) ?: return null
    if (textInputFocused && !match.firesWhileTyping) return null
    return match
}

private fun match(key: Key, alt: Boolean, ctrl: Boolean, meta: Boolean, shift: Boolean): AppShortcut? {
    // The accelerator modifier: Ctrl everywhere, Cmd on macOS. Both are accepted on both, because
    // guessing the platform from a key event is worse than accepting one extra combination.
    val accel = ctrl || meta
    return when {
        key == Key.Escape -> AppShortcut.Dismiss
        key == Key.F5 -> AppShortcut.Refresh
        key == Key.F1 -> AppShortcut.ShowShortcuts
        key == Key.F11 -> AppShortcut.ToggleReadingMode
        key == Key.R && accel -> AppShortcut.Refresh
        key == Key.L && accel -> AppShortcut.OpenLibrary
        key == Key.Comma && accel -> AppShortcut.OpenSettings
        key == Key.Slash && accel -> AppShortcut.ShowShortcuts

        // The list is checked before the bare mark, so Ctrl+Shift+B does not also bookmark.
        key == Key.B && accel && shift -> AppShortcut.OpenBookmarks
        key == Key.B && accel -> AppShortcut.AddBookmark

        // Chapter stepping is checked before plain seeking: Ctrl+Left is a chapter, not a skip.
        key == Key.DirectionLeft && accel -> AppShortcut.PreviousChapter
        key == Key.DirectionRight && accel -> AppShortcut.NextChapter

        // Alt+Left stays Back, and is therefore checked before the bare arrow.
        key == Key.DirectionLeft && alt -> AppShortcut.Back
        // The dedicated "browser back" key some keyboards and mice send.
        key == Key.Back -> AppShortcut.Back

        key == Key.DirectionLeft && !shift -> AppShortcut.SeekBackward
        key == Key.DirectionRight && !shift -> AppShortcut.SeekForward
        key == Key.Spacebar && !accel && !alt -> AppShortcut.PlayPause

        // The keys a keyboard's transport row sends when the app itself has focus. With no focus
        // they go to the desktop, which routes them over MPRIS instead — same actions, other door.
        key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause ->
            AppShortcut.PlayPause
        key == Key.MediaNext -> AppShortcut.NextChapter
        key == Key.MediaPrevious -> AppShortcut.PreviousChapter

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

fun shortcutFor(event: KeyEvent, textInputFocused: Boolean = false): AppShortcut? = shortcutFor(
    key = event.key,
    type = event.type,
    alt = event.isAltPressed,
    ctrl = event.isCtrlPressed,
    meta = event.isMetaPressed,
    shift = event.isShiftPressed,
    textInputFocused = textInputFocused,
)

/** One row of the in-app shortcuts dialog. */
data class ShortcutHelp(val keys: String, val action: String)

/**
 * The shortcut table as the help dialog shows it.
 *
 * Written out rather than derived from [shortcutFor], because the dialog has to name the
 * *alternatives* too, and a listing generated from the matcher would either miss them or read like
 * a decompiled `when`. Kept next to the matcher so the two are edited together.
 */
val ShortcutHelpTable: List<ShortcutHelp> = listOf(
    ShortcutHelp("Space", "Play or pause"),
    ShortcutHelp("Left / Right", "Skip back or forward by your skip interval"),
    ShortcutHelp("Ctrl+Left / Ctrl+Right", "Previous or next chapter"),
    ShortcutHelp("Alt+Left", "Back"),
    ShortcutHelp("Ctrl+L", "Library"),
    ShortcutHelp("Ctrl+B", "Bookmark this spot"),
    ShortcutHelp("Ctrl+Shift+B", "Your bookmarks"),
    ShortcutHelp("F11", "Distraction-free reading, in the reader"),
    ShortcutHelp("Ctrl+,", "Settings"),
    ShortcutHelp("F5 / Ctrl+R", "Refresh the current screen"),
    ShortcutHelp("Escape", "Close a dialog, or go back"),
    ShortcutHelp("F1 / Ctrl+/", "This list"),
    ShortcutHelp("Media keys", "Play/pause, previous and next, also from the desktop"),
    ShortcutHelp("Tab", "Move between controls"),
)
