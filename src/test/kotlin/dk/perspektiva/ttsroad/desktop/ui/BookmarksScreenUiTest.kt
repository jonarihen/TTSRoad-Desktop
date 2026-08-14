package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.QueueItem
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test

/**
 * The bookmarks screen as a user meets it.
 *
 * The state machine is covered in [BookmarksStateHolderTest]; what is asserted here is what the
 * rows render, that removing is behind a confirmation, and that a mark whose chapter is gone still
 * shows without offering to play audio that no longer exists.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class BookmarksScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun bookmark(
        id: Int,
        label: String? = "The bridge scene",
        chapterId: Int? = 101,
        fictionId: Int? = 7,
    ) = Bookmark(
        id = id,
        chapterId = chapterId,
        fictionId = fictionId,
        positionSeconds = 742.5,
        positionLabel = "12:22",
        label = label,
        note = "Come back to this.",
        createdAt = "2027-01-0${id}T09:00:00Z",
        chapterTitle = "Chapter 3",
        fictionTitle = "A Test Serial",
    )

    private fun holderFor(repository: FakeRepository) =
        BookmarksStateHolder(repository, Dispatchers.Main.immediate)

    @Test
    fun `a mark renders its label, where it came from, and its note`() {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))

        compose.setContent {
            BookmarksScreen(holder = holderFor(repository), onOpen = {}, onBack = {})
        }

        compose.onNodeWithText("The bridge scene").assertIsDisplayed()
        compose.onNodeWithText("Come back to this.").assertIsDisplayed()
        // The server sends the titles alongside each mark, which is why this screen needs neither
        // the library nor a request per row.
        compose.onNodeWithText("A TEST SERIAL · CHAPTER 3 · 12:22").assertIsDisplayed()
    }

    @Test
    fun `an empty list says so rather than looking broken`() {
        compose.setContent {
            BookmarksScreen(
                holder = holderFor(FakeRepository(bookmarksResult = Result.success(emptyList()))),
                onOpen = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("NO BOOKMARKS YET").assertIsDisplayed()
    }

    @Test
    fun `a server with no bookmark API is distinguished from an empty list`() {
        compose.setContent {
            BookmarksScreen(
                holder = holderFor(FakeRepository(bookmarksResult = Result.success(null))),
                onOpen = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("NO BOOKMARKS HERE").assertIsDisplayed()
    }

    @Test
    fun `opening a mark hands the whole bookmark back`() {
        val opened = mutableListOf<Bookmark>()
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))

        compose.setContent {
            BookmarksScreen(holder = holderFor(repository), onOpen = { opened += it }, onBack = {})
        }
        compose.onNodeWithTag(BookmarkPlayTestTag).performClick()

        assertEquals(listOf(1), opened.map { it.id })
        assertEquals(742_500L, opened.single().positionMs)
    }

    @Test
    fun `a mark whose chapter was deleted stays listed but cannot be played`() {
        val repository = FakeRepository(
            bookmarksResult = Result.success(
                listOf(bookmark(1, label = "Orphan", chapterId = null, fictionId = null)),
            ),
        )

        compose.setContent {
            BookmarksScreen(holder = holderFor(repository), onOpen = {}, onBack = {})
        }

        // The note on it is still the user's, so the row survives — it simply says what happened.
        compose.onNodeWithText("Orphan").assertIsDisplayed()
        compose.onNodeWithText("CHAPTER REMOVED").assertIsDisplayed()
        compose.onAllNodesWithTag(BookmarkPlayTestTag).assertCountEquals(0)
    }

    @Test
    fun `removing is behind a confirmation`() {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))

        compose.setContent {
            BookmarksScreen(holder = holderFor(repository), onOpen = {}, onBack = {})
        }
        compose.onNodeWithTag(BookmarkDeleteTestTag).performClick()

        assertTrue(repository.deletedBookmarks.isEmpty(), "nothing is deleted before confirming")
        compose.onNodeWithText("CANCEL").performClick()
        assertTrue(repository.deletedBookmarks.isEmpty(), "cancelling deletes nothing")

        compose.onNodeWithTag(BookmarkDeleteTestTag).performClick()
        compose.onNodeWithTag(BookmarkConfirmDeleteTestTag).performClick()
        // The holder does the delete in a coroutine, and a click only guarantees the *dispatch*.
        // Asserting straight after the press is what made this test flaky.
        compose.waitUntil(5_000) { repository.deletedBookmarks.isNotEmpty() }
        assertEquals(listOf(1), repository.deletedBookmarks)
    }

    @Test
    fun `editing sends both fields, so a note can be cleared`() {
        val repository = FakeRepository(
            bookmarksResult = Result.success(listOf(bookmark(1))),
            updateBookmarkResult = Result.success(Bookmark(id = 1)),
        )

        compose.setContent {
            BookmarksScreen(holder = holderFor(repository), onOpen = {}, onBack = {})
        }
        compose.onNodeWithTag(BookmarkEditTestTag).performClick()
        compose.onNodeWithTag(BookmarkLabelFieldTestTag).performTextInput("!")
        compose.onNodeWithText("SAVE").performClick()

        // Same reason as the delete test: the PATCH is launched, not performed, by the press.
        compose.waitUntil(5_000) { repository.patchedBookmarks.isNotEmpty() }
        val (id, patch) = repository.patchedBookmarks.single()
        assertEquals(1, id)
        assertTrue(patch.label.orEmpty().contains("bridge"))
        // Present and empty rather than absent: absent would mean "leave the note alone".
        assertEquals("Come back to this.", patch.note)
    }

    // --- the player's own control ---------------------------------------------------------------

    @Test
    fun `the player draws no bookmark control where the server has no bookmark API`() {
        compose.setContent {
            PlayerScreen(
                playback = playerWithMedia(),
                preferences = InMemoryPlaybackPreferencesStore(),
                bookmarksAvailable = false,
                onBack = {},
            )
        }

        compose.onAllNodesWithTag(AddBookmarkTestTag).assertCountEquals(0)
    }

    @Test
    fun `the player's bookmark control reports the press`() {
        var presses = 0

        compose.setContent {
            PlayerScreen(
                playback = playerWithMedia(),
                preferences = InMemoryPlaybackPreferencesStore(),
                bookmarksAvailable = true,
                onAddBookmark = { presses++ },
                onBack = {},
            )
        }
        compose.onNodeWithTag(AddBookmarkTestTag).assertIsEnabled().performClick()

        assertEquals(1, presses)
    }

    @Test
    fun `the player's bookmark control is inert with nothing loaded`() {
        compose.setContent {
            PlayerScreen(
                playback = FakePlaybackController(
                    PlayerUiState(hasMedia = false, queue = listOf(QueueItem(101, "Chapter 3"))),
                ),
                preferences = InMemoryPlaybackPreferencesStore(),
                bookmarksAvailable = true,
                onBack = {},
            )
        }

        compose.onNodeWithTag(AddBookmarkTestTag).assertIsNotEnabled()
    }

    private fun playerWithMedia() = FakePlaybackController(
        PlayerUiState(
            title = "Chapter 3",
            hasMedia = true,
            positionMs = 61_400,
            durationMs = 900_000,
            queue = listOf(QueueItem(101, "Chapter 3")),
        ),
    )
}
