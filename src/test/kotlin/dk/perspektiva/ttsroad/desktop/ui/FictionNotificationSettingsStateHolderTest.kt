package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationSettingsRequest
import dk.perspektiva.ttsroad.desktop.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.desktop.data.NotificationModeEvery
import dk.perspektiva.ttsroad.desktop.data.NotificationModeOff
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FictionNotificationSettingsStateHolderTest {

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
    fun `opening refreshes and visible state ticks every minute`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings()),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        assertTrue(holder.state.value.visible)
        assertEquals(NotificationModeBacklog, holder.state.value.settings?.mode)
        assertEquals("2", holder.state.value.draft?.hours)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(NotificationModeBacklog, holder.state.value.settings?.mode)

        holder.close()
        assertFalse(holder.state.value.visible)
        holder.clear()
    }

    @Test
    fun `no save from failed initial load`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.failure(java.io.IOException("Network down")),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        assertNull(holder.state.value.settings)
        assertFalse(holder.state.value.canSave)
        assertNotNull(holder.state.value.loadError)
        holder.clear()
    }

    @Test
    fun `saved state independent from draft and dirty tracking works`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, 2.0)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        assertFalse(holder.state.value.dirty)
        holder.setHours("5")
        assertTrue(holder.state.value.dirty)
        assertEquals(2.0, holder.state.value.settings?.backlogHours)
        assertEquals("5", holder.state.value.draft?.hours)
        holder.clear()
    }

    @Test
    fun `switching off ignores hidden invalid hours and can save`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, 2.0)),
            updateFictionNotificationSettingsResult = Result.success(settings(NotificationModeOff, 2.0)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        holder.setHours("invalid-hours")
        assertFalse(holder.state.value.canSave)

        holder.setMode(NotificationModeOff)
        assertTrue(holder.state.value.canSave)

        holder.save()
        runCurrent()

        assertEquals(NotificationModeOff, holder.state.value.settings?.mode)
        assertEquals(1, repo.fictionNotificationSettingsRequests.size)
        assertEquals(NotificationModeOff, repo.fictionNotificationSettingsRequests.single().second.mode)
        assertEquals(2.0, repo.fictionNotificationSettingsRequests.single().second.backlogHours)
        holder.clear()
    }

    @Test
    fun `stale GET never overwrites new save or dirty draft`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeEvery, 2.0)),
            updateFictionNotificationSettingsResult = Result.success(settings(NotificationModeBacklog, 5.0)),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        holder.setMode(NotificationModeBacklog)
        holder.setHours("5")
        assertTrue(holder.state.value.dirty)

        repo.fictionNotificationSettingsResult = Result.success(settings(NotificationModeEvery, 1.0))
        holder.refresh()
        runCurrent()

        // Draft was dirty, so refreshed settings are recorded but dirty draft mode/hours are preserved
        assertEquals("5", holder.state.value.draft?.hours)
        assertEquals(NotificationModeBacklog, holder.state.value.draft?.mode)

        holder.save()
        runCurrent()
        assertEquals(NotificationModeBacklog, holder.state.value.settings?.mode)
        assertEquals(5.0, holder.state.value.settings?.backlogHours)
        holder.clear()
    }

    @Test
    fun `save failure marks stale and shows warning`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings(NotificationModeEvery, 2.0)),
            updateFictionNotificationSettingsResult = Result.failure(java.io.IOException("Timeout")),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        holder.setMode(NotificationModeOff)
        holder.save()
        runCurrent()

        assertTrue(holder.state.value.stale)
        assertNotNull(holder.state.value.error)
        holder.clear()
    }

    @Test
    fun `dispose cancels polling and in flight operations`() = runTest {
        val repo = FakeRepository(
            capabilitiesResult = ServerCapabilities(backlogNotifications = true),
            fictionNotificationSettingsResult = Result.success(settings()),
        )
        val holder = FictionNotificationSettingsStateHolder(
            repo,
            fictionId = 1,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            enabled = true,
        )

        holder.open()
        runCurrent()

        holder.clear()
        assertFalse(holder.state.value.visible)

        advanceTimeBy(120_000L)
        runCurrent()
        assertFalse(holder.state.value.visible)
    }
}
