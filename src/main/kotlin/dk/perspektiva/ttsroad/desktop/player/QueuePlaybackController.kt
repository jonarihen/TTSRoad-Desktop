package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.ListeningStats
import dk.perspektiva.ttsroad.desktop.data.ListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackSkipPreferenceStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackSkipPreferenceStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackSkips
import dk.perspektiva.ttsroad.desktop.data.PlaybackSkipsFetchResult
import dk.perspektiva.ttsroad.desktop.data.playbackSkipTarget
import dk.perspektiva.ttsroad.desktop.data.PlaybackSnapshot
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.describeNetworkFailure
import dk.perspektiva.ttsroad.desktop.data.playbackOrder
import dk.perspektiva.ttsroad.desktop.data.skipIntervalMs
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a chapter into a byte source. The seam the offline cache slots into.
 *
 * Takes the chapter id as well as the URL because "is this chapter already on disk?" is a question
 * about the *chapter*, and a downloaded file is named from its id. Answering it by pattern-matching
 * the URL would tie the cache to whatever path shape the server happens to use today.
 */
fun interface MediaSourceFactory {
    fun create(chapterId: Int, url: String): MediaSource
}

/**
 * Queue, progress and error handling on top of a [PlaybackEngine].
 *
 * This is everything the old `Mp3PlaybackController` did *except* touching audio, which is the
 * point: the engine owns decoding, the clock and the output device, and this owns the parts worth
 * unit-testing. Every test below the UI drives a fake engine, so the queue, the retry ladder, the
 * played threshold and session expiry are all exercised with no sound card and no network.
 *
 * What changed relative to the prototype controller, all of it required by issue #4:
 *
 * - progress is saved on pause, seek, chapter change, stop and shutdown, not only on a 10 s tick
 *   and a byte-perfect end of stream;
 * - a chapter counts as played at 96%, or within 20 s of the end, so a stream that stops a beat
 *   early still marks;
 * - transient failures retry after 2 s, 5 s and 15 s before the UI offers a Retry button, and a
 *   recovery clears the error;
 * - a 401 mid-chapter goes to the central session-expiry path instead of being retried.
 */
class QueuePlaybackController(
    private val repository: TtsRoadRepository,
    private val sources: MediaSourceFactory,
    private val engine: PlaybackEngine,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Listening settings. Observed here rather than read by the player screen, because the issue's
     * requirement is that a media-key start and an auto-advanced chapter use the same values as a
     * chapter the user pressed play on — and only the controller sees all three.
     */
    private val preferencesStore: PlaybackPreferencesStore = InMemoryPlaybackPreferencesStore(),
    private val historyStore: PlaybackHistoryStore = InMemoryPlaybackHistoryStore(),
    /**
     * Day totals for the Listening pane. Fed from the same tick as everything else, because the
     * controller is the only thing that knows the difference between playing and merely loaded.
     */
    private val statsStore: ListeningStatsStore = InMemoryListeningStatsStore(),
    private val sleepTimer: SleepTimer = SleepTimer(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Which account a history snapshot belongs to. Read at record time rather than at construction,
     * because a sign-out and a sign-in as somebody else happen without rebuilding this controller.
     */
    private val ownerKey: () -> String = { "" },
    /** Overridable so tests do not wait real seconds for the retry ladder. */
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000, 15_000),
    private val tickIntervalMs: Long = 250,
    private val progressIntervalMs: Long = 10_000,
    private val historyRecordIntervalMs: Long = 5 * 60_000L,
    private val listeningFlushIntervalMs: Long = 60_000L,
    private val playbackSkipPreference: PlaybackSkipPreferenceStore = InMemoryPlaybackSkipPreferenceStore(),
    private val queueRefreshIntervalMs: Long = 15_000L,
) : PlaybackController {

    private val _state = MutableStateFlow(emptyState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val playbackLock = Any()
    private val transitionMutex = Mutex()
    private var playJob: Job? = null
    private var refreshJob: Job? = null
    private var requestGeneration = 0L
    private var seekGeneration = 0L
    private var playbackRequested = false
    private var endedChapterId: Int? = null
    private var queueRequest: PlayRequest? = null
    private var queue: List<ChapterSummary> = emptyList()
    private var queueFiction: FictionSummary? = null

    private class PlayRequest(
        val generation: Long,
        val owner: String,
        val authorization: String?,
        val server: String,
    )

    private fun newPlayRequest(): PlayRequest = synchronized(playbackLock) {
        requestGeneration++
        refreshJob?.cancel()
        refreshJob = null
        playbackRequested = false
        PlayRequest(requestGeneration, ownerKey(), repository.authHeaderValue(), repository.resolveUrl("/"))
    }

    private fun isCurrent(request: PlayRequest): Boolean =
        request.generation == requestGeneration && request.owner == ownerKey() &&
            request.authorization == repository.authHeaderValue() &&
            request.server == repository.resolveUrl("/") && repository.sessionEnd.value == null

    /**
     * Which account this queue was loaded for, captured at load time rather than read at record
     * time.
     *
     * `recordHistory` runs *after* a suspending progress save, and `stop()` is fire-and-forget. If
     * the session changes while that request is in flight — account A signs out, B signs in — then
     * resolving the owner live would file A's chapter titles under B's key, which is precisely the
     * cross-account disclosure the owner key exists to prevent. The queue and its owner belong
     * together, so they are captured together.
     */
    @Volatile private var queueOwnerKey: String = ""

    @Volatile private var queueIndex = 0

    /** Last position actually reported by the engine — what a save or a retry resumes from. */
    @Volatile private var lastKnownPositionMs = 0L
    @Volatile private var loadedSkipsChapterId = 0
    @Volatile private var loadedSkips: PlaybackSkips? = null
    @Volatile private var skipLoadGeneration = 0L
    private val announcedSkipSegments = mutableSetOf<Pair<Int, Long>>()

    @Volatile private var speed = preferencesStore.preferences.value.speed

    /**
     * The serial whose rate is in force, or 0 for none.
     *
     * Held separately from [queueFiction] because the rate has to be resolved from the *chapter*
     * when playback started from a library shelf, which carries no `FictionSummary` at all.
     */
    @Volatile private var speedFictionId = 0

    /**
     * Serialises every path that resolves a rate.
     *
     * Two of them run concurrently: the preference collector and a queue being loaded. Without a
     * lock they interleave as *compute, then write*, so a collector that resolved the rate before a
     * queue arrived could land its now-stale answer after the queue had applied the serial's own —
     * leaving the engine on one rate and the UI showing another until the next preference change.
     */
    private val rateLock = Any()

    /** Playing time since the last cross-device jump-back breadcrumb. Pauses add nothing. */
    private var playedSinceHistoryRecordMs = 0L

    /**
     * Playing time not yet added to the day total.
     *
     * Batched rather than written per tick: four writes a second to a JSON file for a number nobody
     * reads more than once a week would be absurd. Flushed on the interval below and at every
     * transition, so the most a crash can lose is under a minute of a total measured in hours.
     */
    private var unflushedListeningMs = 0L

    /**
     * Engine events, queued for the attempt loop to consume.
     *
     * The listener is attached once, here, rather than per attempt: the engine can report a
     * failure from inside `prepare` — before any per-attempt subscription would have started — and
     * losing that event would leave the controller ticking on a chapter that had already given up.
     */
    private val engineEvents = ConcurrentLinkedQueue<EngineEvent>()

    init {
        engine.setListener { event -> engineEvents.add(event) }

        // Applied immediately and on every later change. The first application matters as much as
        // the rest: the engine has to know the saved speed and gain *before* the first prepare, or
        // the restored preferences would take effect one chapter late.
        scope.launch {
            preferencesStore.preferences.collect { preferences ->
                reapplyRate()
                engine.setSkipSilence(preferences.skipSilence)
                applyGain()
                _state.update { it.copy(skipIntervalMs = preferences.skipIntervalMs) }
            }
        }

        // The fade is an engine gain, and the gain is boost × fade, so a fade tick has to go
        // through the same multiplication as a preference change rather than writing the element
        // directly — otherwise cancelling a fade would reset a boosted listener to unity.
        scope.launch {
            sleepTimer.state.collect { timer ->
                applyGain()
                _state.update { it.copy(sleepTimer = timer) }
            }
        }
    }

    /** Boost and fade multiplied into the single number the engine takes. */
    private fun applyGain() {
        val boost = preferencesStore.preferences.value.volumeBoost.gain
        val fade = sleepTimer.state.value.fadeGain.toDouble()
        engine.setGain(boost * fade)
    }

    private fun emptyState() = PlayerUiState(
        canChangeSpeed = engine.capabilities.variableSpeed,
        canSkipSilence = engine.capabilities.skipSilence,
        speed = engine.capabilities.coerceSpeed(preferencesStore.preferences.value.speed),
        skipIntervalMs = preferencesStore.preferences.value.skipIntervalMs,
        sleepTimer = sleepTimer.state.value,
    )

    /**
     * Points the rate at the serial now loaded, and applies its rate if that changes anything.
     *
     * Called as the queue's fiction is established rather than on every chapter, because
     * auto-advance stays inside one serial and re-pushing an unchanged rate to the engine mid-book
     * is a needless pipeline poke.
     */
    private fun useFictionSpeed(fictionId: Int) {
        val next = fictionId.takeIf { it > 0 } ?: 0
        if (next == speedFictionId) return
        reapplyRate(next)
    }

    /**
     * Resolves the rate from the current preferences and the loaded serial, and pushes it.
     *
     * The single writer of [speed], so the value the engine holds and the value the UI shows can
     * never disagree about which serial they were derived from. Reads the store rather than taking
     * a snapshot argument for the same reason: inside the lock, "current" has one meaning.
     */
    private fun reapplyRate(fictionId: Int? = null) = synchronized(rateLock) {
        fictionId?.let { speedFictionId = it }
        val preferences = preferencesStore.preferences.value
        val wanted = preferences.speedFor(speedFictionId)
        speed = wanted
        val applied = engine.setRate(wanted)
        _state.update {
            it.copy(
                speed = applied,
                speedIsPerFiction = preferences.fictionSpeeds.containsKey(speedFictionId),
            )
        }
    }

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        val request = newPlayRequest()
        if (!chapter.hasAudio) {
            transitionMutex.withLock {
                if (!synchronized(playbackLock) { isCurrent(request) }) return
                stopInternal(clearQueue = true)
                synchronized(playbackLock) {
                    if (!isCurrent(request)) return
                    queueFiction = fiction
                    _state.value = metadataOf(chapter, fiction, 0L, emptyList(), 0)
                        .copy(error = "This chapter has no audio yet")
                }
            }
            return
        }
        startQueue(listOf(chapter), chapter.resolvedChapterId, fiction, null, request, refreshImmediately = true)
    }

    override suspend fun playQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary?,
        startPositionMs: Long?,
    ) {
        startQueue(chapters, startChapterId, fiction, startPositionMs, newPlayRequest())
    }

    private suspend fun startQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary?,
        startPositionMs: Long?,
        request: PlayRequest,
        refreshImmediately: Boolean = false,
    ) {
        transitionMutex.withLock {
            if (!synchronized(playbackLock) { isCurrent(request) }) return@withLock
            val playable = chapters.playbackOrder().filter { it.hasAudio }.distinctBy { it.resolvedChapterId }
            if (playable.isEmpty()) {
                _state.update { it.copy(error = "No playable chapters yet") }
                return@withLock
            }
            leaveCurrentChapter()
            val fictionId = fiction?.id?.takeIf { it > 0 }
                ?: chapters.firstOrNull()?.resolvedFictionId?.takeIf { it > 0 }
                ?: 0
            synchronized(playbackLock) {
                if (!isCurrent(request)) return@withLock
                queue = playable
                queueFiction = fiction
                queueOwnerKey = ownerKey()
                queueRequest = request
                playbackRequested = true
                endedChapterId = null
            }
            useFictionSpeed(fictionIdOf(playable.first(), fiction))
            val startIndex = playable.indexOfFirst { it.resolvedChapterId == startChapterId }.coerceAtLeast(0)
            val requestedFound = playable[startIndex].resolvedChapterId == startChapterId
            val startMs = startPositionMs?.takeIf { requestedFound }?.coerceAtLeast(0L)
                ?: resumeMsOf(playable[startIndex])
            begin(
                startIndex,
                startMs,
                leaveCurrent = false,
                expectedChapterId = playable[startIndex].resolvedChapterId,
            )
            startQueueRefresh(request, fictionId, immediate = refreshImmediately)
        }
    }

    private fun startQueueRefresh(request: PlayRequest, fictionId: Int, immediate: Boolean = false) {
        if (fictionId <= 0) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (immediate) {
                refreshQueue(request, fictionId)
            }
            while (isActive) {
                delay(queueRefreshIntervalMs)
                if (synchronized(playbackLock) { playbackRequested }) refreshQueue(request, fictionId)
            }
        }
    }

    private suspend fun refreshQueue(request: PlayRequest, fictionId: Int, endedEvent: Boolean = false) {
        synchronized(playbackLock) {
            if (!isCurrent(request) || request.generation != requestGeneration) return
        }
        val seekGen = synchronized(playbackLock) { seekGeneration }
        val loaded = try {
            repository.chapters(fictionId).chapters
        } catch (_: CancellationException) {
            throw CancellationException("cancelled")
        } catch (_: Exception) {
            return
        }
        synchronized(playbackLock) {
            if (!isCurrent(request) || request.generation != requestGeneration) return
            if (seekGeneration != seekGen) return
            val currentChapterId = queue.getOrNull(queueIndex)?.resolvedChapterId ?: return
            val oldQueue = queue
            val merged = mergeQueue(oldQueue, loaded).filter { it.hasAudio }.distinctBy { it.resolvedChapterId }
            if (merged.isEmpty()) return
            val newIndex = merged.indexOfFirst { it.resolvedChapterId == currentChapterId }
            if (newIndex < 0) return
            queue = merged
            queueIndex = newIndex
            _state.update {
                it.copy(
                    queue = merged.map { c -> QueueItem(c.resolvedChapterId, c.resolvedTitle, c.resolvedDisplayNumber) },
                    currentIndex = newIndex,
                    hasNext = newIndex < merged.lastIndex,
                    hasPrevious = newIndex > 0,
                )
            }
        }
    }

    private fun mergeQueue(
        existing: List<ChapterSummary>,
        fresh: List<ChapterSummary>,
    ): List<ChapterSummary> {
        val freshPlayable = fresh.playbackOrder().filter { it.hasAudio }.distinctBy { it.resolvedChapterId }
        if (freshPlayable.isEmpty()) return existing
        val freshById = freshPlayable.associateBy { it.resolvedChapterId }
        val presentIds = existing.map { it.resolvedChapterId }.toMutableSet()
        val result = existing.map { freshById[it.resolvedChapterId] ?: it }.toMutableList()

        for ((index, chapter) in freshPlayable.withIndex()) {
            if (!presentIds.add(chapter.resolvedChapterId)) continue
            val following = freshPlayable.drop(index + 1).map { it.resolvedChapterId }.toSet()
            val insertion = result.indexOfFirst { it.resolvedChapterId in following }
                .takeIf { it >= 0 } ?: result.size
            result.add(insertion, chapter)
        }
        return result
    }

    override fun togglePlayPause() {
        val current = _state.value
        if (!current.hasMedia) return
        if (current.isPlaying) {
            synchronized(playbackLock) { playbackRequested = false }
            engine.pause()
            _state.update { it.copy(isPlaying = false) }
            // A manual pause freezes a countdown; a listener who stops to answer the door should
            // not come back to a timer that ran out while nothing was playing.
            sleepTimer.onPlaybackPaused()
            // Pausing is a natural place to lose a session or a laptop lid, so it is one of the
            // moments issue #4 requires a save at.
            saveCurrentProgress()
            recordHistory()
        } else {
            synchronized(playbackLock) { playbackRequested = true }
            engine.play()
            _state.update { it.copy(isPlaying = true) }
            sleepTimer.onPlaybackResumed()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!_state.value.hasMedia) return
        synchronized(playbackLock) { seekGeneration++ }
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        engine.seekTo(clamped)
        lastKnownPositionMs = clamped
        _state.update { it.copy(positionMs = clamped) }
        saveCurrentProgress()
    }

    override fun skipBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun skipForward() = skipBy(preferencesStore.preferences.value.skipIntervalMs)

    override fun skipBackward() = skipBy(-preferencesStore.preferences.value.skipIntervalMs)

    override fun skipToNextChapter() {
        val targetId = synchronized(playbackLock) { queue.getOrNull(queueIndex + 1)?.resolvedChapterId } ?: return
        scope.launch {
            synchronized(playbackLock) { playbackRequested = true }
            begin(0, 0L, expectedChapterId = targetId)
        }
    }

    override fun skipToPreviousChapter() {
        // Audiobook "previous": restart the current chapter unless we're near its start.
        if (_state.value.positionMs > PREVIOUS_RESTARTS_AFTER_MS || queueIndex == 0) {
            seekTo(0L)
        } else {
            val targetId = synchronized(playbackLock) {
                queue.getOrNull(queueIndex - 1)?.resolvedChapterId
            } ?: return
            scope.launch {
                synchronized(playbackLock) { playbackRequested = true }
                begin(0, 0L, expectedChapterId = targetId)
            }
        }
    }

    override fun skipToQueueIndex(index: Int) {
        val targetId = synchronized(playbackLock) {
            queue.getOrNull(index)?.takeIf { index != queueIndex }?.resolvedChapterId
        } ?: return
        scope.launch {
            synchronized(playbackLock) { playbackRequested = true }
            begin(0, 0L, expectedChapterId = targetId)
        }
    }

    override fun setSpeed(speed: Float) {
        // Persisted rather than held: the preference is the source of truth, and the collector in
        // `init` is what pushes it to the engine and to the UI state. Writing the engine here too
        // would mean two paths to the same setting, one of which does not survive a restart.
        //
        // An engine that cannot resample still stores the wish — a listener who set 1.5× on a
        // machine without GStreamer and later installs it should find 1.5× waiting.
        //
        // With a serial loaded this is *that serial's* rate: a listener slowing down for a dense
        // translation is answering a question about the narrator in front of them, not about every
        // book they will ever open. The default lives in Settings, where it reads as a default.
        val fictionId = speedFictionId
        if (fictionId > 0) {
            preferencesStore.update { it.withFictionSpeed(fictionId, speed) }
        } else {
            preferencesStore.update { it.copy(speed = speed) }
        }
    }

    override fun clearFictionSpeed() {
        val fictionId = speedFictionId.takeIf { it > 0 } ?: return
        preferencesStore.update { it.withFictionSpeed(fictionId, null) }
    }

    override fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimer.arm(mode)
        // Arming while already paused has to start out frozen. The tick loop keeps running through
        // a pause — it is what notices the engine's events — so a timer armed at that moment would
        // count down against silence and expire without a second of audio having played. The
        // freeze-on-pause path cannot cover this: it ran when the user pressed pause, back when
        // there was no deadline to freeze.
        if (!_state.value.isPlaying) sleepTimer.onPlaybackPaused()
    }

    override fun extendSleepTimer() {
        sleepTimer.extendBy(SleepTimer.ExtensionMinutes)
    }

    override fun retry() {
        if (!_state.value.canRetry) return
        val targetId = synchronized(playbackLock) { queue.getOrNull(queueIndex)?.resolvedChapterId } ?: return
        scope.launch {
            synchronized(playbackLock) { playbackRequested = true }
            begin(0, lastKnownPositionMs, expectedChapterId = targetId)
        }
    }

    override fun stop() {
        scope.launch { stopInternal(clearQueue = true) }
    }

    override fun release() {
        playJob?.cancel()
        refreshJob?.cancel()
        recordHistory()
        runBlocking {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { saveProgressNow() }
        }
        runCatching { engine.close() }
        scope.cancel()
    }

    /**
     * Where to start this chapter.
     *
     * Prefers the server's reconciled state when there is one. That map is only populated by a
     * `/playback/sync` round trip, which is strictly later than the chapter list this summary came
     * from — so when a position saved here lost to a newer one reached in the browser, this picks
     * up the browser's position instead of silently restarting from the stale local one. That is
     * the user-visible half of #36.
     */
    private fun resumeMsOf(chapter: ChapterSummary): Long {
        val reconciled = repository.serverPlaybackState.value[chapter.resolvedChapterId]
        val seconds = reconciled?.positionSeconds ?: chapter.resolvedPositionSeconds
        return (seconds * 1000).toLong().coerceAtLeast(0L)
    }

    private suspend fun stopInternal(clearQueue: Boolean) {
        playJob?.cancelAndJoin()
        playJob = null
        refreshJob?.cancel()
        refreshJob = null
        synchronized(playbackLock) {
            playbackRequested = false
            endedChapterId = null
        }
        saveProgressNow()
        recordHistory()
        runCatching { engine.stop() }
        if (clearQueue) {
            queue = emptyList()
            queueFiction = null
            queueOwnerKey = ""
            queueIndex = 0
            lastKnownPositionMs = 0
            sleepTimer.cancel()
            _state.value = emptyState()
        }
    }

    /**
     * Starts (or restarts) playback at [startIndex].
     *
     * One job owns a chapter from prepare to end-of-stream, including its retries, so cancelling
     * it is all that is needed to abandon everything in flight.
     */
    /**
     * Stops the current chapter and files what the listener had reached.
     *
     * Must be called while `queue` and `queueIndex` still describe the chapter being left. A caller
     * that is about to *replace* the queue has to do this first and pass `leaveCurrent = false` to
     * [begin] — otherwise the save and the snapshot are attributed to whatever chapter now happens
     * to sit at the old index, which is a different chapter or none.
     */
    private suspend fun leaveCurrentChapter() {
        playJob?.cancelAndJoin()
        playJob = null
        // Leaving the previous chapter is one of the required save points.
        saveProgressNow()
        recordHistory()
    }

    private suspend fun begin(
        startIndex: Int,
        startMs: Long,
        leaveCurrent: Boolean = true,
        expectedChapterId: Int? = null,
    ) {
        if (leaveCurrent) {
            leaveCurrentChapter()
        } else {
            playJob?.cancelAndJoin()
            playJob = null
        }
        val targetChapterId = synchronized(playbackLock) {
            expectedChapterId ?: queue.getOrNull(startIndex)?.resolvedChapterId
        } ?: return
        val resolvedIndex = synchronized(playbackLock) {
            queue.indexOfFirst { it.resolvedChapterId == targetChapterId }.takeIf { it >= 0 }
        } ?: return
        queueIndex = resolvedIndex
        lastKnownPositionMs = startMs
        publishMetadata(targetChapterId, startMs)

        playJob = scope.launch {
            var chapterId = targetChapterId
            var positionMs = startMs
            while (isActive) {
                val chapter = publishMetadata(chapterId, positionMs) ?: return@launch
                loadPlaybackSkips(chapter.resolvedChapterId)

                val outcome = playChapter(chapter, positionMs)
                if (outcome == ChapterOutcome.Stopped) return@launch

                // Reaching here means the chapter ended on its own.
                val duration = _state.value.durationMs
                saveProgress(chapter, duration.takeIf { it > 0 } ?: lastKnownPositionMs, isPlayed = true)
                // A chapter that ran to its end is the only thing this client can honestly call
                // "finished": marking one played by hand says the listener is done with it, not
                // that they heard it.
                flushListening(chaptersFinished = 1)

                // Checked before the advance, which is the whole requirement: "end of current
                // chapter" has to prevent auto-advance, not stop the next one a moment after it
                // has already started playing.
                if (sleepTimer.shouldStopAtChapterEnd()) {
                    synchronized(playbackLock) { playbackRequested = false }
                    _state.update { it.copy(isPlaying = false, positionMs = duration) }
                    return@launch
                }
                var nextChapterId = synchronized(playbackLock) {
                    val currentIndex = queue.indexOfFirst { it.resolvedChapterId == chapter.resolvedChapterId }
                    queue.getOrNull(currentIndex + 1)?.resolvedChapterId
                }
                if (nextChapterId == null) {
                    val request = synchronized(playbackLock) { queueRequest }
                    val fictionId = (queueFiction?.id ?: queue.firstOrNull()?.resolvedFictionId)
                        ?.takeIf { it > 0 }
                    if (request != null && fictionId != null && synchronized(playbackLock) { playbackRequested }) {
                        synchronized(playbackLock) { endedChapterId = chapter.resolvedChapterId }
                        refreshQueue(request, fictionId, endedEvent = true)
                        nextChapterId = synchronized(playbackLock) {
                            val currentIndex = queue.indexOfFirst { it.resolvedChapterId == chapter.resolvedChapterId }
                            queue.getOrNull(currentIndex + 1)?.resolvedChapterId
                        }
                    }
                }
                if (nextChapterId == null || !synchronized(playbackLock) { playbackRequested }) {
                    synchronized(playbackLock) { playbackRequested = false }
                    _state.update { it.copy(isPlaying = false, positionMs = duration) }
                    return@launch
                }
                chapterId = nextChapterId
                positionMs = 0L
            }
        }
    }

    private fun loadPlaybackSkips(chapterId: Int) {
        loadedSkipsChapterId = chapterId
        loadedSkips = null
        announcedSkipSegments.removeAll { it.first == chapterId }
        val generation = ++skipLoadGeneration
        _state.update { it.copy(playbackNotice = null) }
        if (!repository.currentCapabilities.value.playbackSkips) return
        scope.launch {
            val result = runCatching { repository.playbackSkips(chapterId) }.getOrNull()
            if (loadedSkipsChapterId == chapterId && skipLoadGeneration == generation) {
                loadedSkips = (result as? PlaybackSkipsFetchResult.Available)?.skips
                if (loadedSkips?.needsTimings == true) {
                    _state.update {
                        it.copy(playbackNotice = "Advert skipping needs chapter timings; playing normally.")
                    }
                }
            }
        }
    }

    private enum class ChapterOutcome { Completed, Stopped }

    /**
     * Plays one chapter, retrying transient failures on the 2 s / 5 s / 15 s ladder.
     *
     * Returns [ChapterOutcome.Completed] only when the engine reported end-of-stream, so a failure
     * can never be mistaken for a finished chapter and auto-advance past it.
     */
    private suspend fun playChapter(chapter: ChapterSummary, startMs: Long): ChapterOutcome {
        var attempt = 0
        var resumeMs = startMs
        while (true) {
            when (val result = attemptChapter(chapter, resumeMs)) {
                is AttemptResult.Completed -> return ChapterOutcome.Completed

                // The job was cancelled: a new chapter, a stop, or shutdown superseded this.
                is AttemptResult.Stopped -> return ChapterOutcome.Stopped

                is AttemptResult.SleptOff -> {
                    synchronized(playbackLock) { playbackRequested = false }
                    // A pause, not a stop: the queue and the position stay exactly where they are
                    // so the morning's "resume" is one keypress, not a search for the chapter.
                    engine.pause()
                    _state.update { it.copy(isPlaying = false) }
                    saveProgressNow()
                    recordHistory()
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.SessionExpired -> {
                    synchronized(playbackLock) { playbackRequested = false }
                    _state.update { it.copy(isPlaying = false, error = result.failure.message, canRetry = false) }
                    // Same door as a 401 on an API call: drop the token and return to login rather
                    // than retrying a request that can only fail the same way.
                    repository.endSession(result.failure.sessionEnd)
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.Fatal -> {
                    synchronized(playbackLock) { playbackRequested = false }
                    _state.update { it.copy(isPlaying = false, error = result.message, canRetry = true) }
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.Transient -> {
                    resumeMs = lastKnownPositionMs
                    if (attempt >= retryDelaysMs.size) {
                        synchronized(playbackLock) { playbackRequested = false }
                        _state.update { it.copy(isPlaying = false, error = result.message, canRetry = true) }
                        return ChapterOutcome.Stopped
                    }
                    // Surfaced while waiting so the user sees why nothing is happening, then
                    // cleared automatically once an attempt succeeds.
                    _state.update { it.copy(isPlaying = false, error = result.message, canRetry = false) }
                    delay(retryDelaysMs[attempt])
                    attempt++
                }
            }
        }
    }

    private sealed interface AttemptResult {
        data object Completed : AttemptResult
        data object Stopped : AttemptResult

        /** The sleep timer ran out mid-chapter. Distinct from [Stopped] so it can pause, not tear down. */
        data object SleptOff : AttemptResult
        data class Transient(val message: String) : AttemptResult
        data class Fatal(val message: String) : AttemptResult
        data class SessionExpired(val failure: PlaybackFailure.SessionExpired) : AttemptResult
    }

    /** One attempt: prepare, play, then tick until the engine says it finished or failed. */
    private suspend fun attemptChapter(chapter: ChapterSummary, startMs: Long): AttemptResult {
        val url = chapter.audio?.url ?: return AttemptResult.Fatal("This chapter has no audio yet")

        // Anything the previous attempt left behind belongs to a chapter we are no longer playing.
        engineEvents.clear()

        try {
            engine.prepare(sources.create(chapter.resolvedChapterId, url), startMs)
        } catch (e: SessionExpiredException) {
            return AttemptResult.SessionExpired(PlaybackFailure.SessionExpired(e.sessionEnd))
        } catch (e: Exception) {
            return AttemptResult.Transient(describeNetworkFailure(e))
        }

        // prepare() can report a failure before it returns; the listener is registered in init
        // precisely so that one is already sitting here rather than having been dropped.
        drainEngineEvents()?.let { return it }

        engine.play()
        // Audio is starting here, not only in togglePlayPause, so this is where a frozen countdown
        // has to start running again. Without it, a timer armed while paused stays frozen while a
        // newly-started or retried chapter plays on indefinitely. A no-op when nothing is frozen.
        sleepTimer.onPlaybackResumed()
        // A successful attempt clears whatever the previous one complained about.
        _state.update {
            it.copy(
                hasMedia = true,
                isPlaying = true,
                error = null,
                canRetry = false,
                speed = engine.capabilities.coerceSpeed(speed),
            )
        }

        var lastSavedMs = startMs
        while (coroutineContext[Job]?.isActive != false) {
            delay(tickIntervalMs)
            drainEngineEvents()?.let { return it }

            // The timer is driven by this tick rather than by a scheduler of its own, which is
            // what makes the fade and the expiry deterministic in tests: no wall-clock race, and
            // the fade gain is recomputed on exactly the cadence the position is.
            if (sleepTimer.tick() == SleepTimerEvent.Expired) return AttemptResult.SleptOff

            val position = engine.positionMs()
            if (position > 0) lastKnownPositionMs = position
            val duration = engine.durationMs().takeIf { it > 0 } ?: _state.value.durationMs
            val skipTarget = playbackSkipTarget(
                loadedSkips?.takeIf { loadedSkipsChapterId == chapter.resolvedChapterId },
                lastKnownPositionMs,
                duration,
                _state.value.isPlaying && playbackSkipPreference.enabled.value,
            )
            if (skipTarget != null && skipTarget > lastKnownPositionMs) {
                val segment = loadedSkips?.segments?.firstOrNull { skipTarget == it.endMs || skipTarget == duration }
                engine.seekTo(skipTarget)
                lastKnownPositionMs = skipTarget
                lastSavedMs = skipTarget
                if (segment != null && announcedSkipSegments.add(chapter.resolvedChapterId to segment.startMs)) {
                    val label = segment.label.trim().ifEmpty { "advert or disclaimer" }.lowercase()
                    _state.update { it.copy(playbackNotice = "Skipped $label.") }
                }
            }
            _state.update { it.copy(positionMs = lastKnownPositionMs, durationMs = duration) }

            // The web client writes the same `kind=auto` breadcrumb every five minutes of actual
            // playback. Counting active ticks avoids treating a suspended laptop as five hours of
            // listening when its coroutine resumes after sleep.
            if (_state.value.isPlaying) {
                playedSinceHistoryRecordMs += tickIntervalMs
                if (playedSinceHistoryRecordMs >= historyRecordIntervalMs) {
                    playedSinceHistoryRecordMs = 0L
                    recordHistory()
                }
                // Counted from ticks rather than from the clock for the same reason: a laptop
                // suspended mid-chapter must not wake up having "listened" for five hours.
                unflushedListeningMs += tickIntervalMs
                if (unflushedListeningMs >= listeningFlushIntervalMs) flushListening()
            }

            if (lastKnownPositionMs - lastSavedMs >= progressIntervalMs) {
                lastSavedMs = lastKnownPositionMs
                saveProgress(chapter, lastKnownPositionMs, isPlayed = false)
            }
        }
        return AttemptResult.Stopped
    }

    /**
     * Consumes queued engine events, returning the one that ends this attempt (if any).
     *
     * A duration is not an ending, so it is applied and the drain continues — otherwise a
     * `DurationKnown` arriving in the same tick as a `Failed` would hide the failure.
     */
    private fun drainEngineEvents(): AttemptResult? {
        while (true) {
            when (val event = engineEvents.poll() ?: return null) {
                is EngineEvent.Completed -> return AttemptResult.Completed
                is EngineEvent.Failed -> return event.failure.toAttemptResult()
                is EngineEvent.DurationKnown ->
                    _state.update { it.copy(durationMs = event.durationMs) }
            }
        }
    }

    private fun PlaybackFailure.toAttemptResult(): AttemptResult = when (this) {
        is PlaybackFailure.SessionExpired -> AttemptResult.SessionExpired(this)
        is PlaybackFailure.Transient -> AttemptResult.Transient(message)
        is PlaybackFailure.Fatal -> AttemptResult.Fatal(message)
    }

    private fun publishMetadata(chapterId: Int, positionMs: Long): ChapterSummary? =
        synchronized(playbackLock) {
            val index = queue.indexOfFirst { it.resolvedChapterId == chapterId }
            val chapter = queue.getOrNull(index) ?: return@synchronized null
            queueIndex = index
            _state.value = metadataOf(chapter, queueFiction, positionMs, queue, index)
            chapter
        }

    private fun metadataOf(
        chapter: ChapterSummary,
        fiction: FictionSummary?,
        positionMs: Long,
        queue: List<ChapterSummary>,
        index: Int,
    ) = PlayerUiState(
        title = chapter.resolvedTitle,
        fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
        fictionId = fictionIdOf(chapter, fiction),
        coverImageUrl = (fiction?.coverImageUrl ?: chapter.resolvedCoverUrl)?.let(repository::resolveUrl),
        durationMs = ((chapter.audioDuration ?: 0.0) * 1000).toLong(),
        positionMs = positionMs,
        speed = engine.capabilities.coerceSpeed(speed),
        speedIsPerFiction = preferencesStore.preferences.value.fictionSpeeds.containsKey(speedFictionId),
        canChangeSpeed = engine.capabilities.variableSpeed,
        canSkipSilence = engine.capabilities.skipSilence,
        // Rebuilt from the live values rather than copied from the previous state: this function
        // constructs a whole PlayerUiState, so anything not named here would silently revert to a
        // default every time the chapter changed.
        skipIntervalMs = preferencesStore.preferences.value.skipIntervalMs,
        sleepTimer = sleepTimer.state.value,
        queue = queue.map { QueueItem(it.resolvedChapterId, it.resolvedTitle, it.resolvedDisplayNumber) },
        currentIndex = index,
        hasNext = index < queue.lastIndex,
        hasPrevious = index > 0,
    )

    /**
     * The fiction a queue belongs to.
     *
     * The chapter's own id wins over the passed [fiction] only when the latter is absent: playback
     * started from a library shelf carries no `FictionSummary` at all, and the flat shelf payload
     * still names its fiction.
     */
    private fun fictionIdOf(chapter: ChapterSummary, fiction: FictionSummary?): Int =
        fiction?.id?.takeIf { it > 0 } ?: chapter.resolvedFictionId

    /**
     * Files a local "you were here" snapshot for the chapter currently loaded.
     *
     * Called at transitions — pause, chapter change, sleep, stop, shutdown — and deliberately
     * *not* on the progress tick. Recording every ten seconds would be a write amplification for
     * no extra information, and, more importantly, it would undo a dismissal within one tick of
     * the user making it.
     */
    private fun recordHistory() {
        // Every transition that files a snapshot is also a good moment to bank the minutes.
        flushListening()
        val chapter = queue.getOrNull(queueIndex) ?: return
        val current = _state.value
        if (!current.hasMedia) return
        val fictionId = chapter.resolvedFictionId
        if (fictionId <= 0) return

        historyStore.record(
            PlaybackSnapshot(
                fictionId = fictionId,
                chapterId = chapter.resolvedChapterId,
                // Titles only. Nothing here reconstructs the server, the audio object or the
                // account — see PlaybackSnapshot's own note on what this file may hold.
                fictionTitle = current.fictionTitle ?: chapter.resolvedFictionTitle.orEmpty(),
                chapterTitle = chapter.resolvedTitle,
                positionSeconds = lastKnownPositionMs / 1000.0,
                durationSeconds = current.durationMs / 1000.0,
                recordedAtMs = clock(),
                ownerKey = queueOwnerKey,
            ),
        )
    }

    /**
     * Adds the playing time accumulated since the last flush to today's total.
     *
     * Attributed to [queueOwnerKey] rather than to whoever is signed in *now*, for the reason the
     * history snapshot is: a sign-out while a flush is pending must not file one account's evening
     * under another's name.
     */
    private fun flushListening(chaptersFinished: Int = 0) {
        val seconds = unflushedListeningMs / 1000.0
        unflushedListeningMs = 0L
        if (seconds <= 0.0 && chaptersFinished == 0) return
        statsStore.record(
            ownerKey = queueOwnerKey,
            date = ListeningStats.dateOf(clock()),
            seconds = seconds,
            chaptersFinished = chaptersFinished,
        )
    }

    /** Fire-and-forget save for the transitions that happen on the UI thread. */
    private fun saveCurrentProgress() {
        scope.launch { saveProgressNow() }
    }

    private suspend fun saveProgressNow() {
        val chapter = queue.getOrNull(queueIndex) ?: return
        if (!_state.value.hasMedia) return
        saveProgress(chapter, lastKnownPositionMs, isPlayed = isEffectivelyComplete())
    }

    /**
     * Whether the listener has got far enough for the chapter to count as played.
     *
     * 96%, or within the last 20 seconds, matching the mobile client. Requiring a byte-perfect
     * end-of-stream — which is what the old controller did — meant a chapter whose stream stopped a
     * second early never marked, and the server kept offering it as "continue listening".
     */
    private fun isEffectivelyComplete(): Boolean {
        val duration = _state.value.durationMs
        if (duration <= 0) return false
        val position = lastKnownPositionMs
        return position >= duration * PLAYED_FRACTION || position >= duration - PLAYED_TAIL_MS
    }

    private suspend fun saveProgress(chapter: ChapterSummary, positionMs: Long, isPlayed: Boolean) {
        runCatching {
            repository.saveProgress(
                fictionId = chapter.resolvedFictionId,
                chapterId = chapter.resolvedChapterId,
                positionSeconds = positionMs / 1000.0,
                isPlayed = isPlayed,
            )
        }
    }

    private companion object {
        const val PREVIOUS_RESTARTS_AFTER_MS = 5_000L
        const val RELEASE_TIMEOUT_MS = 3_000L
        const val PLAYED_FRACTION = 0.96
        const val PLAYED_TAIL_MS = 20_000L
    }
}
