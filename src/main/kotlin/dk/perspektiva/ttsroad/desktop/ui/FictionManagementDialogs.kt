package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val AddFictionDialogTestTag: String = "addFictionDialog"
const val ChooseEpubButtonTestTag: String = "chooseEpubButton"
const val ChosenEpubNameTestTag: String = "chosenEpubName"
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
            epubUploadAvailable = state.epubUploadAvailable,
            onUpdateAdd = holder::updateAdd,
            onUpdateEdit = holder::updateEdit,
            onChooseEpub = holder::chooseEpub,
            onClearEpub = holder::clearEpub,
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
    epubUploadAvailable: Boolean,
    onUpdateAdd: (String?, String?) -> Unit,
    onUpdateEdit: (String?, String?, String?) -> Unit,
    onChooseEpub: () -> Unit,
    onClearEpub: () -> Unit,
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
                        val epub = editor.epubFile
                        OutlinedTextField(
                            value = editor.fictionUrl,
                            onValueChange = { onUpdateAdd(it, null) },
                            label = { Text("ROYAL ROAD URL OR FICTION ID") },
                            supportingText = if (epub != null) {
                                { Text("Not used while an EPUB is chosen") }
                            } else {
                                null
                            },
                            // Disabled rather than hidden: the field disappearing under the
                            // cursor after a file picker closes is disorienting, and the reason
                            // it is inert is worth saying rather than implying.
                            enabled = !isBusy && epub == null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (epubUploadAvailable) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(
                                    onClick = if (epub == null) onChooseEpub else onClearEpub,
                                    enabled = !isBusy,
                                    shape = RectangleShape,
                                    modifier = Modifier.testTag(ChooseEpubButtonTestTag),
                                ) { Text(if (epub == null) "OR UPLOAD AN EPUB…" else "REMOVE EPUB") }
                                epub?.let {
                                    Text(
                                        it.name,
                                        color = AarisColor.Ink,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag(ChosenEpubNameTestTag),
                                    )
                                }
                            }
                        }
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
            ) {
                val uploading = editor is FictionEditor.Add && editor.epubFile != null
                Text(
                    when {
                        isBusy && uploading -> "UPLOADING…"
                        isBusy -> "SAVING…"
                        uploading -> "UPLOAD EPUB"
                        adding -> "ADD FICTION"
                        else -> "SAVE CHANGES"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy, shape = RectangleShape) {
                Text("CANCEL")
            }
        },
    )
}
