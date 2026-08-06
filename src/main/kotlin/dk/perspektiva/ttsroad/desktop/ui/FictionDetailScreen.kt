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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterFilter
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.chapterKeys
import dk.perspektiva.ttsroad.desktop.data.chapterView
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.launch

/** Test handle for "how many chapter rows did the lazy list actually compose". */
const val ChapterRowTestTag: String = "chapterRow"

/**
 * One fiction and its chapters.
 *
 * The list is a [LazyColumn] with the header as its first item, rather than a header plus an eager
 * `forEach` inside one `verticalScroll`. A serial with several hundred chapters used to compose
 * every row before the first frame; now the scroll position is also a real, retained
 * `LazyListState`, which is what makes Back from the player land where the user was.
 */
@Composable
fun FictionDetailScreen(
    fiction: FictionSummary,
    cache: LibraryCache,
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onBack: () -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    val state by cache.chapters(fiction.id).collectAsState()
    LaunchedEffect(fiction.id) { cache.ensureChapters(fiction.id) }

    // Saved as a name rather than as the enum itself: Compose Desktop's saveable registry only
    // accepts a small set of primitive types, so storing the constant keeps the filter across
    // navigation instead of silently throwing at the first save.
    var filterName by rememberSaveable { mutableStateOf(ChapterFilter.All.name) }
    val filter = ChapterFilter.entries.firstOrNull { it.name == filterName } ?: ChapterFilter.All
    var actionError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val loaded = state.value
    val error = state.error
    val header = loaded?.fiction ?: fiction
    val chapters = loaded?.chapters.orEmpty()
    val visible = remember(chapters, filter) { chapters.chapterView(filter) }
    val keys = remember(visible) { chapterKeys(visible) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(PageGutter),
        ) {
            item(key = "back", contentType = "header") {
                Column {
                    BackLink("Back", onBack)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (state.isStale) {
                item(key = "stale", contentType = "header") {
                    Column {
                        StaleContentBanner(
                            message = error.orEmpty(),
                            lastSuccessMillis = state.lastSuccessMillis,
                            nowMillis = nowMillis(),
                            onRetry = { cache.refreshChapters(fiction.id) },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            item(key = "header", contentType = "header") {
                // `resumeTarget` is null until chapters load, so the button appears with the list.
                FictionHeader(header, repository, chapters = chapters) { target ->
                    scope.launch { playback.playQueue(chapters, target.resolvedChapterId, header) }
                }
            }

            actionError?.let { message ->
                item(key = "action-error", contentType = "header") {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            when {
                loaded == null && error != null -> item(key = "error", contentType = "header") {
                    Column {
                        Spacer(Modifier.height(32.dp))
                        InitialErrorState(error) { cache.refreshChapters(fiction.id) }
                    }
                }

                loaded == null -> item(key = "loading", contentType = "header") {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CenterProgress()
                    }
                }

                else -> {
                    item(key = "chapters-header", contentType = "header") {
                        Column {
                            Spacer(Modifier.height(36.dp))
                            SectionTitle("01", "Chapters — ${visible.size}")
                            Spacer(Modifier.height(12.dp))
                            ChapterFilterRow(filter) { filterName = it.name }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty", contentType = "header") {
                            EmptyState(
                                if (chapters.isEmpty()) "No chapters yet" else "Nothing matches ${filter.label}",
                                if (chapters.isEmpty()) {
                                    "The server has not published any chapters for this fiction."
                                } else {
                                    "Switch the filter back to All to see every chapter."
                                },
                            )
                        }
                    } else {
                        itemsIndexed(
                            visible,
                            key = { index, _ -> keys[index] },
                            contentType = { _, _ -> "chapter" },
                        ) { _, chapter ->
                            Column(Modifier.testTag(ChapterRowTestTag)) {
                                ChapterListRow(
                                    chapter = chapter,
                                    onPlay = {
                                        scope.launch {
                                            playback.playQueue(chapters, chapter.resolvedChapterId, header)
                                        }
                                    },
                                    onMarkPlayed = { played ->
                                        actionError = null
                                        scope.launch {
                                            runCatching {
                                                cache.setPlayed(
                                                    fiction.id,
                                                    listOf(chapter.resolvedChapterId),
                                                    played,
                                                )
                                            }.onFailure {
                                                actionError = userFacingMessage(it, "Could not update chapter")
                                            }
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
        RefreshingStrip(state.isRefreshing && !state.isStale)
    }
}

/** All / Unplayed / Ready. Selection is retained per destination, like the library's search text. */
@Composable
private fun ChapterFilterRow(current: ChapterFilter, onSelect: (ChapterFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChapterFilter.entries.forEach { entry ->
            val selected = entry == current
            Box(
                Modifier
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(entry) })
                    .pointerHoverIcon(PointerIcon.Hand)
                    .background(if (selected) AarisColor.BgHover else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                MetaText(entry.label, color = if (selected) AarisColor.Accent else AarisColor.Muted)
            }
        }
    }
}

@Composable
fun BackLink(label: String, onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("← $label", color = if (hovered || focused) AarisColor.Ink else AarisColor.Muted)
    }
}

/** Best chapter to resume: furthest in-progress one, else the first playable. */
private fun resumeTarget(chapters: List<ChapterSummary>): ChapterSummary? =
    chapters.filter { it.audio != null && it.resolvedPositionSeconds > 0.0 && it.playback?.isPlayed != true }
        .maxByOrNull { it.resolvedPositionSeconds }
        ?: chapters.firstOrNull { it.audio != null && it.playback?.isPlayed != true }
        ?: chapters.firstOrNull { it.audio != null }

@Composable
private fun FictionHeader(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    chapters: List<ChapterSummary>,
    onResume: (ChapterSummary) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        CoverImage(
            fiction.title,
            fiction.coverImageUrl?.let(repository::resolveUrl),
            Modifier.width(190.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            MetaText(text = "// Fiction", color = AarisColor.Accent)
            Spacer(Modifier.height(8.dp))
            Text(
                fiction.title,
                style = MaterialTheme.typography.displaySmall,
                color = AarisColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            fiction.author?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                MetaText("by $it")
            }
            fiction.rating?.let { rating ->
                Spacer(Modifier.height(6.dp))
                MetaText(
                    buildString {
                        append("★ ")
                        append("%.2f".format(rating))
                        fiction.ratingCount?.takeIf { it > 0 }?.let { append("  ·  $it ratings") }
                    },
                    color = AarisColor.Warning,
                )
            }
            if (fiction.tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fiction.tags.take(5).forEach { AarisTag(it) }
                }
            }
            fiction.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Muted,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            ThinProgress(fiction.readyFraction, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            MetaText(
                buildString {
                    append("${fiction.doneChapters}/${fiction.totalChapters} chapters ready")
                    if (fiction.processingChapters > 0) append("  ·  ${fiction.processingChapters} processing")
                    if (fiction.errorChapters > 0) append("  ·  ${fiction.errorChapters} failed")
                },
                color = AarisColor.Dim,
            )
            val target = remember(chapters) { resumeTarget(chapters) }
            if (target != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onResume(target) },
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (target.resolvedPositionSeconds > 0.0) "RESUME" else "START LISTENING")
                }
            }
        }
    }
}

@Composable
private fun ChapterListRow(
    chapter: ChapterSummary,
    onPlay: () -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
) {
    val playable = chapter.audio != null
    val isPlayed = chapter.playback?.isPlayed == true
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Focus reveals the same row actions hover does, so play and mark-played are reachable
    // without a mouse rather than merely present in the semantics tree.
    val hovered = pointerOver || focused

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .let { if (playable) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = playable, onClick = onPlay)
            .background(if (hovered && playable) AarisColor.BgRaise else Color.Transparent)
            .border(1.dp, if (focused && playable) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(chapterNumberLabel(chapter), color = AarisColor.Dim, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chapter.resolvedTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (playable) AarisColor.Ink else AarisColor.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                chapter.audioDurationLabel,
                chapter.playback?.remainingLabel?.let { "$it left" }
                    ?: chapter.resumeTimeLabel?.let { "$it in" },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                MetaText(meta, color = AarisColor.Dim)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!playable) {
            AarisTag(chapter.status ?: "pending")
        } else {
            // Played-check toggle: always visible once played; revealed on hover otherwise.
            if (isPlayed || hovered) {
                RowIconAction(
                    icon = Icons.Default.Check,
                    contentDescription = if (isPlayed) "Mark unplayed" else "Mark played",
                    tint = if (isPlayed) AarisColor.Ok else AarisColor.Dim,
                ) { onMarkPlayed(!isPlayed) }
            }
            if (hovered) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(30.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = AarisColor.Bg, modifier = Modifier.size(18.dp))
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
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered = pointerOver || focused
    Box(
        Modifier
            .size(30.dp)
            .background(if (hovered) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (hovered) AarisColor.Ink else tint, modifier = Modifier.size(18.dp))
    }
}

private fun chapterNumberLabel(chapter: ChapterSummary): String {
    val n = chapter.displayNumber ?: return "—"
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}
