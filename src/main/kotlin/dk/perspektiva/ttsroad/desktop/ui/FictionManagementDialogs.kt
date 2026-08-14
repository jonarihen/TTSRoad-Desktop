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

const val AddFictionDialogTestTag: String = "addFictionDialog"
const val EditFictionDialogTestTag: String = "editFictionDialog"

/** The holder owns the answers; this composable only renders its one active question. */
@Composable
fun FictionManagementDialogs(holder: FictionManagementStateHolder) {
    val state by holder.state.collectAsState()
    state.editor?.let { editor ->
        FictionEditorDialog(
            editor = editor,
            isBusy = state.isBusy,
            error = state.error,
            onUpdateAdd = holder::updateAdd,
            onUpdateEdit = holder::updateEdit,
            onSubmit = holder::submitEditor,
            onDismiss = holder::dismissOverlay,
        )
    }
    state.deleteConfirmation?.let { pending ->
        ConfirmDialog(
            title = "DELETE ${pending.title.uppercase()}",
            body = "This permanently deletes the fiction, every chapter and every user's " +
                "listening progress from the server. This cannot be undone.",
            confirmLabel = "DELETE FOR EVERYONE",
            onConfirm = holder::confirmDelete,
            onDismiss = holder::dismissOverlay,
        )
    }
}

@Composable
private fun FictionEditorDialog(
    editor: FictionEditor,
    isBusy: Boolean,
    error: String?,
    onUpdateAdd: (String?, String?) -> Unit,
    onUpdateEdit: (String?, String?, String?) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val adding = editor is FictionEditor.Add
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.testTag(if (adding) AddFictionDialogTestTag else EditFictionDialogTestTag),
        containerColor = AarisColor.BgRaise,
        title = {
            Text(
                if (adding) "ADD FICTION" else "EDIT FICTION",
                style = MaterialTheme.typography.titleLarge,
                color = AarisColor.Ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (editor) {
                    is FictionEditor.Add -> {
                        OutlinedTextField(
                            value = editor.fictionUrl,
                            onValueChange = { onUpdateAdd(it, null) },
                            label = { Text("ROYAL ROAD URL OR FICTION ID") },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = editor.voice,
                            onValueChange = { onUpdateAdd(null, it) },
                            label = { Text("VOICE (OPTIONAL)") },
                            supportingText = { Text("Blank uses the server default") },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is FictionEditor.Edit -> {
                        OutlinedTextField(
                            value = editor.title,
                            onValueChange = { onUpdateEdit(it, null, null) },
                            label = { Text("TITLE") },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = editor.author,
                            onValueChange = { onUpdateEdit(null, it, null) },
                            label = { Text("AUTHOR") },
                            supportingText = { Text("Blank clears the author") },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = editor.voice,
                            onValueChange = { onUpdateEdit(null, null, it) },
                            label = { Text("VOICE") },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !isBusy,
                shape = RectangleShape,
            ) { Text(if (isBusy) "SAVING…" else if (adding) "ADD FICTION" else "SAVE CHANGES") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy, shape = RectangleShape) {
                Text("CANCEL")
            }
        },
    )
}
