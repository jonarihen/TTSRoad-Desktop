package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.chapterFileName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The download queue: what to fetch, how many at once, and what the index should say about it.
 *
 * Bounded concurrency is not a performance tuning knob here. A "download next 10" that opened ten
 * connections would saturate the uplink of exactly the kind of small self-hosted server this app
 * talks to, and starve the *streaming* request the user is listening to right now. Two at a time
 * keeps a queue moving without competing with playback.
 *
 * Every state change goes through [index], so the UI observes one flow and a restart reads the same
 * facts from disk. Nothing here writes files: that is [ChapterDownloader]'s job, and keeping the
 * split means the queue logic is testable without a network.
 */
class DownloadManager(
    private val downloader: ChapterDownloader,
    private val storage: DownloadStorage,
    private val index: DownloadIndexStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Overridable so tests do not wait real seconds for the backoff ladder. */
    private val retryDelaysMs: List<Long> = listOf(2_000, 10_000, 30_000),
    private val progressBytesThreshold: Long = DefaultProgressBytesThreshold,
    private val progressIntervalNanos: Long = DefaultProgressIntervalNanos,
    private val progressClockNanos: () -> Long = System::nanoTime,
    maxConcurrent: Int = DefaultConcurrency,
) : AutoCloseable {

    init {
        require(progressBytesThreshold > 0) { "progressBytesThreshold must be positive" }
        require(progressIntervalNanos > 0) { "progressIntervalNanos must be positive" }
    }

    val entries = index.entries

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val permits = Semaphore(maxConcurrent)

    /** In-flight jobs by chapter id, so Cancel has something to cancel. */
    private val jobs = ConcurrentHashMap<Int, Job>()

    /**
     * Queues [chapters], skipping anything already downloaded or in flight.
     *
     * Takes whole [ChapterSummary] values rather than ids because the audio URL and the expected
     * size come from live metadata — the index deliberately stores neither, so the caller has to
     * supply them at the moment of asking.
     */
    fun enqueue(chapters: List<ChapterSummary>, fictionTitle: String) {
        for (chapter in chapters) queueOne(chapter, fictionTitle, replaceFailed = false)
    }

    /**
     * Files one chapter as [DownloadState.Queued] and gives it a worker.
     *
     * [replaceFailed] is what separates Retry from Download: an ordinary enqueue leaves an
     * already-queued or in-flight chapter alone, while a retry is explicitly asking to start over
     * from a failed row. Neither ever restarts something already [DownloadState.Downloaded].
     */
    private fun queueOne(chapter: ChapterSummary, fictionTitle: String, replaceFailed: Boolean) {
        val id = chapter.resolvedChapterId
        if (id <= 0 || chapter.audio?.url.isNullOrBlank()) return

        val existing = DownloadIndex.find(index.entries.value, id)
        if (existing?.state == DownloadState.Downloaded) return
        // A live worker owns this chapter; a second one would fight it for the same .part file.
        if (jobs.containsKey(id)) return
        if (!replaceFailed && existing?.state?.isActive == true) return

        index.put(
            DownloadEntry(
                chapterId = id,
                fictionId = chapter.resolvedFictionId,
                fictionTitle = fictionTitle,
                chapterTitle = chapter.resolvedTitle,
                state = DownloadState.Queued,
                // Kept from any earlier attempt so a resume shows the right starting point.
                bytesDownloaded = existing?.bytesDownloaded ?: 0L,
                totalBytes = chapter.audioFilesize,
                fileName = chapterFileName(id),
                failureMessage = null,
                updatedAtMs = clock(),
            ),
        )
        start(chapter, id)
    }

    /** Queues one chapter. */
    fun download(chapter: ChapterSummary, fictionTitle: String) = enqueue(listOf(chapter), fictionTitle)

    /**
     * Cancels an in-flight or queued download.
     *
     * Cancellation waits for the worker to release its file/network handles, then removes the
     * partial. A crash or network interruption remains resumable; an explicit user Cancel is the
     * instruction to release both the handle and the disk space.
     */
    fun cancel(chapterId: Int) {
        val job = jobs.remove(chapterId)
        job?.cancel()
        val entry = DownloadIndex.find(index.entries.value, chapterId) ?: return
        if (!entry.state.isActive) return
        index.put(entry.copy(state = DownloadState.Removing, updatedAtMs = clock()))
        removeAfterWorker(entry, job)
    }

    /**
     * Deletes a download and everything on disk for it.
     *
     * Goes through [DownloadState.Removing] rather than straight to gone so the row does not
     * flicker back to "Download" while a large file is being unlinked.
     */
    fun remove(chapterId: Int) {
        val job = jobs.remove(chapterId)
        job?.cancel()
        val entry = DownloadIndex.find(index.entries.value, chapterId) ?: return
        index.put(entry.copy(state = DownloadState.Removing, updatedAtMs = clock()))
        removeAfterWorker(entry, job)
    }

    private fun removeAfterWorker(entry: DownloadEntry, job: Job?) {
        val cleanup = scope.launch {
            job?.join()
            val deleted = runCatching { storage.delete(entry.fileName) }
                .onFailure { AppLog.warn("could not delete a download", it) }
                .getOrDefault(false)
            if (deleted) {
                index.remove(entry.chapterId)
            } else {
                index.put(
                    entry.copy(
                        state = if (entry.isOffline) DownloadState.Downloaded else DownloadState.Failed,
                        failureMessage = "Could not remove the local file",
                        updatedAtMs = clock(),
                    ),
                )
            }
        }
        jobs[entry.chapterId] = cleanup
        cleanup.invokeOnCompletion { jobs.remove(entry.chapterId, cleanup) }
    }

    /**
     * Retries a failed chapter.
     *
     * Distinct from [enqueue] in that it is allowed to replace a row that is already sitting in a
     * non-Downloaded state — which is the whole point, since the row it is retrying is a failed one.
     */
    fun retry(chapter: ChapterSummary, fictionTitle: String) =
        queueOne(chapter, fictionTitle, replaceFailed = true)

    /** Deletes every download for this account and returns the bytes reclaimed. */
    suspend fun deleteAll(): Long {
        val active = jobs.values.toList()
        active.forEach { it.cancel() }
        active.joinAll()
        jobs.clear()
        val freed = runCatching { storage.deleteAll() }.getOrDefault(0L)
        index.clear()
        return freed
    }

    /**
     * Re-queues whatever a previous run left unfinished.
     *
     * The index has already turned interrupted rows back into [DownloadState.Queued] at load time;
     * this is what actually gives them a worker again. Called once the library metadata is
     * available, because a chapter's audio URL is not in the index by design.
     */
    fun resumePending(chaptersById: Map<Int, ChapterSummary>, fictionTitles: Map<Int, String>) {
        for (entry in DownloadIndex.pending(index.entries.value)) {
            val chapter = chaptersById[entry.chapterId] ?: continue
            if (jobs.containsKey(entry.chapterId)) continue
            start(chapter, entry.chapterId, fictionTitles[entry.fictionId] ?: entry.fictionTitle)
        }
    }

    private fun start(chapter: ChapterSummary, id: Int, fictionTitle: String? = null) {
        val job = scope.launch {
            permits.withPermit { run(chapter, id, fictionTitle) }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    private suspend fun run(chapter: ChapterSummary, id: Int, fictionTitle: String?) {
        val url = chapter.audio?.url ?: return
        var attempt = 0

        while (true) {
            val current = DownloadIndex.find(index.entries.value, id) ?: return
            val entry = current.copy(
                state = DownloadState.Downloading,
                fictionTitle = fictionTitle ?: current.fictionTitle,
                updatedAtMs = clock(),
            )
            index.put(entry)
            var lastPublishedBytes = entry.bytesDownloaded
            var lastPublishedAtNanos = progressClockNanos()

            val result = downloader.download(
                audioUrl = url,
                fileName = entry.fileName,
                expectedBytes = entry.totalBytes,
            ) { soFar, total ->
                // FileDownloadIndexStore atomically rewrites the complete index. Publishing every
                // 64 KiB read would therefore turn a 100 MiB chapter into roughly 1,600 metadata
                // rewrites. A byte threshold keeps fast transfers bounded; a time threshold keeps
                // slow-transfer UI responsive. Success below always persists the exact final size.
                val nowNanos = progressClockNanos()
                val movedBackwards = soFar < lastPublishedBytes
                val byteThresholdReached = soFar - lastPublishedBytes >= progressBytesThreshold
                val timeThresholdReached = nowNanos - lastPublishedAtNanos >= progressIntervalNanos
                if (!movedBackwards && !byteThresholdReached && !timeThresholdReached) return@download
                val latest = DownloadIndex.find(index.entries.value, id) ?: return@download
                if (latest.state == DownloadState.Downloading) {
                    index.put(latest.copy(bytesDownloaded = soFar, totalBytes = if (total > 0) total else latest.totalBytes))
                    lastPublishedBytes = soFar
                    lastPublishedAtNanos = nowNanos
                }
            }

            when (result) {
                is DownloadResult.Success -> {
                    index.put(
                        entry.copy(
                            state = DownloadState.Downloaded,
                            bytesDownloaded = result.bytes,
                            totalBytes = result.bytes,
                            failureMessage = null,
                            updatedAtMs = clock(),
                        ),
                    )
                    return
                }

                is DownloadResult.Cancelled -> return

                is DownloadResult.Failed -> {
                    val retryable = DownloadFailure.isWorthAutoRetry(result.failure) && attempt < retryDelaysMs.size
                    if (!retryable) {
                        index.put(
                            entry.copy(
                                state = DownloadState.Failed,
                                failureMessage = result.failure.message,
                                updatedAtMs = clock(),
                            ),
                        )
                        return
                    }
                    // Backoff rather than an immediate retry: the common transient failure is a
                    // server or a link under load, and hammering it is how a queue turns one
                    // failure into ten.
                    delay(retryDelaysMs[attempt])
                    attempt++
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * How many chapters download at once.
         *
         * Two, deliberately low. These servers are somebody's home machine, and the request that
         * matters most is the one feeding the audio the user is listening to right now.
         */
        const val DefaultConcurrency: Int = 2

        /** At most one progress write per MiB on a fast transfer. */
        const val DefaultProgressBytesThreshold: Long = 1L * 1024 * 1024

        /** At most two progress writes per second on a slow transfer. */
        const val DefaultProgressIntervalNanos: Long = 500_000_000L
    }
}
