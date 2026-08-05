package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.QueueItem
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose desktop smoke test for the player, driven entirely by a [FakePlaybackController] — no
 * audio device, no network, no session.
 *
 * These use the JUnit 4 `createComposeRule()` API (ui-test-junit4) and therefore run through the
 * JUnit Vintage engine. They need a display; CI runs them under Xvfb.
 */
class PlayerScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun playingState() = PlayerUiState(
        title = "Chapter 3 — The Descent",
        fictionTitle = "A Test Serial",
        isPlaying = true,
        hasMedia = true,
        positionMs = 412_500,
        durationMs = 1_200_000,
        queue = listOf(
            QueueItem(101, "Chapter 3 — The Descent"),
            QueueItem(102, "Chapter 4"),
        ),
        currentIndex = 0,
        hasNext = true,
        hasPrevious = false,
    )

    @Test
    fun `the player renders the current chapter and fiction`() {
        val playback = FakePlaybackController(playingState())
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        // The chapter title appears twice — once as the heading, once as the current queue row.
        compose.onAllNodesWithText("Chapter 3 — The Descent").onFirst().assertIsDisplayed()
        // MetaText uppercases its content.
        compose.onNodeWithText("A TEST SERIAL").assertIsDisplayed()
    }

    @Test
    fun `the transport button reflects the playing state and drives the controller`() {
        val playback = FakePlaybackController(playingState())
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        // Playing -> the button offers Pause.
        compose.onNodeWithContentDescription("Pause").assertHasClickAction().performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("togglePlayPause"), "calls were ${playback.calls}")
        // The fake flipped isPlaying, so the UI must now offer Play.
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun `skip controls call through to the controller`() {
        val playback = FakePlaybackController(playingState())
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        compose.onNodeWithContentDescription("Back 30 seconds").performClick()
        compose.onNodeWithContentDescription("Next chapter").performClick()
        compose.onNodeWithContentDescription("Previous chapter").performClick()
        compose.waitForIdle()

        assertEquals(listOf("skipBy(30000)", "skipBy(-30000)", "next", "previous"), playback.calls)
    }

    @Test
    fun `the up-next panel lists the queue and jumps on click`() {
        val playback = FakePlaybackController(playingState())
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onNodeWithText("// UP NEXT").assertIsDisplayed()
        compose.onNodeWithText("Chapter 4").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("queueIndex(1)"), "calls were ${playback.calls}")
    }

    @Test
    fun `a playback error is shown instead of the buffering hint`() {
        val playback = FakePlaybackController(
            PlayerUiState(
                title = "Chapter 3",
                hasMedia = false,
                error = "Failed to download audio (HTTP 401)",
            ),
        )
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onNodeWithText("Failed to download audio (HTTP 401)").assertIsDisplayed()
    }

    @Test
    fun `the now-playing bar expands to the full player`() {
        val playback = FakePlaybackController(playingState())
        var expanded = false
        compose.setContent { TtsRoadTheme { NowPlayingBar(playback) { expanded = true } } }

        compose.onNodeWithText("Chapter 3 — The Descent").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertTrue(expanded, "clicking the bar's track info should expand the player")
    }
}
