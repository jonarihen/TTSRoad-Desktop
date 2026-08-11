package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * One search hit.
 *
 * The server uses a single item shape across all three groups and fills the irrelevant keys with
 * nulls rather than omitting them, specifically so a client decodes every hit with one type and
 * switches on [kind]. This mirrors that.
 */
data class SearchHit(
    val kind: String = "",
    val score: Double = 0.0,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
    val author: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    val tags: List<String> = emptyList(),
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double = 0.0,
    val status: String? = null,
    val excluded: Boolean = false,
    val playable: Boolean = false,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    val audio: AudioInfo? = null,
    /**
     * Where in the chapter's text the match starts — a character offset, not a timestamp. Turning
     * it into an audio position needs the read-along timings, which this client does not fetch.
     */
    @param:Json(name = "char_offset") val charOffset: Int? = null,
    @param:Json(name = "matched_fields") val matchedFields: List<String> = emptyList(),
    val snippet: String = "",
    /**
     * Ranges to emphasise within [snippet], as `[start, end]` pairs. Computed server-side over
     * Python string indices, which count code points; Kotlin indexes UTF-16 units, so a snippet
     * containing astral characters (emoji) can shift these. Treated as a hint and bounds-checked
     * at render time rather than trusted.
     */
    val highlights: List<List<Int>> = emptyList(),
    val url: String? = null,
) {
    val isFiction: Boolean get() = kind == SearchKind.FICTION
}

object SearchKind {
    const val FICTION = "fiction"
    const val CHAPTER = "chapter"
    const val TEXT = "text"
}

data class SearchGroup(
    val items: List<SearchHit> = emptyList(),
    val total: Int = 0,
    /** The server stops counting at a cap; when true, [total] is a floor rather than a figure. */
    val capped: Boolean = false,
    @param:Json(name = "has_more") val hasMore: Boolean = false,
)

/**
 * Groups are always present, even when empty, and their order is the global rank order: fictions,
 * then chapter titles, then narration text.
 */
data class SearchResponse(
    val query: String = "",
    val tokens: List<String> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val fictions: SearchGroup = SearchGroup(),
    val chapters: SearchGroup = SearchGroup(),
    val text: SearchGroup = SearchGroup(),
    /**
     * Whether a real text index is behind the narration search. False means the server fell back to
     * scanning, which still answers but is slower and worth not hiding from the user.
     */
    val indexed: Boolean = false,
    val total: Int = 0,
)
