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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dk.perspektiva.ttsroad.desktop.data.ListeningTotals
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.chapterKeys
import dk.perspektiva.ttsroad.desktop.data.chapterView
import dk.perspektiva.ttsroad.desktop.data.chaptersBefore
import dk.perspektiva.ttsroad.desktop.data.formatListeningSpan
import dk.perspektiva.ttsroad.desktop.data.indexOfChapter
import dk.perspektiva.ttsroad.desktop.data.listeningTotals
import dk.perspektiva.ttsroad.desktop.data.markableIds
import dk.perspektiva.ttsroad.desktop.data.playbackOrder
import dk.perspektiva.ttsroad.desktop.data.canRetry
import dk.perspektiva.ttsroad.desktop.data.FictionMaintenanceAction
import dk.perspektiva.ttsroad.desktop.data.chapterDeleteConfirmation
import dk.perspektiva.ttsroad.desktop.data.reconvertConfirmation
import dk.perspektiva.ttsroad.desktop.data.statusLabel
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.playingChapterIdIn
import kotlinx.coroutines.launch

/** Test handle for "how many chapter rows did the lazy list actually compose". */
const val PollFictionButtonTestTag: String = "pollFictionButton"
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
 * The chapter list's link to the account's server-side queue.
 *
 * Absent by default and hidden entirely when [available] is false, so a server without the `queue`
 * capability shows a chapter list with no controls that would 404. [notice] is the holder's, not
 * this screen's: the queue is hoisted above navigation, so "Added 2 chapters to the queue" is the
 * same message whether the user is looking at the chapter list or the queue when it arrives.
 */
/**
 * Repairing one chapter, as the row draws it (#113).
 *
 * Two flags rather than one, because the three routes do not share a gate. [retryAvailable] is the
 * capability alone — retry is open to any signed-in account — while [canModerate] additionally
 * requires `is_admin`, because excluding a chapter changes what *every* account's podcast feed
 * contains and deleting it destroys the audio for everybody.
 */
data class ChapterMaintenanceUi(
    val retryAvailable: Boolean = false,
    val canModerate: Boolean = false,
    val busyChapterId: Int? = null,
    val notice: String? = null,
    val error: String? = null,
    /** Whole-fiction actions this account may run, already filtered by capability and role. */
    val fictionActions: List<FictionMaintenanceAction> = emptyList(),
    val busyAction: FictionMaintenanceAction? = null,
    val confirming: FictionMaintenanceAction? = null,
    val onFictionAction: (FictionMaintenanceAction) -> Unit = {},
    val onConfirmAction: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onRetry: (ChapterSummary) -> Unit = {},
    val onSetExcluded: (ChapterSummary, Boolean) -> Unit = { _, _ -> },
    val onDelete: (ChapterSummary) -> Unit = {},
)

data class ChapterQueueUi(
    val available: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val onAddToQueue: (List<Int>) -> Unit = {},
    val onPlayNext: (List<Int>) -> Unit = {},
    /** Server-side `fill` from this fiction's unplayed chapters — the server picks, not the client. */
    val onQueueUnplayed: () -> Unit = {},
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
    queue: ChapterQueueUi = ChapterQueueUi(),
    maintenance: ChapterMaintenanceUi = ChapterMaintenanceUi(),
    fictionManagement: FictionManagementUiState = FictionManagementUiState(),
    onEditFiction: (FictionSummary) -> Unit = {},
    onDeleteFiction: (FictionSummary) -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    val state by cache.chapters(fiction.id).collectAsState()
    val options by cache.chapterOptions(fiction.id).collectAsState()
    val capabilities by repository.currentCapabilities.collectAsState()
    val player by playback.state.collectAsState()
    LaunchedEffect(fiction.id) { cache.ensureChapters(fiction.id) }

    var actionError by remember { mutableStateOf<String?>(null) }
    // Which chapter's admin disclosure is open, and which delete is awaiting a second answer.
    var managingChapter by remember { mutableStateOf<ChapterSummary?>(null) }
    var deletingChapter by remember { mutableStateOf<ChapterSummary?>(null) }
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

    // Seeded from the library summary and mutated only by the toggle. Deliberately *not* re-read
    // from `loaded.fiction`: the chapters endpoint builds its fiction payload without a `following`
    // key at all, so a screen that trusted it would flip every followed book to "unfollowed" the
    // moment its chapters arrived. See [FictionSummary.following].
    var followOverride by remember(fiction.id) { mutableStateOf<Boolean?>(null) }
    var followBusy by remember(fiction.id) { mutableStateOf(false) }
    val following = followOverride ?: fiction.following ?: cache.followingOf(fiction.id)

    fun toggleFollow() {
        val target = !(following ?: false)
        followBusy = true
        actionError = null
        scope.launch {
            runCatching { cache.setFollowing(fiction.id, target) }
                .onSuccess { confirmed ->
                    // Null is the server's 404 — no such fiction, or no such endpoint. Nothing
                    // changed, so nothing may render as though it had.
                    if (confirmed == null) {
                        actionError = "The server does not have this fiction any more"
                    } else {
                        followOverride = confirmed
                    }
                }
                .onFailure { actionError = userFacingMessage(it, "Could not update your shelf") }
            followBusy = false
        }
    }

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The app promises 720 dp. At that width the 28 dp gutters leave 664, and the header's
        // fixed 190 dp cover plus its 28 dp gap left barely 440 for a title, the counters and four
        // buttons — which is why this screen needed a size class of its own like Settings has.
        val compact = windowSizeClassFor(maxWidth).isCompact
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
                    FictionHeader(
                        header,
                        repository,
                        chapters = chapters,
                        onResume = ::play,
                        // Absent, not disabled, on a server whose library is still the whole shared
                        // list: there is no shelf there to add anything to.
                        follow = if (capabilities.follows) {
                            FollowUi(following = following ?: false, busy = followBusy, onToggle = ::toggleFollow)
                        } else {
                            null
                        },
                        management = if (fictionManagement.canManage) {
                            FictionManagementActions(
                                busy = fictionManagement.isBusy,
                                onEdit = { onEditFiction(header) },
                                onDelete = { onDeleteFiction(header) },
                            )
                        } else {
                            null
                        },
                        compact = compact,
                    )

                    // Outside the admin block on purpose: the server leaves polling open to any
                    // account, so gating it on `canManage` would hide it from most of them.
                    if (FictionMaintenanceAction.Poll in maintenance.fictionActions) {
                        Spacer(Modifier.height(16.dp))
                        AarisSecondaryAction(
                            label = if (maintenance.busyAction == FictionMaintenanceAction.Poll) {
                                "Checking…"
                            } else {
                                FictionMaintenanceAction.Poll.title
                            },
                            onClick = { maintenance.onFictionAction(FictionMaintenanceAction.Poll) },
                            enabled = maintenance.busyAction == null,
                            modifier = Modifier.testTag(PollFictionButtonTestTag),
                        )
                    }

                    if (fictionManagement.canManage) {
                        Spacer(Modifier.height(16.dp))
                        ManageFictionBlock(
                            FictionManagementActions(
                                busy = fictionManagement.isBusy,
                                onEdit = { onEditFiction(header) },
                                onDelete = { onDeleteFiction(header) },
                            ),
                            maintenance = maintenance,
                        )
                    }

                    actionError?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    fictionManagement.notice?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        MetaText(message, color = AarisColor.Ok)
                    }
                    if (fictionManagement.editor == null) {
                        fictionManagement.error?.let { message ->
                            Spacer(Modifier.height(12.dp))
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    queue.error?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    queue.notice?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        MetaText(message, color = AarisColor.Ok)
                    }
                    // Beside the queue's, not inside the chapter list: a delete removes the row
                    // the notice would have hung off, and a retry changes its status.
                    maintenance.error?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    maintenance.notice?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        MetaText(message, color = AarisColor.Ok)
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
                                queue = queue,
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
                        // The capability gates the endpoint. `has_timings` only changes whether
                        // the reader follows audio: chapters converted earlier still have useful
                        // plain narration text and must remain openable.
                        readAlongAvailable = capabilities.readAlong,
                        readAlongTimed = chapter.hasTimings,
                        download = downloads.stateFor(chapter),
                        queue = queue,
                        maintenance = maintenance,
                        onManage = { managingChapter = chapter },
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

    managingChapter?.let { chapter ->
        ChapterMaintenanceDialog(
            chapter = chapter,
            busy = maintenance.busyChapterId == chapter.resolvedChapterId,
            onSetExcluded = { excluded ->
                maintenance.onSetExcluded(chapter, excluded)
                managingChapter = null
            },
            // Closes the disclosure and raises the confirmation: two dialogs stacked would leave
            // the consequence text behind the question about it.
            onDelete = {
                managingChapter = null
                deletingChapter = chapter
            },
            onDismiss = { managingChapter = null },
        )
    }

    maintenance.confirming?.let { action ->
        ConfirmDialog(
            title = action.title.uppercase(),
            body = reconvertConfirmation(fiction.doneChapters),
            confirmLabel = "RE-NARRATE EVERYTHING",
            onConfirm = maintenance.onConfirmAction,
            onDismiss = maintenance.onDismissConfirmation,
        )
    }

    deletingChapter?.let { chapter ->
        ConfirmDialog(
            title = "DELETE ${chapter.resolvedTitle.uppercase()}",
            body = chapterDeleteConfirmation(chapter.resolvedTitle),
            confirmLabel = "DELETE FOR EVERYONE",
            onConfirm = {
                maintenance.onDelete(chapter)
                deletingChapter = null
            },
            onDismiss = { deletingChapter = null },
        )
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
    queue: ChapterQueueUi,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // FlowRow, not Row: three filter tabs, an Order label and two sort tabs already fill the
        // 664 dp a 720 dp window leaves after its gutters, and any UI scaling above 100% pushed them
        // straight off the edge with nowhere to go.
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // Kept together in their own Row so a wrap never splits one group across two lines,
            // which would read as five unrelated tabs rather than two choices.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChapterFilter.entries.forEach { entry ->
                    SegmentTab(entry.label, entry == options.filter) { onOptions(options.copy(filter = entry)) }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText("Order", color = AarisColor.Dim)
                ChapterSort.entries.forEach { entry ->
                    SegmentTab(entry.label, entry == options.sort) { onOptions(options.copy(sort = entry)) }
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            // Server-side `fill`: the backend picks this fiction's unplayed chapters itself, which
            // is both fewer round trips and the same answer every client gets.
            if (queue.available) {
                BulkAction("Queue unplayed", enabled = !queue.busy, onClick = queue.onQueueUnplayed)
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

/**
 * "54h 38m remaining  ·  12/73 played  ·  61 left".
 *
 * The remaining span leads because it is the number a listener plans an evening around; it is
 * dropped once nothing is left rather than rendered as "0m remaining", which reads as a stall.
 */
internal fun listeningTotalsLabel(totals: ListeningTotals): String = buildString {
    if (totals.remainingSeconds > 0.0) append("${formatListeningSpan(totals.remainingSeconds)} remaining  ·  ")
    append("${totals.played}/${totals.listenable} played")
    if (totals.unplayed > 0) append("  ·  ${totals.unplayed} left")
}

/** Best chapter to resume: furthest in-progress one, else the first playable. */
private fun resumeTarget(chapters: List<ChapterSummary>): ChapterSummary? =
    chapters.filter { it.hasAudio && it.resolvedPositionSeconds > 0.0 && !it.isPlayed }
        .maxByOrNull { it.resolvedPositionSeconds }
        ?: chapters.firstOrNull { it.hasAudio && !it.isPlayed }
        ?: chapters.firstOrNull { it.hasAudio }

/** The follow control's whole state, or null where the server has no per-user library. */
data class FollowUi(val following: Boolean, val busy: Boolean, val onToggle: () -> Unit)

/**
 * The two admin controls on a fiction header.
 *
 * `onEdit` **opens the editor screen** rather than a dialog: the fields it changes are shared with
 * every account, and on a server that tracks hand edits saving one takes it away from the source
 * permanently — which is more than a header button should be able to do in passing.
 */
data class FictionManagementActions(
    val busy: Boolean,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
)

const val FollowToggleTestTag: String = "followToggle"
const val EditFictionButtonTestTag: String = "editFictionButton"
const val DeleteFictionButtonTestTag: String = "deleteFictionButton"

@Composable
private fun FictionHeader(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    chapters: List<ChapterSummary>,
    onResume: (ChapterSummary) -> Unit,
    follow: FollowUi? = null,
    management: FictionManagementActions? = null,
    /** Below this the cover shrinks; the app promises 720 dp and the cover was a fixed 190. */
    compact: Boolean = false,
) {
    Row(Modifier.fillMaxWidth()) {
        CoverImage(
            fiction.title,
            fiction.coverImageUrl?.let(repository::resolveUrl),
            Modifier.width(if (compact) 120.dp else 190.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(if (compact) 16.dp else 28.dp))
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
            // What is left to *listen to*, as opposed to the conversion progress above it. Summed
            // from the same rows the list draws, so a bulk mark-played moves it immediately and a
            // failed mark rolls it back with the checkmarks.
            val totals = remember(chapters) { chapters.listeningTotals() }
            if (!totals.isEmpty) {
                Spacer(Modifier.height(6.dp))
                MetaText(listeningTotalsLabel(totals), color = AarisColor.Muted)
            }
            val target = remember(chapters) { resumeTarget(chapters) }
            if (target != null || follow != null || management != null) {
                Spacer(Modifier.height(16.dp))
                // One primary control, then a wrapping row of secondaries. The admin pair moves out
                // entirely — see `ManageFictionBlock`: an edit that permanently takes a field away
                // from the source, and a delete that destroys every account's progress, are not
                // things a header button should be able to do in passing.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (target != null) {
                        AarisPrimaryAction(
                            label = if (target.resolvedPositionSeconds > 0.0) "Resume" else "Start listening",
                            onClick = { onResume(target) },
                            icon = Icons.Default.PlayArrow,
                        )
                    }
                    follow?.let { FollowButton(it) }
                }
            }
        }
    }
}

const val ManageFictionTestTag: String = "manageFiction"

/**
 * The admin housekeeping, behind a disclosure rather than in the header.
 *
 * Both of these need a sentence to be safe to press — editing metadata claims the field against
 * every future refresh of the source, and deleting destroys the shared chapters and every account's
 * progress — which is the test for rank three. A header row of four equal buttons could say neither.
 */
@Composable
private fun ManageFictionBlock(
    actions: FictionManagementActions,
    maintenance: ChapterMaintenanceUi = ChapterMaintenanceUi(),
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag(ManageFictionTestTag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AarisSecondaryAction(
            label = if (open) "Hide management" else "Manage this fiction",
            onClick = { open = !open },
        )
        if (open) {
            AarisActionRow(
                title = "Edit metadata",
                subtitle = "Correcting a field stops the server refreshing it from the source.",
                onClick = actions.onEdit,
                enabled = !actions.busy,
                modifier = Modifier.testTag(EditFictionButtonTestTag),
            )
            // The admin maintenance rows sit between editing and deleting: each needs its own
            // sentence, which is the same reason Edit and Delete are rows rather than buttons.
            maintenance.fictionActions.filter { it.adminOnly }.forEach { action ->
                AarisActionRow(
                    title = if (maintenance.busyAction == action) "${action.title}…" else action.title,
                    subtitle = action.subtitle,
                    onClick = { maintenance.onFictionAction(action) },
                    enabled = !actions.busy && maintenance.busyAction == null,
                    // Severity: re-narration throws away audio that exists. That does not promote
                    // it above the others in the order anything is reached for.
                    titleColor = if (action == FictionMaintenanceAction.ReconvertAll) {
                        AarisColor.Warning
                    } else {
                        AarisColor.Ink
                    },
                    modifier = Modifier.testTag("maintenance:${action.name}"),
                )
            }
            AarisActionRow(
                title = "Delete fiction",
                subtitle = "Destroys the shared chapters and every account's progress. Not undoable.",
                onClick = actions.onDelete,
                enabled = !actions.busy,
                titleColor = AarisColor.Danger,
                modifier = Modifier.testTag(DeleteFictionButtonTestTag),
            )
        }
    }
}

/**
 * Add to, or take off, this account's shelf.
 *
 * Labelled for the state it is *in* rather than the action it performs — "Following" with a filled
 * bookmark, "Follow" with an outline — because the row above it is a description of the fiction,
 * and a control there that reads as an instruction is ambiguous about which of the two it is
 * saying. The content description carries the action for a screen reader.
 */
@Composable
private fun FollowButton(follow: FollowUi) {
    OutlinedButton(
        onClick = follow.onToggle,
        enabled = !follow.busy,
        shape = RectangleShape,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(FollowToggleTestTag)
            .semantics {
                contentDescription = if (follow.following) "Unfollow this fiction" else "Follow this fiction"
            },
    ) {
        Icon(
            if (follow.following) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
            contentDescription = null,
            Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (follow.following) "FOLLOWING" else "FOLLOW")
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
    readAlongTimed: Boolean,
    download: ChapterDownloadUi,
    queue: ChapterQueueUi,
    maintenance: ChapterMaintenanceUi,
    onManage: () -> Unit,
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
        // Only for a chapter that can actually be queued: the server drops an id with no audio, so
        // offering the action on a converting chapter would be a button that reports nothing added.
        if (queue.available && playable) {
            RowIconAction(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = "Play ${chapter.resolvedTitle} next",
                tint = if (active) AarisColor.Ink else AarisColor.Dim,
                enabled = !queue.busy,
                onClick = { queue.onPlayNext(listOf(chapter.resolvedChapterId)) },
            )
            Spacer(Modifier.width(4.dp))
            RowIconAction(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = "Add ${chapter.resolvedTitle} to the queue",
                tint = if (active) AarisColor.Ink else AarisColor.Dim,
                enabled = !queue.busy,
                onClick = { queue.onAddToQueue(listOf(chapter.resolvedChapterId)) },
            )
            Spacer(Modifier.width(4.dp))
        }
        // Only on a failed chapter, and for any account: the server leaves retry ungated because it
        // repairs one row and harms nobody. A converting chapter is already doing this and answers
        // 409; an excluded one has to be put back first.
        // Admin-only, and a disclosure rather than two more icons: excluding changes every
        // account's feed and deleting destroys the audio for everybody. Neither fits on a label.
        if (maintenance.canModerate) {
            RowIconAction(
                icon = Icons.Default.Tune,
                contentDescription = "Manage ${chapter.resolvedTitle}",
                tint = if (active) AarisColor.Ink else AarisColor.Dim,
                enabled = maintenance.busyChapterId != chapter.resolvedChapterId,
                onClick = { onManage() },
            )
            Spacer(Modifier.width(4.dp))
        }
        if (maintenance.retryAvailable && chapter.canRetry()) {
            RowIconAction(
                icon = Icons.Default.Refresh,
                contentDescription = "Convert ${chapter.resolvedTitle} again",
                tint = if (active) AarisColor.Warning else AarisColor.Dim,
                enabled = maintenance.busyChapterId != chapter.resolvedChapterId,
                onClick = { maintenance.onRetry(chapter) },
            )
            Spacer(Modifier.width(4.dp))
        }
                if (readAlongAvailable) {
            RowIconAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = if (readAlongTimed) "Read along" else "Read chapter text",
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

private fun chapterNumberLabel(chapter: ChapterSummary): String {
    val n = chapter.resolvedDisplayNumber ?: return "—"
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}
