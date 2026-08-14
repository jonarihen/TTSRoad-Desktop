package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.SearchGroup
import dk.perspektiva.ttsroad.desktop.data.SearchHit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

/**
 * The search screen, driven by a [FakeRepository] over the real server fixture — no socket.
 *
 * Uses the JUnit 4 `createComposeRule()` API and therefore runs through JUnit Vintage; it needs a
 * display, and CI runs it under Xvfb.
 */
class SearchScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun holder(repository: FakeRepository) = SearchStateHolder(repository, Dispatchers.Main)

    @Test
    fun `a search renders a row from every group`() {
        val search = holder(FakeRepository(searchResult = Result.success(ParsedFixtures.search)))
        compose.setContent {
            TtsRoadTheme {
                SearchScreen(search, readAlongAvailable = true, onOpenFiction = {}, onOpenReader = { _, _ -> }, onBack = {})
            }
        }

        compose.onNodeWithTag(SearchFieldTestTag).performTextInput("ashfall gate")
        compose.onNodeWithTag(SearchSubmitTestTag).performClick()
        compose.waitForIdle()

        assertEquals(3, compose.onAllNodesWithTag(SearchHitTestTag).fetchSemanticsNodes().size)
        compose.onNodeWithText("The Ashfall Gate").assertIsDisplayed()
    }

    @Test
    fun `the search action is dead until something has been typed`() {
        val search = holder(FakeRepository(searchResult = Result.success(ParsedFixtures.search)))
        compose.setContent {
            TtsRoadTheme {
                SearchScreen(search, readAlongAvailable = true, onOpenFiction = {}, onOpenReader = { _, _ -> }, onBack = {})
            }
        }

        compose.onNodeWithTag(SearchSubmitTestTag).assertIsNotEnabled()
    }

    @Test
    fun `a text hit opens the reader, where its text actually is`() {
        val search = holder(FakeRepository(searchResult = Result.success(ParsedFixtures.search)))
        var openedChapter: Int? = null
        var openedFiction: Int? = null
        compose.setContent {
            TtsRoadTheme {
                SearchScreen(
                    search,
                    readAlongAvailable = true,
                    onOpenFiction = { openedFiction = it.fictionId },
                    onOpenReader = { chapterId, _ -> openedChapter = chapterId },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag(SearchFieldTestTag).performTextInput("ashfall gate")
        compose.onNodeWithTag(SearchSubmitTestTag).performClick()
        compose.waitForIdle()

        // Index 2 is the narration-text hit: fiction, then chapter title, then text.
        compose.onAllNodesWithTag(SearchHitTestTag)[2].performClick()

        assertEquals(103, openedChapter)
        assertNull(openedFiction)
    }

    @Test
    fun `without a reader a chapter hit falls back to the fiction`() {
        val search = holder(FakeRepository(searchResult = Result.success(ParsedFixtures.search)))
        var openedChapter: Int? = null
        var openedFiction: Int? = null
        compose.setContent {
            TtsRoadTheme {
                SearchScreen(
                    search,
                    readAlongAvailable = false,
                    onOpenFiction = { openedFiction = it.fictionId },
                    onOpenReader = { chapterId, _ -> openedChapter = chapterId },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag(SearchFieldTestTag).performTextInput("ashfall gate")
        compose.onNodeWithTag(SearchSubmitTestTag).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(SearchHitTestTag)[2].performClick()

        assertEquals(7, openedFiction)
        assertNull(openedChapter)
    }

    @Test
    fun `a server that cannot search says so instead of showing no matches`() {
        val search = holder(FakeRepository(searchResult = Result.success(null)))
        compose.setContent {
            TtsRoadTheme {
                SearchScreen(search, readAlongAvailable = true, onOpenFiction = {}, onOpenReader = { _, _ -> }, onBack = {})
            }
        }

        compose.onNodeWithTag(SearchFieldTestTag).performTextInput("gate")
        compose.onNodeWithTag(SearchSubmitTestTag).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("THIS SERVER CANNOT SEARCH").assertIsDisplayed()
    }

    // --- Pure helpers ----------------------------------------------------------------------------

    @Test
    fun `a group heading says how much of the total is on screen`() {
        val one = SearchHit(fictionId = 7, fictionTitle = "A Test Serial")

        assertEquals("Chapters — 1", groupTitle("Chapters", SearchGroup(items = listOf(one), total = 1)))
        assertEquals(
            "Chapters — 1 shown of 340",
            groupTitle("Chapters", SearchGroup(items = listOf(one), total = 340)),
        )
        assertEquals(
            "Chapters — 1 shown of 500+",
            groupTitle("Chapters", SearchGroup(items = listOf(one), total = 500, capped = true)),
        )
    }

    @Test
    fun `a fiction the library already knows is opened with its real summary`() {
        val known = FictionSummary(id = 7, title = "A Test Serial", totalChapters = 340, doneChapters = 300)
        val hit = SearchHit(fictionId = 7, fictionTitle = "A Test Serial")

        assertEquals(known, fictionForHit(listOf(known), hit))
    }

    @Test
    fun `a fiction the library has not loaded still opens, from what the hit carries`() {
        val hit = SearchHit(
            fictionId = 9,
            fictionTitle = "Unfollowed Serial",
            author = "Someone",
            fictionSlug = "unfollowed-serial",
            tags = listOf("LitRPG"),
        )

        val summary = fictionForHit(emptyList(), hit)

        assertEquals(9, summary.id)
        assertEquals("Unfollowed Serial", summary.title)
        assertEquals("Someone", summary.author)
        assertEquals(listOf("LitRPG"), summary.tags)
    }

    @Test
    fun `a chapter hit headlines the chapter and names the book underneath it`() {
        val hit = SearchHit(
            fictionId = 7,
            fictionTitle = "A Test Serial",
            chapterId = 102,
            chapterTitle = "The Ashfall Gate",
            playable = true,
        )

        assertEquals("The Ashfall Gate", hitTitle(hit))
        assertEquals("A Test Serial", hitContext(hit))
    }

    @Test
    fun `a chapter found before its audio exists says so rather than looking broken`() {
        val hit = SearchHit(fictionId = 7, fictionTitle = "A Test Serial", chapterId = 104, chapterTitle = "New", playable = false)

        assertEquals("A Test Serial  ·  No audio yet", hitContext(hit))
    }
}
