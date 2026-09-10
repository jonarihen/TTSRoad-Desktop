package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import kotlin.math.roundToLong

/** Raw playback-skip payload from the mobile API. */
data class PlaybackSkipsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "rule_count") val ruleCount: Int = 0,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    val segments: List<PlaybackSkipSegmentWire> = emptyList(),
    @param:Json(name = "total_skipped_seconds") val totalSkippedSeconds: Double = 0.0,
)

data class PlaybackSkipSegmentWire(
    @param:Json(name = "start_seconds") val startSeconds: Double = 0.0,
    @param:Json(name = "end_seconds") val endSeconds: Double = 0.0,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    val label: String = "",
    val preview: String = "",
)

data class PlaybackSkipSegment(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val preview: String,
)

data class PlaybackSkips(
    val chapterId: Int,
    val audioDurationMs: Long?,
    val segments: List<PlaybackSkipSegment>,
    val needsTimings: Boolean = false,
) {
    companion object {
        fun from(response: PlaybackSkipsResponse): PlaybackSkips? {
            if (response.chapterId <= 0) return null
            val duration = response.audioDuration?.takeIf { it.isFinite() && it > 0.0 }?.secondsToMs()
            if (!response.hasTimings) {
                return PlaybackSkips(response.chapterId, duration, emptyList(), needsTimings = response.ruleCount > 0)
            }
            var previousEnd = -1L
            val segments = response.segments.mapNotNull { wire ->
                if (!wire.startSeconds.isFinite() || !wire.endSeconds.isFinite()) return@mapNotNull null
                val start = wire.startSeconds.secondsToMs()
                val end = wire.endSeconds.secondsToMs()
                if (start < 0 || end <= start || start < previousEnd || duration != null && end > duration) {
                    return@mapNotNull null
                }
                previousEnd = end
                PlaybackSkipSegment(start, end, wire.label, wire.preview)
            }
            if (segments.size != response.segments.size) return null
            return PlaybackSkips(response.chapterId, duration, segments)
        }
    }
}

private fun Double.secondsToMs(): Long = (this * 1_000.0).roundToLong()

data class PlaybackSkipPreferencePatch(
    @param:Json(name = "skip_ad_segments") val skipAdSegments: Boolean,
)

sealed interface PlaybackSkipsFetchResult {
    data class Available(val skips: PlaybackSkips) : PlaybackSkipsFetchResult
    data object Unsupported : PlaybackSkipsFetchResult
}

fun playbackSkipTarget(
    skips: PlaybackSkips?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
): Long? {
    if (!isPlaying || skips == null) return null
    val segment = skips.segments.firstOrNull {
        positionMs >= it.startMs - 250L && positionMs < it.endMs - 250L
    } ?: return null
    val duration = durationMs.takeIf { it > 0 } ?: skips.audioDurationMs ?: 0L
    return if (duration > 0 && duration - segment.endMs <= 1_500L) duration else segment.endMs
}
