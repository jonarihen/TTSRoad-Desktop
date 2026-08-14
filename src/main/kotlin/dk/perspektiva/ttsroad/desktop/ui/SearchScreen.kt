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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.SearchGroup
import dk.perspektiva.ttsroad.desktop.data.SearchHit
import dk.perspektiva.ttsroad.desktop.data.snippetSpans

const val SearchFieldTestTag: String = "searchField"
const val SearchHitTestTag: String = "searchHit"
const val SearchSubmitTestTag: String = "searchSubmit"

/**
 * Server-side search across fiction metadata, chapter titles and **narration text**.
 *
 * Deliberately a second, explicit path rather than a replacement for the library's own filter. That
 * filter is instant, needs no network and keeps working against a downloaded library with the
 * server unreachable; this one can find a sentence inside a chapter, which the local filter
 * structurally cannot do. Both are worth having and they are not the same tool.
 */
@Composable
fun SearchScreen(
    holder: SearchStateHolder,
    readAlongAvailable: Boolean,
    onOpenFiction: (SearchHit) -> Unit,
    onOpenReader: (chapterId: Int, title: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    val field = remember { FocusRequester() }
    // Arriving on an empty search screen means typing, so put the caret where the typing goes.
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
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
                    SectionTitle("01", "Search the server")
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = holder::queryChanged,
                            label = { Text("TITLE, AUTHOR, CHAPTER OR ANYTHING SAID IN ONE") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { holder.submit() }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(field)
                                .testTag(SearchFieldTestTag),
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = holder::submit,
                            enabled = !state.busy && state.query.isNotBlank(),
                            shape = RectangleShape,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .testTag(SearchSubmitTestTag),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.busy) "SEARCHING" else "SEARCH")
                        }
                    }
                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    state.result?.let { result ->
                        Spacer(Modifier.height(12.dp))
                        MetaText(resultSummary(result.total, state.resultQuery), color = AarisColor.Dim)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            when {
                state.unsupported -> item(key = "unsupported", contentType = "empty") {
                    EmptyState(
                        "This server cannot search",
                        "It advertised the search feature but has no /api/mobile/search endpoint. " +
                            "The library filter above still works.",
                    )
                }

                state.result?.isEmpty == true -> item(key = "no-matches", contentType = "empty") {
                    EmptyState(
                        "No matches for \"${state.resultQuery}\"",
                        "Nothing in a title, a chapter name or any chapter's narration text.",
                    )
                }

                state.result == null && !state.hasSearched -> item(key = "prompt", contentType = "empty") {
                    EmptyState(
                        "Search every chapter",
                        "Unlike the library filter, this reaches chapter titles and the words inside them.",
                    )
                }
            }

            state.result?.let { result ->
                group("02", "Fictions", result.fictions) { hit ->
                    HitRow(hit, action = null) { onOpenFiction(hit) }
                }
                group("03", "Chapters", result.chapters) { hit ->
                    HitRow(hit, action = readerAction(hit, readAlongAvailable)) {
                        openHit(hit, readAlongAvailable, onOpenFiction, onOpenReader)
                    }
                }
                group("04", "In the text", result.text) { hit ->
                    HitRow(hit, action = readerAction(hit, readAlongAvailable)) {
                        openHit(hit, readAlongAvailable, onOpenFiction, onOpenReader)
                    }
                }
            }
        }
        RefreshingStrip(state.busy)
    }
}

/**
 * Where a hit goes when it is clicked.
 *
 * A chapter opens the reader, which is where its *text* is — that is the whole point of having
 * matched narration. Without the read-along capability there is no reader to open, so it falls back
 * to the fiction's chapter list, which is also where a fiction hit goes.
 *
 * It does **not** start playback. `char_offset` is a character offset into `clean_text`, not an
 * audio timestamp, so there is no position to start at; and beginning to play a chapter somebody
 * clicked to *read* is not what they asked for.
 */
private fun openHit(
    hit: SearchHit,
    readAlongAvailable: Boolean,
    onOpenFiction: (SearchHit) -> Unit,
    onOpenReader: (Int, String) -> Unit,
) {
    if (readAlongAvailable && hit.isChapterHit) {
        onOpenReader(hit.resolvedChapterId, hit.chapterTitle.orEmpty().ifBlank { "Chapter" })
    } else {
        onOpenFiction(hit)
    }
}

private fun readerAction(hit: SearchHit, readAlongAvailable: Boolean): String? =
    if (readAlongAvailable && hit.isChapterHit) "Read" else null

/** One ranked group, drawn only when it has hits — an empty "Fictions" heading is noise. */
private fun androidx.compose.foundation.lazy.LazyListScope.group(
    kicker: String,
    title: String,
    group: SearchGroup,
    row: @Composable (SearchHit) -> Unit,
) {
    if (group.items.isEmpty()) return
    item(key = "header-$kicker", contentType = "header") {
        Column {
            SectionTitle(kicker, groupTitle(title, group))
            Spacer(Modifier.height(8.dp))
        }
    }
    items(
        count = group.items.size,
        key = { index -> "$kicker:${group.items[index].fictionId}:${group.items[index].resolvedChapterId}:$index" },
        contentType = { "hit" },
    ) { index ->
        Column {
            row(group.items[index])
            HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
        }
    }
    item(key = "spacer-$kicker", contentType = "header") { Spacer(Modifier.height(28.dp)) }
}

/** "Chapters — 12 shown of 340", or "of 500+" where the server stopped counting. */
internal fun groupTitle(title: String, group: SearchGroup): String = when {
    group.total <= group.items.size -> "$title — ${group.items.size}"
    group.capped -> "$title — ${group.items.size} shown of ${group.total}+"
    else -> "$title — ${group.items.size} shown of ${group.total}"
}

internal fun resultSummary(total: Int, query: String): String =
    if (total == 0) "No matches for \"$query\"" else "$total ${if (total == 1) "match" else "matches"} for \"$query\""

@Composable
private fun HitRow(hit: SearchHit, action: String?, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = hovered || focused

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .background(if (active) AarisColor.BgRaise else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(SearchHitTestTag),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                hitTitle(hit),
                style = MaterialTheme.typography.titleMedium,
                color = if (active) AarisColor.Accent else AarisColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hitContext(hit)?.let {
                Spacer(Modifier.height(2.dp))
                MetaText(it, color = AarisColor.Dim)
            }
            if (hit.snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    highlightedSnippet(hit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = if (active) AarisColor.Accent else AarisColor.Dim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                MetaText(action, color = if (active) AarisColor.Accent else AarisColor.Dim)
            }
        }
    }
}

/** The chapter is the headline where there is one; the fiction is the headline otherwise. */
internal fun hitTitle(hit: SearchHit): String = when {
    hit.isChapterHit -> hit.chapterTitle.orEmpty().ifBlank { "Untitled chapter" }
    else -> hit.fictionTitle.orEmpty().ifBlank { "Untitled" }
}

/** The line under the headline: which book, and anything the row would otherwise hide. */
internal fun hitContext(hit: SearchHit): String? {
    val parts = buildList {
        if (hit.isChapterHit) hit.fictionTitle?.takeIf { it.isNotBlank() }?.let(::add)
        else hit.author?.takeIf { it.isNotBlank() }?.let { add("by $it") }
        if (hit.excluded) add("Excluded")
        // Not a defect: a chapter can be found by its text before its audio has been produced.
        if (hit.isChapterHit && !hit.playable && !hit.excluded) add("No audio yet")
    }
    return parts.joinToString("  ·  ").takeIf { it.isNotBlank() }
}

/**
 * The snippet with the matched words emphasised.
 *
 * The server sends *ranges*, never markup, precisely so nothing it returns is interpreted as
 * formatting here — and [snippetSpans] is what makes those ranges safe to index with.
 */
@Composable
private fun highlightedSnippet(hit: SearchHit): AnnotatedString {
    val spans = remember(hit.snippet, hit.highlights) { snippetSpans(hit.snippet, hit.highlights) }
    if (spans.isEmpty()) return AnnotatedString(hit.snippet)
    return buildAnnotatedString {
        append(hit.snippet)
        spans.forEach { span ->
            addStyle(
                SpanStyle(color = AarisColor.Accent, fontWeight = FontWeight.SemiBold),
                span.start,
                span.end,
            )
        }
    }
}

/**
 * The library's own fictions, so a hit opens the detail screen with a header it can paint at once.
 *
 * A search hit carries less than a library row does — no chapter counts — so a fiction the library
 * has not loaded gets a summary synthesised from the hit rather than nothing at all; the detail
 * screen replaces it as soon as the chapter list lands.
 */
internal fun fictionForHit(known: List<FictionSummary>, hit: SearchHit): FictionSummary =
    known.firstOrNull { it.id == hit.fictionId }
        ?: FictionSummary(
            id = hit.fictionId,
            title = hit.fictionTitle.orEmpty().ifBlank { "Untitled" },
            author = hit.author,
            slug = hit.fictionSlug,
            coverImageUrl = hit.coverImageUrl,
            tags = hit.tags,
        )
