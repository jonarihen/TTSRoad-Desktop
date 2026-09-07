package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stretches of a chapter that are an advert or a disclaimer rather than the book.
 *
 * Serials carry things nobody subscribed for: a Patreon plug welded onto the end of every chapter,
 * an "I don't own Marvel, this is a fan work" paragraph welded onto the front of one. The server
 * matches a rule against the text a chapter was **already narrated from** and converts what it
 * finds into seconds of the existing MP3, through the read-along cues. So the file is untouched —
 * a downloaded chapter is still the right download — and the player simply seeks past it.
 *
 * Server contract: capability `playback_skips`, `GET /api/mobile/chapters/{id}/skips`.
 */
data class ChapterSkipsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "rule_count") val ruleCount: Int = 0,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    val segments: List<ChapterSkipSegmentWire> = emptyList(),
    @param:Json(name = "total_skipped_seconds") val totalSkippedSeconds: Double = 0.0,
)

data class ChapterSkipSegmentWire(
    @param:Json(name = "start_seconds") val startSeconds: Double = 0.0,
    @param:Json(name = "end_seconds") val endSeconds: Double = 0.0,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    val label: String? = null,
    val preview: String? = null,
)

/** One stretch to jump over, in milliseconds of media time. */
data class ChapterSkipSegment(val startMs: Long, val endMs: Long)

/**
 * A chapter's skip list and every decision taken from it.
 *
 * Pure, and deliberately the whole of the thinking: the controller around it owns an engine and a
 * tick loop, while *where to seek* is arithmetic that costs a listener prose when it is wrong. The
 * tolerance is on the near edge only — a quarter second short of an advert still means hearing it,
 * a quarter second short of the end of one means a seek that saves nothing.
 */
data class ChapterSkips(
    val chapterId: Int = 0,
    val segments: List<ChapterSkipSegment> = emptyList(),
    val durationMs: Long = 0,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** Where playback should jump to from [positionMs], or null when it is not inside an advert. */
    fun targetFor(positionMs: Long): Long? {
        val segment = segments.firstOrNull {
            positionMs >= it.startMs - EdgeToleranceMs && positionMs < it.endMs - EdgeToleranceMs
        } ?: return null
        val target = if (durationMs > 0) minOf(segment.endMs, durationMs) else segment.endMs
        return target.takeIf { it > positionMs + EdgeToleranceMs }
    }

    /**
     * Whether jumping to [targetMs] is really the end of the chapter.
     *
     * What follows a trailing plug is silence and an ID3 tag. Treating that as a finished chapter
     * hands the decision to everything that already knows what the end of one means — auto-advance,
     * the "stop at end of chapter" sleep timer, the listening tally — rather than leaving a sliver
     * playing to nobody.
     */
    fun endsChapter(targetMs: Long): Boolean =
        durationMs > 0 && targetMs >= durationMs - TailToleranceMs

    companion object {
        const val EdgeToleranceMs = 250L
        const val TailToleranceMs = 1_500L

        /** Nothing to skip: no rules, no timings, an older server, or a call that failed. */
        val None = ChapterSkips()

        /**
         * Read a server payload, dropping anything that cannot be acted on.
         *
         * A malformed row is dropped rather than repaired. These are seconds of somebody's book,
         * and a segment whose numbers do not make sense is not one whose numbers can be guessed.
         */
        fun from(response: ChapterSkipsResponse, chapterId: Int): ChapterSkips = ChapterSkips(
            chapterId = chapterId,
            segments = response.segments
                .filter { segment ->
                    segment.startSeconds.isFinite() &&
                        segment.endSeconds.isFinite() &&
                        segment.startSeconds >= 0 &&
                        segment.endSeconds > segment.startSeconds
                }
                .map { ChapterSkipSegment((it.startSeconds * 1000).toLong(), (it.endSeconds * 1000).toLong()) }
                .sortedBy { it.startMs },
            durationMs = ((response.audioDuration ?: 0.0) * 1000).toLong().coerceAtLeast(0),
        )
    }
}

/**
 * The one account preference this client reads off `/api/me/preferences` outside the reader keys.
 *
 * Its own request and its own patch type, so the "only the four reader keys are ever sent" rule in
 * [ReaderPreferencesPatch] keeps holding: a PATCH from here carries exactly one key and cannot
 * overwrite a reader setting made in a browser, and one from there cannot overwrite this.
 */
data class SkipAdSegmentsResponse(
    val preferences: SkipAdSegmentsWire = SkipAdSegmentsWire(),
)

/** Null on an account that has never been asked, which is not the same as false. */
data class SkipAdSegmentsWire(
    @param:Json(name = "skip_ad_segments") val skipAdSegments: Boolean? = null,
)

data class SkipAdSegmentsPatch(
    @param:Json(name = "skip_ad_segments") val skipAdSegments: Boolean,
)

/**
 * Keeps the local "skip adverts" setting in step with the account.
 *
 * A separate object rather than a method on [PlaybackPreferencesStore], because that store is
 * deliberately account-less: speed, skip interval, silence and gain are properties of this machine
 * and its speakers, and signing out must not reset them. This one setting is not shaped like those
 * — "do I want to hear this book's Patreon plug" is a fact about the listener and the library — so
 * it follows the account, and the exception is named here rather than smuggled into a file whose
 * whole contract is that it holds nothing account-shaped.
 *
 * Local-first all the same. The stored value is what the controller reads, so the setting works
 * with no network, on a server too old to hold it, and before the first sync of a session lands.
 */
class SkipAdSegmentsSync(
    private val repository: TtsRoadRepository,
    private val store: PlaybackPreferencesStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var syncJob: Job? = null

    /** Adopt the account's answer, if it has one. A server that says nothing changes nothing. */
    suspend fun refreshFromServer() {
        runCatching { repository.skipAdSegmentsPreference() }
            .onSuccess { value ->
                if (value != null) store.update { it.copy(skipAdSegments = value) }
            }
            .onFailure { AppLog.warn("could not refresh the advert-skipping preference", it) }
    }

    /** Set it here and tell the account. The local write is not conditional on the request. */
    fun set(enabled: Boolean) {
        store.update { it.copy(skipAdSegments = enabled) }
        syncJob?.cancel()
        syncJob = scope.launch {
            // The same small debounce the reader settings use, so a listener flipping a switch
            // twice sends one PATCH rather than a race between two.
            delay(300)
            runCatching { repository.updateSkipAdSegments(enabled) }
                .onFailure { AppLog.warn("could not sync the advert-skipping preference", it) }
        }
    }

    override fun close() {
        syncJob?.cancel()
        scope.cancel()
    }
}
