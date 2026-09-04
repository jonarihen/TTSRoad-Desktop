package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.formatReportPosition

const val PronunciationDialogTestTag: String = "pronunciationDialog"
const val PronunciationWordFieldTestTag: String = "pronunciationWord"
const val PronunciationSendTestTag: String = "pronunciationSend"

/**
 * "That word sounded wrong", filed from the player (#121).
 *
 * The chapter and the position are already on the draft, frozen at the moment the form opened —
 * they are shown rather than editable, because they are facts the player knows exactly and a typed
 * timestamp would only ever be worse.
 */
@Composable
fun PronunciationReportDialog(holder: PronunciationReportsStateHolder) {
    val ui by holder.state.collectAsState()
    val draft = ui.draft ?: return

    AlertDialog(
        onDismissRequest = holder::dismiss,
        shape = RectangleShape,
        containerColor = AarisColor.BgRaise,
        modifier = Modifier.testTag(PronunciationDialogTestTag),
        title = { Text("REPORT A PRONUNCIATION") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    listOfNotNull(draft.chapterTitle.takeIf { it.isNotBlank() }, formatReportPosition(draft.positionMs / 1000.0))
                        .joinToString("  ·  "),
                    color = AarisColor.Dim,
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = draft.word,
                    onValueChange = holder::setWord,
                    label = { Text("THE WORD") },
                    supportingText = { Text("As it is written — the server's tools match on the text.") },
                    singleLine = true,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().testTag(PronunciationWordFieldTestTag),
                )
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = holder::setNote,
                    label = { Text("NOTE (OPTIONAL)") },
                    supportingText = { Text("How it should sound, if you can say.") },
                    minLines = 2,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                draft.problem?.takeIf { draft.word.isNotEmpty() || draft.note.isNotEmpty() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = holder::send,
                enabled = draft.canSend && !ui.busy,
                shape = RectangleShape,
                modifier = Modifier.testTag(PronunciationSendTestTag),
            ) { Text(if (ui.busy) "SENDING…" else "REPORT") }
        },
        dismissButton = {
            TextButton(onClick = holder::dismiss, shape = RectangleShape) { Text("CANCEL") }
        },
    )
}
