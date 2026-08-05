package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.App
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end-ish Compose smoke tests for the screens Phase 0 must not break: the login gate, the
 * library, and the fiction detail list. Everything is driven by fakes and by the real server-1.4.0
 * fixtures, so no socket and no audio device is involved.
 */
class ScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun container(
        session: SessionState,
        repository: FakeRepository,
        playback: FakePlaybackController = FakePlaybackController(),
    ) = AppContainer(
        sessionStore = InMemorySessionStore(session),
        repositoryFactory = { _, _, _ -> repository },
        playbackFactory = { _, _, _, _ -> playback },
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

    // --- LibraryScreen --------------------------------------------------------------------

    @Test
    fun `the library renders the continue-listening hero and the fictions grid`() {
        val repository = FakeRepository(libraryResult = Result.success(ParsedFixtures.library))
        val playback = FakePlaybackController()

        compose.setContent {
            TtsRoadTheme { LibraryScreen(repository, playback, onOpenFiction = {}, onOpenPlayer = {}) }
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
                LibraryScreen(repository, playback, onOpenFiction = {}, onOpenPlayer = { openedPlayer = true })
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
                LibraryScreen(repository, FakePlaybackController(), onOpenFiction = {}, onOpenPlayer = {})
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
                LibraryScreen(repository, FakePlaybackController(), onOpenFiction = {}, onOpenPlayer = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("no route to host").assertIsDisplayed()
    }

    // --- FictionDetailScreen ---------------------------------------------------------------

    @Test
    fun `fiction details render the chapter list from the server payload`() {
        val response = ParsedFixtures.chapters
        val repository = FakeRepository(chaptersResult = Result.success(response))

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(response.fiction, repository, FakePlaybackController(), onBack = {})
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
            TtsRoadTheme { FictionDetailScreen(response.fiction, repository, playback, onBack = {}) }
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
                FictionDetailScreen(response.fiction, repository, FakePlaybackController()) { backPressed = true }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("← LIBRARY").performClick()
        compose.waitForIdle()

        assertTrue(backPressed)
    }
}
