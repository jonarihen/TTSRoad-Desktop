package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.QueueState
import dk.perspektiva.ttsroad.desktop.data.ServerQueueItem
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.launch

/**
 * The shared Up Next queue.
 *
 * A surface you play *from*, not a replacement for reading order. Playing an item does not consume
 * it and nothing here changes what happens when a chapter ends — see #38 for why that coupling is
 * left alone for now.
 */
@Composable
fun QueueScreen(
    repository: TtsRoadRepository,
    onPlayChapter: (fictionId: Int, chapterId: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<QueueState>>(Load.Loading) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        state = runCatching { repository.queue() }
            .fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Could not load the queue") })
    }

    LaunchedEffect(Unit) { load() }

    /** Every mutation answers with the whole queue, so the result replaces local state wholesale. */
    fun mutate(block: suspend () -> QueueState) {
        if (busy) return
        scope.launch {
            busy = true
            actionError = null
            runCatching { block() }
                .fold(
                    onSuccess = { state = Load.Ok(it) },
                    onFailure = { actionError = it.message ?: "Could not update the queue" },
                )
            busy = false
        }
    }

    when (val s = state) {
        Load.Loading -> CenterProgress()
        is Load.Err -> CenterError(s.message)
        is Load.Ok -> {
            val queue = s.value
            PageScroll {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("01", "Up next — ${queue.total}")
                    }
                    if (queue.items.isNotEmpty()) {
                        Spacer(Modifier.width(16.dp))
                        TextAction("CLEAR", enabled = !busy) { mutate { repository.clearQueue() } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                if (queue.items.isEmpty()) {
                    MetaText("The queue is empty — add chapters from a fiction")
                } else {
                    MetaText(
                        "${queue.total} of ${queue.maxItems} · when empty: ${queue.whenEmpty}",
                        color = AarisColor.Dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    queue.items.forEachIndexed { index, item ->
                        QueueItemRow(
                            item = item,
                            repository = repository,
                            index = index,
                            isFirst = index == 0,
                            isLast = index == queue.items.lastIndex,
                            enabled = !busy,
                            onPlay = { onPlayChapter(item.fictionId, item.chapterId) },
                            onRemove = { mutate { repository.removeFromQueue(listOf(item.id)) } },
                            onMove = { delta ->
                                // The server takes the complete desired order, not a delta, so the
                                // swap happens here and the whole list is sent.
                                val reordered = queue.items.map { it.id }.toMutableList()
                                val target = index + delta
                                if (target in reordered.indices) {
                                    reordered[index] = reordered[target].also { reordered[target] = reordered[index] }
                                    mutate { repository.reorderQueue(reordered) }
                                }
                            },
                        )
                        HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: ServerQueueItem,
    repository: TtsRoadRepository,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .background(if (hovered) AarisColor.BgRaise else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("%02d".format(index + 1), color = AarisColor.Dim, modifier = Modifier.width(36.dp))
        CoverImage(
            item.fictionTitle ?: "?",
            item.coverImageUrl?.let(repository::resolveUrl),
            Modifier.width(40.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.chapterTitle ?: "Untitled chapter",
                style = MaterialTheme.typography.titleMedium,
                color = AarisColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(
                listOfNotNull(
                    item.fictionTitle,
                    item.audioDurationLabel,
                    "played".takeIf { item.isPlayed },
                ).joinToString("  ·  "),
                color = AarisColor.Dim,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (hovered) {
            if (!isFirst) {
                RowIconAction(Icons.Default.ArrowUpward, "Move up", AarisColor.Dim, enabled) { onMove(-1) }
            }
            if (!isLast) {
                RowIconAction(Icons.Default.ArrowDownward, "Move down", AarisColor.Dim, enabled) { onMove(1) }
            }
            RowIconAction(Icons.Default.Close, "Remove from queue", AarisColor.Danger, enabled, onRemove)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(30.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = AarisColor.Bg,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TextAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .hoverable(interaction)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        MetaText(label, color = if (hovered && enabled) AarisColor.Danger else AarisColor.Dim)
    }
}

@Composable
private fun RowIconAction(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(30.dp)
            .background(if (hovered && enabled) AarisColor.BgHover else Color.Transparent)
            .hoverable(interaction)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (hovered && enabled) AarisColor.Ink else tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
