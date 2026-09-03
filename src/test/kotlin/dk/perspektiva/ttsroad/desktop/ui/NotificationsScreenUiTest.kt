package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationsResponse
import dk.perspektiva.ttsroad.desktop.data.NotificationChapter
import dk.perspektiva.ttsroad.desktop.data.NotificationFiction
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test

/**
 * The screen draws two things that look alike and mean opposite things.
 *
 * A chapter that is coming and a chapter that has arrived sit in the same list, so what is asserted
 * here is mostly which controls each one is allowed to offer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun notice(id: Int, state: String) = ChapterNotification(
        id = id,
        state = state,
        dismissible = state == "ready",
        playable = state == "ready",
        fiction = NotificationFiction(id = 7, title = "A Test Serial"),
        chapter = NotificationChapter(id = 100 + id, title = "Chapter $id", chapterNumber = id, ttsProgress = 62),
    )

    private fun screen(vararg notifications: ChapterNotification): FakeRepository {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(
                ChapterNotificationsResponse(
                    notifications = notifications.toList(),
                    unread = notifications.size,
                    ready = notifications.count { it.playable },
                ),
            )
        }
        val holder = ChapterNotificationsStateHolder(repository, Dispatchers.Main.immediate)
        compose.setContent {
            TtsRoadTheme {
                NotificationsScreen(holder, repository, onPlay = {}, onOpenFiction = {})
            }
        }
        compose.waitForIdle()
        return repository
    }

    @Test
    fun `a converting chapter offers neither Play nor Dismiss`() {
        screen(notice(1, "pulled"))

        compose.onNodeWithTag(NotificationRowTestTag).assertIsDisplayed()
        // The row says what is happening; it does not offer to throw away the only record of it.
        compose.onNodeWithText("CHAPTER 1  ·  CONVERTING 62%").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(NotificationDismissTestTag).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(NotificationPlayTestTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `a ready chapter offers both, and clearing says how many are ready`() {
        val repository = screen(notice(1, "ready"), notice(2, "pulled"))

        compose.onNodeWithTag(NotificationPlayTestTag).assertIsDisplayed()
        // "the 1 ready", never "all": the converting one is what is being waited for.
        compose.onNodeWithText("CLEAR THE 1 READY").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(1, repository.dismissReadCalls)
    }

    @Test
    fun `a server without the route says so instead of showing an empty list`() {
        val repository = FakeRepository().apply { chapterNotificationsResult = Result.success(null) }
        val holder = ChapterNotificationsStateHolder(repository, Dispatchers.Main.immediate)
        compose.setContent {
            TtsRoadTheme {
                NotificationsScreen(holder, repository, onPlay = {}, onOpenFiction = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("THIS SERVER CANNOT REPORT NEW CHAPTERS").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(NotificationRowTestTag).fetchSemanticsNodes().isEmpty())
    }
}
