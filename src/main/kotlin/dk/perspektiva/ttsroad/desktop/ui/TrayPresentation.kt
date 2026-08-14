package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.player.PlayerUiState

/**
 * What pressing the window's close control actually does.
 *
 * A value rather than a branch inside `onCloseRequest`, because the rule has a failure mode that is
 * invisible until someone runs the app on the wrong desktop: a tray that the platform will not
 * show. Closing to a tray icon nobody can see is a process the user cannot reach and cannot quit,
 * which is strictly worse than either honest answer.
 */
enum class WindowCloseIntent {
    /** Save the placement, close the container, leave. */
    Quit,

    /** Hide the window, keep playing, keep the tray icon as the way back. */
    HideToTray,
}

/**
 * The close rule.
 *
 * Both inputs are required to say yes. The preference is the user's answer to "what should the X
 * do"; [traySupported] is the platform's answer to "can I put anything in the tray", and it is
 * false on a desktop with no system tray at all — several Wayland sessions ship without one. When
 * the platform says no, the honest behaviour is the one every window has: it closes.
 */
fun windowCloseIntent(closeToTray: Boolean, traySupported: Boolean): WindowCloseIntent =
    if (closeToTray && traySupported) WindowCloseIntent.HideToTray else WindowCloseIntent.Quit

/**
 * The tray icon's hover text.
 *
 * The same audiobook mapping `MprisState` uses — the chapter is the title and the serial is what it
 * belongs to — because a tray tooltip and a media applet are answering the same question, and two
 * different answers on the same desktop would look like a bug in one of them.
 *
 * Not "TTSRoad — nothing playing": the app name alone already says which icon this is, and a
 * tooltip that reports absence is noise on a tray whose whole purpose is to be glanceable.
 */
fun trayTooltip(player: PlayerUiState, appName: String = "TTSRoad"): String {
    val chapter = trayNowPlayingLabel(player) ?: return appName
    val state = if (player.isPlaying) "Playing" else "Paused"
    return "$appName — $state: $chapter"
}

/** The label of the tray's play/pause entry, which has to name the *action*, not the state. */
fun trayPlayPauseLabel(player: PlayerUiState): String = if (player.isPlaying) "Pause" else "Play"

/**
 * The chapter line at the top of the tray menu, or null when there is nothing to name.
 *
 * Kept out of [trayTooltip] because a menu entry and a tooltip fail differently: an empty tooltip
 * is a tooltip that does not appear, while an empty menu entry is a blank clickable row.
 */
fun trayNowPlayingLabel(player: PlayerUiState): String? {
    if (!player.hasSession) return null
    val chapter = player.title.takeIf { it.isNotBlank() } ?: return null
    val serial = player.fictionTitle?.takeIf { it.isNotBlank() } ?: return chapter
    return "$chapter · $serial"
}
