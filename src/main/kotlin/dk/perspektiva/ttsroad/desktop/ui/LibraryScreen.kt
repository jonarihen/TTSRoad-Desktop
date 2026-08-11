package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LibraryScope
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onOpenFiction: (FictionSummary) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capabilities by repository.capabilities.collectAsState()
    var state by remember { mutableStateOf<Load<LibraryResponse>>(Load.Loading) }
    var browsingAll by remember { mutableStateOf(false) }

    // Re-fetches when the scope flips. Asking for a scope at all is gated on the capability: a
    // server without follows returns the whole shared list either way, and sending the parameter
    // would imply a distinction it does not make.
    LaunchedEffect(browsingAll, capabilities.follows) {
        state = Load.Loading
        val requested = when {
            !capabilities.follows -> null
            browsingAll -> LibraryScope.ALL
            else -> LibraryScope.FOLLOWED
        }
        state = runCatching { repository.library(requested) }
            .fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Could not load library") })
    }

    when (val s = state) {
        Load.Loading -> CenterProgress()
        is Load.Err -> CenterError(s.message)
        is Load.Ok -> {
            val library = s.value
            PageScroll {
                val continueList = library.continueListening
                if (continueList.isNotEmpty()) {
                    ContinueHero(continueList.first(), repository) {
                        scope.launch { playback.play(continueList.first(), continueList.first().fiction) }
                        onOpenPlayer()
                    }
                    if (continueList.size > 1) {
                        Spacer(Modifier.height(28.dp))
                        SectionTitle("01", "Continue listening")
                        Spacer(Modifier.height(16.dp))
                        ContinueShelf(continueList.drop(1), repository) { chapter ->
                            scope.launch { playback.play(chapter, chapter.fiction) }
                        }
                    }
                    Spacer(Modifier.height(36.dp))
                }
                SectionTitle("02", if (browsingAll) "All fictions" else "Fictions")
                Spacer(Modifier.height(16.dp))
                if (capabilities.follows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScopeTab("MY SHELF", active = !browsingAll) { browsingAll = false }
                        Spacer(Modifier.width(8.dp))
                        ScopeTab("BROWSE ALL", active = browsingAll) { browsingAll = true }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (library.fictions.isEmpty()) {
                    MetaText(
                        if (browsingAll) {
                            "Nothing here yet — add fictions on the server"
                        } else {
                            "Your shelf is empty — browse all and follow something"
                        },
                    )
                } else {
                    var query by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("SEARCH TITLE, AUTHOR OR TAG") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    val filtered = remember(library.fictions, query) { filterFictions(library.fictions, query) }
                    if (filtered.isEmpty()) {
                        MetaText("No matches for \"$query\"")
                    } else {
                        FictionGrid(filtered, repository, onOpenFiction)
                    }
                }
                if (library.recentChapters.isNotEmpty()) {
                    Spacer(Modifier.height(36.dp))
                    SectionTitle("03", "Recent")
                    Spacer(Modifier.height(16.dp))
                    ContinueShelf(library.recentChapters, repository) { chapter ->
                        scope.launch { playback.play(chapter, chapter.fiction) }
                    }
                }
            }
        }
    }
}

/** Square scope selector, matching the header's active-tab treatment. */
@Composable
private fun ScopeTab(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .background(if (active) AarisColor.Accent else Color.Transparent)
            .border(1.dp, if (active) AarisColor.Accent else AarisColor.Line)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        MetaText(
            label,
            color = when {
                active -> AarisColor.Bg
                hovered -> AarisColor.Ink
                else -> AarisColor.Muted
            },
        )
    }
}

private fun filterFictions(fictions: List<FictionSummary>, query: String): List<FictionSummary> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return fictions
    return fictions.filter { fiction ->
        fiction.title.lowercase().contains(q) ||
            fiction.author?.lowercase()?.contains(q) == true ||
            fiction.tags.any { it.lowercase().contains(q) }
    }
}

/** Fraction of the chapter already listened to, for progress-on-artwork. */
private fun listenedFraction(chapter: ChapterSummary): Float {
    val duration = chapter.audioDuration ?: return 0f
    if (duration <= 0.0) return 0f
    return (chapter.resolvedPositionSeconds / duration).toFloat().coerceIn(0f, 1f)
}

/**
 * Netflix-style billboard for the most recent in-progress chapter: large cover, gradient panel,
 * one prominent resume action.
 */
@Composable
private fun ContinueHero(chapter: ChapterSummary, repository: TtsRoadRepository, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .border(1.dp, AarisColor.Line)
            .background(Brush.horizontalGradient(listOf(AarisColor.BgHover, AarisColor.Bg))),
    ) {
        CoverImage(
            chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
            chapter.resolvedCoverUrl?.let(repository::resolveUrl),
            Modifier.fillMaxHeight().aspectRatio(2f / 3f),
            bordered = false,
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 24.dp)) {
            MetaText(text = "// Continue listening", color = AarisColor.Accent)
            Spacer(Modifier.height(10.dp))
            Text(
                chapter.resolvedTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = AarisColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            chapter.resolvedFictionTitle?.let {
                Spacer(Modifier.height(6.dp))
                MetaText(it)
            }
            Spacer(Modifier.weight(1f))
            ThinProgress(listenedFraction(chapter), Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onResume,
                    enabled = chapter.audio != null,
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (chapter.resolvedPositionSeconds > 0) "RESUME" else "PLAY")
                }
                Spacer(Modifier.width(16.dp))
                val meta = listOfNotNull(chapter.playback?.remainingLabel, chapter.audioDurationLabel).firstOrNull()
                meta?.let { MetaText(it, color = AarisColor.Dim) }
            }
        }
    }
}

/** Horizontal shelf of in-progress chapters — hover shows a play overlay, progress sits on the art. */
@Composable
private fun ContinueShelf(
    chapters: List<ChapterSummary>,
    repository: TtsRoadRepository,
    onPlay: (ChapterSummary) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(chapters, key = { it.resolvedChapterId }) { chapter ->
            ShelfCard(chapter, repository) { onPlay(chapter) }
        }
    }
}

@Composable
private fun ShelfCard(chapter: ChapterSummary, repository: TtsRoadRepository, onPlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val coverScale by animateFloatAsState(if (hovered) 1.05f else 1f)

    Column(
        Modifier
            .width(156.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = chapter.audio != null, onClick = onPlay),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).border(1.dp, if (hovered) AarisColor.Dim else AarisColor.Line)) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                CoverImage(
                    chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
                    chapter.resolvedCoverUrl?.let(repository::resolveUrl),
                    Modifier.fillMaxSize().graphicsLayer { scaleX = coverScale; scaleY = coverScale },
                    bordered = false,
                )
            }
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(44.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = AarisColor.Bg, modifier = Modifier.size(26.dp))
                    }
                }
            }
            val fraction = listenedFraction(chapter)
            if (fraction > 0f) {
                ThinProgress(fraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            chapter.resolvedTitle,
            style = MaterialTheme.typography.titleMedium,
            color = if (hovered) AarisColor.Ink else AarisColor.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        chapter.resolvedFictionTitle?.let {
            Spacer(Modifier.height(2.dp))
            MetaText(it, color = AarisColor.Dim)
        }
    }
}

/**
 * Even-column grid that fills the available width, matching the web app's
 * `repeat(auto-fill, minmax(200px, 1fr))`: cards are at least ~200dp wide and stretch to fill,
 * so there is never a ragged right edge regardless of window width.
 */
@Composable
private fun FictionGrid(
    fictions: List<FictionSummary>,
    repository: TtsRoadRepository,
    onOpen: (FictionSummary) -> Unit,
) {
    val gap = 18.dp
    val minCard = 200.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + gap).value / (minCard + gap).value).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            fictions.chunked(columns).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { fiction ->
                        FictionCard(fiction, repository, Modifier.weight(1f)) { onOpen(fiction) }
                    }
                    // Keep cards in a short final row at their natural width instead of stretching.
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Cover-forward fiction card: art bleeds to the card edge, TTS-ready progress sits on the art. */
@Composable
private fun FictionCard(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    AarisCard(modifier = modifier, onClick = onOpen) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                CoverImage(
                    fiction.title,
                    fiction.coverImageUrl?.let(repository::resolveUrl),
                    Modifier.fillMaxSize(),
                    bordered = false,
                )
                ThinProgress(fiction.readyFraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    fiction.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AarisColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaText(
                    listOfNotNull(
                        fiction.author?.takeIf { it.isNotBlank() },
                        "${fiction.doneChapters}/${fiction.totalChapters} ready",
                    ).joinToString("  ·  "),
                    color = AarisColor.Dim,
                )
            }
        }
    }
}
