package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * `GET /api/mobile/search`.
 *
 * Three groups, always present even when empty, returned in rank order — fictions, then chapter
 * titles, then narration text (`app/services/search.py`). Everything is defaulted because the
 * endpoint is additive like the rest of the mobile surface: a server that grows a fourth group must
 * not fail this decode.
 */
data class SearchResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val query: String = "",
    val tokens: List<String> = emptyList(),
    val limit: Int = SearchLimits.Default,
    val offset: Int = 0,
    /**
     * Whether the server answered from its narration-text index or from a scan. Reported because a
     * non-indexed server still answers, just less well — not because the client does anything
     * differently.
     */
    val indexed: Boolean = false,
    val total: Int = 0,
    val fictions: SearchGroup = SearchGroup(),
    val chapters: SearchGroup = SearchGroup(),
    val text: SearchGroup = SearchGroup(),
) {
    val isEmpty: Boolean
        get() = fictions.items.isEmpty() && chapters.items.isEmpty() && text.items.isEmpty()
}

data class SearchGroup(
    val items: List<SearchHit> = emptyList(),
    val total: Int = 0,
    /** The server stopped counting at its cap; "500 results" would be a guess dressed as a number. */
    val capped: Boolean = false,
    @param:Json(name = "has_more") val hasMore: Boolean = false,
)

/**
 * One hit, in the single shape all three groups share.
 *
 * The server deliberately sends null rather than omitting a field, so a client decodes every hit
 * with one type and switches on [kind]. This mirrors that: the chapter fields are null on a fiction
 * hit rather than modelled as a separate class.
 */
data class SearchHit(
    /** `fiction`, `chapter` or `text`. Compared as a string: an unknown kind must not fail a decode. */
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
    @param:Json(name = "chapter_number") val chapterNumber: Int? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double = 0.0,
    val status: String? = null,
    /** Excluded chapters stay searchable and are flagged rather than hidden — the server's choice. */
    val excluded: Boolean = false,
    val playable: Boolean = false,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    val audio: AudioInfo? = null,
    /**
     * Where the match starts in the chapter's `clean_text`, in characters.
     *
     * **Not an audio timestamp.** Turning one into the other needs the read-along timings, so this
     * is decoded and not yet acted on; a text hit opens the chapter, not the passage.
     */
    @param:Json(name = "char_offset") val charOffset: Int? = null,
    @param:Json(name = "matched_fields") val matchedFields: List<String> = emptyList(),
    val snippet: String = "",
    /**
     * `[[start, end], …]` over [snippet], in **Unicode code points** — see [snippetSpans], which is
     * the only thing that should read this field.
     */
    val highlights: List<List<Int>> = emptyList(),
) {
    val resolvedChapterId: Int get() = chapterId ?: 0

    /** A chapter row the reader can be opened on. Fiction hits carry no chapter at all. */
    val isChapterHit: Boolean get() = resolvedChapterId > 0
}

/** What the server will accept, so the client never sends a request it has told us it will reject. */
object SearchLimits {
    const val Default: Int = 20
    const val Max: Int = 50
    const val MaxQueryLength: Int = 200
}

/** A half-open `[start, end)` range of **UTF-16 indices** into a snippet. */
data class SnippetSpan(val start: Int, val end: Int)

/**
 * Resolves a hit's [SearchHit.highlights] against its snippet.
 *
 * Two corrections happen here, and both are load-bearing:
 *
 * The offsets are computed over Python string indices, which count **code points**. Kotlin indexes
 * UTF-16 units, so a snippet containing an astral character — an emoji anywhere before the match —
 * shifts every span after it. Converting rather than trusting is what keeps the highlight on the
 * word that actually matched.
 *
 * And a span outside the snippet is *dropped*, not clamped-and-drawn: an out-of-range index throws
 * when it reaches an `AnnotatedString`, so a malformed payload would take down the results list
 * rather than merely mis-highlighting one row.
 */
fun snippetSpans(snippet: String, highlights: List<List<Int>>): List<SnippetSpan> {
    if (snippet.isEmpty() || highlights.isEmpty()) return emptyList()
    val codePoints = snippet.codePointCount(0, snippet.length)
    // The overwhelmingly common case: no surrogate pairs, so a code point index *is* a char index.
    val simple = codePoints == snippet.length
    val spans = ArrayList<SnippetSpan>(highlights.size)
    for (pair in highlights) {
        if (pair.size < 2) continue
        val startCodePoint = pair[0]
        val endCodePoint = pair[1].coerceAtMost(codePoints)
        if (startCodePoint < 0 || endCodePoint <= startCodePoint || startCodePoint >= codePoints) continue
        spans += if (simple) {
            SnippetSpan(startCodePoint, endCodePoint)
        } else {
            SnippetSpan(
                snippet.offsetByCodePoints(0, startCodePoint),
                snippet.offsetByCodePoints(0, endCodePoint),
            )
        }
    }
    return spans
}
