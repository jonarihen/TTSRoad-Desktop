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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterFilter
import dk.perspektiva.ttsroad.desktop.data.ChapterListOptions
import dk.perspektiva.ttsroad.desktop.data.ChapterSort
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.chapterKeys
import dk.perspektiva.ttsroad.desktop.data.chapterView
import dk.perspektiva.ttsroad.desktop.data.chaptersBefore
import dk.perspektiva.ttsroad.desktop.data.indexOfChapter
import dk.perspektiva.ttsroad.desktop.data.markableIds
import dk.perspektiva.ttsroad.desktop.data.playbackOrder
import dk.perspektiva.ttsroad.desktop.data.statusLabel
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.playingChapterIdIn
import kotlinx.coroutines.launch

/** Test handle for "how many chapter rows did the lazy list actually compose". */
const val ChapterRowTestTag: String = "chapterRow"

/** Test handle for the chapter list's scroll container. */
const val ChapterListTestTag: String = "chapterList"

/**
 * Everything above the chapter rows is emitted as a **single** lazy item.
 *
 * That is what makes "scroll to the playing chapter" arithmetic rather than guesswork: row `i` is
 * always at lazy index `i + 1`, no matter how many banners, errors or controls the header happens
 * to be showing this frame.
 */
private const val ChapterListHeaderItems = 1

/**
 * Where a chapter stands with the download queue.
 *
 * [Unavailable] renders nothing at all — shipping a greyed-out download button that cannot be
 * pressed would be worse than shipping none — and is what a signed-out session or a machine with no
 * writable data directory reports.
 */
enum class ChapterDownloadState {
    Unavailable,
    NotDownloaded,
    Queued,
    Downloading,
    Downloaded,
    Failed,
    Removing,
}

/**
 * One chapter's download state as the row draws it.
 *
 * [progress] is null when the server never reported a size: a determinate bar filling from a total
 * this app invented would be a lie, so those rows show an indeterminate one instead.
 */
data class ChapterDownloadUi(
    val state: ChapterDownloadState = ChapterDownloadState.Unavailable,
    val progress: Float? = null,
    val failureMessage: String? = null,
)

/**
 * What the chapter list can ask the download queue to do.
 *
 * Bundled rather than passed as six separate lambdas so the screen's signature stays readable and
 * so a caller cannot wire half of them. Every action defaults to a no-op, which — combined with
 * `available = false` — is what makes the whole feature absent rather than broken on a build or a
 * session that has no download storage.
 */
data class ChapterDownloadsUi(
    val available: Boolean = false,
    val stateFor: (ChapterSummary) -> ChapterDownloadUi = { ChapterDownloadUi() },
    val onDownload: (ChapterSummary) -> Unit = {},
    val onCancel: (ChapterSummary) -> Unit = {},
    val onDelete: (ChapterSummary) -> Unit = {},
    val onRetry: (ChapterSummary) -> Unit = {},
    /** "Download next 10", counted from the resume position rather than from the top of the list. */
    val onDownloadNext: () -> Unit = {},
)

/**
 * One fiction and its chapters.
 *
 * The list is a [LazyColumn] whose only eager content is the header item, so a thousand-chapter
 * serial composes the dozen rows that fit on screen rather than a thousand. Everything the user
 * chooses about the list — filter and sort — lives in [LibraryCache] keyed by fiction, so it
 * survives leaving the screen entirely, not merely a recomposition.
 */
@Composable
fun FictionDetailScreen(
    fiction: FictionSummary,
    cache: LibraryCache,
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onBack: () -> Unit,
    onOpenReader: (ChapterSummary) -> Unit = {},
    downloads: ChapterDownloadsUi = ChapterDownloadsUi(),
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    val state by cache.chapters(fiction.id).collectAsState()
    val options by cache.chapterOptions(fiction.id).collectAsState()
    val capabilities by repository.currentCapabilities.collectAsState()
    val player by playback.state.collectAsState()
    LaunchedEffect(fiction.id) { cache.ensureChapters(fiction.id) }

    var actionError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val loaded = state.value
    val error = state.error
    val header = loaded?.fiction ?: fiction
    val chapters = loaded?.chapters.orEmpty()
    val visible = remember(chapters, options) { chapters.chapterView(options) }
    val keys = remember(visible) { chapterKeys(visible) }

    // Where each chapter sits in canonical reading order, computed once per list rather than once
    // per row — "is there anything before this chapter" would otherwise be O(n) inside an O(n) list.
    val canonicalIndex = remember(chapters) {
        chapters.playbackOrder().withIndex().associate { (index, chapter) -> chapter.resolvedChapterId to index }
    }
    val playedAllIds = remember(chapters) { chapters.markableIds(played = true) }
    val unplayedAllIds = remember(chapters) { chapters.markableIds(played = false) }

    val playingChapterId = player.playingChapterIdIn(fiction.id)
    val currentRow = remember(visible, playingChapterId) { visible.indexOfChapter(playingChapterId) }

    fun mark(ids: List<Int>, played: Boolean) {
        if (ids.isEmpty()) return
        actionError = null
        scope.launch {
            runCatching { cache.setPlayed(fiction.id, ids, played) }
                .onFailure { actionError = userFacingMessage(it, "Could not update chapters") }
        }
    }

    fun play(chapter: ChapterSummary) {
        // The queue is always built from the *whole* fiction in reading order, never from the
        // filtered view: filtering to "Unplayed" is a way of finding a chapter, not an instruction
        // to skip everything else once playback starts.
        scope.launch { playback.playQueue(chapters, chapter.resolvedChapterId, header) }
    }

    // Open on the chapter that is playing — and only on that, and only once.
    //
    // Deliberately not "scroll to whatever Resume would start": opening a serial you are not
    // listening to should show you its beginning, and a screen that silently jumps somewhere on
    // every visit is disorienting. Returning from the player must land where the user left, which
    // is what the destination's retained scroll offset already does.
    var autoScrolled by rememberSaveable(fiction.id) { mutableStateOf(false) }
    LaunchedEffect(currentRow) {
        if (autoScrolled || currentRow < 0) return@LaunchedEffect
        autoScrolled = true
        if (currentRow > 0) listState.scrollToItem(currentRow + ChapterListHeaderItems)
    }

    // `derivedStateOf` so scrolling does not recompose the whole screen on every pixel — only the
    // one boolean the jump affordance reads.
    val currentOffScreen by remember(currentRow) {
        derivedStateOf {
            currentRow >= 0 &&
                listState.layoutInfo.visibleItemsInfo.none { it.index == currentRow + ChapterListHeaderItems }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .testTag(ChapterListTestTag),
            contentPadding = PaddingValues(PageGutter),
        ) {
            item(key = "head", contentType = "header") {
                Column {
                    BackLink("Back", onBack)
                    Spacer(Modifier.height(20.dp))

                    if (state.isStale) {
                        StaleContentBanner(
                            message = error.orEmpty(),
                            lastSuccessMillis = state.lastSuccessMillis,
                            nowMillis = nowMillis(),
                            onRetry = { cache.refreshChapters(fiction.id) },
                        )
                        Spacer(Modifier.height(20.dp))
                    }

                    // `resumeTarget` is null until chapters load, so the button appears with the list.
                    FictionHeader(header, repository, chapters = chapters, onResume = ::play)

                    actionError?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }

                    when {
                        loaded == null && error != null -> {
                            Spacer(Modifier.height(32.dp))
                            InitialErrorState(error) { cache.refreshChapters(fiction.id) }
                        }

                        loaded == null -> Box(
                            Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) { CenterProgress() }

                        else -> {
                            Spacer(Modifier.height(36.dp))
                            SectionTitle("01", chapterCountLabel(visible.size, chapters.size, options))
                            Spacer(Modifier.height(12.dp))
                            ChapterListControls(
                                options = options,
                                canMarkPlayed = playedAllIds.isNotEmpty(),
                                canMarkUnplayed = unplayedAllIds.isNotEmpty(),
                                onOptions = { cache.setChapterOptions(fiction.id, it) },
                                onMarkAllPlayed = { mark(playedAllIds, played = true) },
                                onMarkAllUnplayed = { mark(unplayedAllIds, played = false) },
                                downloadsAvailable = downloads.available,
                                onDownloadNext = downloads.onDownloadNext,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            if (loaded != null && visible.isEmpty()) {
                item(key = "empty", contentType = "header") {
                    EmptyState(
                        if (chapters.isEmpty()) "No chapters yet" else "Nothing matches ${options.filter.label}",
                        if (chapters.isEmpty()) {
                            "The server has not published any chapters for this fiction."
                        } else {
                            "Switch the filter back to All to see every chapter."
                        },
                    )
                }
            }

            itemsIndexed(
                visible,
                key = { index, _ -> keys[index] },
                contentType = { _, _ -> "chapter" },
            ) { index, chapter ->
                Column {
                    ChapterListRow(
                        modifier = Modifier.testTag(ChapterRowTestTag),
                        chapter = chapter,
                        isCurrent = index == currentRow,
                        isPlaying = player.isPlaying,
                        canMarkPrevious = (canonicalIndex[chapter.resolvedChapterId] ?: 0) > 0,
                        readAlongAvailable = capabilities.readAlong && chapter.hasTimings,
                        download = downloads.stateFor(chapter),
                        onPlay = { play(chapter) },
                        onMarkPlayed = { played -> mark(listOf(chapter.resolvedChapterId), played) },
                        onMarkPrevious = {
                            mark(
                                chapters.chaptersBefore(chapter.resolvedChapterId).markableIds(played = true),
                                played = true,
                            )
                        },
                        onOpenReader = { onOpenReader(chapter) },
                        onDownload = { downloads.onDownload(chapter) },
                        onCancelDownload = { downloads.onCancel(chapter) },
                        onDeleteDownload = { downloads.onDelete(chapter) },
                        onRetryDownload = { downloads.onRetry(chapter) },
                    )
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
                }
            }
        }

        if (currentOffScreen) {
            JumpToCurrent(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                scope.launch { listState.animateScrollToItem(currentRow + ChapterListHeaderItems) }
            }
        }
        RefreshingStrip(state.isRefreshing && !state.isStale)
    }
}

/** "Chapters — 12 of 340" while filtered; the plain total otherwise. */
private fun chapterCountLabel(visible: Int, total: Int, options: ChapterListOptions): String =
    if (options.isFiltered && visible != total) "Chapters — $visible of $total" else "Chapters — $total"

/**
 * Filter, order and the bulk actions.
 *
 * Filter and sort are `selectable` tabs rather than a cycling toggle: a tab announces its selected
 * state to a screen reader and can be reached in one Tab stop each, where a toggle button that
 * relabels itself announces the *next* state rather than the current one.
 */
@Composable
private fun ChapterListControls(
    options: ChapterListOptions,
    canMarkPlayed: Boolean,
    canMarkUnplayed: Boolean,
    onOptions: (ChapterListOptions) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onMarkAllUnplayed: () -> Unit,
    downloadsAvailable: Boolean,
    onDownloadNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChapterFilter.entries.forEach { entry ->
                    SegmentTab(entry.label, entry == options.filter) { onOptions(options.copy(filter = entry)) }
                }
            }
            Spacer(Modifier.width(24.dp))
            MetaText("Order", color = AarisColor.Dim)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChapterSort.entries.forEach { entry ->
                    SegmentTab(entry.label, entry == options.sort) { onOptions(options.copy(sort = entry)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BulkAction("Mark all played", enabled = canMarkPlayed, onClick = onMarkAllPlayed)
            BulkAction("Mark all unplayed", enabled = canMarkUnplayed, onClick = onMarkAllUnplayed)
            // Absent rather than disabled when there is nowhere to download to: a control that can
            // never be pressed is worse than one that is not there.
            if (downloadsAvailable) {
                BulkAction(
                    "Download next ${dk.perspektiva.ttsroad.desktop.download.DownloadIndex.DefaultBatch}",
                    enabled = true,
                    onClick = onDownloadNext,
                )
            }
        }
    }
}

@Composable
private fun SegmentTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onSelect,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        MetaText(label, color = if (selected) AarisColor.Accent else AarisColor.Muted)
    }
}

/**
 * A bulk mark.
 *
 * Disabled — rather than hidden — when there is nothing left to change, so the affordance stays in
 * the same place and a keyboard user's tab order does not shift under them mid-session.
 */
@Composable
private fun BulkAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) { MetaText(label, color = if (enabled) AarisColor.Ink else AarisColor.Dim) }
}

/** Returns the reader to the chapter that is playing after they have scrolled away from it. */
@Composable
private fun JumpToCurrent(modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RectangleShape,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Default.MyLocation, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("JUMP TO CURRENT")
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
    chapters.filter { it.hasAudio && it.resolvedPositionSeconds > 0.0 && !it.isPlayed }
        .maxByOrNull { it.resolvedPositionSeconds }
        ?: chapters.firstOrNull { it.hasAudio && !it.isPlayed }
        ?: chapters.firstOrNull { it.hasAudio }

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

/**
 * One chapter.
 *
 * Every action on the row is composed unconditionally and merely *dims* when the row is not hovered
 * or focused. The obvious alternative — compose the icons only while the row is hovered — reads the
 * same with a mouse and is unusable without one: taking focus into a revealed button removes focus
 * from the row, which un-reveals the button that is trying to take it.
 */
@Composable
private fun ChapterListRow(
    modifier: Modifier,
    chapter: ChapterSummary,
    isCurrent: Boolean,
    isPlaying: Boolean,
    canMarkPrevious: Boolean,
    readAlongAvailable: Boolean,
    download: ChapterDownloadUi,
    onPlay: () -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
    onMarkPrevious: () -> Unit,
    onOpenReader: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val playable = chapter.hasAudio
    val isPlayed = chapter.isPlayed
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pointerOver || focused
    val status = chapter.statusLabel()

    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .let { if (playable) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = playable, onClick = onPlay)
            .background(
                when {
                    isCurrent -> AarisColor.BgHover
                    active && playable -> AarisColor.BgRaise
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    focused && playable -> AarisColor.Accent
                    isCurrent -> AarisColor.Accent.copy(alpha = 0.4f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(
            chapterNumberLabel(chapter),
            color = if (isCurrent) AarisColor.Accent else AarisColor.Dim,
            modifier = Modifier.width(48.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                chapter.resolvedTitle,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isCurrent -> AarisColor.Accent
                    playable -> AarisColor.Ink
                    else -> AarisColor.Dim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                if (isCurrent) (if (isPlaying) "Playing" else "Paused") else null,
                chapter.audioDurationLabel?.takeIf { playable },
                chapter.playback?.remainingLabel?.let { "$it left" }
                    ?: chapter.resumeTimeLabel?.let { "$it in" },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                MetaText(meta, color = if (isCurrent) AarisColor.Accent else AarisColor.Dim)
            }
        }
        Spacer(Modifier.width(12.dp))
        status?.let {
            AarisTag(it)
            Spacer(Modifier.width(8.dp))
        }
        ChapterDownloadSlot(
            download = download,
            onDownload = onDownload,
            onCancel = onCancelDownload,
            onDelete = onDeleteDownload,
            onRetry = onRetryDownload,
        )
        if (readAlongAvailable) {
            RowIconAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = "Read along",
                tint = if (active) AarisColor.Ink else AarisColor.Dim,
                onClick = onOpenReader,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (canMarkPrevious) {
            RowIconAction(
                icon = Icons.Default.DoneAll,
                contentDescription = "Mark all previous chapters as played",
                tint = if (active) AarisColor.Ink else AarisColor.Dim,
                onClick = onMarkPrevious,
            )
            Spacer(Modifier.width(4.dp))
        }
        RowIconAction(
            icon = Icons.Default.Check,
            contentDescription = if (isPlayed) "Mark unplayed" else "Mark played",
            tint = if (isPlayed) AarisColor.Ok else if (active) AarisColor.Ink else AarisColor.Dim,
            onClick = { onMarkPlayed(!isPlayed) },
        )
        if (playable) {
            Spacer(Modifier.width(4.dp))
            RowIconAction(
                icon = Icons.Default.PlayArrow,
                contentDescription = if (isCurrent) "Restart chapter" else "Play chapter",
                tint = if (active || isCurrent) AarisColor.Accent else AarisColor.Dim,
                onClick = onPlay,
            )
        }
    }
}

/**
 * The row's download control: state and the one action that state affords.
 *
 * Each state offers exactly one action, because a row that shows Download *and* Cancel *and* Delete
 * is a row nobody can read at a glance. [ChapterDownloadState.Removing] deliberately offers none —
 * the deletion is already happening and a second press would have nothing to do.
 *
 * The whole slot is absent for [ChapterDownloadState.Unavailable], which is what a session with no
 * download storage reports.
 */
@Composable
private fun ChapterDownloadSlot(
    download: ChapterDownloadUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    when (download.state) {
        ChapterDownloadState.Unavailable -> return

        ChapterDownloadState.NotDownloaded ->
            RowIconAction(Icons.Default.Download, "Download", AarisColor.Muted, onDownload)

        // A queued row has nothing to show but the fact that it is waiting, and the useful action
        // is the same one an in-flight row offers.
        ChapterDownloadState.Queued ->
            RowIconAction(Icons.Default.Downloading, "Queued for download — cancel", AarisColor.Muted, onCancel)

        ChapterDownloadState.Downloading -> DownloadProgressAction(download.progress, onCancel)

        ChapterDownloadState.Downloaded ->
            RowIconAction(Icons.Default.OfflinePin, "Available offline — delete", AarisColor.Ok, onDelete)

        ChapterDownloadState.Failed ->
            RowIconAction(
                Icons.Default.ErrorOutline,
                // The reason travels in the accessible description rather than a tooltip, so a
                // screen reader gets it too and a UI test can assert on it.
                download.failureMessage?.let { "Download failed: $it — retry" } ?: "Download failed — retry",
                AarisColor.Danger,
                onRetry,
            )

        ChapterDownloadState.Removing ->
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).semantics { contentDescription = "Removing download" },
                    strokeWidth = 2.dp,
                    color = AarisColor.Muted,
                )
            }
    }
    Spacer(Modifier.width(4.dp))
}

/**
 * An in-flight download: a ring showing how far along it is, and a click to cancel.
 *
 * Indeterminate when the server never reported a size — a determinate ring filling from a total
 * this app invented would be worse than an honest spinner.
 */
@Composable
private fun DownloadProgressAction(progress: Float?, onCancel: () -> Unit) {
    val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pointerOver || focused
    val description = "Downloading$percent — cancel"
    Box(
        Modifier
            .size(30.dp)
            .background(if (active) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onCancel,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AarisColor.Accent)
        } else {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = AarisColor.Accent,
                trackColor = AarisColor.BgHover,
            )
        }
    }
}

/** Borderless icon action used inside list rows. Always present; brightens on hover or focus. */
@Composable
private fun RowIconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pointerOver || focused
    // Bound to a local before entering the semantics lambda: inside it, the bare name resolves to
    // the write-only semantics property rather than to this parameter.
    val description = contentDescription
    Box(
        Modifier
            .size(30.dp)
            .background(if (active) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            // On the clickable node rather than on the icon: that is the node a screen reader
            // lands on and the node a test asks for by description.
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) AarisColor.Ink else tint, modifier = Modifier.size(18.dp))
    }
}

private fun chapterNumberLabel(chapter: ChapterSummary): String {
    val n = chapter.resolvedDisplayNumber ?: return "—"
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}
