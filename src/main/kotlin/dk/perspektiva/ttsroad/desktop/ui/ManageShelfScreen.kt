package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionSummary

const val ManageShelfScreenTestTag: String = "manageShelfScreen"
const val UnfollowSelectedTestTag: String = "unfollowSelected"
const val ShelfRowTestTagPrefix: String = "shelfRow:"

/**
 * The followed shelf as something to edit.
 *
 * Subtractive by design — there is no bulk *follow* here. A shelf gets filled without anybody
 * pressing Follow (the per-user library upgrade backfilled every account with every fiction, and
 * adding one auto-follows it for the adder), so the operation that was missing is the one that
 * empties it, and a faster way to fill it would not answer the same complaint.
 *
 * Unfollow is drawn as a plain action rather than a tinted destructive one: colour here carries
 * severity, and nothing is destroyed. Following decides whose dashboard and default library view a
 * fiction appears on and nothing else — progress is kept, the book stays openable, and it can be
 * followed again. The confirmation says so, because the word invites the opposite guess.
 */
@Composable
fun ManageShelfScreen(holder: ManageShelfStateHolder, onBack: () -> Unit) {
    val ui by holder.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .padding(horizontal = PageGutter, vertical = 20.dp)
                .testTag(ManageShelfScreenTestTag),
        ) {
            BackLink("Back", onBack)
            Spacer(Modifier.height(12.dp))
            SectionTitle("shelf", "Fictions you follow")
            Spacer(Modifier.height(8.dp))
            Text(
                "Following decides what appears on your shelf and in Continue listening. " +
                    "Unfollowing removes nothing else.",
                color = AarisColor.Dim,
                style = MaterialTheme.typography.bodyMedium,
            )
            ui.notice?.let {
                Spacer(Modifier.height(12.dp))
                InlineNotice(it) { holder.dismissNotice() }
            }
            Spacer(Modifier.height(16.dp))

            if (ui.fictions.isEmpty()) {
                EmptyState(
                    "Nothing on your shelf",
                    "Open a fiction under all fictions and follow it to see it here.",
                )
                return@Column
            }

            ShelfControls(
                total = ui.fictions.size,
                selected = ui.selectedCount,
                allSelected = ui.allSelected,
                busy = ui.isBusy,
                canUnfollow = ui.canUnfollow,
                onToggleAll = holder::toggleAll,
                onUnfollow = holder::askToUnfollow,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(ui.fictions, key = { it.id }) { fiction ->
                    ShelfRow(
                        fiction = fiction,
                        checked = fiction.id in ui.selected,
                        enabled = !ui.isBusy,
                        onToggle = { holder.toggle(fiction.id) },
                    )
                }
            }
        }
    }

    ui.confirming?.let { count ->
        ConfirmDialog(
            title = if (count == 1) "UNFOLLOW ONE FICTION" else "UNFOLLOW $count FICTIONS",
            body = ui.confirmationBody.orEmpty(),
            confirmLabel = "UNFOLLOW",
            onConfirm = holder::confirmUnfollow,
            onDismiss = holder::dismissConfirmation,
        )
    }
}

@Composable
private fun ShelfControls(
    total: Int,
    selected: Int,
    allSelected: Boolean,
    busy: Boolean,
    canUnfollow: Boolean,
    onToggleAll: () -> Unit,
    onUnfollow: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AarisSecondaryAction(
            label = if (allSelected) "Select none" else "Select all",
            onClick = onToggleAll,
            enabled = !busy,
        )
        Text(
            // States the total as well as the selection: "3 selected" alone does not say of what,
            // and the shelf is exactly the number somebody came here to reduce.
            if (selected == 0) "$total on your shelf" else "$selected of $total selected",
            color = AarisColor.Dim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        AarisSecondaryAction(
            label = if (busy) "Unfollowing…" else "Unfollow selected",
            onClick = onUnfollow,
            enabled = canUnfollow,
            modifier = Modifier.testTag(UnfollowSelectedTestTag),
        )
    }
}

@Composable
private fun ShelfRow(
    fiction: FictionSummary,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (checked) AarisColor.BgHover else AarisColor.BgRaise)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("$ShelfRowTestTagPrefix${fiction.id}"),
    ) {
        // Null, because the whole row carries the toggle: a checkbox with its own click target
        // inside a toggleable row is two controls reporting one state.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(fiction.title, color = AarisColor.Ink, style = MaterialTheme.typography.bodyMedium)
            fiction.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AarisColor.Dim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
