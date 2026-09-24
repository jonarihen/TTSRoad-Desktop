package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.desktop.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.desktop.data.NotificationModeEvery
import dk.perspektiva.ttsroad.desktop.data.NotificationModeOff
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.notificationStatusLabel
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FictionNotificationSettingsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun settings(
        mode: String = NotificationModeBacklog,
        hours: Double = 2.0,
        armed: Boolean? = true,
        remaining: Double = 3600.0,
    ) = FictionNotificationSettings(
        mode = mode,
        backlogHours = hours,
        backlogArmed = armed,
        remainingSeconds = remaining,
    )

    @Test
    fun `status labels map Armed, Waiting, Off, Every chapter and Status unavailable correctly`() {
        assertEquals("Armed", notificationStatusLabel(settings(NotificationModeBacklog, armed = true)))
        assertEquals("Waiting", notificationStatusLabel(settings(NotificationModeBacklog, armed = false)))
        assertEquals("Status unavailable", notificationStatusLabel(settings(NotificationModeBacklog, armed = null)))
        assertEquals("Every chapter", notificationStatusLabel(settings(NotificationModeEvery)))
        assertEquals("Off", notificationStatusLabel(settings(NotificationModeOff)))
        assertEquals("Status unavailable", notificationStatusLabel(null))
    }

    @Test
    fun `dialog displays authoritative status and ready to listen 1x even when zero`() {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, armed = true, remaining = 0.0)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = Dispatchers.Main.immediate,
            enabled = true,
        )
        holder.open()

        compose.setContent {
            TtsRoadTheme {
                FictionNotificationSettingsDialog(holder)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(FictionNotificationDialogTestTag).assertIsDisplayed()
        compose.onNodeWithTag(FictionNotificationStatusTestTag).assertIsDisplayed()
        compose.onNodeWithText("Armed").assertIsDisplayed()
        compose.onNodeWithText("Backlog alert armed.").assertIsDisplayed()
        compose.onNodeWithText("Ready to listen (1x): 0 min").assertIsDisplayed()
    }

    @Test
    fun `modes and hour choices update draft and allow saving`() {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeEvery, 2.0)),
            updateFictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, 5.0, armed = true)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = Dispatchers.Main.immediate,
            enabled = true,
        )
        holder.open()

        compose.setContent {
            TtsRoadTheme {
                FictionNotificationSettingsDialog(holder)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("BACKLOG ALERT").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("5H").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Not saved yet.").assertIsDisplayed()
        compose.onNodeWithTag(FictionNotificationSaveTestTag).assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(1, repo.fictionNotificationSettingsRequests.size)
        assertEquals(5.0, repo.fictionNotificationSettingsRequests.single().second.backlogHours)
        assertEquals(NotificationModeBacklog, repo.fictionNotificationSettingsRequests.single().second.mode)
    }

    @Test
    fun `custom hours field validates input and disables save on invalid input`() {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, 2.0)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = Dispatchers.Main.immediate,
            enabled = true,
        )
        holder.open()

        compose.setContent {
            TtsRoadTheme {
                FictionNotificationSettingsDialog(holder)
            }
        }
        compose.waitForIdle()

        val hoursField = compose.onNodeWithTag(FictionNotificationHoursTestTag)
        hoursField.assertIsDisplayed()

        // Invalid hour: 0
        holder.setHours("0")
        compose.waitForIdle()
        compose.onNodeWithTag(FictionNotificationSaveTestTag).assertIsNotEnabled()

        // Invalid hour: 1001
        holder.setHours("1001")
        compose.waitForIdle()
        compose.onNodeWithTag(FictionNotificationSaveTestTag).assertIsNotEnabled()

        // Valid hour: 3.5
        holder.setHours("3.5")
        compose.waitForIdle()
        compose.onNodeWithTag(FictionNotificationSaveTestTag).assertIsEnabled()
    }
}
