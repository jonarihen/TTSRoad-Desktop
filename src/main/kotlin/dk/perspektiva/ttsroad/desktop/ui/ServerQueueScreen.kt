package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ServerQueueItem
import dk.perspektiva.ttsroad.desktop.data.remainingSeconds
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlin.time.Duration.Companion.seconds

const val ServerQueueRowTestTag: String = "serverQueueRow"

/**
 * The account's cross-library queue.
 *
 * A **browsable surface**, which is the design decision this screen embodies and the reason it does
 * not touch the player's own queue: playing a row here opens that row's fiction and starts it the
 * ordinary way, so what happens at the end of a chapter is exactly what it was before this screen
 * existed. The alternative — driving playback from the server's `advance` action — would put the
 * network in the path of auto-advance, and the local queue is what keeps working when the server is
 * unreachable. See `ServerQueueStateHolder.play`.
 */
@Composable
fun ServerQueueScreen(
    holder: ServerQueueStateHolder,
    playback: PlaybackController,
    onBack: () -> Unit,
    onOpenFiction: (ServerQueueItem) -> Unit = {},
) {
    val s by holder.state.collectAsState()
    val player by playback.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { holder.ensureLoaded() }

    val playingChapterId = remember(player.queue, player.currentIndex) {
        player.queue.getOrNull(player.currentIndex)?.chapterId?.takeIf { it > 0 }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(PageGutter),
        ) {
            item(key = "head", contentType = "header") {
                Column {
                    BackLink("Back", onBack)
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("01", queueCountLabel(s))
                    Spacer(Modifier.height(12.dp))

                    QueueSummary(s)

                    if (s.items.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BulkAction("Clear queue", enabled = !s.isBusy, onClick = holder::askClear)
                        }
                    }

                    s.error?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    s.notice?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        MetaText(message, color = AarisColor.Ok)
                    }

                    when {
                        s.unsupported -> {
                            Spacer(Modifier.height(32.dp))
                            EmptyState(
                                "No shared queue on this server",
                                "This server does not offer the cross-library queue. The player still " +
                                    "builds a queue from the fiction you are listening to.",
                            )
                        }

                        s.isInitialLoad -> Box(
                            Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) { CenterProgress() }

                        s.loaded == null && s.error != null -> {
                            Spacer(Modifier.height(32.dp))
                            InitialErrorState(s.error.orEmpty()) { holder.refresh() }
                        }

                        s.items.isEmpty() -> {
                            Spacer(Modifier.height(32.dp))
                            EmptyState(
                                "Nothing queued",
                                "Add chapters from a fiction's chapter list. The queue is shared with " +
                                    "every client signed in to this account.",
                            )
                        }

                        else -> Spacer(Modifier.height(8.dp))
                    }
                }
            }

            itemsIndexed(
                s.items,
                key = { _, item -> item.id },
                contentType = { _, _ -> "queueItem" },
            ) { index, item ->
                Column {
                    ServerQueueRow(
                        item = item,
                        position = index + 1,
                        isPlaying = playingChapterId == item.chapterId,
                        enabled = !s.isBusy,
                        canMoveUp = index > 0,
                        canMoveDown = index < s.items.lastIndex,
                        onPlay = { holder.play(item) },
                        onOpenFiction = { onOpenFiction(item) },
                        onMoveUp = { holder.move(index, index - 1) },
                        onMoveDown = { holder.move(index, index + 1) },
                        onRemove = { holder.remove(item) },
                    )
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
                }
            }
        }
        RefreshingStrip(s.isLoading && s.loaded != null)
    }

    if (s.confirmingClear) {
        ConfirmDialog(
            title = "Clear the queue?",
            body = "This empties the queue for every client signed in to this account. Nothing is " +
                "deleted and no progress is lost — the chapters simply stop being queued.",
            confirmLabel = "CLEAR",
            onConfirm = holder::confirmClear,
            onDismiss = holder::dismissConfirmation,
        )
    }
}

/** "Up next — 12 chapters", or what is loading/absent instead. */
internal fun queueCountLabel(s: ServerQueueUiState): String = when {
    s.unsupported -> "Up next — unavailable"
    s.loaded == null -> "Up next"
    s.items.size == 1 -> "Up next — 1 chapter"
    else -> "Up next — ${s.items.size} chapters"
}

/**
 * Time left, the cap, and what the server will do when the queue empties.
 *
 * The last of those is stated rather than quietly ignored: `queue_when_empty` is an account
 * preference this client reads and does not act on, because acting on it means calling `advance`.
 * Saying so is better than a user discovering the discrepancy between two clients on their own.
 */
@Composable
private fun QueueSummary(s: ServerQueueUiState) {
    if (s.loaded == null || s.items.isEmpty()) return
    val remaining = remember(s.items) { s.items.remainingSeconds() }
    val parts = listOfNotNull(
        formatDuration(remaining.seconds.inWholeMilliseconds).takeIf { remaining > 0 }?.let { "$it left" },
        s.maxItems.takeIf { it > 0 }?.let { "${s.items.size} of $it" },
    )
    Column {
        if (parts.isNotEmpty()) MetaText(parts.joinToString("  ·  "))
        s.whenEmpty?.let { whenEmpty ->
            Spacer(Modifier.height(6.dp))
            MetaText(whenEmptyExplanation(whenEmpty), color = AarisColor.Dim)
        }
    }
}

/**
 * What the account's `queue_when_empty` means here.
 *
 * "on other clients" is the honest part: this client does not call `advance`, so the preference
 * governs what the web player and Android Auto do, not what happens when a chapter ends here.
 */
internal fun whenEmptyExplanation(whenEmpty: String): String = when (whenEmpty) {
    "continue" -> "When this queue empties, other clients continue with the oldest unplayed chapter"
    "stop" -> "When this queue empties, other clients stop"
    else -> "When this queue empties: $whenEmpty"
}

@Composable
private fun ServerQueueRow(
    item: ServerQueueItem,
    position: Int,
    isPlaying: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onOpenFiction: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pointerOver || focused

    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ServerQueueRowTestTag)
            .hoverable(interaction, enabled = enabled)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onPlay)
            .background(
                when {
                    isPlaying -> AarisColor.BgHover
                    active -> AarisColor.BgRaise
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    focused -> AarisColor.Accent
                    isPlaying -> AarisColor.Accent.copy(alpha = 0.4f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(
            "%02d".format(position),
            color = if (isPlaying) AarisColor.Accent else AarisColor.Dim,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.resolvedTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) AarisColor.Accent else AarisColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // The fiction is the reason this screen exists — a queue that spans books is unreadable
            // if a row only says "Chapter 12" — so it gets its own line and its own click target.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FictionLink(item.resolvedFictionTitle, enabled = enabled, onClick = onOpenFiction)
                val meta = listOfNotNull(
                    if (isPlaying) "Playing" else null,
                    item.audioDurationLabel?.takeIf { it.isNotBlank() },
                    if (item.isPlayed) "Played" else null,
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    MetaText(meta, color = if (isPlaying) AarisColor.Accent else AarisColor.Dim)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        RowIconAction(
            icon = Icons.Default.ArrowUpward,
            contentDescription = "Move ${item.resolvedTitle} up",
            tint = AarisColor.Dim,
            enabled = enabled && canMoveUp,
            onClick = onMoveUp,
        )
        Spacer(Modifier.width(4.dp))
        RowIconAction(
            icon = Icons.Default.ArrowDownward,
            contentDescription = "Move ${item.resolvedTitle} down",
            tint = AarisColor.Dim,
            enabled = enabled && canMoveDown,
            onClick = onMoveDown,
        )
        Spacer(Modifier.width(4.dp))
        RowIconAction(
            icon = Icons.Default.Close,
            contentDescription = "Remove ${item.resolvedTitle} from the queue",
            tint = AarisColor.Dim,
            enabled = enabled,
            onClick = onRemove,
        )
        Spacer(Modifier.width(4.dp))
        RowIconAction(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play ${item.resolvedTitle}",
            tint = if (active || isPlaying) AarisColor.Accent else AarisColor.Dim,
            enabled = enabled,
            onClick = onPlay,
        )
    }
}

/** The fiction name as a link, so a queue row is also a way into the book it came from. */
@Composable
private fun FictionLink(title: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    MetaText(
        title,
        color = if (pointerOver || focused) AarisColor.Ink else AarisColor.Muted,
        modifier = Modifier
            .hoverable(interaction, enabled = enabled)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
    )
}
