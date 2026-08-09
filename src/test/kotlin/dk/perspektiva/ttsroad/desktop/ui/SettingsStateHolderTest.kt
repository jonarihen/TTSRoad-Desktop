package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.data.DeviceSession
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.download.OfflineFictionUsage
import dk.perspektiva.ttsroad.desktop.download.OfflineStorageController
import dk.perspektiva.ttsroad.desktop.download.OfflineStorageSummary
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The settings/device-session state machine.
 *
 * Every state the issue asks the UI to have — supported, unsupported, loading, error, empty — and
 * both answers to both confirmation dialogs are decided here rather than in the composable, which
 * is why they can be asserted without a display.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateHolderTest {

    private class FakeOfflineStorage(
        var current: OfflineStorageSummary = OfflineStorageSummary(available = true),
    ) : OfflineStorageController {
        var summaryCalls = 0
        var deleteCalls = 0
        var clearCalls = 0

        override fun summary(): OfflineStorageSummary {
            summaryCalls++
            return current
        }

        override suspend fun deleteAllDownloads(): Long {
            deleteCalls++
            val bytes = current.downloadBytes
            current = current.copy(downloadBytes = 0, downloadedChapters = 0, fictions = emptyList())
            return bytes
        }

        override suspend fun clearStreamingCache(): Long {
            clearCalls++
            val bytes = current.streamingCacheBytes
            current = current.copy(streamingCacheBytes = 0, streamingCacheFiles = 0)
            return bytes
        }
    }

    private val signedIn = SessionState(
        serverUrl = "https://ttsroad.example.com/",
        token = "ttsr_token",
        username = "admin",
        deviceId = 42,
    )

    private val capable = ServerCapabilities(
        serverName = "Perspektiva TTSRoad",
        serverVersion = "1.4.0",
        deviceManagement = true,
    )

    private fun TestScope.holder(
        repository: FakeRepository,
        store: InMemorySessionStore = InMemorySessionStore(signedIn),
        offline: OfflineStorageController = FakeOfflineStorage(),
    ): SettingsStateHolder {
        // Publishing capabilities is what a real sign-in does; the gate reads them from here.
        val holder = SettingsStateHolder(
            repository,
            store,
            UnconfinedTestDispatcher(testScheduler),
            offline,
        )
        return holder
    }

    private suspend fun FakeRepository.publish(capabilities: ServerCapabilities) {
        capabilitiesResult = capabilities
        refreshCurrentCapabilities(forceRefresh = true)
    }

    // --- Sections ----------------------------------------------------------------------------

    @Test
    fun `the account pane is where settings opens`() = runTest {
        val holder = holder(FakeRepository())

        assertEquals(SettingsSection.Account, holder.state.value.section)
        holder.clear()
    }

    @Test
    fun `selecting a pane keeps whatever is already loaded`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        repository.publish(capable)
        val holder = holder(repository)

        holder.openSection(SettingsSection.Devices)
        holder.ensureDevicesLoaded()
        runCurrent()
        holder.openSection(SettingsSection.About)
        holder.openSection(SettingsSection.Devices)
        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(3, holder.state.value.devices.loaded?.size)
        assertEquals(1, repository.devicesCalls, "re-entering a pane must not refetch")
        holder.clear()
    }

    // --- Offline storage ----------------------------------------------------------------------

    @Test
    fun `offline totals load once and retain their per-fiction breakdown`() = runTest {
        val offline = FakeOfflineStorage(
            OfflineStorageSummary(
                available = true,
                downloadBytes = 4096,
                downloadedChapters = 2,
                streamingCacheBytes = 1024,
                streamingCacheFiles = 1,
                fictions = listOf(OfflineFictionUsage(7, "A Serial", 4096, 2)),
            ),
        )
        val holder = holder(FakeRepository(), offline = offline)

        holder.ensureOfflineLoaded()
        runCurrent()
        holder.ensureOfflineLoaded()
        runCurrent()

        assertEquals(1, offline.summaryCalls)
        assertEquals("A Serial", holder.state.value.offline.loaded?.fictions?.single()?.title)
        holder.clear()
    }

    @Test
    fun `download deletion and cache clearing are separate confirmed actions`() = runTest {
        val offline = FakeOfflineStorage(
            OfflineStorageSummary(
                available = true,
                downloadBytes = 4096,
                downloadedChapters = 2,
                streamingCacheBytes = 1024,
                streamingCacheFiles = 1,
                fictions = listOf(OfflineFictionUsage(7, "A Serial", 4096, 2)),
            ),
        )
        val holder = holder(FakeRepository(), offline = offline)
        holder.ensureOfflineLoaded()
        runCurrent()

        holder.askClearStreamingCache()
        assertIs<SettingsConfirmation.ClearStreamingCache>(holder.state.value.confirmation)
        holder.confirm()
        runCurrent()

        assertEquals(1, offline.clearCalls)
        assertEquals(0, offline.deleteCalls)
        assertEquals(0L, holder.state.value.offline.loaded?.streamingCacheBytes)
        assertEquals(4096L, holder.state.value.offline.loaded?.downloadBytes)

        holder.askDeleteAllDownloads()
        assertIs<SettingsConfirmation.DeleteAllDownloads>(holder.state.value.confirmation)
        holder.confirm()
        runCurrent()

        assertEquals(1, offline.deleteCalls)
        assertEquals(0L, holder.state.value.offline.loaded?.downloadBytes)
        holder.clear()
    }

    @Test
    fun `ending the session drops account-protected offline titles from settings`() = runTest {
        val offline = FakeOfflineStorage(
            OfflineStorageSummary(
                available = true,
                downloadBytes = 1,
                fictions = listOf(OfflineFictionUsage(7, "Private serial", 1, 1)),
            ),
        )
        val holder = holder(FakeRepository(), offline = offline)
        holder.ensureOfflineLoaded()
        runCurrent()

        holder.sessionEnded()

        assertNull(holder.state.value.offline.loaded)
        holder.clear()
    }

    @Test
    fun `storage sizes use binary units without locale drift`() {
        assertEquals("0 B", formatStorageBytes(0))
        assertEquals("1.0 KiB", formatStorageBytes(1024))
        assertEquals("1.5 KiB", formatStorageBytes(1536))
        assertEquals("10 MiB", formatStorageBytes(10L * 1024 * 1024))
    }

    // --- Loading / supported -----------------------------------------------------------------

    @Test
    fun `a capable server lists sessions and marks the current one`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        repository.publish(capable)
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        val devices = holder.state.value.devices
        assertFalse(devices.isLoading)
        assertFalse(devices.unsupported)
        assertNull(devices.error)
        assertEquals(3, devices.loaded?.size)
        assertEquals(42, holder.currentDevice()?.id)
        assertEquals(listOf(43, 44), holder.otherDevices().map { it.id })
        holder.clear()
    }

    @Test
    fun `the current session leads the list`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        repository.publish(capable)
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(42, holder.visibleDevices().first().id)
        holder.clear()
    }

    @Test
    fun `an account with only this session reports no others rather than an error`() = runTest {
        val repository = FakeRepository(
            devicesResult = Result.success(ParsedFixtures.devicesFrom(ServerFixtures.DEVICES_ONLY_CURRENT)),
        )
        repository.publish(capable)
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(emptyList(), holder.otherDevices())
        assertNotNull(holder.currentDevice())
        assertFalse(holder.state.value.devices.unsupported)
        holder.clear()
    }

    @Test
    fun `an empty list is an empty state, not an unsupported one`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(emptyList()))
        repository.publish(capable)
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertFalse(holder.state.value.devices.unsupported)
        assertEquals(emptyList(), holder.state.value.devices.loaded)
        assertNull(holder.currentDevice())
        holder.clear()
    }

    // --- Unsupported -------------------------------------------------------------------------

    @Test
    fun `a server that says it has no device management is never asked`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        repository.publish(capable.copy(deviceManagement = false))
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertTrue(holder.state.value.devices.unsupported)
        assertEquals(0, repository.devicesCalls, "the capability gate must save the round trip")
        holder.clear()
    }

    @Test
    fun `an undiscovered server is still asked — silence is not a no`() = runTest {
        // Baseline means discovery never answered (404, offline, a proxy). Refusing to try on that
        // basis would hide a working feature behind a failed unrelated request.
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(1, repository.devicesCalls)
        assertFalse(holder.state.value.devices.unsupported)
        holder.clear()
    }

    @Test
    fun `a 404 from the endpoint is an unsupported state, not an error screen`() = runTest {
        // The repository reports a missing endpoint as null.
        val repository = FakeRepository(devicesResult = Result.success(null))
        repository.publish(capable)
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        val devices = holder.state.value.devices
        assertTrue(devices.unsupported)
        assertNull(devices.error)
        assertNull(devices.loaded)
        holder.clear()
    }

    @Test
    fun `an unsupported server is not re-asked on every visit`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(null))
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()
        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(1, repository.devicesCalls)
        holder.clear()
    }

    // --- Errors ------------------------------------------------------------------------------

    @Test
    fun `a first-load failure becomes an error with a retry, not an endless spinner`() = runTest {
        val repository = FakeRepository(
            devicesResult = Result.failure(java.net.UnknownHostException("ttsroad.example.com")),
        )
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        val devices = holder.state.value.devices
        assertFalse(devices.isLoading)
        assertFalse(devices.isInitialLoad)
        assertNull(devices.loaded)
        assertEquals("Cannot reach that server — its address did not resolve", devices.error)
        holder.clear()
    }

    @Test
    fun `a failed refresh keeps the list that is already on screen`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        repository.devicesResult = Result.failure(IllegalStateException("server exploded"))
        holder.refreshDevices()
        runCurrent()

        val devices = holder.state.value.devices
        assertEquals("server exploded", devices.error)
        assertEquals(3, devices.loaded?.size, "a failed refresh must not blank the list")
        holder.clear()
    }

    @Test
    fun `a failure with no message still says something a user can read`() = runTest {
        val repository = FakeRepository(devicesResult = Result.failure(RuntimeException()))
        val holder = holder(repository)

        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals("Could not load device sessions", holder.state.value.devices.error)
        holder.clear()
    }

    @Test
    fun `retry after a failure loads the list`() = runTest {
        val repository = FakeRepository(devicesResult = Result.failure(IllegalStateException("nope")))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        repository.devicesResult = Result.success(ParsedFixtures.devices)
        holder.refreshDevices()
        runCurrent()

        assertNull(holder.state.value.devices.error)
        assertEquals(3, holder.state.value.devices.loaded?.size)
        holder.clear()
    }

    // --- Confirmation: revoke one ------------------------------------------------------------

    @Test
    fun `revoking one device asks first and does nothing until confirmed`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevoke(holder.otherDevices().first())
        runCurrent()

        val pending = assertIs<SettingsConfirmation.RevokeDevice>(holder.state.value.confirmation)
        assertEquals(43, pending.device.id)
        assertEquals(emptyList(), repository.revokedDevices, "asking must not revoke")
        holder.clear()
    }

    @Test
    fun `cancelling the revoke dialog revokes nothing`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevoke(holder.otherDevices().first())
        holder.dismissConfirmation()
        runCurrent()

        assertNull(holder.state.value.confirmation)
        assertEquals(emptyList(), repository.revokedDevices)
        assertEquals(1, repository.devicesCalls, "cancelling must not even refetch")
        holder.clear()
    }

    @Test
    fun `confirming the revoke calls the server and re-reads the list`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevoke(holder.otherDevices().first())
        holder.confirm()
        runCurrent()

        assertEquals(listOf(43), repository.revokedDevices)
        assertEquals(2, repository.devicesCalls, "the server decides what survived, so re-read")
        assertEquals("Signed out Pixel 9", holder.state.value.devices.notice)
        assertFalse(holder.state.value.devices.isBusy)
        holder.clear()
    }

    @Test
    fun `the current session cannot be revoked from its row, even if something asks`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevoke(requireNotNull(holder.currentDevice()))
        runCurrent()

        assertNull(holder.state.value.confirmation, "there is no dialog for signing this device out")
        assertEquals(emptyList(), repository.revokedDevices)
        holder.clear()
    }

    @Test
    fun `a revoke failure is reported inline and the list survives`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        repository.revokeResult = Result.failure(IllegalStateException("gateway timeout"))
        holder.askRevoke(holder.otherDevices().first())
        holder.confirm()
        runCurrent()

        val devices = holder.state.value.devices
        assertEquals("gateway timeout", devices.error, "the failure must survive the reload after it")
        assertEquals(3, devices.loaded?.size)
        assertNull(devices.notice)
        assertFalse(devices.isBusy)
        holder.clear()
    }

    @Test
    fun `a 404 while revoking a listed session means it was already gone, not an old server`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        repository.revokeResult = Result.success(false)
        holder.askRevoke(holder.otherDevices().first())
        holder.confirm()
        runCurrent()

        val devices = holder.state.value.devices
        assertFalse(devices.unsupported, "a server that just listed sessions clearly has the API")
        assertEquals("That session was already signed out", devices.notice)
        holder.clear()
    }

    // --- Confirmation: revoke all others -----------------------------------------------------

    @Test
    fun `revoking all other devices asks first`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevokeOtherDevices()
        runCurrent()

        assertEquals(SettingsConfirmation.RevokeOtherDevices, holder.state.value.confirmation)
        assertEquals(0, repository.revokeOtherDevicesCalls)
        holder.clear()
    }

    @Test
    fun `cancelling revoke-all leaves every session alone`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevokeOtherDevices()
        holder.dismissConfirmation()
        runCurrent()

        assertEquals(0, repository.revokeOtherDevicesCalls)
        assertNull(holder.state.value.confirmation)
        holder.clear()
    }

    @Test
    fun `confirming revoke-all makes one server call and reloads`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.askRevokeOtherDevices()
        holder.confirm()
        runCurrent()

        assertEquals(1, repository.revokeOtherDevicesCalls)
        assertEquals(emptyList(), repository.revokedDevices, "one call, not a loop of deletes")
        assertEquals(2, repository.devicesCalls)
        assertEquals("Signed out every other device", holder.state.value.devices.notice)
        holder.clear()
    }

    @Test
    fun `confirm does nothing when nothing is pending`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)

        holder.confirm()
        runCurrent()

        assertEquals(0, repository.revokeOtherDevicesCalls)
        assertEquals(emptyList(), repository.revokedDevices)
        holder.clear()
    }

    // --- Sign out ----------------------------------------------------------------------------

    @Test
    fun `signing out asks first and only then calls the server`() = runTest {
        val repository = FakeRepository()
        val holder = holder(repository)

        holder.askSignOut()
        runCurrent()
        assertEquals(SettingsConfirmation.SignOut, holder.state.value.confirmation)
        assertEquals(0, repository.logoutCalls)

        holder.confirm()
        runCurrent()
        assertEquals(1, repository.logoutCalls)
        assertFalse(holder.state.value.signingOut)
        holder.clear()
    }

    @Test
    fun `cancelling sign-out keeps the session`() = runTest {
        val repository = FakeRepository()
        val holder = holder(repository)

        holder.askSignOut()
        holder.dismissConfirmation()
        runCurrent()

        assertEquals(0, repository.logoutCalls)
        holder.clear()
    }

    @Test
    fun `losing the session drops the device rows of the account that ended`() = runTest {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val holder = holder(repository)
        holder.openSection(SettingsSection.Devices)
        holder.ensureDevicesLoaded()
        runCurrent()

        holder.sessionEnded()
        runCurrent()

        assertEquals(SettingsUiState(), holder.state.value)
        holder.clear()
    }

    // --- Account -----------------------------------------------------------------------------

    @Test
    fun `the account is confirmed against the server once`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(id = 1, username = "operator", isAdmin = true)),
        )
        val holder = holder(repository)

        holder.verifyAccount()
        runCurrent()
        holder.verifyAccount()
        runCurrent()

        assertEquals("operator", holder.state.value.verifiedUser?.username)
        holder.clear()
    }

    @Test
    fun `a server without a me endpoint leaves the stored account standing`() = runTest {
        val repository = FakeRepository(currentUserResult = Result.failure(IllegalStateException("boom")))
        val holder = holder(repository)

        holder.verifyAccount()
        runCurrent()

        assertNull(holder.state.value.verifiedUser, "a failed check must not invent a user")
        holder.clear()
    }

    @Test
    fun `a device row the server did not mark is still recognised by the stored id`() = runTest {
        val repository = FakeRepository(
            devicesResult = Result.success(listOf(DeviceSession(id = 42, deviceName = "workstation"))),
        )
        val holder = holder(repository, InMemorySessionStore(signedIn))
        holder.ensureDevicesLoaded()
        runCurrent()

        assertEquals(42, holder.currentDevice()?.id)
        assertEquals(emptyList(), holder.otherDevices())
        holder.clear()
    }

    // --- The window-level Refresh action ---------------------------------------------------

    @Test
    fun `Refresh on the account pane re-asks who is signed in, not the device list`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(id = 1, username = "operator", isAdmin = true)),
            devicesResult = Result.success(listOf(DeviceSession(id = 42, deviceName = "workstation"))),
        )
        val holder = holder(repository, InMemorySessionStore(signedIn))
        holder.verifyAccount()
        runCurrent()

        holder.refreshCurrentSection()
        runCurrent()

        assertEquals("operator", holder.state.value.verifiedUser?.username)
        assertEquals(0, repository.devicesCalls, "F5 on the account pane must not list devices")
        holder.clear()
    }

    @Test
    fun `Refresh on the device pane re-reads the device list`() = runTest {
        val repository = FakeRepository(
            devicesResult = Result.success(listOf(DeviceSession(id = 42, deviceName = "workstation"))),
        )
        val holder = holder(repository, InMemorySessionStore(signedIn))
        holder.openSection(SettingsSection.Devices)
        holder.ensureDevicesLoaded()
        runCurrent()
        assertEquals(1, repository.devicesCalls)

        holder.refreshCurrentSection()
        runCurrent()

        assertEquals(2, repository.devicesCalls)
        holder.clear()
    }

    // --- Escape's precedence -----------------------------------------------------------------

    @Test
    fun `dismissing the top overlay reports whether there actually was one`() = runTest {
        val holder = holder(FakeRepository(), InMemorySessionStore(signedIn))

        assertFalse(holder.hasOpenOverlay)
        assertFalse(holder.dismissTopOverlay(), "with nothing open, Escape must fall through to Back")

        holder.askSignOut()
        assertTrue(holder.hasOpenOverlay)
        assertTrue(holder.dismissTopOverlay(), "an open dialog swallows the key")
        assertNull(holder.state.value.confirmation)
        holder.clear()
    }
}
