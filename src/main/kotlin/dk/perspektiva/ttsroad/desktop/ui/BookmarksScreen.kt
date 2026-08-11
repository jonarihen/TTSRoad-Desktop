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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.launch

/**
 * The account's marks, newest book first.
 *
 * Grouped by fiction because a flat list of positions with no book attached is unreadable once
 * there is more than one book in it — and the payload carries the titles precisely so this can be
 * rendered without a request per row.
 */
@Composable
fun BookmarksScreen(
    repository: TtsRoadRepository,
    onOpenChapter: (fictionId: Int, chapterId: Int, positionSeconds: Double) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<Bookmark>>>(Load.Loading) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }

    suspend fun load() {
        state = runCatching { repository.bookmarks() }
            .fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Could not load bookmarks") })
    }

    LaunchedEffect(Unit) { load() }

    when (val s = state) {
        Load.Loading -> CenterProgress()
        is Load.Err -> CenterError(s.message)
        is Load.Ok -> PageScroll {
            SectionTitle("01", "Bookmarks — ${s.value.size}")
            Spacer(Modifier.height(8.dp))
            actionError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (s.value.isEmpty()) {
                MetaText("No bookmarks yet — mark a position from the player")
            } else {
                s.value.groupBy { it.fictionTitle ?: "Unknown fiction" }
                    .forEach { (fictionTitle, marks) ->
                        Spacer(Modifier.height(20.dp))
                        MetaText("// $fictionTitle", color = AarisColor.Accent)
                        Spacer(Modifier.height(8.dp))
                        marks.sortedWith(compareBy({ it.chapterNumber ?: 0.0 }, { it.positionSeconds }))
                            .forEach { bookmark ->
                                BookmarkRow(
                                    bookmark = bookmark,
                                    isEditing = editing == bookmark.id,
                                    onPlay = {
                                        val chapterId = bookmark.chapterId
                                        val fictionId = bookmark.fictionId
                                        if (chapterId != null && fictionId != null) {
                                            onOpenChapter(fictionId, chapterId, bookmark.positionSeconds)
                                        }
                                    },
                                    onStartEdit = { editing = bookmark.id },
                                    onCancelEdit = { editing = null },
                                    onRename = { label ->
                                        scope.launch {
                                            actionError = null
                                            runCatching {
                                                repository.updateBookmark(bookmark.id, label = label)
                                                editing = null
                                                load()
                                            }.onFailure { actionError = it.message ?: "Could not rename bookmark" }
                                        }
                                    },
                                    onDelete = {
                                        scope.launch {
                                            actionError = null
                                            runCatching {
                                                repository.deleteBookmark(bookmark.id)
                                                load()
                                            }.onFailure { actionError = it.message ?: "Could not delete bookmark" }
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
private fun BookmarkRow(
    bookmark: Bookmark,
    isEditing: Boolean,
    onPlay: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val playable = bookmark.isPlayable

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .let { if (playable && !isEditing) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = playable && !isEditing,
                onClick = onPlay,
            )
            .background(if (hovered && playable && !isEditing) AarisColor.BgRaise else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(
            bookmark.positionLabel ?: formatDuration((bookmark.positionSeconds * 1000).toLong()),
            color = if (playable) AarisColor.Accent else AarisColor.Dim,
            modifier = Modifier.width(72.dp),
        )
        Column(Modifier.weight(1f)) {
            if (isEditing) {
                var draft by remember(bookmark.id) { mutableStateOf(bookmark.label.orEmpty()) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("LABEL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowIconAction(Icons.Default.Check, "Save label", AarisColor.Ok) { onRename(draft) }
                    RowIconAction(Icons.Default.Close, "Cancel", AarisColor.Dim) { onCancelEdit() }
                }
            } else {
                Text(
                    bookmark.label?.takeIf { it.isNotBlank() } ?: bookmark.chapterTitle ?: "Bookmark",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (playable) AarisColor.Ink else AarisColor.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    bookmark.chapterTitle.takeIf { !bookmark.label.isNullOrBlank() },
                    bookmark.note?.takeIf { it.isNotBlank() },
                    "chapter removed".takeIf { !playable },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    MetaText(meta, color = AarisColor.Dim)
                }
            }
        }
        if (!isEditing) {
            Spacer(Modifier.width(12.dp))
            if (hovered) {
                RowIconAction(Icons.Default.Edit, "Rename bookmark", AarisColor.Dim) { onStartEdit() }
                Spacer(Modifier.width(8.dp))
                RowIconAction(Icons.Default.Delete, "Delete bookmark", AarisColor.Danger) { onDelete() }
                if (playable) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(30.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play from here",
                            tint = AarisColor.Bg,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Borderless hover-reveal icon action used inside list rows. */
@Composable
private fun RowIconAction(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(30.dp)
            .background(if (hovered) AarisColor.BgHover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (hovered) AarisColor.Ink else tint, modifier = Modifier.size(18.dp))
    }
}
