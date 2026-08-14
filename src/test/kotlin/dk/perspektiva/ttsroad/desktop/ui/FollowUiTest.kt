package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.testLibraryCache
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The follow control and the library's two scopes, under the Compose rule.
 *
 * Needs a display; CI runs it under Xvfb.
 */
class FollowUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val followed = FictionSummary(id = 7, title = "A Test Serial", totalChapters = 3, following = true)
    private val unfollowed = FictionSummary(id = 9, title = "Someone Else's Serial", following = false)

    /** What the chapters endpoint really sends: a fiction payload with **no** `following` key. */
    private val chaptersFiction = FictionSummary(id = 7, title = "A Test Serial", totalChapters = 3)

    private fun repository(follows: Boolean) = FakeRepository(
        libraryResult = Result.success(LibraryResponse(scope = "followed", fictions = listOf(followed))),
        browseAllResult = Result.success(
            LibraryResponse(scope = "all", fictions = listOf(followed, unfollowed)),
        ),
        chaptersResult = Result.success(ChaptersResponse(fiction = chaptersFiction)),
        capabilitiesResult = ServerCapabilities(serverVersion = "1.5.0", follows = follows),
    ).also { repository ->
        // The screens gate on `currentCapabilities`, which only discovery publishes.
        runBlocking { repository.refreshCurrentCapabilities() }
    }

    private fun detail(repository: FakeRepository, seed: FictionSummary, cache: LibraryCache) {
        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    fiction = seed,
                    cache = cache,
                    repository = repository,
                    playback = FakePlaybackController(),
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun library(repository: FakeRepository, follows: Boolean) {
        val cache = testLibraryCache(repository)
        compose.setContent {
            TtsRoadTheme {
                LibraryScreen(
                    cache = cache,
                    repository = repository,
                    playback = FakePlaybackController(),
                    onOpenFiction = {},
                    onOpenPlayer = {},
                    followsAvailable = follows,
                )
            }
        }
        compose.waitForIdle()
    }

    // --- The toggle -------------------------------------------------------------------------------

    @Test
    fun `a followed fiction says so rather than telling the user to follow it`() {
        val repository = repository(follows = true)
        val cache = testLibraryCache(repository)
        runBlockingRefresh(cache)

        detail(repository, followed, cache)

        compose.onNodeWithText("FOLLOWING").assertExists()
    }

    @Test
    fun `following calls the server and takes its answer`() {
        val repository = repository(follows = true)
        val cache = testLibraryCache(repository)
        runBlockingRefresh(cache)

        detail(repository, unfollowed, cache)
        clickFollow()
        compose.waitForIdle()

        assertEquals(listOf(9 to true), repository.followCalls)
        compose.onNodeWithText("FOLLOWING").assertExists()
    }

    @Test
    fun `the chapters response arriving does not unfollow the fiction on screen`() {
        // The regression this guards: `/fictions/{id}/chapters` builds its `fiction` payload
        // without a `following` key, so a screen that re-read follow state from it would flip every
        // followed book to "Follow" the moment its chapters landed.
        val repository = repository(follows = true)
        val cache = testLibraryCache(repository)
        runBlockingRefresh(cache)

        detail(repository, followed, cache)
        compose.waitForIdle()

        assertTrue(repository.chaptersCalls > 0, "the chapters response really did arrive")
        compose.onNodeWithText("FOLLOWING").assertExists()
    }

    @Test
    fun `a server without per-user libraries offers no follow control at all`() {
        val repository = repository(follows = false)
        val cache = testLibraryCache(repository)
        runBlockingRefresh(cache)

        detail(repository, followed, cache)

        assertEquals(0, compose.onAllNodesWithTag(FollowToggleTestTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `a fiction the server no longer has says so instead of looking followed`() {
        val repository = repository(follows = true).apply { followResult = Result.success(null) }
        val cache = testLibraryCache(repository)
        runBlockingRefresh(cache)

        detail(repository, unfollowed, cache)
        clickFollow()
        compose.waitForIdle()

        compose.onNodeWithText("The server does not have this fiction any more").assertExists()
        compose.onNodeWithText("FOLLOW").assertExists()
    }

    // --- The two scopes ---------------------------------------------------------------------------

    @Test
    fun `the shelf is what the library opens on`() {
        val repository = repository(follows = true)

        library(repository, follows = true)

        assertEquals(1, compose.onAllNodesWithTag(FictionCardTestTag).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithTag(LibraryScopeTabTestTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `Everything widens the grid to the whole server`() {
        val repository = repository(follows = true)
        library(repository, follows = true)

        compose.onNodeWithText("EVERYTHING").performClick()
        compose.waitForIdle()

        assertEquals(2, compose.onAllNodesWithTag(FictionCardTestTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `a server without per-user libraries has no mode to pick`() {
        val repository = repository(follows = false)

        library(repository, follows = false)

        assertEquals(0, compose.onAllNodesWithTag(LibraryScopeTabTestTag).fetchSemanticsNodes().size)
    }

    /** The control lives inside the header item, which is taller than the test window. */
    private fun clickFollow() {
        compose.onNodeWithTag(ChapterListTestTag).performScrollToNode(hasTestTag(FollowToggleTestTag))
        compose.onNodeWithTag(FollowToggleTestTag).performClick()
    }

    private fun runBlockingRefresh(cache: LibraryCache) {
        cache.refreshLibrary()
        compose.waitForIdle()
    }
}
