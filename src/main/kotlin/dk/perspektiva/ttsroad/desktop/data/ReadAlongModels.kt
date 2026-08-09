package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/** Raw `GET /api/mobile/chapters/{chapter_id}/readalong` response. */
data class ReadAlongResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val chapter: ReadAlongChapter = ReadAlongChapter(),
    val text: String = "",
    /** Half-open `[start, end]` character offsets into [text]. */
    val paragraphs: List<List<Double>> = emptyList(),
    /** `[start, end, media_time_seconds]`, compact because a chapter can contain many thousands. */
    val cues: List<List<Double>> = emptyList(),
)

data class ReadAlongChapter(
    val id: Int = 0,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "",
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "timing_version") val timingVersion: Int? = null,
)

/** Raw document plus the validator needed for a conditional re-open. */
data class CachedReadAlong(
    val version: Int = CurrentVersion,
    val etag: String? = null,
    val response: ReadAlongResponse = ReadAlongResponse(),
) {
    companion object {
        const val CurrentVersion: Int = 1
    }
}

/** Result of one authenticated conditional read-along request. */
sealed interface ReadAlongFetchResult {
    data class Modified(val response: ReadAlongResponse, val etag: String?) : ReadAlongFetchResult
    data object NotModified : ReadAlongFetchResult
    data object NotFound : ReadAlongFetchResult
}

/** The account preference envelope returned by both GET and PATCH. */
data class ReaderPreferencesResponse(
    val preferences: ReaderPreferencesWire = ReaderPreferencesWire(),
)

/** Nullable because an older account can have none of the reader keys yet. */
data class ReaderPreferencesWire(
    @param:Json(name = "reader_font_size") val fontSize: Double? = null,
    @param:Json(name = "reader_line_height") val lineHeight: Double? = null,
    @param:Json(name = "reader_theme") val theme: String? = null,
    @param:Json(name = "reader_highlight") val highlight: String? = null,
)

/** Only the four reader keys are ever sent; unrelated account preferences cannot be overwritten. */
data class ReaderPreferencesPatch(
    @param:Json(name = "reader_font_size") val fontSize: Double,
    @param:Json(name = "reader_line_height") val lineHeight: Double,
    @param:Json(name = "reader_theme") val theme: String,
    @param:Json(name = "reader_highlight") val highlight: String,
)
