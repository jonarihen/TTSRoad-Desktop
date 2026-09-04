package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.chapterExcludeConfirmation

const val ChapterMaintenanceDialogTestTag: String = "chapterMaintenanceDialog"
const val ExcludeChapterRowTestTag: String = "excludeChapterRow"
const val DeleteChapterRowTestTag: String = "deleteChapterRow"

/**
 * The admin-only half of chapter repair, behind a disclosure (#113).
 *
 * Both of these are rank three by this repo's own test: **a control that needs a sentence under it
 * to be safe to press is not a button, it is a row.** Excluding a chapter silently changes what
 * every account's podcast feed contains, and deleting one destroys the audio and the recorded
 * progress for everybody. Neither fits on a label, and neither belongs on the chapter row next to
 * Play — which is why this is a dialog rather than two more icons.
 *
 * Colour here carries **severity, not rank**: Delete is tinted because it is irreversible, which
 * does not promote it above Exclude in the order things are reached for.
 */
@Composable
fun ChapterMaintenanceDialog(
    chapter: ChapterSummary,
    busy: Boolean,
    onSetExcluded: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = chapter.resolvedTitle
    val excluded = chapter.excluded

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = AarisColor.BgRaise,
        modifier = Modifier.testTag(ChapterMaintenanceDialogTestTag),
        title = { Text("MANAGE ${title.uppercase()}") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "A chapter is one shared row, not a copy for each listener. Both of these " +
                        "change what every account sees.",
                    color = AarisColor.Dim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                AarisActionRow(
                    title = if (excluded) "Put back on every feed" else "Exclude from every feed",
                    subtitle = chapterExcludeConfirmation(title, excluding = !excluded),
                    onClick = { onSetExcluded(!excluded) },
                    enabled = !busy,
                    modifier = Modifier.testTag(ExcludeChapterRowTestTag),
                )
                AarisActionRow(
                    title = "Delete this chapter",
                    subtitle = "Destroys the audio and every account's progress on it. Cannot be undone.",
                    onClick = onDelete,
                    enabled = !busy,
                    // Severity, not rank: it is irreversible, which does not make it the thing
                    // somebody came here to press.
                    titleColor = AarisColor.Danger,
                    modifier = Modifier.testTag(DeleteChapterRowTestTag),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("CLOSE") } },
    )
}
