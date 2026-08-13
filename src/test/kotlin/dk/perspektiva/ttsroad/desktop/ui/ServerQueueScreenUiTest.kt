package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.data.ServerQueueAction
import dk.perspektiva.ttsroad.desktop.data.ServerQueueResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The queue screen as a user meets it.
 *
 * The holder's state machine is covered in [ServerQueueStateHolderTest]; what is asserted here is
 * that a cross-fiction queue actually renders both books, that Clear is behind a confirmation, and
 * that the row actions carry the descriptions a screen reader and a keyboard user need.
 */
@OptIn(ExperimentalTestApi::class)
class ServerQueueScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a cross-library queue shows every row and names both fictions`() {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag(ServerQueueRowTestTag).assertCountEquals(3)
        compose.onNodeWithText("A Practical Guide to Sorcery").assertIsDisplayed()
        compose.onNodeWithText("MOTHER OF LEARNING").assertIsDisplayed()
    }

    @Test
    fun `an empty queue explains how to fill it instead of showing nothing`() {
        val repository = FakeRepository(queueResult = Result.success(ServerQueueResponse()))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("NOTHING QUEUED").assertIsDisplayed()
    }

    /** A server with no queue API says so, rather than showing an empty list with dead actions. */
    @Test
    fun `an unsupported server says the feature is absent`() {
        val repository = FakeRepository(queueResult = Result.success(null))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("NO SHARED QUEUE ON THIS SERVER").assertIsDisplayed()
        compose.onAllNodesWithTag(ServerQueueRowTestTag).assertCountEquals(0)
    }

    @Test
    fun `clearing the queue is behind a confirmation`() {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("CLEAR QUEUE", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(repository.queueRequests.isEmpty(), "the dialog must come first")

        compose.onNodeWithText("Clear the queue?").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()

        assertTrue(repository.queueRequests.isEmpty(), "cancel clears nothing")
    }

    @Test
    fun `confirming the clear posts it`() {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("CLEAR QUEUE", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("CLEAR").performClick()
        compose.waitForIdle()

        assertEquals(ServerQueueAction.Clear, repository.queueRequests.single().action)
    }

    /** Row actions are reachable by description, which is what a screen reader announces. */
    @Test
    fun `row actions carry accessible descriptions`() {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play A Practical Guide to Sorcery").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove A Practical Guide to Sorcery from the queue")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Move Interlude - The Titan up").assertIsDisplayed()
    }

    @Test
    fun `removing a row addresses its queue row id`() {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Remove A Practical Guide to Sorcery from the queue")
            .performClick()
        compose.waitForIdle()

        val request = repository.queueRequests.single()
        assertEquals(ServerQueueAction.Remove, request.action)
        assertEquals(listOf(4801), request.itemIds)
    }

    @Test
    fun `playing a row starts it through the player rather than the queue endpoint`() {
        val repository = FakeRepository(
            chaptersResult = Result.success(ParsedFixtures.chapters),
            queueResult = Result.success(ParsedFixtures.queue),
        )
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                ServerQueueScreen(
                    holder = ServerQueueStateHolder(repository, playback),
                    playback = playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play A Practical Guide to Sorcery").performClick()
        compose.waitForIdle()

        assertEquals(listOf("playQueue(101)"), playback.calls)
        assertTrue(repository.queueRequests.isEmpty(), "advance must never be called")
    }
}
