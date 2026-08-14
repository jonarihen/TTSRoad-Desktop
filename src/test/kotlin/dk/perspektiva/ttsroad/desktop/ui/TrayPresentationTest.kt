package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class TrayPresentationTest {

    private val playing = PlayerUiState(
        title = "Chapter 41",
        fictionTitle = "A Test Serial",
        hasMedia = true,
        isPlaying = true,
        durationMs = 600_000,
    )

    // --- What the close control does ------------------------------------------------------------

    @Test
    fun `closing quits unless the user asked for the tray`() {
        assertEquals(
            WindowCloseIntent.Quit,
            windowCloseIntent(closeToTray = false, traySupported = true),
        )
        assertEquals(
            WindowCloseIntent.HideToTray,
            windowCloseIntent(closeToTray = true, traySupported = true),
        )
    }

    @Test
    fun `a desktop with no tray closes even when the preference says otherwise`() {
        // The failure this prevents is the worst one available: a hidden window, a playing process,
        // and no icon anywhere to reach either of them from.
        assertEquals(
            WindowCloseIntent.Quit,
            windowCloseIntent(closeToTray = true, traySupported = false),
        )
    }

    // --- What the tray says ---------------------------------------------------------------------

    @Test
    fun `the tooltip names the chapter, the serial and whether it is running`() {
        assertEquals("TTSRoad — Playing: Chapter 41 · A Test Serial", trayTooltip(playing))
        assertEquals("TTSRoad — Paused: Chapter 41 · A Test Serial", trayTooltip(playing.copy(isPlaying = false)))
    }

    @Test
    fun `with nothing loaded the tooltip is just the app name`() {
        // Not "TTSRoad — nothing playing": a tray tooltip that reports absence is noise.
        assertEquals("TTSRoad", trayTooltip(PlayerUiState()))
    }

    @Test
    fun `a chapter with no serial behind it still names itself`() {
        assertEquals("Chapter 41", trayNowPlayingLabel(playing.copy(fictionTitle = null)))
        assertEquals("TTSRoad — Playing: Chapter 41", trayTooltip(playing.copy(fictionTitle = null)))
    }

    @Test
    fun `there is no now-playing entry to draw before anything has loaded`() {
        // An empty tooltip simply does not appear; an empty menu entry is a blank clickable row.
        assertNull(trayNowPlayingLabel(PlayerUiState()))
    }

    @Test
    fun `the play-pause entry names the action rather than the state`() {
        assertEquals("Pause", trayPlayPauseLabel(playing))
        assertEquals("Play", trayPlayPauseLabel(playing.copy(isPlaying = false)))
    }
}
