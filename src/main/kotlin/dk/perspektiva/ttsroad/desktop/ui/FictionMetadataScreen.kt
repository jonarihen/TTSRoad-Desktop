package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.CoverImageFormats
import dk.perspektiva.ttsroad.desktop.data.FictionMetadataFields
import dk.perspektiva.ttsroad.desktop.data.FictionTagLimits
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository

const val FictionMetadataScreenTestTag: String = "fictionMetadataScreen"
const val ChooseCoverButtonTestTag: String = "chooseCoverButton"
const val ChosenCoverNameTestTag: String = "chosenCoverName"
const val SaveMetadataButtonTestTag: String = "saveMetadataButton"
const val UseSourceValuesButtonTestTag: String = "useSourceValuesButton"
const val TagDraftFieldTestTag: String = "tagDraftField"

/** How many characters of tag text fit on one row before the next chip wraps. */
private const val TagRowBudget = 46

/**
 * The fiction editor — a form, not a dialog.
 *
 * This is the screen the desktop client exists to have: a real window, a real file picker for cover
 * art, and enough room to show what a metadata edit *costs*. Every field here is shared: it is the
 * title in everybody's podcast feed, and on a server that tracks hand edits, saving one also stops
 * the server refreshing it from the source for good. So the ownership state is drawn next to the
 * field it belongs to rather than buried in a legend, and handing a field back is one click from
 * the same place.
 *
 * Reached only from the fiction screen's Edit control, which exists only for an account the server
 * confirms is an administrator. That is presentation; the server is still the gate.
 */
@Composable
fun FictionMetadataScreen(
    holder: FictionMetadataStateHolder,
    repository: TtsRoadRepository,
    onBack: () -> Unit,
) {
    val s by holder.state.collectAsState()
    val fiction = s.fiction

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = windowSizeClassFor(maxWidth).isCompact
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PageGutter)
                    .testTag(FictionMetadataScreenTestTag)
                    .semantics { paneTitle = "Edit fiction" },
            ) {
                BackLink("Back", onBack)
                Spacer(Modifier.height(20.dp))
                if (fiction == null) {
                    EmptyState(
                        "Nothing to edit",
                        "Open a fiction and choose Edit to change what the library shows for it.",
                    )
                    return@Column
                }

                SectionTitle("01", "Edit ${fiction.title}")
                Spacer(Modifier.height(12.dp))
                MetaText(
                    "These values are shared: every account sees them, and they name the podcast feed.",
                )
                Spacer(Modifier.height(20.dp))

                OwnershipBanner(s, holder)

                if (stacked) {
                    CoverPanel(s, holder, repository, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    MetadataForm(s, holder, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        CoverPanel(s, holder, repository, Modifier.width(220.dp))
                        Spacer(Modifier.width(28.dp))
                        MetadataForm(s, holder, Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Spacer(Modifier.height(16.dp))

                s.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                s.notice?.let {
                    MetaText(it, color = AarisColor.Ok)
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = holder::save,
                        enabled = s.canSave,
                        shape = RectangleShape,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag(SaveMetadataButtonTestTag),
                    ) { Text(if (s.isBusy) "SAVING…" else "SAVE CHANGES") }
                    TextButton(
                        onClick = holder::revertEdits,
                        enabled = s.hasChanges && !s.isBusy,
                        shape = RectangleShape,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) { Text("DISCARD CHANGES") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * What this server has been told belongs to a person, and the way back out of it.
 *
 * Absent entirely where nothing is hand-edited — including on a server that has never heard of the
 * idea, which reports no names and therefore gets no banner and no release control it could not
 * honour.
 */
@Composable
private fun OwnershipBanner(state: FictionMetadataUiState, holder: FictionMetadataStateHolder) {
    val overridden = state.overrides
    if (overridden.isEmpty()) return
    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            MetaText("Hand-edited", color = AarisColor.Warning)
            Spacer(Modifier.height(8.dp))
            Text(
                FictionMetadataStateHolder.humanList(
                    overridden.map(FictionMetadataFields::labelOf).sorted(),
                ) + " no longer follow the source: refreshing this fiction leaves them alone. " +
                    "Handing them back does not restore the old text — it lets the next refresh " +
                    "write over what is here.",
                style = MaterialTheme.typography.bodyMedium,
                color = AarisColor.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { holder.useSourceValues() },
                enabled = !state.isBusy,
                shape = RectangleShape,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(UseSourceValuesButtonTestTag),
            ) { Text("USE SOURCE VALUES") }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun MetadataForm(
    state: FictionMetadataUiState,
    holder: FictionMetadataStateHolder,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditorField(
            value = draft.title,
            onValueChange = holder::setTitle,
            label = "TITLE",
            enabled = !state.isBusy,
            supporting = "The name in the library and in the podcast feed",
            field = FictionMetadataFields.Title,
            state = state,
            holder = holder,
        )
        EditorField(
            value = draft.author,
            onValueChange = holder::setAuthor,
            label = "AUTHOR",
            enabled = !state.isBusy,
            supporting = "Blank clears the author",
            field = FictionMetadataFields.Author,
            state = state,
            holder = holder,
        )
        EditorField(
            value = draft.description,
            onValueChange = holder::setDescription,
            label = "DESCRIPTION",
            enabled = !state.isBusy,
            supporting = "Blank clears the description",
            field = FictionMetadataFields.Description,
            state = state,
            holder = holder,
            singleLine = false,
            minLines = 4,
        )
        TagEditor(state, holder)
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
        MetaText("Narration")
        // Deliberately outside the metadata block above: the voice is a conversion setting, not a
        // description of the book, so changing it claims nothing and no refresh would overwrite it.
        OutlinedTextField(
            value = draft.voice,
            onValueChange = holder::setVoice,
            label = { Text("VOICE") },
            supportingText = { Text("The edge-tts voice new chapters are narrated with") },
            enabled = !state.isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One text field plus the ownership line that belongs to it. */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    supporting: String,
    field: String,
    state: FictionMetadataUiState,
    holder: FictionMetadataStateHolder,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            supportingText = { Text(supporting) },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth().testTag(fieldTestTag(field)),
        )
        FieldOwnership(field, state, holder)
    }
}

/** Test tag for the field editing [field], so a test names the field rather than its position. */
fun fieldTestTag(field: String): String = "metadataField_$field"

@Composable
private fun FieldOwnership(
    field: String,
    state: FictionMetadataUiState,
    holder: FictionMetadataStateHolder,
) {
    if (field !in state.overrides) return
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaText("Hand-edited — not refreshed", color = AarisColor.Warning)
        TextButton(
            onClick = { holder.useSourceValues(setOf(field)) },
            enabled = !state.isBusy,
            shape = RectangleShape,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag("useSource_$field"),
        ) { Text("USE SOURCE") }
    }
}

@Composable
private fun TagEditor(state: FictionMetadataUiState, holder: FictionMetadataStateHolder) {
    val draft = state.draft
    Column(Modifier.fillMaxWidth()) {
        MetaText("Tags  ·  ${FictionMetadataStateHolder.tagLimitLabel(draft.tags.size)}")
        Spacer(Modifier.height(8.dp))
        val rows = remember(draft.tags) { wrapTags(draft.tags, TagRowBudget) }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { tag ->
                    TagChip(tag, enabled = !state.isBusy, onRemove = { holder.removeTag(tag) })
                }
            }
        }
        if (draft.tags.isEmpty()) {
            MetaText("None", color = AarisColor.Dim)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = draft.tagDraft,
            onValueChange = holder::setTagDraft,
            label = { Text("ADD A TAG") },
            supportingText = { Text("Enter adds it; duplicates are ignored") },
            enabled = !state.isBusy && draft.tags.size < FictionTagLimits.MaxTags,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TagDraftFieldTestTag)
                // Preview, so the key is claimed before the field's own editor sees it: Enter in a
                // single-line field otherwise does nothing at all, and a tag typed and left behind
                // is the commonest way to lose one.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        holder.commitTagDraft()
                        true
                    } else {
                        false
                    }
                },
        )
        FieldOwnership(FictionMetadataFields.Tags, state, holder)
    }
}

@Composable
private fun TagChip(tag: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AarisTag(tag)
        RowIconAction(
            icon = Icons.Default.Close,
            contentDescription = "Remove tag $tag",
            tint = AarisColor.Muted,
            onClick = onRemove,
            enabled = enabled,
        )
    }
}

/**
 * The cover, and the one way to change it.
 *
 * An upload rather than a URL field because that is what the server accepts, and because cover art
 * it does not hold is art it cannot embed in the MP3s. A server without the route says so here
 * instead of failing on save.
 */
@Composable
private fun CoverPanel(
    state: FictionMetadataUiState,
    holder: FictionMetadataStateHolder,
    repository: TtsRoadRepository,
    modifier: Modifier = Modifier,
) {
    val fiction = state.fiction ?: return
    val chosen = state.chosenCover
    Column(modifier) {
        MetaText("Cover art")
        Spacer(Modifier.height(8.dp))
        CoverImage(
            fiction.title,
            fiction.coverImageUrl?.let(repository::resolveUrl),
            Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(12.dp))
        if (!state.coverUploadSupported) {
            MetaText("This server cannot replace cover art from a client", color = AarisColor.Warning)
        } else {
            OutlinedButton(
                onClick = if (chosen == null) holder::chooseCover else holder::clearChosenCover,
                enabled = !state.isBusy,
                shape = RectangleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(ChooseCoverButtonTestTag),
            ) { Text(if (chosen == null) "CHOOSE AN IMAGE…" else "REMOVE") }
            Spacer(Modifier.height(8.dp))
            if (chosen == null) {
                MetaText("${CoverImageFormats.Description}, uploaded when you save", color = AarisColor.Dim)
            } else {
                Text(
                    chosen.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Ink,
                    modifier = Modifier.testTag(ChosenCoverNameTestTag),
                )
                Spacer(Modifier.height(4.dp))
                MetaText("Uploaded when you save", color = AarisColor.Dim)
            }
            FieldOwnership(FictionMetadataFields.CoverImage, state, holder)
        }
    }
}

/**
 * Split tags into rows that will roughly fit, by counting characters.
 *
 * Compose's wrapping row layout is still an opt-in experimental API, and a form that fails the
 * build's warnings-as-errors gate to avoid four lines of arithmetic is a poor trade. Counting
 * characters is an approximation, deliberately: the cost of getting it slightly wrong is one short
 * row, and being pure is what lets it be tested at all.
 */
internal fun wrapTags(tags: List<String>, budget: Int): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var used = 0
    for (tag in tags) {
        // The chip's border, padding and remove control are worth roughly this many characters.
        val cost = tag.length + 6
        if (row.isNotEmpty() && used + cost > budget) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += tag
        used += cost
    }
    if (row.isNotEmpty()) rows += row
    return rows
}
