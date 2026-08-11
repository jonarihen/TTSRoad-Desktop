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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.SearchGroup
import dk.perspektiva.ttsroad.desktop.data.SearchHit
import dk.perspektiva.ttsroad.desktop.data.SearchResponse
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.launch

/**
 * Server-side search, as an explicit action rather than as-you-type.
 *
 * The library screen keeps its instant local filter — it works with no network and no latency, and
 * removing it would be a regression. This is the second, deliberate path: it reaches chapter titles
 * and narration text, neither of which a filter over the loaded list can see.
 */
@Composable
fun SearchScreen(
    repository: TtsRoadRepository,
    onOpenFiction: (FictionSummary) -> Unit,
    onPlayChapter: (fictionId: Int, chapterId: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SearchResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        val q = query.trim()
        if (q.isBlank() || busy) return
        scope.launch {
            busy = true
            error = null
            runCatching { repository.search(q) }
                .fold({ result = it }, { error = it.message ?: "Search failed" })
            busy = false
        }
    }

    PageScroll {
        SectionTitle("01", "Search")
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("SEARCH FICTIONS, CHAPTERS AND NARRATION TEXT") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = ::run,
                enabled = !busy && query.isNotBlank(),
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text(if (busy) "SEARCHING" else "SEARCH") }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (busy && result == null) {
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AarisColor.Accent)
            }
        }

        result?.let { response ->
            Spacer(Modifier.height(20.dp))
            if (response.total == 0) {
                MetaText("No matches for \"${response.query}\"")
            } else {
                // The server stops counting at a cap rather than paying for an exact total, so
                // "500 results" would be a lie when it is really "at least 500".
                MetaText(
                    buildString {
                        append(if (response.capped()) "At least " else "")
                        append("${response.total} result${if (response.total == 1) "" else "s"}")
                        if (!response.indexed) append("  ·  unindexed server, narration search is a scan")
                    },
                    color = AarisColor.Dim,
                )
                ResultGroup("Fictions", response.fictions, repository, onOpenFiction, onPlayChapter)
                ResultGroup("Chapters", response.chapters, repository, onOpenFiction, onPlayChapter)
                ResultGroup("In the text", response.text, repository, onOpenFiction, onPlayChapter)
            }
        }
    }
}

private fun SearchResponse.capped(): Boolean = fictions.capped || chapters.capped || text.capped

@Composable
private fun ResultGroup(
    title: String,
    group: SearchGroup,
    repository: TtsRoadRepository,
    onOpenFiction: (FictionSummary) -> Unit,
    onPlayChapter: (fictionId: Int, chapterId: Int) -> Unit,
) {
    if (group.items.isEmpty()) return
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MetaText("// $title", color = AarisColor.Accent)
        Spacer(Modifier.weight(1f))
        MetaText(
            buildString {
                append("${group.items.size} of ")
                if (group.capped) append("at least ")
                append(group.total)
            },
            color = AarisColor.Dim,
        )
    }
    Spacer(Modifier.height(8.dp))
    group.items.forEach { hit ->
        SearchResultRow(hit, repository) {
            if (hit.isFiction) {
                // Built from the hit rather than refetched: FictionDetailScreen reloads the
                // fiction with its chapters anyway, so this only has to carry the header until
                // that lands — and it saves a round trip before the screen can open.
                onOpenFiction(
                    FictionSummary(
                        id = hit.fictionId,
                        title = hit.fictionTitle ?: "Untitled",
                        author = hit.author,
                        slug = hit.fictionSlug,
                        coverImageUrl = hit.coverImageUrl,
                        tags = hit.tags,
                    ),
                )
            } else {
                hit.chapterId?.let { onPlayChapter(hit.fictionId, it) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, repository: TtsRoadRepository, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // A text or chapter hit is only actionable if there is audio behind it; a fiction hit always
    // opens its detail screen.
    val actionable = hit.isFiction || (hit.chapterId != null && hit.playable)

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .let { if (actionable) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = actionable, onClick = onOpen)
            .background(if (hovered && actionable) AarisColor.BgRaise else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            hit.fictionTitle ?: "?",
            hit.coverImageUrl?.let(repository::resolveUrl),
            Modifier.width(44.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                hit.chapterTitle ?: hit.fictionTitle ?: "Untitled",
                style = MaterialTheme.typography.titleMedium,
                color = if (actionable) AarisColor.Ink else AarisColor.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.chapterTitle != null) {
                hit.fictionTitle?.let { MetaText(it, color = AarisColor.Dim) }
            }
            if (hit.snippet.isNotBlank()) {
                HighlightedSnippet(hit.snippet, hit.highlights)
            }
            if (!actionable && !hit.isFiction) {
                MetaText(hit.status ?: "no audio yet", color = AarisColor.Dim)
            }
        }
    }
}

/**
 * The snippet with the server's matched ranges emphasised.
 *
 * Ranges are bounds-checked rather than trusted: they are computed over Python string indices,
 * which count code points, while Kotlin indexes UTF-16 units. A snippet containing an emoji shifts
 * them, and an out-of-range span would throw. A wrong highlight is cosmetic; a crash is not.
 */
@Composable
private fun HighlightedSnippet(snippet: String, highlights: List<List<Int>>) {
    val annotated = remember(snippet, highlights) {
        buildAnnotatedString {
            append(snippet)
            highlights.forEach { span ->
                val start = span.getOrNull(0) ?: return@forEach
                val end = span.getOrNull(1) ?: return@forEach
                if (start in 0..snippet.length && end in start..snippet.length) {
                    addStyle(
                        SpanStyle(color = AarisColor.Accent, fontWeight = FontWeight.Bold),
                        start,
                        end,
                    )
                }
            }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodySmall,
        color = AarisColor.Muted,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
