package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import org.junit.Rule
import org.junit.Test

/**
 * A chapter row can show eight icon-only actions at once, several of them near-identical glyphs in
 * front of a state change that is awkward to undo. The description was reaching a screen reader and
 * a test and nobody else; these pin that a mouse user is told as well.
 */
class RowIconActionTooltipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `hovering an icon action reveals its label`() {
        compose.setContent {
            TtsRoadTheme {
                Row {
                    RowIconAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to shared queue", AarisColor.Muted, {})
                    RowIconAction(Icons.Default.Delete, "Delete download", AarisColor.Danger, {})
                }
            }
        }

        // Nothing is claimed before the pointer arrives.
        compose.onNodeWithText("Add to shared queue").assertDoesNotExist()

        compose.onNodeWithContentDescription("Add to shared queue").performMouseInput { moveTo(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Add to shared queue").fetchSemanticsNodes().isNotEmpty()
        }

        // The neighbouring action, which shares neither glyph nor consequence, stays silent.
        compose.onNodeWithText("Delete download").assertDoesNotExist()
    }
}
