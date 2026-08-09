package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.PlaybackInfo
import dk.perspektiva.ttsroad.desktop.data.PlaybackMarkResponse
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.QueueItem
import dk.perspektiva.ttsroad.desktop.testLibraryCache
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The acceptance criteria of the chapter-browsing phase, driven through the real
 * [FictionDetailScreen] and [PlayerScreen] with a fake repository and a fake player.
 *
 * These use the JUnit 4 `createComposeRule()` API and therefore run through the JUnit Vintage
 * engine. They need a display; CI runs them under Xvfb.
 */
class ChapterBrowsingUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val fiction = FictionSummary(id = 7, title = "A Test Serial")

    private fun chapter(
        number: Int,
        played: Boolean = false,
        audio: Boolean = true,
        hasTimings: Boolean = false,
        ttsProgress: Int? = null,
    ) = ChapterSummary(
        id = 1000 + number,
        fictionId = 7,
        title = "Chapter $number",
        displayNumber = number.toDouble(),
        status = if (audio) "done" else "processing",
        ttsProgress = ttsProgress,
        audioDuration = 600.0,
        audioDurationLabel = "10:00",
        hasTimings = hasTimings,
        audio = if (audio) AudioInfo(url = "/audio/a-test-serial/$number.mp3") else null,
        playback = PlaybackInfo(isPlayed = played, positionSeconds = if (played) 600.0 else 0.0),
    )

    private fun response(chapters: List<ChapterSummary>) =
        ChaptersResponse(fiction = fiction, total = chapters.size, chapters = chapters)

    private fun screen(
        repository: FakeRepository,
        playback: FakePlaybackController = FakePlaybackController(),
        cache: LibraryCache = testLibraryCache(repository),
        downloads: ChapterDownloadsUi = ChapterDownloadsUi(),
    ) {
        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    fiction = fiction,
                    cache = cache,
                    repository = repository,
                    playback = playback,
                    onBack = {},
                    downloads = downloads,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The controls live in the header item, which auto-scroll may have pushed off screen. */
    private fun scrollToControls() {
        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(0)
        compose.waitForIdle()
    }

    // --- Large fictions ---------------------------------------------------------------------

    @Test
    fun `a thousand-chapter fiction composes only the rows on screen`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..1_000).map { chapter(it) })))

        screen(repository)

        val composed = compose.onAllNodesWithTag(ChapterRowTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..100, "the lazy list composed $composed of 1000 chapter rows")
        compose.onNodeWithText("CHAPTERS — 1000").assertIsDisplayed()
    }

    @Test
    fun `scrolling deep into a thousand chapters keeps composition bounded`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..1_000).map { chapter(it) })))
        screen(repository)

        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(900)
        compose.waitForIdle()

        val composed = compose.onAllNodesWithTag(ChapterRowTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..100, "the lazy list composed $composed rows after scrolling to 900")
    }

    // --- Filter and sort --------------------------------------------------------------------

    @Test
    fun `filtering says how many of how many are shown`() {
        val repository = FakeRepository(
            chaptersResult = Result.success(
                response(listOf(chapter(1, played = true), chapter(2), chapter(3, audio = false))),
            ),
        )
        screen(repository)
        compose.onNodeWithText("CHAPTERS — 3").assertIsDisplayed()

        compose.onNodeWithText("UNPLAYED").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("CHAPTERS — 2 OF 3").assertIsDisplayed()
        compose.onAllNodesWithText("Chapter 1").assertCountEquals(0)

        compose.onNodeWithText("READY").performClick()
        compose.waitForIdle()

        // "Ready" is about having audio, not about being unplayed — chapter 1 comes back and the
        // still-converting chapter 3 goes.
        compose.onNodeWithText("CHAPTERS — 2 OF 3").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertIsDisplayed()
        compose.onAllNodesWithText("Chapter 3").assertCountEquals(0)
    }

    @Test
    fun `newest-first reverses the rows on screen`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..4).map { chapter(it) })))
        screen(repository)

        compose.onAllNodesWithTag(ChapterRowTestTag)[0].assert(hasText("Chapter 1", substring = true))

        compose.onNodeWithText("NEWEST").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(ChapterRowTestTag)[0].assert(hasText("Chapter 4", substring = true))
        compose.onNode(hasText("NEWEST") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `sorting the screen never reorders the queue`() {
        // The single most important thing about the sort control: it is a view, not an instruction
        // to play the serial backwards.
        val repository = FakeRepository(chaptersResult = Result.success(response((1..4).map { chapter(it) })))
        val playback = FakePlaybackController()
        screen(repository, playback)

        compose.onNodeWithText("NEWEST").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter 4").performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("playQueue(1004)"), "calls were ${playback.calls}")
    }

    @Test
    fun `filter and sort outlive the screen that set them`() {
        // Not merely "survive a recomposition": the whole composable is disposed and rebuilt, which
        // is what navigating away from a fiction and back to it does.
        val repository = FakeRepository(chaptersResult = Result.success(response((1..4).map { chapter(it) })))
        val cache = testLibraryCache(repository)
        val showDetail = mutableStateOf(true)
        compose.setContent {
            TtsRoadTheme {
                if (showDetail.value) {
                    FictionDetailScreen(fiction, cache, repository, FakePlaybackController(), onBack = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("NEWEST").performClick()
        compose.waitForIdle()

        compose.runOnIdle { showDetail.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showDetail.value = true }
        compose.waitForIdle()

        compose.onNode(hasText("NEWEST") and isSelectable()).assertIsSelected()
        compose.onAllNodesWithTag(ChapterRowTestTag)[0].assert(hasText("Chapter 4", substring = true))
    }

    // --- The currently playing chapter --------------------------------------------------------

    private fun playing(chapterNumber: Int, chapters: Int) = PlayerUiState(
        title = "Chapter $chapterNumber",
        fictionId = 7,
        isPlaying = true,
        hasMedia = true,
        durationMs = 600_000,
        queue = (1..chapters).map { QueueItem(1000 + it, "Chapter $it", it.toDouble()) },
        currentIndex = chapterNumber - 1,
    )

    @Test
    fun `the playing chapter announces itself on its own row`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..20).map { chapter(it) })))

        screen(repository, FakePlaybackController(playing(3, 20)))

        compose.onNode(hasText("Chapter 3", substring = true) and hasText("PLAYING", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a queue belonging to another fiction highlights nothing here`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..20).map { chapter(it) })))
        val elsewhere = playing(3, 20).copy(fictionId = 99)

        screen(repository, FakePlaybackController(elsewhere))

        compose.onAllNodesWithText("PLAYING", substring = true).assertCountEquals(0)
    }

    @Test
    fun `opening a fiction lands on the chapter that is playing`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..300).map { chapter(it) })))

        screen(repository, FakePlaybackController(playing(200, 300)))

        compose.onNodeWithText("Chapter 200").assertIsDisplayed()
        // Already on screen, so there is nothing to jump back to.
        compose.onAllNodesWithText("JUMP TO CURRENT").assertCountEquals(0)
    }

    @Test
    fun `jump to current appears once the reader scrolls away, and takes them back`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..300).map { chapter(it) })))
        screen(repository, FakePlaybackController(playing(5, 300)))

        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(250)
        compose.waitForIdle()
        compose.onNodeWithText("JUMP TO CURRENT").assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Chapter 5").assertIsDisplayed()
        compose.onAllNodesWithText("JUMP TO CURRENT").assertCountEquals(0)
    }

    @Test
    fun `a filter that hides the playing chapter also withdraws the jump affordance`() {
        // Chapter 5 is played and playing; "Unplayed" removes it from the view, and with it any
        // claim that there is a current row to jump to.
        val chapters = (1..300).map { chapter(it, played = it == 5) }
        val repository = FakeRepository(chaptersResult = Result.success(response(chapters)))
        screen(repository, FakePlaybackController(playing(5, 300)))

        scrollToControls()
        compose.onNodeWithText("UNPLAYED").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(250)
        compose.waitForIdle()

        compose.onAllNodesWithText("JUMP TO CURRENT").assertCountEquals(0)
    }

    // --- Row actions ---------------------------------------------------------------------------

    @Test
    fun `every row action is reachable by name rather than only by hovering`() {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(serverVersion = "1.4.0", readAlong = true),
            chaptersResult = Result.success(response(listOf(chapter(1), chapter(2, hasTimings = true)))),
        )
        runBlocking { repository.refreshCurrentCapabilities() }

        screen(repository)

        // No pointer has been anywhere near these. They are in the tree because they are always
        // composed, which is the only way a keyboard can ever reach them.
        compose.onAllNodesWithContentDescription("Play chapter").onFirst().assertHasClickAction()
        compose.onAllNodesWithContentDescription("Mark played").onFirst().assertHasClickAction()
        compose.onNodeWithContentDescription("Read along").assertHasClickAction()
        compose.onNodeWithContentDescription("Mark all previous chapters as played").assertHasClickAction()
    }

    @Test
    fun `read-along is hidden when the server does not support the endpoint`() {
        val repository = FakeRepository(
            chaptersResult = Result.success(response(listOf(chapter(1, hasTimings = true)))),
        )
        // Capabilities left at Baseline: this server has no read-along route at all.
        screen(repository)

        compose.onAllNodesWithContentDescription("Read along").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Read chapter text").assertCountEquals(0)
    }

    @Test
    fun `an untimed chapter still offers its plain narration text on a capable server`() {
        val repository = FakeRepository(
            capabilitiesResult = ServerCapabilities(readAlong = true),
            chaptersResult = Result.success(response(listOf(chapter(1, hasTimings = false)))),
        )
        runBlocking { repository.refreshCurrentCapabilities() }

        screen(repository)

        compose.onNodeWithContentDescription("Read chapter text").assertHasClickAction()
    }

    @Test
    fun `the first chapter offers no mark-all-previous, because there is nothing before it`() {
        val repository = FakeRepository(chaptersResult = Result.success(response(listOf(chapter(1)))))

        screen(repository)

        compose.onAllNodesWithContentDescription("Mark all previous chapters as played").assertCountEquals(0)
    }

    @Test
    fun `a chapter with no audio is never queued and says why`() {
        val repository = FakeRepository(
            chaptersResult = Result.success(response(listOf(chapter(1, audio = false, ttsProgress = 41)))),
        )
        val playback = FakePlaybackController()
        screen(repository, playback)

        compose.onNodeWithText("CONVERTING 41%").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").performClick()
        compose.waitForIdle()

        assertEquals(emptyList(), playback.calls, "a row with no audio must not reach the player")
        compose.onAllNodesWithContentDescription("Play chapter").assertCountEquals(0)
    }

    // --- Downloads -----------------------------------------------------------------------------

    @Test
    fun `each download state exposes only its useful row action`() {
        val chapters = (1..6).map { chapter(it) }
        val repository = FakeRepository(chaptersResult = Result.success(response(chapters)))
        val actions = mutableListOf<String>()
        val states = mapOf(
            1001 to ChapterDownloadUi(ChapterDownloadState.NotDownloaded),
            1002 to ChapterDownloadUi(ChapterDownloadState.Queued),
            1003 to ChapterDownloadUi(ChapterDownloadState.Downloading, progress = 0.25f),
            1004 to ChapterDownloadUi(ChapterDownloadState.Downloaded),
            1005 to ChapterDownloadUi(ChapterDownloadState.Failed, failureMessage = "The disk is full"),
            1006 to ChapterDownloadUi(ChapterDownloadState.Removing),
        )
        screen(
            repository,
            downloads = ChapterDownloadsUi(
                available = true,
                stateFor = { states.getValue(it.resolvedChapterId) },
                onDownload = { actions += "download:${it.resolvedChapterId}" },
                onCancel = { actions += "cancel:${it.resolvedChapterId}" },
                onDelete = { actions += "delete:${it.resolvedChapterId}" },
                onRetry = { actions += "retry:${it.resolvedChapterId}" },
            ),
        )

        compose.onNodeWithContentDescription("Download").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Queued for download — cancel").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Downloading 25% — cancel").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Available offline — delete").assertHasClickAction().performClick()
        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Download failed: The disk is full — retry")
            .assertHasClickAction()
            .performClick()
        // Six rows plus the eager header are just taller than the test window. Bring the final
        // state into the lazy list's composition before asserting it.
        compose.onNodeWithTag(ChapterListTestTag).performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Removing download").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(
                listOf("download:1001", "cancel:1002", "cancel:1003", "delete:1004", "retry:1005"),
                actions,
            )
        }
    }

    @Test
    fun `download next is absent without storage and delegates once when available`() {
        val repository = FakeRepository(chaptersResult = Result.success(response(listOf(chapter(1)))))
        screen(repository)
        compose.onAllNodesWithText("DOWNLOAD NEXT 10").assertCountEquals(0)

        var requests = 0
        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    fiction = fiction,
                    cache = testLibraryCache(repository),
                    repository = repository,
                    playback = FakePlaybackController(),
                    onBack = {},
                    downloads = ChapterDownloadsUi(
                        available = true,
                        onDownloadNext = { requests++ },
                    ),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("DOWNLOAD NEXT 10").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    // --- Marking -------------------------------------------------------------------------------

    @Test
    fun `marking one chapter sends one request and ticks that row only`() {
        val repository = FakeRepository(
            chaptersResult = Result.success(
                // Only the last chapter starts played, so "Mark unplayed" identifies exactly one row.
                response(listOf(chapter(1), chapter(2), chapter(3, played = true))),
            ),
        )
        screen(repository)

        compose.onNodeWithContentDescription("Mark unplayed").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf(1003) to false), repository.markedPlayed)
        assertEquals(1, repository.chaptersCalls, "one checkmark must not refetch the fiction")
        compose.onAllNodesWithContentDescription("Mark unplayed").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Mark played").assertCountEquals(3)
    }

    @Test
    fun `mark all previous sends one request with the earlier chapters only`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..3).map { chapter(it) })))
        screen(repository)

        // Chapters 2 and 3 offer the action; the second one in traversal order is chapter 3.
        compose.onAllNodesWithContentDescription("Mark all previous chapters as played")[1].performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf(1001, 1002) to true), repository.markedPlayed)
    }

    @Test
    fun `mark all played is a single request and then has nothing left to do`() {
        val repository = FakeRepository(chaptersResult = Result.success(response((1..40).map { chapter(it) })))
        screen(repository)

        compose.onNodeWithText("MARK ALL PLAYED").performClick()
        compose.waitForIdle()

        assertEquals(1, repository.markedPlayed.size, "40 chapters is one request, not forty")
        assertEquals(40, repository.markedPlayed.single().first.size)
        compose.onNodeWithText("MARK ALL PLAYED").assertIsNotEnabled()

        compose.onNodeWithText("MARK ALL UNPLAYED").performClick()
        compose.waitForIdle()

        assertEquals(2, repository.markedPlayed.size)
        assertEquals(40, repository.markedPlayed.last().first.size)
    }

    @Test
    fun `a failed mark rolls the row back and explains itself without blanking the list`() {
        val repository = object : FakeRepository(
            chaptersResult = Result.success(
                response(
                    listOf(
                        chapter(1).copy(playback = PlaybackInfo(positionSeconds = 412.5)),
                        chapter(2),
                    ),
                ),
            ),
        ) {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
                throw IllegalStateException("connection reset")
        }
        screen(repository)

        compose.onAllNodesWithContentDescription("Mark played").onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("connection reset").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Mark unplayed").assertCountEquals(0)
        compose.onNodeWithText("Chapter 1").assertIsDisplayed()
        compose.onNodeWithText("Chapter 2").assertIsDisplayed()
    }

    @Test
    fun `an id the server drops is unticked again on its own`() {
        val repository = object : FakeRepository(
            chaptersResult = Result.success(response(listOf(chapter(1), chapter(2)))),
        ) {
            // What an excluded chapter looks like on the wire: accepted, but not in the echo.
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean) =
                PlaybackMarkResponse(status = "ok", played = played, chapterIds = listOf(1001), count = 1)
        }
        screen(repository)

        compose.onNodeWithText("MARK ALL PLAYED").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("Mark unplayed").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Mark played").assertCountEquals(1)
    }

    // --- The up-next panel ----------------------------------------------------------------------

    private fun queueState(size: Int) = PlayerUiState(
        title = "Chapter 1",
        fictionId = 7,
        hasMedia = true,
        durationMs = 600_000,
        queue = (1..size).map { QueueItem(1000 + it, "Chapter $it", it.toDouble()) },
        currentIndex = 0,
    )

    @Test
    fun `a long queue can be searched from the player`() {
        val playback = FakePlaybackController(queueState(40))
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }
        compose.waitForIdle()

        compose.onNodeWithText("FIND A CHAPTER").performTextInput("Chapter 37")
        compose.waitForIdle()

        // Clicking a filtered row jumps to its real queue position, not to its position in the
        // filtered list. Selected by tag as well as by text, because the search field now holds
        // the very same string.
        compose.onNode(hasTestTag(QueueRowTestTag) and hasText("Chapter 37", substring = true))
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertTrue(playback.calls.contains("queueIndex(36)"), "calls were ${playback.calls}")
    }

    @Test
    fun `a short queue is not worth a search box`() {
        val playback = FakePlaybackController(queueState(3))
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }
        compose.waitForIdle()

        compose.onAllNodesWithText("FIND A CHAPTER").assertCountEquals(0)
    }

    @Test
    fun `a search with no matches says so instead of showing an empty panel`() {
        val playback = FakePlaybackController(queueState(40))
        compose.setContent { TtsRoadTheme { PlayerScreen(playback, onBack = {}) } }
        compose.waitForIdle()

        compose.onNodeWithText("FIND A CHAPTER").performTextInput("epilogue")
        compose.waitForIdle()

        compose.onNodeWithText("NO MATCHES FOR \"EPILOGUE\"").assertIsDisplayed()
    }
}
