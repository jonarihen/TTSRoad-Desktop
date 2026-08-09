package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferences
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.data.VolumeBoost
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The settings screen as a user meets it.
 *
 * The state machine itself is covered in [SettingsStateHolderTest]; what is asserted here is what
 * the panes render, that both destructive actions are behind a dialog, and the keyboard and
 * screen-reader affordances the issue makes an acceptance criterion.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val signedIn = SessionState(
        serverUrl = "https://ttsroad.example.com/",
        token = "ttsr_token",
        username = "admin",
        isAdmin = true,
        serverName = "Perspektiva TTSRoad",
        serverVersion = "1.4.0",
        deviceId = 42,
        expiresAt = "2026-11-04T09:12:33.123456Z",
    )

    private val capable = ServerCapabilities(
        serverName = "Perspektiva TTSRoad",
        serverVersion = "1.4.0",
        readAlong = true,
        deviceManagement = true,
    )

    /** Fixed "now" so the expiry wording asserted below cannot drift with the calendar. */
    private val now = java.time.Instant.parse("2026-08-06T09:00:00Z").toEpochMilli()

    private fun setContent(
        repository: FakeRepository,
        session: SessionState = signedIn,
        capabilities: ServerCapabilities = capable,
        // What the *engine* can do. The playback pane draws a control only where the backend can
        // honour it, so these two flags decide which half of that pane is under test.
        preferences: InMemoryPlaybackPreferencesStore = InMemoryPlaybackPreferencesStore(),
        canChangeSpeed: Boolean = true,
        canSkipSilence: Boolean = true,
    ) {
        val store = InMemorySessionStore(session)
        repository.capabilitiesResult = capabilities
        runBlocking { repository.refreshCurrentCapabilities(forceRefresh = true) }
        compose.setContent {
            TtsRoadTheme {
                SettingsScreen(
                    store,
                    repository,
                    preferences = preferences,
                    canChangeSpeed = canChangeSpeed,
                    canSkipSilence = canSkipSilence,
                    nowMs = { now },
                )
            }
        }
        compose.waitForIdle()
    }

    /** A pane entry, told apart from the identically worded pane heading by being selectable. */
    private fun navEntry(label: String) = compose.onNode(hasText(label) and isSelectable())

    private fun openDevices() {
        navEntry("DEVICE SESSIONS").performClick()
        compose.waitForIdle()
    }

    // --- Account pane ----------------------------------------------------------------------

    @Test
    fun `settings opens on the account pane and names the server`() {
        setContent(FakeRepository())

        compose.onNodeWithText("Perspektiva TTSRoad 1.4.0").assertIsDisplayed()
        compose.onNodeWithText("https://ttsroad.example.com/").assertIsDisplayed()
        compose.onNodeWithText("admin").assertIsDisplayed()
        compose.onNodeWithText("Admin").assertIsDisplayed()
        compose.onNodeWithText("Read-along, Device management").assertIsDisplayed()
    }

    @Test
    fun `the account pane shows this session's identity and expiry`() {
        setContent(FakeRepository())

        compose.onNodeWithText("42").assertIsDisplayed()
        // The date itself is rendered in the machine's own zone, so only the coarse part — the bit
        // that actually tells a user whether this session is about to lapse — is asserted verbatim.
        compose.onNodeWithText("expires in 90 days", substring = true).assertIsDisplayed()
    }

    @Test
    fun `signing out is behind a confirmation, and cancelling keeps the session`() {
        val repository = FakeRepository()
        setContent(repository)

        compose.onNodeWithText("SIGN OUT").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SIGN OUT THIS DEVICE").assertIsDisplayed()
        assertEquals(0, repository.logoutCalls, "opening the dialog must not sign anyone out")

        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()

        assertEquals(0, repository.logoutCalls)
    }

    @Test
    fun `confirming sign out calls the repository`() {
        val repository = FakeRepository()
        setContent(repository)

        compose.onNodeWithText("SIGN OUT").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIGN OUT THIS DEVICE").performClick()
        compose.waitForIdle()

        assertEquals(1, repository.logoutCalls)
    }

    // --- Navigation ------------------------------------------------------------------------

    @Test
    fun `the pane list is announced as tabs and marks the open one`() {
        setContent(FakeRepository())

        navEntry("ACCOUNT").assertIsSelected()

        navEntry("PLAYBACK").performClick()
        compose.waitForIdle()

        navEntry("PLAYBACK").assertIsSelected()
    }

    @Test
    fun `a pane entry activates from the keyboard`() {
        setContent(FakeRepository())

        // Enter on a focused entry is the same as clicking it — the acceptance criterion for
        // keyboard users, and the reason the entries are `selectable` rather than plain rows.
        navEntry("OFFLINE").requestFocus()
        navEntry("OFFLINE").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        compose.onNodeWithText("Offline downloads are not part of this build.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a narrow window stacks the panes and the list still navigates`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        val store = InMemorySessionStore(signedIn)
        repository.capabilitiesResult = capable
        runBlocking { repository.refreshCurrentCapabilities(forceRefresh = true) }
        // Below the two-pane threshold the nav becomes a scrolling strip above the content; the
        // point of the test is that navigation keeps working, not what the strip looks like.
        compose.setContent {
            TtsRoadTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(420.dp)) {
                    SettingsScreen(store, repository, nowMs = { now })
                }
            }
        }
        compose.waitForIdle()

        navEntry("DEVICE SESSIONS").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
    }

    // MetaText uppercases what it renders, so matches against it are case-insensitive here
    // rather than asserting on the presentation.
    // --- Playback pane -------------------------------------------------------------------------

    private fun openPlayback() {
        navEntry("PLAYBACK").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the playback pane offers the speed presets including 1_25x`() {
        setContent(FakeRepository())
        openPlayback()

        compose.onNodeWithText("1.25×").assertExists()
        compose.onNodeWithText("30 s", ignoreCase = true).assertExists()
    }

    @Test
    fun `choosing a skip interval stores it`() {
        val preferences = InMemoryPlaybackPreferencesStore()
        setContent(FakeRepository(), preferences = preferences)
        openPlayback()

        compose.onNodeWithText("15 s", ignoreCase = true).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(15, preferences.preferences.value.skipIntervalSeconds)
    }

    @Test
    fun `choosing a speed stores it`() {
        val preferences = InMemoryPlaybackPreferencesStore()
        setContent(FakeRepository(), preferences = preferences)
        openPlayback()

        compose.onNodeWithText("1.5×").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(1.5f, preferences.preferences.value.speed)
    }

    @Test
    fun `a custom speed from another build is still offered`() {
        // The stored value is not one of this build's presets; it must not be rounded away.
        val preferences = InMemoryPlaybackPreferencesStore(PlaybackPreferences(speed = 1.35f))
        setContent(FakeRepository(), preferences = preferences)
        openPlayback()

        compose.onNodeWithText("1.35×").assertExists()
    }

    @Test
    fun `an engine that cannot resample gets no speed control at all`() {
        // The Phase 5 rule, applied to Settings: no control rather than one that does nothing.
        setContent(FakeRepository(), canChangeSpeed = false)
        openPlayback()

        compose.onNodeWithText("1.25×").assertDoesNotExist()
        compose.onNodeWithText("cannot resample", substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun `an engine without removesilence gets no skip-silence toggle`() {
        setContent(FakeRepository(), canSkipSilence = false)
        openPlayback()

        compose.onNodeWithText("Not available on this computer", ignoreCase = true).assertExists()
        compose.onNodeWithText("gst-plugins-bad", substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun `skip silence is off by default and can be turned on`() {
        val preferences = InMemoryPlaybackPreferencesStore()
        setContent(FakeRepository(), preferences = preferences)
        openPlayback()

        assertFalse(preferences.preferences.value.skipSilence)
        compose.onNodeWithContentDescription("SKIP SILENCE").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(preferences.preferences.value.skipSilence)
    }

    @Test
    fun `choosing a volume boost stores it`() {
        val preferences = InMemoryPlaybackPreferencesStore()
        setContent(FakeRepository(), preferences = preferences)
        openPlayback()

        compose.onNodeWithText("Medium", ignoreCase = true).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(VolumeBoost.Medium, preferences.preferences.value.volumeBoost)
    }

    // --- Panes for phases that do not exist yet ----------------------------------------------

    @Test
    fun `the offline pane promises nothing and says signing out deletes nothing`() {
        setContent(FakeRepository())

        navEntry("OFFLINE").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("signing out cannot delete anything you asked to keep", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the about pane shows the build, licences and diagnostics`() {
        setContent(FakeRepository())

        navEntry("UPDATES & ABOUT").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Installed build").assertIsDisplayed()
        compose.onNodeWithText("Not available — install a newer build to update").assertIsDisplayed()
        compose.onNodeWithText("Kotlin, Compose Multiplatform, OkHttp, Retrofit, Moshi, Coil").assertIsDisplayed()
        compose.onNodeWithText("COPY DIAGNOSTICS").assertHasClickAction()
    }

    // --- Devices: supported ------------------------------------------------------------------

    @Test
    fun `a capable server lists sessions, marks the current one, and offers no revoke on its row`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)

        openDevices()

        compose.onNodeWithText("workstation · Windows 11").assertIsDisplayed()
        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
        compose.onNodeWithText("Unnamed device").assertIsDisplayed()
        compose.onNodeWithText("THIS DEVICE").assertIsDisplayed()
        // One revoke control per *other* session, and none at all for the current one.
        assertEquals(2, compose.onAllNodesWithText("SIGN OUT").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Sign out Pixel 9").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Sign out workstation · Windows 11")
                .fetchSemanticsNodes().size,
            "the current session must have no revoke control at all",
        )
    }

    @Test
    fun `a device row reads as one sentence for a screen reader`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)

        openDevices()

        compose.onNodeWithContentDescription("Pixel 9, active, last used", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("this device", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an account whose only session is this one says so`() {
        val repository = FakeRepository(
            devicesResult = Result.success(ParsedFixtures.devicesFrom(ServerFixtures.DEVICES_ONLY_CURRENT)),
        )
        setContent(repository)

        openDevices()

        compose.onNodeWithText("Nothing else is signed in.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("SIGN OUT").fetchSemanticsNodes().size)
    }

    @Test
    fun `a row with missing fields lists with dashes rather than breaking the page`() {
        val repository = FakeRepository(
            devicesResult = Result.success(ParsedFixtures.devicesFrom(ServerFixtures.DEVICES_MALFORMED_ROW)),
        )
        setContent(repository)

        openDevices()

        compose.onNodeWithText("Unnamed device").assertIsDisplayed()
        compose.onNodeWithContentDescription("last used NEVER", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    // --- Devices: unsupported and error --------------------------------------------------------

    @Test
    fun `an older server shows a concise unsupported state and keeps every other setting`() {
        val repository = FakeRepository(devicesResult = Result.success(null))
        setContent(repository, capabilities = capable.copy(deviceManagement = false))

        openDevices()

        compose.onNodeWithText("This server has no device-session API.", substring = true).assertIsDisplayed()
        assertEquals(0, repository.devicesCalls, "a server that said no must not be asked")

        navEntry("ACCOUNT").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("https://ttsroad.example.com/").assertIsDisplayed()
    }

    @Test
    fun `an endpoint that answers 404 lands on the same unsupported state`() {
        // Capability says yes, the endpoint disagrees — a proxy, or a backend mid-rollback.
        val repository = FakeRepository(devicesResult = Result.success(null))
        setContent(repository)

        openDevices()

        compose.onNodeWithText("This server has no device-session API.", substring = true).assertIsDisplayed()
        assertEquals(1, repository.devicesCalls)
    }

    @Test
    fun `a failed load offers a retry instead of an endless spinner`() {
        val repository = FakeRepository(devicesResult = Result.failure(IllegalStateException("no route to host")))
        setContent(repository)

        openDevices()

        compose.onNodeWithText("no route to host").assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertHasClickAction()

        repository.devicesResult = Result.success(ParsedFixtures.devices)
        compose.onNodeWithText("RETRY").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
    }

    @Test
    fun `a failed refresh reports itself above the list instead of replacing it`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        repository.devicesResult = Result.failure(IllegalStateException("server exploded"))
        // REFRESH sits below the fold of a three-session list in the test window.
        compose.onNodeWithText("REFRESH").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("server exploded").assertIsDisplayed()
        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
    }

    // --- Devices: both confirmation paths ------------------------------------------------------

    @Test
    fun `revoking one session asks first, and cancelling revokes nothing`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithContentDescription("Sign out Pixel 9").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SIGN OUT DEVICE").assertIsDisplayed()
        compose.onNodeWithText("Pixel 9 will need to sign in again. Anything playing on it stops.")
            .assertIsDisplayed()

        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()

        assertTrue(repository.revokedDevices.isEmpty(), "cancel must revoke nothing")
        assertEquals(0, compose.onAllNodesWithText("SIGN IT OUT").fetchSemanticsNodes().size)
    }

    @Test
    fun `revoking one session goes through on confirm and re-reads the list`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithContentDescription("Sign out Pixel 9").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIGN IT OUT").performClick()
        compose.waitForIdle()

        assertEquals(listOf(43), repository.revokedDevices)
        assertEquals(2, repository.devicesCalls, "the server decides what survived, so re-read")
        compose.onNodeWithText("SIGNED OUT PIXEL 9").assertIsDisplayed()
    }

    @Test
    fun `revoking every other session asks first, and cancelling revokes nothing`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithText("SIGN OUT ALL OTHER DEVICES").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SIGN OUT OTHER DEVICES").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()

        assertEquals(0, repository.revokeOtherDevicesCalls)
    }

    @Test
    fun `revoking every other session goes through on confirm`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithText("SIGN OUT ALL OTHER DEVICES").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIGN THEM OUT").performClick()
        compose.waitForIdle()

        assertEquals(1, repository.revokeOtherDevicesCalls)
        assertTrue(repository.revokedDevices.isEmpty(), "one server call, not a loop of deletes")
        compose.onNodeWithText("SIGNED OUT EVERY OTHER DEVICE").assertIsDisplayed()
    }

    // --- Dialog keyboard behaviour ------------------------------------------------------------

    @Test
    fun `a destructive dialog opens with the safe answer under the keyboard`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithContentDescription("Sign out Pixel 9").performClick()
        compose.waitForIdle()

        // Focus on CANCEL, not on the destructive button: the reflexive Enter must be the safe one.
        compose.onNodeWithText("CANCEL").assertIsFocused()
        compose.onNodeWithText("CANCEL").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        assertTrue(repository.revokedDevices.isEmpty())
        assertEquals(0, compose.onAllNodesWithText("SIGN IT OUT").fetchSemanticsNodes().size)
    }

    @Test
    fun `escape dismisses a confirmation without doing anything`() {
        val repository = FakeRepository(devicesResult = Result.success(ParsedFixtures.devices))
        setContent(repository)
        openDevices()

        compose.onNodeWithContentDescription("Sign out Pixel 9").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("CANCEL").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("SIGN IT OUT").fetchSemanticsNodes().size)
        assertTrue(repository.revokedDevices.isEmpty())
    }
}
