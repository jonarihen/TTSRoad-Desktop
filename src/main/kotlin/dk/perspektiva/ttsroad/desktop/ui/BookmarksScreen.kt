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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.BookmarkLimits

const val BookmarkRowTestTag: String = "bookmarkRow"
const val BookmarkPlayTestTag: String = "bookmarkPlay"
const val BookmarkEditTestTag: String = "bookmarkEdit"
const val BookmarkDeleteTestTag: String = "bookmarkDelete"
const val BookmarkConfirmDeleteTestTag: String = "bookmarkConfirmDelete"
const val BookmarkNoticeTestTag: String = "bookmarkNotice"

/**
 * The account's marks, newest first.
 *
 * Rows carry the fiction and chapter titles the server sends alongside each mark, which is why this
 * screen needs neither the library nor a request per row to render — a bookmark on a serial that
 * was never opened in this session still reads as a sentence rather than as an id.
 */
@Composable
fun BookmarksScreen(
    holder: BookmarksStateHolder,
    onOpen: (Bookmark) -> Unit,
    onBack: () -> Unit,
) {
    val ui by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.ensureLoaded() }

    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var confirmDelete by remember { mutableStateOf<Bookmark?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize()
                .padding(horizontal = PageGutter, vertical = 20.dp),
        ) {
            BackLink("Back", onBack)
            Spacer(Modifier.height(12.dp))
            SectionTitle("bookmarks", "Your bookmarks")
            RefreshingStrip(ui.loading && !ui.isEmpty)
            ui.notice?.let {
                Spacer(Modifier.height(12.dp))
                BookmarkNotice(it) { holder.dismissNotice() }
            }
            Spacer(Modifier.height(16.dp))
            when {
                ui.unsupported -> EmptyState(
                    "No bookmarks here",
                    "This server does not have the bookmarks API.",
                )

                ui.loading && ui.isEmpty -> CenterProgress()

                ui.isEmpty && ui.error != null -> InitialErrorState(ui.error.orEmpty()) { holder.refresh() }

                ui.isEmpty -> EmptyState(
                    "No bookmarks yet",
                    "Press Ctrl+B while listening, or use the bookmark action in the player.",
                )

                else -> {
                    ui.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(ui.bookmarks, key = { it.id }) { bookmark ->
                            BookmarkRow(
                                bookmark = bookmark,
                                onOpen = { onOpen(bookmark) },
                                onEdit = { editing = bookmark },
                                onDelete = { confirmDelete = bookmark },
                            )
                            HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
                        }
                    }
                }
            }
        }
    }

    editing?.let { bookmark ->
        BookmarkEditDialog(
            bookmark = bookmark,
            onDismiss = { editing = null },
            onSave = { label, note ->
                holder.edit(bookmark.id, label, note)
                editing = null
            },
        )
    }

    confirmDelete?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove this bookmark?") },
            text = { Text(bookmark.displayLabel) },
            confirmButton = {
                TextButton(
                    onClick = {
                        holder.remove(bookmark.id)
                        confirmDelete = null
                    },
                    modifier = Modifier.testTag(BookmarkConfirmDeleteTestTag),
                ) { Text("REMOVE") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL") } },
            shape = RectangleShape,
        )
    }
}

/**
 * One mark.
 *
 * The actions are explicit rather than the row itself being clickable, because two of the three are
 * destructive or modal and a row-wide hit target would fire them by accident. Play still shares the
 * row's interaction source, so hovering anywhere lights up the primary action.
 *
 * Play appears only when there is still audio behind the mark: a chapter deleted on the server
 * leaves its bookmarks in place with the link cleared, so a row can outlive the thing it points at.
 * That row stays visible — the note on it is still the user's — and simply says what happened.
 */
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = hovered || focused
    Row(
        Modifier
            .testTag(BookmarkRowTestTag)
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (active) AarisColor.BgHover.copy(alpha = 0.5f) else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                bookmark.displayLabel,
                style = MaterialTheme.typography.titleMedium,
                color = AarisColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            MetaText(bookmarkSubtitle(bookmark), color = AarisColor.Dim)
            bookmark.note?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bookmark.isPlayable) {
                RowAction("PLAY", BookmarkPlayTestTag, interaction, onOpen)
            } else {
                MetaText("Chapter removed", color = AarisColor.Warning)
            }
            RowAction("EDIT", BookmarkEditTestTag, onClick = onEdit)
            RowAction("REMOVE", BookmarkDeleteTestTag, onClick = onDelete)
        }
    }
}

/**
 * "Serial · Chapter 4 · 12:07", skipping whatever the server did not send.
 *
 * Pure, and total over a mark whose chapter has been deleted: every part is optional, so a bookmark
 * with nothing left but a position still gets a subtitle rather than a row of separators.
 */
fun bookmarkSubtitle(bookmark: Bookmark): String = listOfNotNull(
    bookmark.fictionTitle?.takeIf { it.isNotBlank() },
    bookmark.chapterTitle?.takeIf { it.isNotBlank() && it != bookmark.label },
    bookmark.positionLabel?.takeIf { it.isNotBlank() } ?: formatDuration(bookmark.positionMs),
).joinToString(" · ")

@Composable
private fun RowAction(
    label: String,
    tag: String,
    interaction: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    val own = remember { MutableInteractionSource() }
    val source = interaction ?: own
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    MetaText(
        label,
        color = if (hovered || focused) AarisColor.Accent else AarisColor.Muted,
        modifier = Modifier
            .testTag(tag)
            .hoverable(source)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Transient confirmation from a write. Dismissible, because it sits above a scrolling list. */
@Composable
private fun BookmarkNotice(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AarisColor.BgRaise)
            .border(1.dp, AarisColor.Line)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(BookmarkNoticeTestTag)
            .semantics(mergeDescendants = true) { contentDescription = message },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AarisColor.Ink, modifier = Modifier.weight(1f))
        RowAction("DISMISS", "bookmarkNoticeDismiss", onClick = onDismiss)
    }
}

/**
 * Label and note, edited together.
 *
 * Both fields are sent on every save, blank included — an empty string is how the server is told to
 * *clear* a value, where an absent key would mean "leave it alone". Editing a note to nothing and
 * having it come back is the bug this avoids.
 */
@Composable
private fun BookmarkEditDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onSave: (label: String, note: String) -> Unit,
) {
    var label by remember(bookmark.id) { mutableStateOf(bookmark.label.orEmpty()) }
    var note by remember(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit bookmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MetaText(bookmarkSubtitle(bookmark), color = AarisColor.Dim)
                OutlinedTextField(
                    value = label,
                    // Bounded in the field rather than at the request, so the limit is visible
                    // while typing instead of silently truncating on save.
                    onValueChange = { label = it.take(BookmarkLimits.MaxLabelChars) },
                    label = { Text("LABEL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(BookmarkLabelFieldTestTag),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(BookmarkLimits.MaxNoteChars) },
                    label = { Text("NOTE") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag(BookmarkNoteFieldTestTag),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(label, note) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
        shape = RectangleShape,
    )
}

const val BookmarkLabelFieldTestTag: String = "bookmarkLabelField"
const val BookmarkNoteFieldTestTag: String = "bookmarkNoteField"

/** The player's and reader's "mark this spot" control, drawn only where the server has the API. */
@Composable
fun BookmarkAction(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        modifier = modifier.testTag(AddBookmarkTestTag).pointerHoverIcon(PointerIcon.Hand),
    ) { Text("BOOKMARK") }
}

const val AddBookmarkTestTag: String = "addBookmark"
