package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackSnapshot
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.describeNetworkFailure
import dk.perspektiva.ttsroad.desktop.data.playbackOrder
import dk.perspektiva.ttsroad.desktop.data.skipIntervalMs
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.withTimeoutOrNull

/** Turns a chapter's audio URL into a byte source. The seam Phase 7's cache replaces. */
fun interface MediaSourceFactory {
    fun create(url: String): MediaSource
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
    private val sleepTimer: SleepTimer = SleepTimer(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Overridable so tests do not wait real seconds for the retry ladder. */
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000, 15_000),
    private val tickIntervalMs: Long = 250,
    private val progressIntervalMs: Long = 10_000,
) : PlaybackController {

    private val _state = MutableStateFlow(emptyState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var playJob: Job? = null
    private var queue: List<ChapterSummary> = emptyList()
    private var queueFiction: FictionSummary? = null

    @Volatile private var queueIndex = 0

    /** Last position actually reported by the engine — what a save or a retry resumes from. */
    @Volatile private var lastKnownPositionMs = 0L

    @Volatile private var speed = preferencesStore.preferences.value.speed

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
                speed = preferences.speed
                val applied = engine.setRate(preferences.speed)
                engine.setSkipSilence(preferences.skipSilence)
                applyGain()
                _state.update {
                    it.copy(
                        speed = applied,
                        skipIntervalMs = preferences.skipIntervalMs,
                    )
                }
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

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        if (chapter.audio == null) {
            stopInternal(clearQueue = true)
            queueFiction = fiction
            _state.value = metadataOf(chapter, fiction, 0L, emptyList(), 0)
                .copy(error = "This chapter has no audio yet")
            return
        }
        queue = listOf(chapter)
        queueFiction = fiction
        begin(0, resumeMsOf(chapter))
    }

    override suspend fun playQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary?,
    ) {
        // Canonical reading order, never the order the screen happens to be sorted in: a listener
        // who flipped the list to newest-first still wants the serial to play forwards.
        val playable = chapters.playbackOrder().filter { it.hasAudio }
        if (playable.isEmpty()) {
            _state.update { it.copy(error = "No playable chapters yet") }
            return
        }
        queue = playable
        queueFiction = fiction
        val startIndex = playable.indexOfFirst { it.resolvedChapterId == startChapterId }.coerceAtLeast(0)
        begin(startIndex, resumeMsOf(playable[startIndex]))
    }

    override fun togglePlayPause() {
        val current = _state.value
        if (!current.hasMedia) return
        if (current.isPlaying) {
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
            engine.play()
            _state.update { it.copy(isPlaying = true) }
            sleepTimer.onPlaybackResumed()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!_state.value.hasMedia) return
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
        val next = queueIndex + 1
        if (next in queue.indices) scope.launch { begin(next, 0L) }
    }

    override fun skipToPreviousChapter() {
        // Audiobook "previous": restart the current chapter unless we're near its start.
        if (_state.value.positionMs > PREVIOUS_RESTARTS_AFTER_MS || queueIndex == 0) {
            seekTo(0L)
        } else {
            scope.launch { begin(queueIndex - 1, 0L) }
        }
    }

    override fun skipToQueueIndex(index: Int) {
        if (index in queue.indices && index != queueIndex) {
            scope.launch { begin(index, 0L) }
        }
    }

    override fun setSpeed(speed: Float) {
        // Persisted rather than held: the preference is the source of truth, and the collector in
        // `init` is what pushes it to the engine and to the UI state. Writing the engine here too
        // would mean two paths to the same setting, one of which does not survive a restart.
        //
        // An engine that cannot resample still stores the wish — a listener who set 1.5× on a
        // machine without GStreamer and later installs it should find 1.5× waiting.
        preferencesStore.update { it.copy(speed = speed) }
    }

    override fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimer.arm(mode)
    }

    override fun extendSleepTimer() {
        sleepTimer.extendBy(SleepTimer.ExtensionMinutes)
    }

    override fun retry() {
        if (!_state.value.canRetry) return
        val index = queueIndex.takeIf { it in queue.indices } ?: return
        scope.launch { begin(index, lastKnownPositionMs) }
    }

    override fun stop() {
        scope.launch { stopInternal(clearQueue = true) }
    }

    override fun release() {
        // Called from window close, off any coroutine. The save has to happen before the process
        // goes away, so this is the one place that blocks — bounded, so a dead server cannot hold
        // the window open.
        // Cancel without joining: the job may be parked in the retry ladder, and the window must
        // not wait for it. The save below reads `lastKnownPositionMs`, which the job has already
        // published, so it does not need the job to finish first.
        playJob?.cancel()
        // Local and synchronous, so it happens whether or not the server is reachable — the whole
        // point of a local history is that closing the lid on a dead network still remembers.
        recordHistory()
        runBlocking {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { saveProgressNow() }
        }
        runCatching { engine.close() }
        scope.cancel()
    }

    private fun resumeMsOf(chapter: ChapterSummary): Long =
        (chapter.resolvedPositionSeconds * 1000).toLong().coerceAtLeast(0L)

    private suspend fun stopInternal(clearQueue: Boolean) {
        playJob?.cancelAndJoin()
        playJob = null
        saveProgressNow()
        recordHistory()
        runCatching { engine.stop() }
        if (clearQueue) {
            queue = emptyList()
            queueFiction = null
            queueIndex = 0
            lastKnownPositionMs = 0
            // A stop is also the end of any sleep timer: the thing it was counting down to has
            // already happened, and leaving it armed would silence the *next* chapter.
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
    private suspend fun begin(startIndex: Int, startMs: Long) {
        playJob?.cancelAndJoin()
        // Leaving the previous chapter is one of the required save points.
        saveProgressNow()
        recordHistory()
        queueIndex = startIndex
        lastKnownPositionMs = startMs
        publishMetadata(startIndex, startMs)

        playJob = scope.launch {
            var index = startIndex
            var positionMs = startMs
            while (isActive && index in queue.indices) {
                queueIndex = index
                val chapter = queue[index]
                publishMetadata(index, positionMs)

                val outcome = playChapter(chapter, positionMs)
                if (outcome == ChapterOutcome.Stopped) return@launch

                // Reaching here means the chapter ended on its own.
                val duration = _state.value.durationMs
                saveProgress(chapter, duration.takeIf { it > 0 } ?: lastKnownPositionMs, isPlayed = true)

                // Checked before the advance, which is the whole requirement: "end of current
                // chapter" has to prevent auto-advance, not stop the next one a moment after it
                // has already started playing.
                if (sleepTimer.shouldStopAtChapterEnd()) {
                    _state.update { it.copy(isPlaying = false, positionMs = duration) }
                    return@launch
                }
                if (index == queue.lastIndex) {
                    _state.update { it.copy(isPlaying = false, positionMs = duration) }
                    return@launch
                }
                index++
                positionMs = 0L
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
                    // A pause, not a stop: the queue and the position stay exactly where they are
                    // so the morning's "resume" is one keypress, not a search for the chapter.
                    engine.pause()
                    _state.update { it.copy(isPlaying = false) }
                    saveProgressNow()
                    recordHistory()
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.SessionExpired -> {
                    _state.update { it.copy(isPlaying = false, error = result.failure.message, canRetry = false) }
                    // Same door as a 401 on an API call: drop the token and return to login rather
                    // than retrying a request that can only fail the same way.
                    repository.endSession(result.failure.sessionEnd)
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.Fatal -> {
                    _state.update { it.copy(isPlaying = false, error = result.message, canRetry = true) }
                    return ChapterOutcome.Stopped
                }

                is AttemptResult.Transient -> {
                    resumeMs = lastKnownPositionMs
                    if (attempt >= retryDelaysMs.size) {
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
            engine.prepare(sources.create(url), startMs)
        } catch (e: SessionExpiredException) {
            return AttemptResult.SessionExpired(PlaybackFailure.SessionExpired(e.sessionEnd))
        } catch (e: Exception) {
            return AttemptResult.Transient(describeNetworkFailure(e))
        }

        // prepare() can report a failure before it returns; the listener is registered in init
        // precisely so that one is already sitting here rather than having been dropped.
        drainEngineEvents()?.let { return it }

        engine.play()
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
            _state.update { it.copy(positionMs = lastKnownPositionMs, durationMs = duration) }

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

    private fun publishMetadata(index: Int, positionMs: Long) {
        val chapter = queue.getOrNull(index) ?: return
        _state.value = metadataOf(chapter, queueFiction, positionMs, queue, index)
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
            ),
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
