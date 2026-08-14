package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.InMemoryReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.ReadAlongCache
import dk.perspektiva.ttsroad.desktop.data.ReadAlongChapter
import dk.perspektiva.ttsroad.desktop.data.ReadAlongFetchResult
import dk.perspektiva.ttsroad.desktop.data.ReadAlongResponse
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.QueueItem
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun response(
        chapterId: Int = 10,
        text: String = "Snow fell softly. Then snow stopped.",
        hasTimings: Boolean = true,
        paragraphs: List<List<Double>> = listOf(listOf(0.0, text.length.toDouble())),
    ) = ReadAlongResponse(
        chapter = ReadAlongChapter(
            id = chapterId,
            fictionId = 7,
            title = "Chapter One",
            audioDuration = 60.0,
            hasTimings = hasTimings,
        ),
        text = text,
        paragraphs = paragraphs,
        cues = if (hasTimings) {
            listOf(
                listOf(0.0, 4.0, 0.0),
                listOf(5.0, 9.0, 1.0),
                listOf(10.0, 16.0, 2.0),
            )
        } else {
            emptyList()
        },
    )

    private fun screen(
        response: ReadAlongResponse = response(),
        player: FakePlaybackController = FakePlaybackController(),
        preferences: InMemoryReaderPreferencesStore = InMemoryReaderPreferencesStore(),
        onAdvanced: (Int, String) -> Unit = { _, _ -> },
        bookmarksAvailable: Boolean = false,
        onAddBookmark: (Long, String?) -> Unit = { _, _ -> },
        readingMode: Boolean = false,
    ) {
        val repository = FakeRepository(
            readAlongResult = Result.success(ReadAlongFetchResult.Modified(response, "\"etag\"")),
        )
        compose.setContent {
            TtsRoadTheme {
                ReaderScreen(
                    chapterId = response.chapter.id,
                    fallbackTitle = response.chapter.title,
                    cache = ReadAlongCache(repository),
                    preferences = preferences,
                    playback = player,
                    bookmarksAvailable = bookmarksAvailable,
                    onAddBookmark = onAddBookmark,
                    readingMode = readingMode,
                    onBack = {},
                    onChapterAdvanced = onAdvanced,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Playing this chapter, at a position inside the second cue. */
    private fun playingThisChapter(positionMs: Long = 6_000) = FakePlaybackController(
        PlayerUiState(
            title = "Chapter One",
            hasMedia = true,
            positionMs = positionMs,
            durationMs = 60_000,
            queue = listOf(QueueItem(10, "Chapter One")),
        ),
    )

    @Test
    fun `no bookmark control at all where the server has no bookmark API`() {
        screen(player = playingThisChapter(), bookmarksAvailable = false)

        compose.onAllNodesWithTag(ReaderBookmarkButtonTestTag).assertCountEquals(0)
    }

    @Test
    fun `bookmarking a passage sends the sentence start and the sentence itself`() {
        var marked: Pair<Long, String?>? = null
        screen(
            player = playingThisChapter(positionMs = 6_000),
            bookmarksAvailable = true,
            onAddBookmark = { position, label -> marked = position to label },
        )

        compose.onNodeWithTag(ReaderBookmarkButtonTestTag).performClick()

        // The cue at 6s starts at 5s and belongs to the first sentence, which starts at 0s. A mark
        // means the passage, not the syllable that was sounding — and it names itself with the
        // words, which is the thing this client can do that a phone's transport button cannot.
        assertEquals(0L to "Snow fell softly.", marked)
    }

    @Test
    fun `the bookmark control is inert while reading a chapter that is not playing`() {
        // Nothing is playing, so there is no honest position — the same rule that already disables
        // highlighting. The control stays put rather than appearing later and moving its neighbours.
        screen(player = FakePlaybackController(), bookmarksAvailable = true)

        compose.onNodeWithTag(ReaderBookmarkButtonTestTag).assertIsNotEnabled()
    }

    @Test
    fun `a timed chapter renders its selectable narration`() {
        screen()
        compose.onNodeWithText("// READ ALONG").assertIsDisplayed()
        compose.onNodeWithText("Snow fell softly. Then snow stopped.").assertIsDisplayed()
    }

    @Test
    fun `a text-only chapter explains why it cannot follow audio`() {
        screen(response(hasTimings = false))
        compose.onNodeWithText("// TEXT ONLY").assertIsDisplayed()
        compose.onNodeWithText("This chapter has narration text but no word timings.").assertIsDisplayed()
    }

    @Test
    fun `find in chapter reports and cycles through matches`() {
        screen()

        compose.onNodeWithContentDescription("Find in chapter").performClick()
        compose.onNodeWithTag(ReaderFindFieldTestTag).performTextInput("snow")
        compose.waitForIdle()

        compose.onNodeWithText("1 OF 2").assertIsDisplayed()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("2 OF 2").assertIsDisplayed()
    }

    @Test
    fun `reader settings are keyboard-visible and update the local store`() {
        val preferences = InMemoryReaderPreferencesStore()
        screen(preferences = preferences)

        compose.onNodeWithContentDescription("Reading settings").assertHasClickAction().performClick()
        compose.onNodeWithText("Reading settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase Font size").performClick()

        assertEquals(20.0, preferences.preferences.value.fontSize)
    }

    @Test
    fun `queue auto-advance moves a reader that was following the playing chapter`() {
        val initial = PlayerUiState(
            title = "Chapter One",
            hasMedia = true,
            positionMs = 10_000,
            durationMs = 60_000,
            queue = listOf(QueueItem(10, "Chapter One"), QueueItem(11, "Chapter Two")),
            currentIndex = 0,
            hasNext = true,
        )
        val player = FakePlaybackController(initial)
        var advanced: Pair<Int, String>? = null
        screen(player = player, onAdvanced = { id, title -> advanced = id to title })

        player.emit(initial.copy(title = "Chapter Two", currentIndex = 1, hasNext = false))
        compose.waitForIdle()

        assertEquals(11 to "Chapter Two", advanced)
    }

    @Test
    fun `opening a different chapter never follows unrelated playback`() {
        val player = FakePlaybackController(
            PlayerUiState(
                title = "Somewhere else",
                hasMedia = true,
                queue = listOf(QueueItem(99, "Somewhere else"), QueueItem(100, "Later")),
                currentIndex = 0,
            ),
        )
        var advances = 0
        screen(player = player, onAdvanced = { _, _ -> advances++ })

        player.emit(player.state.value.copy(currentIndex = 1))
        compose.waitForIdle()

        assertEquals(0, advances)
        compose.onNodeWithText("Play this chapter to follow the narration.").assertIsDisplayed()
    }

    @Test
    fun `a ten-thousand-word fixture composes only visible paragraph nodes`() {
        val paragraph = "word ".repeat(20).trim()
        val paragraphs = ArrayList<List<Double>>()
        val text = buildString {
            repeat(500) {
                val start = length
                append(paragraph)
                val end = length
                paragraphs += listOf(start.toDouble(), end.toDouble())
                append("\n\n")
            }
        }
        screen(response(text = text, hasTimings = false, paragraphs = paragraphs))

        val composed = compose.onAllNodesWithTag(ReaderParagraphTestTag).fetchSemanticsNodes().size
        assertTrue(composed in 1..80, "the lazy reader composed $composed of 500 paragraphs")
    }

    // --- Distraction-free reading -------------------------------------------------------------

    @Test
    fun `reading mode hides the reader's own toolbar until the pointer has not moved`() {
        screen(player = playingThisChapter(), readingMode = true)

        compose.onAllNodesWithTag(ReaderToolbarTestTag).assertCountEquals(0)
        compose.onAllNodesWithTag(ReaderTransportTestTag).assertCountEquals(0)
        // The page itself is untouched: this hides the frame, not the chapter.
        compose.onAllNodesWithTag(ReaderParagraphTestTag).assertCountEquals(1)
    }

    @Test
    fun `ordinary reading keeps the toolbar and offers the mode`() {
        screen(player = playingThisChapter())

        compose.onNodeWithTag(ReaderToolbarTestTag).assertIsDisplayed()
        compose.onNodeWithTag(ReaderModeButtonTestTag).assertHasClickAction()
        compose.onNodeWithContentDescription("Distraction-free reading").assertIsDisplayed()
        // The now-playing bar belongs to `App`; the reader draws no transport of its own here.
        compose.onAllNodesWithTag(ReaderTransportTestTag).assertCountEquals(0)
    }

    @Test
    fun `reaching for the top edge brings the frame and the transport back`() {
        screen(player = playingThisChapter(), readingMode = true)

        compose.onRoot().performMouseInput { moveTo(Offset(120f, 4f)) }
        compose.waitForIdle()

        compose.onNodeWithTag(ReaderToolbarTestTag).assertIsDisplayed()
        // Hiding the app's now-playing bar without offering a pause is the trap this avoids.
        compose.onNodeWithTag(ReaderTransportTestTag).assertIsDisplayed()
        compose.onNodeWithContentDescription("Leave distraction-free reading").assertIsDisplayed()
    }

    @Test
    fun `moving back into the page hides the frame again`() {
        screen(player = playingThisChapter(), readingMode = true)

        compose.onRoot().performMouseInput { moveTo(Offset(120f, 4f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderToolbarTestTag).assertIsDisplayed()

        compose.onRoot().performMouseInput { moveTo(Offset(120f, 320f)) }
        compose.waitForIdle()

        compose.onAllNodesWithTag(ReaderToolbarTestTag).assertCountEquals(0)
        compose.onAllNodesWithTag(ReaderTransportTestTag).assertCountEquals(0)
    }
}
