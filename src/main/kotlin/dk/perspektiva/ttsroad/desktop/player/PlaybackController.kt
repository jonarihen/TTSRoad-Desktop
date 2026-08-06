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
     */
    suspend fun playQueue(chapters: List<ChapterSummary>, startChapterId: Int, fiction: FictionSummary?)

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun skipBy(deltaMs: Long)

    fun skipToNextChapter()

    fun skipToPreviousChapter()

    fun skipToQueueIndex(index: Int)

    /** Requests a playback rate. What the backend actually applied appears in [state]. */
    fun setSpeed(speed: Float)

    /** Retries the current chapter after the automatic attempts have been exhausted. */
    fun retry() = Unit

    fun stop()

    /**
     * Tears down background work owned by the controller and flushes progress. Called when the app
     * window closes; the default no-op keeps test fakes from having to care.
     */
    fun release() = Unit
}
