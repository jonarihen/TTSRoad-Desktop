package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import kotlinx.coroutines.flow.StateFlow

data class QueueItem(
    val chapterId: Int,
    val title: String,
    /**
     * The chapter's own number, so the up-next panel can label rows the way the chapter list does
     * rather than by queue position — the queue holds only playable chapters, so position 4 and
     * "Chapter 4" are routinely different things.
     */
    val displayNumber: Double? = null,
)

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    /**
     * Which fiction the loaded queue belongs to, or 0 when nothing is loaded.
     *
     * The chapter list needs it to answer "is the row I am drawing the one that is playing?" — a
     * chapter id alone is not enough, because two fictions can be open in the same session and the
     * user must not see a highlight on a serial that is not playing.
     */
    val fictionId: Int = 0,
    val coverImageUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The rate actually being played, which is what the backend accepted — never a wish. */
    val speed: Float = 1f,
    /**
     * Whether this backend can change speed at all.
     *
     * The UI draws the speed control only when this is true. Before Phase 5 there was no such
     * flag: `setSpeed` stored a number no backend acted on, so the only thing stopping the app
     * from showing a control that did nothing was not drawing one at all.
     */
    val canChangeSpeed: Boolean = false,
    /**
     * Whether [speed] came from this serial's own rate rather than the listener's default.
     *
     * The player needs to distinguish them to offer a way back: a rate that silently overrode the
     * default with no visible sign and no way to clear it would be a setting the user could not
     * find again.
     */
    val speedIsPerFiction: Boolean = false,
    /**
     * Whether this backend can drop silent passages.
     *
     * Same rule as [canChangeSpeed]: the control is drawn only where the engine can honour it.
     */
    val canSkipSilence: Boolean = false,
    /** The skip preference, resolved to milliseconds, so the transport buttons can label themselves. */
    val skipIntervalMs: Long = 30_000L,
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val error: String? = null,
    /** Set when playback stopped for a reason another attempt could plausibly fix. */
    val canRetry: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/**
 * The chapter currently loaded in the queue, but only when that queue belongs to [fictionId].
 *
 * Pure and total: an empty queue, an out-of-range index, an id-less payload, and a queue from a
 * different fiction all answer null, which is exactly the set of cases where a chapter list must
 * highlight nothing.
 */
fun PlayerUiState.playingChapterIdIn(fictionId: Int): Int? {
    if (fictionId <= 0 || this.fictionId != fictionId) return null
    return queue.getOrNull(currentIndex)?.chapterId?.takeIf { it > 0 }
}

/**
 * Playback as the UI sees it.
 *
 * Everything above the audio backend lives behind this: the queue, auto-advance, progress saving,
 * the retry ladder and session expiry. The backend itself is [PlaybackEngine], one level down, so
 * swapping GStreamer for Java Sound changes nothing here and UI tests keep running against a fake
 * that never opens an audio device.
 */
interface PlaybackController {
    val state: StateFlow<PlayerUiState>

    suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?)

    /**
     * Play a whole fiction as a queue, starting at [startChapterId] — enables next/previous,
     * auto-advance, and the up-next list (mirrors the Android client's playQueue).
     *
     * [startPositionMs] overrides the chapter's saved resume position, and exists for the one
     * caller that knows better than the server does: opening a bookmark means "start *here*", and
     * resuming where this chapter was last left would silently ignore the mark that was clicked.
     * Null — every other caller — keeps the saved position.
     */
    suspend fun playQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary?,
        startPositionMs: Long? = null,
    )

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun skipBy(deltaMs: Long)

    /**
     * Skips by the listener's configured interval.
     *
     * The interval lives here rather than in the button that calls it, so the player, the
     * now-playing bar, the keyboard shortcut and a media key all move by the same amount without
     * each having to read the preference for itself.
     */
    fun skipForward() = skipBy(DefaultSkipMs)

    fun skipBackward() = skipBy(-DefaultSkipMs)

    fun skipToNextChapter()

    fun skipToPreviousChapter()

    fun skipToQueueIndex(index: Int)

    /** Requests a playback rate. What the backend actually applied appears in [state]. */
    /**
     * Sets the rate for the serial that is loaded, or the listener's default when none is.
     *
     * Per-serial because that is the question a listener is actually answering: different narrators
     * want different paces, and the pace chosen for a dense translation should not follow them into
     * the next book. Settings owns the default; this owns the exception.
     */
    fun setSpeed(speed: Float)

    /** Drops the loaded serial's own rate so it follows the default again. No-op with none set. */
    fun clearFictionSpeed() = Unit

    /** Arms, re-arms or (with [SleepTimerMode.Off]) cancels the sleep timer. */
    fun setSleepTimer(mode: SleepTimerMode) = Unit

    /**
     * The "+5 min" action, which is mainly reached during the fade.
     *
     * Separate from [setSleepTimer] because re-arming would restart from the chosen duration; this
     * adds to whatever is left, which is what a listener who is still awake actually wants.
     */
    fun extendSleepTimer() = Unit

    /** Retries the current chapter after the automatic attempts have been exhausted. */
    fun retry() = Unit

    fun stop()

    /**
     * Tears down background work owned by the controller and flushes progress. Called when the app
     * window closes; the default no-op keeps test fakes from having to care.
     */
    fun release() = Unit

    companion object {
        /**
         * Fallback for the interface's default [skipForward]/[skipBackward].
         *
         * Only reached by implementations that do not override them — the test fakes. The real
         * controller reads the listener's preference.
         */
        const val DefaultSkipMs: Long = 30_000L
    }
}
