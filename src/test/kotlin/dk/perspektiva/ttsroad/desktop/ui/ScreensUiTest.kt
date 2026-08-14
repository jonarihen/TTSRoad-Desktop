package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.App
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.testLibraryCache
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.SessionEndReason
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.download.DownloadCoordinator
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end-ish Compose smoke tests for the screens Phase 0 must not break: the login gate, the
 * library, and the fiction detail list. Everything is driven by fakes and by the real server-1.4.0
 * fixtures, so no socket and no audio device is involved.
 */
class ScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder()

    private fun testDownloads(
        store: dk.perspektiva.ttsroad.desktop.data.SessionStore,
        client: okhttp3.OkHttpClient,
        repository: dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository,
        dispatchers: dk.perspektiva.ttsroad.desktop.di.AppDispatchers,
    ) = DownloadCoordinator(
        store,
        client,
        repository,
        dataDir = files.root.resolve("data"),
        cacheDir = files.root.resolve("cache"),
        dispatcher = dispatchers.io,
    )

    private fun container(
        session: SessionState,
        repository: FakeRepository,
        playback: FakePlaybackController = FakePlaybackController(),
    ) = AppContainer(
        sessionStore = InMemorySessionStore(session),
        repositoryFactory = { _, _, _ -> repository },
        playbackFactory = { _, _, _, _, _, _, _, _ -> playback },
        // In-memory, so rendering a screen in a test never touches the real
        // ~/.config/TTSRoad files the production stores default to.
        playbackPreferences = InMemoryPlaybackPreferencesStore(),
        playbackHistory = InMemoryPlaybackHistoryStore(),
        readerPreferencesFactory = { _, _ -> InMemoryReaderPreferencesStore() },
        downloadCoordinatorFactory = ::testDownloads,
    )

    // --- App: the login gate --------------------------------------------------------------

    @Test
    fun `a signed-out app shows the login form, not the library`() {
        val repository = FakeRepository()
        val app = container(SessionState(), repository)

        compose.setContent { TtsRoadTheme { App(app) } }

        compose.onNodeWithText("SIGN IN").assertIsDisplayed()
        compose.onNodeWithText("// OPERATOR CONSOLE").assertIsDisplayed()
        assertEquals(0, repository.libraryCalls, "the library must not be fetched while signed out")
    }

    @Test
    fun `a signed-in app shows the header and loads the library`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))
        val app = container(
            SessionState(serverUrl = "https://x/", token = "t", username = "admin", serverName = "Perspektiva"),
            repository,
        )

        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        compose.onNodeWithText("TTSROAD").assertIsDisplayed()
        compose.onNodeWithText("// PERSPEKTIVA").assertIsDisplayed()
        compose.onNodeWithText("LIBRARY").assertIsDisplayed()
        assertEquals(1, repository.libraryCalls)
    }

    @Test
    fun `the sign-in button stays disabled until a password is typed`() {
        val repository = FakeRepository()
        val app = container(SessionState(), repository)
        compose.setContent { TtsRoadTheme { App(app) } }

        // serverUrl and username are prefilled; password is not.
        compose.onNodeWithText("SIGN IN").performClick()
        compose.waitForIdle()
        assertEquals(0, repository.loginCalls, "an empty password must not submit")

        compose.onNodeWithText("PASSWORD").performTextInput("hunter2")
        compose.onNodeWithText("SIGN IN").performClick()
        compose.waitForIdle()

        assertEquals(1, repository.loginCalls)
    }

    @Test
    fun `the login screen identifies the server before a password is typed`() {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(serverName = "Perspektiva TTSRoad", serverVersion = "1.4.0"),
        )
        val app = container(SessionState(serverUrl = "https://ttsroad.example.com/"), repository)

        compose.setContent { TtsRoadTheme { App(app) } }
        // The probe is debounced, so give the composition time to run it.
        compose.waitUntil(5_000) { repository.capabilityProbes.isNotEmpty() }
        compose.waitForIdle()

        compose.onNodeWithText("PERSPEKTIVA TTSROAD 1.4.0").assertIsDisplayed()
        assertEquals(0, repository.loginCalls, "identification must not need a credential")
    }

    @Test
    fun `an expired session explains itself on the login screen`() {
        val repository = FakeRepository()
        val app = container(SessionState(serverUrl = "https://x/", username = "admin"), repository)
        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        runBlocking {
            repository.endSession(
                SessionEnd(SessionEndReason.Revoked, "This device session was revoked. Sign in again."),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("This device session was revoked. Sign in again.").assertIsDisplayed()
    }

    @Test
    fun `losing the session stops playback instead of leaving audio behind the login screen`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))
        val playback = FakePlaybackController()
        val store = InMemorySessionStore(
            SessionState(serverUrl = "https://x/", token = "t", username = "admin"),
        )
        val app = AppContainer(
            sessionStore = store,
            repositoryFactory = { _, _, _ -> repository },
            playbackFactory = { _, _, _, _, _, _, _, _ -> playback },
            // In-memory, so rendering a screen in a test never touches the real
            // ~/.config/TTSRoad files the production stores default to.
            playbackPreferences = InMemoryPlaybackPreferencesStore(),
            playbackHistory = InMemoryPlaybackHistoryStore(),
            readerPreferencesFactory = { _, _ -> InMemoryReaderPreferencesStore() },
            downloadCoordinatorFactory = ::testDownloads,
        )
        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()
        playback.calls.clear()

        // What a 401 on an API or audio call does: the repository drops the token.
        store.clearToken()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("stop"), "calls were ${playback.calls}")
        compose.onNodeWithText("SIGN IN").assertIsDisplayed()
    }

    @Test
    fun `a signed-out app prefills the retained server and user hints`() {
        val repository = FakeRepository()
        val app = container(
            // What clearToken() leaves behind after an expiry: hints, no credential.
            SessionState(serverUrl = "https://ttsroad.example.com/", username = "operator"),
            repository,
        )

        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        compose.onNodeWithText("https://ttsroad.example.com/").assertIsDisplayed()
        compose.onNodeWithText("operator").assertIsDisplayed()
    }

    // --- App: settings ----------------------------------------------------------------------

    @Test
    fun `settings keeps its open pane and loaded devices across a trip to the library`() {
        val repository = FakeRepository(
            libraryResult = Result.success(ParsedFixtures.library),
            devicesResult = Result.success(ParsedFixtures.devices),
        )
        val app = container(
            SessionState(serverUrl = "https://x/", token = "t", username = "admin", deviceId = 42),
            repository,
        )
        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("DEVICE SESSIONS") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pixel 9").assertIsDisplayed()

        // Away and back: the holder lives above navigation, so nothing is refetched or reset.
        compose.onNodeWithText("LIBRARY").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
        assertEquals(1, repository.devicesCalls, "returning to settings must not refetch")
    }

    @Test
    fun `signing out from settings drops the device rows of the account that ended`() {
        val repository = FakeRepository(
            libraryResult = Result.success(ParsedFixtures.library),
            devicesResult = Result.success(ParsedFixtures.devices),
        )
        val store = InMemorySessionStore(
            SessionState(serverUrl = "https://x/", token = "t", username = "admin", deviceId = 42),
        )
        val app = AppContainer(
            sessionStore = store,
            repositoryFactory = { _, _, _ -> repository },
            playbackFactory = { _, _, _, _, _, _, _, _ -> FakePlaybackController() },
            // In-memory, so rendering a screen in a test never touches the real
            // ~/.config/TTSRoad files the production stores default to.
            playbackPreferences = InMemoryPlaybackPreferencesStore(),
            playbackHistory = InMemoryPlaybackHistoryStore(),
            readerPreferencesFactory = { _, _ -> InMemoryReaderPreferencesStore() },
            downloadCoordinatorFactory = ::testDownloads,
        )
        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()
        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("DEVICE SESSIONS") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pixel 9").assertIsDisplayed()

        // What logout — or a 401 — does: the token goes.
        store.clearToken()
        compose.waitForIdle()

        compose.onNodeWithText("SIGN IN").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Pixel 9").fetchSemanticsNodes().size,
            "another account's sessions must not survive a sign-out",
        )
    }

    // --- LibraryScreen --------------------------------------------------------------------

    @Test
    fun `an advertised admin can add edit and reach the destructive delete warning`() {
        val repository = FakeRepository(
            libraryResult = Result.success(ParsedFixtures.library),
            chaptersResult = Result.success(ParsedFixtures.chapters),
            capabilitiesResult = ServerCapabilities(
                serverVersion = "2.0.0",
                fictionManagement = true,
            ),
            currentUserResult = Result.success(MobileUser(1, "admin", isAdmin = true)),
        )
        val app = container(
            SessionState(
                serverUrl = "https://ttsroad.example.com/",
                token = "ttsr_admin",
                username = "admin",
                isAdmin = true,
            ),
            repository,
        )

        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        compose.onNodeWithTag(AddFictionButtonTestTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AddFictionButtonTestTag).performClick()
        compose.onNodeWithTag(AddFictionDialogTestTag).assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()

        compose.onAllNodesWithTag(FictionCardTestTag).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EditFictionButtonTestTag).assertIsDisplayed()
        compose.onNodeWithTag(DeleteFictionButtonTestTag).performClick()
        compose.onNodeWithText(
            "This permanently deletes the fiction, every chapter and every user's listening progress",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `a non-admin never sees fiction management controls even when routes exist`() {
        val repository = FakeRepository(
            libraryResult = Result.success(ParsedFixtures.library),
            capabilitiesResult = ServerCapabilities(
                serverVersion = "2.0.0",
                fictionManagement = true,
            ),
            currentUserResult = Result.success(MobileUser(2, "listener", isAdmin = false)),
        )
        val app = container(
            SessionState(
                serverUrl = "https://ttsroad.example.com/",
                token = "ttsr_listener",
                username = "listener",
                // Deliberately stale: `/me` must win over this login-time claim.
                isAdmin = true,
            ),
            repository,
        )

        compose.setContent { TtsRoadTheme { App(app) } }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTag(AddFictionButtonTestTag).fetchSemanticsNodes().size)
        assertEquals(1, repository.currentUserCalls)
    }

    @Test
    fun `the library renders the continue-listening hero and the fictions grid`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme { LibraryScreen(remember { testLibraryCache(repository) }, repository, playback, onOpenFiction = {}, onOpenPlayer = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithText("// CONTINUE LISTENING").assertIsDisplayed()
        compose.onNodeWithText("Chapter 3 — The Descent").assertIsDisplayed()
        compose.onAllNodesWithText("A Test Serial").onFirst().assertIsDisplayed()
        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }

    @Test
    fun `resuming from the hero starts playback and opens the player`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))
        val playback = FakePlaybackController()
        var openedPlayer = false

        compose.setContent {
            TtsRoadTheme {
                LibraryScreen(
                    remember { testLibraryCache(repository) },
                    repository,
                    playback,
                    onOpenFiction = {},
                    onOpenPlayer = { openedPlayer = true },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("RESUME").performClick()
        compose.waitForIdle()

        assertTrue(openedPlayer, "resuming must expand the player")
        assertTrue(playback.calls.contains("play(101)"), "calls were ${playback.calls}")
    }

    @Test
    fun `the library search box filters the fictions grid`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))

        compose.setContent {
            TtsRoadTheme {
                LibraryScreen(
                    remember { testLibraryCache(repository) },
                    repository,
                    FakePlaybackController(),
                    onOpenFiction = {},
                    onOpenPlayer = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").performTextInput("nothing matches this")
        compose.waitForIdle()

        compose.onNodeWithText("NO MATCHES FOR \"NOTHING MATCHES THIS\"").assertIsDisplayed()
    }

    @Test
    fun `a library failure shows the error instead of an endless spinner`() {
        val repository = FakeRepository(libraryResult = Result.failure(IllegalStateException("no route to host")))

        compose.setContent {
            TtsRoadTheme {
                LibraryScreen(
                    remember { testLibraryCache(repository) },
                    repository,
                    FakePlaybackController(),
                    onOpenFiction = {},
                    onOpenPlayer = {},
                )
            }
        }
        compose.waitForIdle()

        // The one failure that earns the whole screen — nothing was cached to show behind it —
        // and it always comes with a way to act on it.
        compose.onNodeWithText("no route to host", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertIsDisplayed()
    }

    // --- FictionDetailScreen ---------------------------------------------------------------

    @Test
    fun `fiction details render the chapter list from the server payload`() {
        val response = ParsedFixtures.chapters
        val repository = FakeRepository(chaptersResult = Result.success(response))

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    response.fiction,
                    remember { testLibraryCache(repository) },
                    repository,
                    FakePlaybackController(),
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("A Test Serial").onFirst().assertIsDisplayed()
        compose.onNodeWithText("CHAPTERS — 2").assertIsDisplayed()
        compose.onNodeWithText("Chapter 3 — The Descent").assertIsDisplayed()
        compose.onNodeWithText("Chapter 4").assertIsDisplayed()
        assertEquals(1, repository.chaptersCalls)
    }

    @Test
    fun `clicking a chapter plays the whole fiction as a queue starting there`() {
        val response = ParsedFixtures.chapters
        val repository = FakeRepository(chaptersResult = Result.success(response))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    response.fiction,
                    remember { testLibraryCache(repository) },
                    repository,
                    playback,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Chapter 3 — The Descent").performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("playQueue(101)"), "calls were ${playback.calls}")
    }

    @Test
    fun `the back link returns to the library`() {
        val response = ParsedFixtures.chapters
        val repository = FakeRepository(chaptersResult = Result.success(response))
        var backPressed = false

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    response.fiction,
                    remember { testLibraryCache(repository) },
                    repository,
                    FakePlaybackController(),
                    onBack = { backPressed = true },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("← BACK").performClick()
        compose.waitForIdle()

        assertTrue(backPressed)
    }
}
