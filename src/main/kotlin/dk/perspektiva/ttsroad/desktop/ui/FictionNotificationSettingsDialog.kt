package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationModes
import dk.perspektiva.ttsroad.desktop.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.desktop.data.NotificationModeEvery
import dk.perspektiva.ttsroad.desktop.data.NotificationModeOff
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.canConfigureFictionNotifications
import dk.perspektiva.ttsroad.desktop.data.notificationStatusDescription
import dk.perspektiva.ttsroad.desktop.data.notificationStatusLabel
import dk.perspektiva.ttsroad.desktop.data.remainingBacklogLabel
import dk.perspektiva.ttsroad.desktop.data.validBacklogHours

const val FictionNotificationOpenTestTag: String = "fictionNotificationOpen"
const val FictionNotificationDialogTestTag: String = "fictionNotificationDialog"
const val FictionNotificationHoursTestTag: String = "fictionNotificationHours"
const val FictionNotificationSaveTestTag: String = "fictionNotificationSave"
const val FictionNotificationRetryTestTag: String = "fictionNotificationRetry"
const val FictionNotificationStatusTestTag: String = "fictionNotificationStatus"

@Composable
fun FictionNotificationSettingsControl(
    repository: TtsRoadRepository,
    fictionId: Int,
    isFollowed: Boolean,
    sessionKey: Any?,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
    val capabilities by repository.currentCapabilities.collectAsState()
    if (!canConfigureFictionNotifications(capabilities, isFollowed)) return
    val savedCallback by rememberUpdatedState(onSaved)
    val holder = rememberStateHolder(repository, fictionId, sessionKey) {
        FictionNotificationSettingsStateHolder(repository, fictionId, onSaved = { savedCallback() })
    }
    val state by holder.state.collectAsState()
    AarisSecondaryAction(
        label = "Chapter notifications",
        onClick = holder::open,
        enabled = !state.busy,
        modifier = modifier.testTag(FictionNotificationOpenTestTag).semantics {
            contentDescription = "Chapter notifications"
            stateDescription = notificationStatusLabel(state.settings) + if (state.stale) "; last known state" else ""
        },
    )
    FictionNotificationSettingsDialog(holder)
}

@Composable
fun FictionNotificationSettingsDialog(holder: FictionNotificationSettingsStateHolder) {
    val state by holder.state.collectAsState()
    if (!state.visible) return
    DisposableEffect(holder) { onDispose { holder.close() } }
    AlertDialog(
        onDismissRequest = { if (!state.saving) holder.close() },
        shape = RectangleShape,
        containerColor = AarisColor.BgRaise,
        modifier = Modifier.testTag(FictionNotificationDialogTestTag).semantics { paneTitle = "Chapter notifications" },
        title = { Text("CHAPTER NOTIFICATIONS") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaText("Saved status")
                Text(
                    notificationStatusLabel(state.settings),
                    color = AarisColor.Ink,
                    modifier = Modifier.testTag(FictionNotificationStatusTestTag),
                )
                Text(notificationStatusDescription(state.settings), color = AarisColor.Muted)
                state.settings?.remainingSeconds?.let(::remainingBacklogLabel)?.let {
                    Text(it, color = AarisColor.Accent)
                }
                if (state.stale) {
                    Text("Last known state. Refresh to confirm the current server settings.", color = AarisColor.Warning)
                }
                if (state.loading) Text("Checking server settings…", color = AarisColor.Muted)
                state.error?.let { PoliteStatus(it, error = true) }
                if (state.error != null || state.stale) {
                    AarisSecondaryAction(
                        label = "Retry",
                        onClick = holder::refresh,
                        enabled = !state.busy,
                        modifier = Modifier.testTag(FictionNotificationRetryTestTag),
                    )
                }
                AarisChoiceRow(
                    label = "Notification mode",
                    options = FictionNotificationModes,
                    selected = state.draft?.mode.orEmpty(),
                    labelOf = {
                        when (it) {
                            NotificationModeEvery -> "Every chapter"
                            NotificationModeOff -> "Off"
                            else -> "Backlog alert"
                        }
                    },
                    onSelect = holder::setMode,
                    enabled = state.canEdit,
                )
                if (state.draft?.mode == NotificationModeBacklog) {
                    val hours = state.draft?.hours.orEmpty()
                    AarisChoiceRow(
                        label = "Backlog threshold",
                        options = listOf(1.0, 2.0, 5.0),
                        selected = validBacklogHours(hours) ?: Double.NaN,
                        labelOf = { "${it.toInt()}h" },
                        onSelect = { holder.setHours(it.toInt().toString()) },
                        enabled = state.canEdit,
                    )
                    OutlinedTextField(
                        value = hours,
                        onValueChange = holder::setHours,
                        label = { Text("Custom hours") },
                        supportingText = { Text("Enter a number over 0 and up to 1000") },
                        isError = validBacklogHours(hours) == null,
                        enabled = state.canEdit,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(FictionNotificationHoursTestTag),
                    )
                    Text(
                        "Ready, unplayed audio is counted at 1x speed. One alert at the threshold; " +
                            "listen below it to re-arm.",
                        color = AarisColor.Muted,
                    )
                }
                Text("Changing this setting clears this book's existing chapter notices.", color = AarisColor.Dim)
                if (state.dirty) Text("Not saved yet.", color = AarisColor.Warning)
            }
        },
        confirmButton = {
            AarisPrimaryAction(
                label = if (state.saving) "Saving…" else "Save",
                onClick = holder::save,
                enabled = state.canSave,
                modifier = Modifier.testTag(FictionNotificationSaveTestTag),
            )
        },
        dismissButton = {
            AarisSecondaryAction(label = "Close", onClick = holder::close, enabled = !state.saving)
        },
    )
}
