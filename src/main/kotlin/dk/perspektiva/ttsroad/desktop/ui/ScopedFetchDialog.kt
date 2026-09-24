package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionFetchCounts
import dk.perspektiva.ttsroad.desktop.data.FictionFetchRange
import dk.perspektiva.ttsroad.desktop.data.FictionPollScope

const val ScopedFetchDialogTestTag: String = "scopedFetchDialog"
const val FetchAllOptionTestTag: String = "fetchAllOption"
const val FetchRangeFirstTestTag: String = "fetchRangeFirst"
const val FetchRangeLastTestTag: String = "fetchRangeLast"
const val CustomCountFieldTestTag: String = "customCountField"
const val SubmitFetchButtonTestTag: String = "submitFetchButton"

@Composable
fun ScopedFetchDialog(
    fictionTitle: String,
    busy: Boolean,
    onFetch: (FictionPollScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(FictionFetchRange.Last) }
    var selectedCount by remember { mutableStateOf<Int?>(25) }
    var customText by remember { mutableStateOf("") }
    var isCustom by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = null
        if (range == FictionFetchRange.All) {
            onFetch(FictionPollScope(full = true))
            return
        }
        val count = if (isCustom) {
            val parsed = customText.trim().toIntOrNull()
            if (parsed == null || parsed <= 0) {
                error = "Count must be a positive number"
                return
            }
            parsed
        } else {
            selectedCount
        }
        if (count == null || count <= 0) {
            error = "Choose or enter a positive number of chapters"
            return
        }
        val scope = when (range) {
            FictionFetchRange.First -> FictionPollScope(firstN = count)
            FictionFetchRange.Last -> FictionPollScope(lastN = count)
            FictionFetchRange.All -> FictionPollScope(full = true)
        }
        onFetch(scope)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = AarisColor.BgRaise,
        modifier = Modifier.testTag(ScopedFetchDialogTestTag),
        title = { Text("FETCH CHAPTERS — ${fictionTitle.uppercase()}") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Fetch a scoped slice of chapters from the source instead of waiting for scheduled polling.",
                    color = AarisColor.Dim,
                    style = MaterialTheme.typography.bodyMedium,
                )

                MetaText("Scope", color = AarisColor.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FetchTab(
                        label = "Last",
                        selected = range == FictionFetchRange.Last,
                        tag = FetchRangeLastTestTag,
                    ) {
                        range = FictionFetchRange.Last
                        error = null
                    }
                    FetchTab(
                        label = "First",
                        selected = range == FictionFetchRange.First,
                        tag = FetchRangeFirstTestTag,
                    ) {
                        range = FictionFetchRange.First
                        error = null
                    }
                    FetchTab(
                        label = "Fetch all",
                        selected = range == FictionFetchRange.All,
                        tag = FetchAllOptionTestTag,
                    ) {
                        range = FictionFetchRange.All
                        error = null
                    }
                }

                if (range == FictionFetchRange.All) {
                    Text(
                        "Re-reads the complete backlog from the source. This may take longer.",
                        color = AarisColor.Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    MetaText("Chapter count", color = AarisColor.Dim)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FictionFetchCounts.forEach { count ->
                            FetchTab(
                                label = count.toString(),
                                selected = !isCustom && selectedCount == count,
                                tag = "fetchCount$count",
                            ) {
                                isCustom = false
                                selectedCount = count
                                error = null
                            }
                        }
                    }

                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it.filter(Char::isDigit)
                            isCustom = true
                            error = null
                        },
                        label = { Text("Custom count") },
                        placeholder = { Text("e.g. 15") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag(CustomCountFieldTestTag),
                        shape = RectangleShape,
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                enabled = !busy,
                shape = RectangleShape,
                modifier = Modifier.testTag(SubmitFetchButtonTestTag),
            ) {
                Text(if (busy) "FETCHING…" else "FETCH")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RectangleShape) {
                Text("CANCEL")
            }
        },
    )
}

@Composable
private fun FetchTab(
    label: String,
    selected: Boolean,
    tag: String? = null,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .let { if (tag != null) it.testTag(tag) else it }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onSelect,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else if (selected) AarisColor.Line else AarisColor.LineSoft)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        MetaText(label, color = if (selected) AarisColor.Accent else AarisColor.Muted)
    }
}
