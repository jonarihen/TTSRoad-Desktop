package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionMaintenanceAction
import dk.perspektiva.ttsroad.desktop.data.FictionPollScope
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.testLibraryCache
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test

class ScopedFetchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val fiction = FictionSummary(id = 5, title = "Test Fiction")

    @Test
    fun `scoped fetch dialog validates inputs and submits chosen scope`() {
        var submittedScope: FictionPollScope? = null
        var dismissed = false

        compose.setContent {
            TtsRoadTheme {
                ScopedFetchDialog(
                    fictionTitle = "Test Fiction",
                    busy = false,
                    onFetch = { submittedScope = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ScopedFetchDialogTestTag).assertIsDisplayed()

        // Default is Last 25, click Fetch
        compose.onNodeWithTag(SubmitFetchButtonTestTag).performClick()
        assertEquals(FictionPollScope(lastN = 25), submittedScope)

        // Select Fetch all
        submittedScope = null
        compose.onNodeWithTag(FetchAllOptionTestTag).performClick()
        compose.onNodeWithTag(SubmitFetchButtonTestTag).performClick()
        assertEquals(FictionPollScope(full = true), submittedScope)

        // Select First with 50 preset
        submittedScope = null
        compose.onNodeWithTag(FetchRangeFirstTestTag).performClick()
        compose.onNodeWithTag("fetchCount50").performClick()
        compose.onNodeWithTag(SubmitFetchButtonTestTag).performClick()
        assertEquals(FictionPollScope(firstN = 50), submittedScope)

        // Custom count
        submittedScope = null
        compose.onNodeWithTag(CustomCountFieldTestTag).performTextInput("17")
        compose.onNodeWithTag(SubmitFetchButtonTestTag).performClick()
        assertEquals(FictionPollScope(firstN = 17), submittedScope)
    }

    @Test
    fun `fiction detail screen displays scoped fetch and epub export when enabled and hides for epub source`() {
        val repository = FakeRepository()
        repository.capabilitiesResult = ServerCapabilities(fictionMaintenance = true, ebookExport = true)
        repository.chaptersResult = Result.success(ChaptersResponse(fiction = fiction))
        val cache = testLibraryCache(repository)

        var polledScope: FictionPollScope? = null
        var exported = false

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    fiction = fiction,
                    cache = cache,
                    repository = repository,
                    playback = FakePlaybackController(),
                    onBack = {},
                    maintenance = ChapterMaintenanceUi(
                        fictionActions = listOf(FictionMaintenanceAction.Poll),
                        onPollScoped = { polledScope = it },
                    ),
                    epub = EpubExportUi(
                        available = true,
                        onExport = { exported = true },
                    ),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(PollFictionButtonTestTag).assertIsDisplayed()
        compose.onNodeWithTag(ScopedFetchButtonTestTag).assertIsDisplayed()
        compose.onNodeWithTag(ExportEpubButtonTestTag).assertIsDisplayed()

        compose.onNodeWithTag(ExportEpubButtonTestTag).performClick()
        assertEquals(true, exported)

        compose.onNodeWithTag(ScopedFetchButtonTestTag).performClick()
        compose.onNodeWithTag(ScopedFetchDialogTestTag).assertIsDisplayed()
        compose.onNodeWithTag(SubmitFetchButtonTestTag).performClick()
        assertEquals(FictionPollScope(lastN = 25), polledScope)
    }

    @Test
    fun `poll and fetch are hidden for epub source type`() {
        val epubFiction = fiction.copy(sourceType = "epub")
        val repository = FakeRepository()
        repository.capabilitiesResult = ServerCapabilities(fictionMaintenance = true)
        repository.chaptersResult = Result.success(ChaptersResponse(fiction = epubFiction))
        val cache = testLibraryCache(repository)

        compose.setContent {
            TtsRoadTheme {
                FictionDetailScreen(
                    fiction = epubFiction,
                    cache = cache,
                    repository = repository,
                    playback = FakePlaybackController(),
                    onBack = {},
                    maintenance = ChapterMaintenanceUi(
                        fictionActions = listOf(FictionMaintenanceAction.Poll),
                    ),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(PollFictionButtonTestTag).assertDoesNotExist()
        compose.onNodeWithTag(ScopedFetchButtonTestTag).assertDoesNotExist()
    }
}
