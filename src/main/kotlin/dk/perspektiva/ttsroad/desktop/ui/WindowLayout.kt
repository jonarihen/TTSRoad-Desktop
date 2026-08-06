package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.WindowPlacements

/** The supported minimum window size, in the same numbers the window itself is clamped to. */
val MinWindowWidth: Dp = WindowPlacements.MinWidth.dp
val MinWindowHeight: Dp = WindowPlacements.MinHeight.dp

/**
 * How much horizontal room a layout has to work with.
 *
 * Three classes rather than a pile of ad-hoc `if (maxWidth < 780.dp)` checks scattered through the
 * screens: the thresholds are declared once, named, and testable, so "narrow" means the same thing
 * in Settings, in the player and in the library.
 *
 * - [Compact]  — below 900 dp. One content column. Side panels become stacked sections; secondary
 *                labels that would otherwise squeeze the transport controls are dropped.
 * - [Medium]   — 900–1280 dp. Two panes fit; the grid gets a few columns.
 * - [Expanded] — above 1280 dp. Content is capped at [ContentMaxWidth] and centred rather than
 *                stretched across an ultrawide display.
 */
enum class WindowSizeClass { Compact, Medium, Expanded }

/** Upper bound (exclusive) of [WindowSizeClass.Compact]. */
val CompactWidthMax: Dp = 900.dp

/** Upper bound (exclusive) of [WindowSizeClass.Medium]. */
val MediumWidthMax: Dp = 1280.dp

fun windowSizeClassFor(width: Dp): WindowSizeClass = when {
    width < CompactWidthMax -> WindowSizeClass.Compact
    width < MediumWidthMax -> WindowSizeClass.Medium
    else -> WindowSizeClass.Expanded
}

val WindowSizeClass.isCompact: Boolean get() = this == WindowSizeClass.Compact
