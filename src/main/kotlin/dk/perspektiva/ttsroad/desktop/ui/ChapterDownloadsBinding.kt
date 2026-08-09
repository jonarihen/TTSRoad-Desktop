package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.playbackOrder
import dk.perspektiva.ttsroad.desktop.download.DownloadCoordinator
import dk.perspektiva.ttsroad.desktop.download.DownloadEntry
import dk.perspektiva.ttsroad.desktop.download.DownloadIndex
import dk.perspektiva.ttsroad.desktop.download.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How one index row looks to a chapter row.
 *
 * Pure, and separate from the composable below, because the mapping is the part with decisions in
 * it: a missing entry is *not* "unavailable", it is "not downloaded yet", and the two render
 * completely differently — one draws nothing at all, the other draws the control that starts a
 * download.
 */
fun chapterDownloadUi(entry: DownloadEntry?, available: Boolean): ChapterDownloadUi {
    if (!available) return ChapterDownloadUi(ChapterDownloadState.Unavailable)
    if (entry == null) return ChapterDownloadUi(ChapterDownloadState.NotDownloaded)
    return when (entry.state) {
        DownloadState.None -> ChapterDownloadUi(ChapterDownloadState.NotDownloaded)
        DownloadState.Queued -> ChapterDownloadUi(ChapterDownloadState.Queued)
        DownloadState.Downloading -> ChapterDownloadUi(ChapterDownloadState.Downloading, entry.progress)
        DownloadState.Downloaded -> ChapterDownloadUi(ChapterDownloadState.Downloaded)
        DownloadState.Failed -> ChapterDownloadUi(ChapterDownloadState.Failed, null, entry.failureMessage)
        DownloadState.Removing -> ChapterDownloadUi(ChapterDownloadState.Removing)
    }
}

/**
 * Which chapters "Download next N" should queue.
 *
 * Counted from where the listener actually is — the first unplayed chapter in reading order —
 * rather than from the top of the list, and in reading order regardless of how the screen happens
 * to be sorted. A listener who flipped the list to newest-first still means "the next ten I am
 * going to hear".
 */
fun chaptersToDownloadNext(
    chapters: List<ChapterSummary>,
    entries: List<DownloadEntry>,
    count: Int = DownloadIndex.DefaultBatch,
): List<ChapterSummary> {
    val ordered = chapters.playbackOrder().filter { it.hasAudio }
    if (ordered.isEmpty()) return emptyList()
    val resumeFrom = ordered.firstOrNull { !it.isPlayed } ?: ordered.first()
    val wanted = DownloadIndex.nextToDownload(
        order = ordered.map { it.resolvedChapterId },
        startChapterId = resumeFrom.resolvedChapterId,
        entries = entries,
        count = count,
    ).toSet()
    return ordered.filter { it.resolvedChapterId in wanted }
}

/** An empty index, so the binding has something to collect when nobody is signed in. */
private val NoEntries = MutableStateFlow(emptyList<DownloadEntry>())

/**
 * Binds the chapter list to the signed-in account's download queue.
 *
 * Kept out of `App` so the screen's wiring is one expression there, and out of the screen itself so
 * a screen test can drive [ChapterDownloadsUi] directly without a coordinator, a temp directory or
 * a queue.
 */
@Composable
fun rememberChapterDownloads(
    coordinator: DownloadCoordinator,
    fiction: FictionSummary,
    chapters: List<ChapterSummary>,
): ChapterDownloadsUi {
    val session by coordinator.current.collectAsState()
    val entries by (session?.index?.entries ?: NoEntries).collectAsState()

    val byChapter = remember(entries) { entries.associateBy { it.chapterId } }
    val manager = session?.manager
    val title = fiction.title

    // The disk index can restore "Queued" without storing a bearer-protected URL. As soon as the
    // account's chapter metadata is available, hand those live rows back to the queue so unfinished
    // work resumes even when the metadata cache was missing at process start.
    LaunchedEffect(manager, chapters) {
        manager?.resumePending(
            chaptersById = chapters.associateBy { it.resolvedChapterId },
            fictionTitles = mapOf(fiction.id to title),
        )
    }

    return ChapterDownloadsUi(
        available = manager != null,
        stateFor = { chapter -> chapterDownloadUi(byChapter[chapter.resolvedChapterId], manager != null) },
        onDownload = { chapter -> manager?.download(chapter, title) },
        onCancel = { chapter -> manager?.cancel(chapter.resolvedChapterId) },
        onDelete = { chapter -> manager?.remove(chapter.resolvedChapterId) },
        onRetry = { chapter -> manager?.retry(chapter, title) },
        onDownloadNext = {
            manager?.enqueue(chaptersToDownloadNext(chapters, entries), title)
        },
    )
}
