package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferences
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
    fun `a rate that belongs to one book offers the way back to the default`() {
        val playback = FakePlaybackController(
            playingState().copy(canChangeSpeed = true, speed = 1.5f, speedIsPerFiction = true),
        )
        compose.setContent {
            TtsRoadTheme {
                PlayerScreen(
                    playback,
                    onBack = {},
                    preferences = InMemoryPlaybackPreferencesStore(PlaybackPreferences(speed = 1.25f)),
                )
            }
        }

        // Naming the default is the point: "use the default" without saying what it is asks the
        // listener to remember a number from another screen.
        compose.onNodeWithTag(SpeedDefaultChipTestTag).assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("clearFictionSpeed"), "calls were ${playback.calls}")
    }

    @Test
    fun `a book following the default is not offered a way back to it`() {
        // A row that always carried the entry would imply an override exists whenever a book is open.
        val playback = FakePlaybackController(playingState().copy(canChangeSpeed = true, speed = 1.25f))
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onAllNodesWithTag(SpeedDefaultChipTestTag).assertCountEquals(0)
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
    fun `the capable player opens the current chapter in the reader`() {
        val playback = FakePlaybackController(playingState())
        var opened: Pair<Int, String>? = null
        compose.setContent {
            TtsRoadTheme {
                PlayerScreen(
                    playback,
                    readAlongAvailable = true,
                    onOpenReader = { id, title -> opened = id to title },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("READ ALONG").assertHasClickAction().performClick()

        assertEquals(101 to "Chapter 3 — The Descent", opened)
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
fun `the now-playing bar honours the configured skip interval`() {
        // Regression: the bar hard-coded 30 seconds while the full player and reading mode read the
        // preference, so the same-looking control jumped twice as far depending on which surface
        // happened to be on screen.
        val playback = FakePlaybackController(playingState().copy(skipIntervalMs = 15_000))
        compose.setContent { TtsRoadTheme { NowPlayingBar(playback) {} } }

        compose.onNodeWithContentDescription("Back 15 seconds").assertIsDisplayed()
        compose.onNodeWithContentDescription("Forward 15 seconds").assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back 30 seconds").assertDoesNotExist()
        assertEquals(1, playback.calls.size, "calls were ${playback.calls}")
    }

    @Test
    fun `speed presets announce as a radio group with a selected option`() {
        // Regression: the presets were plain `Text.clickable`, so a screen reader read them as
        // unrelated pieces of text with nothing to say which was in force, and the only visual cue
        // for the current one was the accent colour.
        val playback = FakePlaybackController(playingState().copy(speed = 1.5f, canChangeSpeed = true))
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onNodeWithContentDescription("Speed 1.5x").assertIsSelected()
        compose.onNodeWithContentDescription("Speed 1x").assertIsNotSelected()
    }

    @Test
    fun `transport labels are on the node that is actually clickable`() {
        // The description used to sit on the child Icon while the outer Box carried the click, so
        // the node a screen reader lands on announced nothing and declared no role.
        val playback = FakePlaybackController(playingState())
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }

        compose.onNodeWithContentDescription("Pause").assertHasClickAction()
        compose.onNodeWithContentDescription("Next chapter").assertHasClickAction()
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
