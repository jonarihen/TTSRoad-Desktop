package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.SyncScope

const val AddFictionDialogTestTag: String = "addFictionDialog"
const val ChooseEpubButtonTestTag: String = "chooseEpubButton"
const val ChosenEpubNameTestTag: String = "chosenEpubName"
const val AddRateFieldTestTag: String = "addRateField"

/**
 * The holder owns the answers; this composable only renders its one active question.
 *
 * Two questions are left here, and both are one-shot: which fiction to add, and whether the user
 * really means to delete one. Editing an existing fiction moved to a screen of its own — a dialog
 * is the wrong shape for a form whose every field is shared with every account, and whose cover
 * control needs a native file picker.
 */
@Composable
fun FictionManagementDialogs(holder: FictionManagementStateHolder) {
    val state by holder.state.collectAsState()
    state.editor?.let { editor ->
        AddFictionDialog(
            editor = editor,
            isBusy = state.isBusy,
            error = state.error,
            epubUploadAvailable = state.epubUploadAvailable,
            voices = state.voices.takeIf { state.canPickVoice },
            rateProblem = state.rateProblem,
            onUpdateAdd = holder::updateAdd,
            onChooseEpub = holder::chooseEpub,
            onClearEpub = holder::clearEpub,
            onSubmit = holder::submitAdd,
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
private fun AddFictionDialog(
    editor: FictionAddDraft,
    isBusy: Boolean,
    error: String?,
    epubUploadAvailable: Boolean,
    /** The narrator catalogue, or null on a server without it — then the voice stays typed. */
    voices: List<MobileVoice>?,
    rateProblem: String?,
    onUpdateAdd: (String?, String?, String?, Boolean?, SyncScope?) -> Unit,
    onChooseEpub: () -> Unit,
    onClearEpub: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val epub = editor.epubFile
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.testTag(AddFictionDialogTestTag),
        containerColor = AarisColor.BgRaise,
        title = {
            Text(
                "ADD FICTION",
                style = MaterialTheme.typography.titleLarge,
                color = AarisColor.Ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editor.fictionUrl,
                    onValueChange = { onUpdateAdd(it, null, null, null, null) },
                    label = { Text("ROYAL ROAD URL OR FICTION ID") },
                    supportingText = if (epub != null) {
                        { Text("Not used while an EPUB is chosen") }
                    } else {
                        null
                    },
                    // Disabled rather than hidden: the field disappearing under the cursor after a
                    // file picker closes is disorienting, and the reason it is inert is worth
                    // saying rather than implying.
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
                if (voices != null) {
                    VoiceField(
                        voices = voices,
                        selected = editor.voice,
                        enabled = !isBusy,
                        onSelect = { onUpdateAdd(null, it, null, null, null) },
                        label = "VOICE (OPTIONAL)",
                    )
                } else {
                    // No catalogue on this server, so the exact name still has to be typed. Worth
                    // keeping rather than hiding: it is the only way to set a voice at all there.
                    OutlinedTextField(
                        value = editor.voice,
                        onValueChange = { onUpdateAdd(null, it, null, null, null) },
                        label = { Text("VOICE (OPTIONAL)") },
                        supportingText = { Text("Blank uses the server default") },
                        enabled = !isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = editor.rate,
                    onValueChange = { onUpdateAdd(null, null, it, null, null) },
                    label = { Text("SPEECH RATE (OPTIONAL)") },
                    // The server stores this string without checking it, so a typo does not fail
                    // here — it fails hours later as a chapter that will not narrate. This is the
                    // only place it can be caught.
                    isError = rateProblem != null,
                    supportingText = {
                        Text(
                            rateProblem
                                ?: "Blank uses the server default. Nothing already converted is re-narrated.",
                        )
                    },
                    enabled = !isBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(AddRateFieldTestTag),
                )
                // Only meaningful on the Royal Road path: an EPUB has no source to poll and no
                // backlog to bound, so offering either control there would be inventing a choice.
                if (epub == null) {
                    SyncScopeChoice(
                        selected = editor.syncScope,
                        enabled = !isBusy,
                        onSelect = { onUpdateAdd(null, null, null, null, it) },
                    )
                    AutoPollChoice(
                        enabled = !isBusy,
                        checked = editor.autoPoll,
                        onChange = { onUpdateAdd(null, null, null, it, null) },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                // A malformed rate is refused here rather than sent. The server would accept it.
                enabled = !isBusy && rateProblem == null,
                shape = RectangleShape,
            ) {
                Text(
                    when {
                        isBusy && epub != null -> "UPLOADING…"
                        isBusy -> "ADDING…"
                        epub != null -> "UPLOAD EPUB"
                        else -> "ADD FICTION"
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

const val SyncScopeTestTag: String = "addFictionSyncScope"
const val AutoPollTestTag: String = "addFictionAutoPoll"

/**
 * How much of the backlog to convert.
 *
 * Drawn as a choice rather than left to a default because the default the *server* applies when
 * this is absent is "everything", and the cost of that is hours of TTS on a long serial. The
 * whole-backlog option says what it costs before it is picked, not after.
 */
@Composable
private fun SyncScopeChoice(selected: SyncScope, enabled: Boolean, onSelect: (SyncScope) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(SyncScopeTestTag)) {
        MetaText("// Chapters to convert now")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SyncScope.entries.forEach { scope ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = scope == selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(scope) },
                        )
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (scope == selected) "· ${scope.label}" else scope.label,
                        color = when {
                            !enabled -> AarisColor.Dim
                            scope == selected -> AarisColor.Accent
                            else -> AarisColor.Muted
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MetaText(scope.detail, color = AarisColor.Dim)
                }
            }
        }
    }
}

/** The server's `enabled` flag, named for what it does rather than for what the field is called. */
@Composable
private fun AutoPollChoice(enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onChange(it) }
            .testTag(AutoPollTestTag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (checked) "[x]" else "[ ]",
            color = if (checked) AarisColor.Accent else AarisColor.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Keep checking for new chapters",
                color = if (enabled) AarisColor.Ink else AarisColor.Dim,
                style = MaterialTheme.typography.bodyMedium,
            )
            MetaText("Turn off for a finished work.", color = AarisColor.Dim)
        }
    }
}
