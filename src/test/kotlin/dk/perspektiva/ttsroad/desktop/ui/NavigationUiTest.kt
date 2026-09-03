package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import dk.perspektiva.ttsroad.desktop.App
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryBrowsePreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.ReadAlongChapter
import dk.perspektiva.ttsroad.desktop.data.ReadAlongFetchResult
import dk.perspektiva.ttsroad.desktop.data.ReadAlongResponse
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.download.DownloadCoordinator
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The acceptance criteria of the navigation phase, driven through the real `App` composable:
 * browsing state survives a round trip, a failed refresh does not blank the screen, and a large
 * library does not compose itself into existence all at once.
 */
class NavigationUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder()

    private fun bigLibrary(count: Int) = LibraryResponse(
        fictions = (1..count).map {
            FictionSummary(id = it, title = "Serial %02d".format(it), author = "Someone", totalChapters = 3)
        },
    )

    /** Two tags, deliberately carried by different fictions, so "both" and "either" differ. */
    private fun taggedLibrary() = LibraryResponse(
        fictions = listOf(
            FictionSummary(id = 1, title = "Serial 01", tags = listOf("LitRPG"), totalChapters = 3),
            FictionSummary(id = 2, title = "Serial 02", tags = listOf("Romance"), totalChapters = 3),
        ),
    )

    private val chapters = ChaptersResponse(
        fiction = FictionSummary(id = 50, title = "Serial 50"),
        total = 1,
        chapters = listOf(ChapterSummary(id = 5001, fictionId = 50, title = "Chapter One", displayNumber = 1.0)),
    )

    /** A playing session, so the now-playing bar is on screen and the player is reachable. */
    private val playing = PlayerUiState(
        title = "NOW PLAYING TRACK",
        hasMedia = true,
        isPlaying = true,
        durationMs = 600_000,
    )

    private fun container(
        repository: FakeRepository,
        playback: FakePlaybackController = FakePlaybackController(playing),
    ) = AppContainer(
        sessionStore = InMemorySessionStore(
            SessionState(serverUrl = "https://x/", token = "t", username = "admin", serverName = "Perspektiva"),
        ),
        repositoryFactory = { _, _, _ -> repository },
        playbackFactory = { _, _, _, _, _, _, _, _ -> playback },
        // In-memory, so rendering a screen in a test never touches the real
        // ~/.config/TTSRoad files the production stores default to.
        playbackPreferences = InMemoryPlaybackPreferencesStore(),
        browsePreferences = InMemoryBrowsePreferencesStore(),
        playbackHistory = InMemoryPlaybackHistoryStore(),
        readerPreferencesFactory = { _, _ -> InMemoryReaderPreferencesStore() },
        // See `testLibraryCache`: immediate main dispatch is what makes `waitForIdle` sufficient.
        libraryCacheFactory = { repo, _, now -> LibraryCache(repo, Dispatchers.Main.immediate, now) },
        downloadCoordinatorFactory = { store, client, repo, dispatchers ->
            DownloadCoordinator(
                store,
                client,
                repo,
                dataDir = files.root.resolve("data"),
                cacheDir = files.root.resolve("cache"),
                dispatcher = dispatchers.io,
            )
        },
    )

    // --- Retained browsing context ------------------------------------------------------------

    @Test
    fun `library to fiction to player and back twice restores the search text and the scroll position`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(60)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").performTextInput("serial")
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryGridTestTag).performScrollToNode(hasText("Serial 50"))
        compose.waitForIdle()

        compose.onNodeWithText("Serial 50").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter One").assertIsDisplayed()

        // Expanding the now-playing bar is how a listener reaches the player mid-browse.
        compose.onNodeWithText("NOW PLAYING TRACK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("// NOW PLAYING").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter One").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        // The exact browsing context: the same scroll offset…
        compose.onNodeWithText("Serial 50").assertIsDisplayed()
        // …and the same query. The search box lives in the first grid item, which is legitimately
        // not composed while scrolled away, so scroll back to it before asking.
        compose.onNodeWithTag(LibraryGridTestTag).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithText("serial").assertIsDisplayed()
        assertEquals(1, repository.libraryCalls, "returning to the library must not reload it")
        assertEquals(1, repository.chaptersCalls)
    }

    @Test
    fun `re-opening a fiction that is already open pops back to it instead of stacking a copy`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(4)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NOW PLAYING TRACK").performClick()
        compose.waitForIdle()

        // Back once lands on the fiction, back again on the library — three entries, not four.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }

    // --- Refresh ------------------------------------------------------------------------------

    @Test
    fun `a failed refresh keeps the library on screen and reports itself inline`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(3)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").assertIsDisplayed()

        repository.libraryResult = Result.failure(IllegalStateException("connection reset"))
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.waitForIdle()

        assertEquals(2, repository.libraryCalls)
        compose.onNodeWithText("Serial 01").assertIsDisplayed()
        // One announcement for a screen reader: the failure *and* how old what is on screen is.
        compose.onNode(hasContentDescription("connection reset", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("Showing content from", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a failed refresh stays visible even when the grid is scrollable`() {
        // Regression: both notices used to be items *inside* the lazy grid. A lazy list anchors its
        // scroll position on the key of whatever is at the top, so inserting a banner above that
        // anchor scrolled by exactly the banner's height and the notice arrived already out of
        // view — on precisely the screens with enough content to scroll, which is most of them.
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(40)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithTag(LibraryGridTestTag).performScrollToIndex(20)
        compose.waitForIdle()

        repository.libraryResult = Result.failure(IllegalStateException("connection reset"))
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.waitForIdle()

        compose.onNode(hasContentDescription("connection reset", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the shelf can be reordered and the control says which order is in force`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(3)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        // The label is the order itself: a grid should never have to be read to work out how it is
        // arranged.
        compose.onNodeWithTag(BrowseSortTestTag).assertIsDisplayed().performClick()
        compose.waitForIdle()
        // Picking an order closes the sheet: there is exactly one answer, so there is nothing
        // left to confirm.
        compose.onNodeWithText("Title").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("ORDER: TITLE").assertIsDisplayed()
    }

    @Test
    fun `a tag filter states itself and can be cleared`() {
        val repository = FakeRepository(libraryResult = Result.success(taggedLibrary()))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithTag(BrowseTagTestTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Romance").performClick()
        compose.onNodeWithText("DONE").performClick()
        compose.waitForIdle()

        // A filter that hides rows without saying it is on is indistinguishable from a server that
        // has lost the shelf.
        compose.onNodeWithTag(BrowseFilterSummaryTestTag).assertIsDisplayed()
        compose.onNodeWithText("Serial 01").assertDoesNotExist()

        compose.onNodeWithText("CLEAR TAGS").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").assertIsDisplayed()
    }

    @Test
    fun `a tag that excludes everything says so rather than blaming the server`() {
        val repository = FakeRepository(libraryResult = Result.success(taggedLibrary()))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithTag(BrowseTagTestTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Romance").performClick()
        compose.onNodeWithText("LitRPG").performClick()
        compose.onNodeWithText("DONE").performClick()
        compose.waitForIdle()

        // Two ticked tags mean both, and no fiction here carries both.
        compose.onNode(hasText("NO FICTIONS CARRY ALL 2 OF THOSE TAGS", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a successful refresh replaces the content and drops the failure notice`() {
        val repository = FakeRepository(libraryResult = Result.failure(IllegalStateException("connection reset")))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("RETRY").assertIsDisplayed()

        repository.libraryResult = Result.success(bigLibrary(2))
        compose.onNodeWithText("RETRY").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Serial 01").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag("noSuchTag").fetchSemanticsNodes().size,
            "sanity: the tag matcher is working",
        )
    }

    // --- Large libraries -----------------------------------------------------------------------

    @Test
    fun `a thousand-fiction library composes only what is on screen`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(1_000)))

        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        val composed = compose.onAllNodesWithTag(FictionCardTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..100, "the lazy grid composed $composed of 1000 cards")
    }

    @Test
    fun `scrolling a thousand-fiction library keeps the number of composed cards bounded`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(1_000)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithTag(LibraryGridTestTag).performScrollToNode(hasText("Serial 900"))
        compose.waitForIdle()

        val composed = compose.onAllNodesWithTag(FictionCardTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..100, "the lazy grid composed $composed cards after scrolling")
        compose.onNodeWithText("Serial 900").assertIsDisplayed()
    }

    @Test
    fun `the primary library and settings flows remain usable at two hundred percent text scaling`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(4)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TtsRoadTheme { App(container(repository, FakePlaybackController())) }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
        compose.onNodeWithText("Serial 01").assertIsDisplayed()
        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("ACCOUNT", useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onNodeWithText("SIGN OUT").performScrollTo().assertIsDisplayed()
    }

    // --- Settings as a destination --------------------------------------------------------------

    @Test
    fun `opening the device pane makes Back return to the library, not to the account pane`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(2)),
            devicesResult = Result.success(emptyList()),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MANAGE DEVICE SESSIONS").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DEVICE SESSIONS", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }

    // --- Keyboard -------------------------------------------------------------------------------

    @Test
    fun `F5 refreshes the screen the user is on`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(2)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        assertEquals(1, repository.libraryCalls)

        compose.onRoot().performKeyInput { pressKey(Key.F5) }
        compose.waitForIdle()

        assertEquals(2, repository.libraryCalls)
    }

    @Test
    fun `Alt+Left pops the back stack`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(4)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter One").assertIsDisplayed()

        compose.onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } }
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }

    @Test
    fun `Escape navigates back when no dialog is open`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(4)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()

        compose.onRoot().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }

    @Test
    fun `Escape closes an open dialog and does not also navigate away`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(2)),
            devicesResult = Result.success(emptyList()),
        )
        // No now-playing bar in this one: it costs vertical room the settings pane needs.
        compose.setContent { TtsRoadTheme { App(container(repository, FakePlaybackController())) } }
        compose.waitForIdle()
        compose.onNodeWithText("SETTINGS").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIGN OUT").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIGN OUT THIS DEVICE").assertIsDisplayed()

        compose.onNodeWithText("CANCEL").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        // The dialog is gone…
        assertEquals(0, compose.onAllNodesWithText("SIGN OUT THIS DEVICE").fetchSemanticsNodes().size)
        // …and the screen it belonged to is still the one on screen.
        compose.onNodeWithText("MANAGE DEVICE SESSIONS").assertIsDisplayed()
        assertEquals(0, repository.logoutCalls)
    }

    // --- Keyboard reachability -------------------------------------------------------------------

    @Test
    fun `Tab moves focus off the root into the first enabled header action`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(2)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()

        // Back is disabled at the root, so traversal correctly skips it and lands on Refresh.
        compose.onNodeWithContentDescription("Refresh").assertIsFocused()
    }

    @Test
    fun `the header actions and the navigation tabs are keyboard reachable`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(2)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Refresh").assert(isFocusable())
        compose.onNodeWithText("LIBRARY").assert(isFocusable())
        compose.onNodeWithText("SETTINGS").assert(isFocusable())
        compose.onNodeWithText("Serial 01").assert(isFocusable())
    }

    @Test
    fun `the Back control becomes reachable once there is somewhere to go back to`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(4)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()

        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsEnabled()
        compose.onNodeWithContentDescription("Back").assert(isFocusable())
    }

    // --- Chapters --------------------------------------------------------------------------------

    @Test
    fun `a capability-gated chapter row opens the real reader destination`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(60)),
            chaptersResult = Result.success(chapters),
            capabilitiesResult = ServerCapabilities(serverVersion = "1.4.0", readAlong = true),
            readAlongResult = Result.success(
                ReadAlongFetchResult.Modified(
                    ReadAlongResponse(
                        chapter = ReadAlongChapter(5001, 50, "Chapter One", hasTimings = false),
                        text = "The real reader is here.",
                        paragraphs = listOf(listOf(0.0, 24.0)),
                    ),
                    "\"reader\"",
                ),
            ),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithTag(LibraryGridTestTag).performScrollToNode(hasText("Serial 50"))
        compose.onNodeWithText("Serial 50").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Read chapter text").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The real reader is here.").assertIsDisplayed()
        compose.onNodeWithText("// TEXT ONLY").assertIsDisplayed()
    }

    @Test
    fun `F11 in the reader hides the app chrome and Escape brings it back without leaving`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(60)),
            chaptersResult = Result.success(chapters),
            capabilitiesResult = ServerCapabilities(serverVersion = "1.4.0", readAlong = true),
            readAlongResult = Result.success(
                ReadAlongFetchResult.Modified(
                    ReadAlongResponse(
                        chapter = ReadAlongChapter(5001, 50, "Chapter One", hasTimings = false),
                        text = "The real reader is here.",
                        paragraphs = listOf(listOf(0.0, 24.0)),
                    ),
                    "\"reader\"",
                ),
            ),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithTag(LibraryGridTestTag).performScrollToNode(hasText("Serial 50"))
        compose.onNodeWithText("Serial 50").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Read chapter text").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SETTINGS").assertIsDisplayed()

        compose.onRoot().performKeyInput { pressKey(Key.F11) }
        compose.waitForIdle()

        // The header is half of what distraction-free reading is free of.
        compose.onAllNodesWithText("SETTINGS").assertCountEquals(0)
        compose.onNodeWithText("The real reader is here.").assertIsDisplayed()

        // Escape restores the frame rather than leaving the chapter, so the reader keeps its place.
        compose.onRoot().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
        compose.onNodeWithText("The real reader is here.").assertIsDisplayed()
    }

    @Test
    fun `a fiction with many chapters composes only the rows on screen`() {
        val many = ChaptersResponse(
            fiction = FictionSummary(id = 1, title = "Serial 01", totalChapters = 500),
            total = 500,
            chapters = (1..500).map {
                ChapterSummary(id = it, fictionId = 1, title = "Chapter $it", displayNumber = it.toDouble())
            },
        )
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(2)),
            chaptersResult = Result.success(many),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()

        val composed = compose.onAllNodesWithTag(ChapterRowTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..100, "the lazy list composed $composed of 500 chapter rows")
    }

    @Test
    fun `a failed chapter refresh keeps the list and reports itself inline`() {
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(2)),
            chaptersResult = Result.success(chapters),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter One").assertIsDisplayed()

        repository.chaptersResult = Result.failure(IllegalStateException("gateway timeout"))
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Chapter One").assertIsDisplayed()
        compose.onNode(hasContentDescription("gateway timeout", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the chapter filter is retained across a trip to the player`() {
        val mixed = ChaptersResponse(
            fiction = FictionSummary(id = 1, title = "Serial 01"),
            total = 2,
            chapters = listOf(
                ChapterSummary(id = 1, fictionId = 1, title = "Already heard", displayNumber = 1.0)
                    .copy(playback = dk.perspektiva.ttsroad.desktop.data.PlaybackInfo(isPlayed = true)),
                ChapterSummary(id = 2, fictionId = 1, title = "Not yet heard", displayNumber = 2.0),
            ),
        )
        val repository = FakeRepository(
            libraryResult = Result.success(bigLibrary(2)),
            chaptersResult = Result.success(mixed),
        )
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Serial 01").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("UNPLAYED").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Already heard").assertDoesNotExist()

        compose.onNodeWithText("NOW PLAYING TRACK").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Not yet heard").assertIsDisplayed()
        compose.onNodeWithText("Already heard").assertDoesNotExist()
    }

    @Test
    fun `the Back control is inert at the root`() {
        val repository = FakeRepository(libraryResult = Result.success(bigLibrary(2)))
        compose.setContent { TtsRoadTheme { App(container(repository)) } }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("SEARCH TITLE, AUTHOR OR TAG").assertIsDisplayed()
    }
}
