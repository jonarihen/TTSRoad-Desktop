package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.initiallyExpandedVoiceLocale
import dk.perspektiva.ttsroad.desktop.data.voiceGroups

const val VoicePickerTestTag: String = "voice-picker"
const val VoicePickerSearchTestTag: String = "voice-picker-search"
const val ChooseVoiceButtonTestTag: String = "choose-voice"

/**
 * The voice control: what is chosen now, and a way to change it.
 *
 * A row rather than a text field, because the value is no longer typed. The full wire name is on
 * screen next to the readable one — `en-US-BrianNeural` is what is stored, and two locales' "Brian"
 * are different narrators — so the row states the thing that will actually be saved.
 *
 * [voices] being null is a server without the catalogue route. The caller draws its own text field
 * in that case rather than this; there is no half-picker.
 */
@Composable
fun VoiceField(
    voices: List<MobileVoice>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "VOICE",
    /** What a blank selection means here. Add and edit answer this differently. */
    emptyLabel: String = "Server default",
) {
    var picking by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AarisColor.Dim, style = MaterialTheme.typography.labelMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selected.trim().ifEmpty { emptyLabel },
                color = if (selected.isBlank()) AarisColor.Dim else AarisColor.Ink,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            AarisSecondaryAction(
                label = if (selected.isBlank()) "Choose…" else "Change…",
                onClick = { picking = true },
                enabled = enabled,
                modifier = Modifier.testTag(ChooseVoiceButtonTestTag),
            )
        }
    }

    if (picking) {
        VoicePickerDialog(
            voices = voices,
            current = selected,
            onPick = {
                onSelect(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The catalogue, grouped and searchable.
 *
 * Several hundred narrators across a hundred-odd locales, so every group is collapsed except the
 * one holding the current voice — "which is it now" and "what else is near it" are the same
 * question, and answering it should not require scrolling past ninety locales of alphabet.
 *
 * A search opens everything it matched, because a collapsed group that contains the only hit is a
 * search that looks like it failed.
 */
@Composable
fun VoicePickerDialog(
    voices: List<MobileVoice>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val groups = voiceGroups(voices = voices, current = current, query = query)
    val initiallyOpen = remember(voices, current) {
        initiallyExpandedVoiceLocale(voiceGroups(voices, current), current)
    }
    var expanded by remember(initiallyOpen) { mutableStateOf(setOfNotNull(initiallyOpen)) }
    val searching = query.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text("CHOOSE A VOICE") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("SEARCH") },
                    supportingText = { Text("A name, a language, or a gender") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(VoicePickerSearchTestTag),
                )
                if (groups.isEmpty()) {
                    Text(
                        if (searching) "No voice matches \"${query.trim()}\"." else "This server published no voices.",
                        color = AarisColor.Dim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag(VoicePickerTestTag),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        groups.forEach { group ->
                            val open = searching || group.locale in expanded
                            item(key = "group-${group.locale}") {
                                LocaleHeader(
                                    label = group.label,
                                    locale = group.locale,
                                    count = group.voices.size,
                                    expanded = open,
                                    // While searching, every matched group is already open and the
                                    // chevron would be a control that does nothing.
                                    onToggle = if (searching) null else {
                                        {
                                            expanded = if (group.locale in expanded) {
                                                expanded - group.locale
                                            } else {
                                                expanded + group.locale
                                            }
                                        }
                                    },
                                )
                            }
                            if (open) {
                                items(group.voices, key = { it.name }) { choice ->
                                    VoiceRow(
                                        shortName = choice.shortName,
                                        detail = choice.detail,
                                        chosen = choice.name == current.trim(),
                                        onClick = { onPick(choice.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("CANCEL") } },
    )
}

@Composable
private fun LocaleHeader(
    label: String,
    locale: String,
    count: Int,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle).pointerHoverIcon(PointerIcon.Hand) else it }
            .background(AarisColor.BgRaise)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            label.uppercase(),
            color = AarisColor.Ink,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Text("$locale  ·  $count", color = AarisColor.Dim, style = MaterialTheme.typography.labelSmall)
        if (onToggle != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AarisColor.Dim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun VoiceRow(shortName: String, detail: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(if (chosen) Modifier.border(1.dp, AarisColor.Accent) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(shortName, color = AarisColor.Ink, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = AarisColor.Dim, style = MaterialTheme.typography.labelSmall)
        }
        if (chosen) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Currently selected",
                tint = AarisColor.Accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
