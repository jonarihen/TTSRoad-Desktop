package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * One row of the shared Up Next queue — the same rows the browser reads.
 *
 * Named for the server to keep it apart from `player.QueueItem`, which is this client's local
 * reading-order queue for a single fiction. They are different things: this one is explicit,
 * curated and cross-fiction; that one is derived and always one book.
 *
 * The entry carries the fiction slug, cover and audio descriptor rather than assuming the caller
 * can look them up, because a cross-fiction queue gets played from places that know nothing about
 * the fiction in question.
 */
data class ServerQueueItem(
    /** The queue row's own id — what reorder and remove address. Not the chapter id. */
    val id: Int = 0,
    val position: Int = 0,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double = 0.0,
    @param:Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    val audio: AudioInfo? = null,
)

data class QueueState(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String? = null,
    val items: List<ServerQueueItem> = emptyList(),
    val total: Int = 0,
    /**
     * What the account wants when the queue runs dry — `stop` or `continue`. Read here only to
     * describe the queue; this client does not act on it (see [QueueAction.ADVANCE]).
     */
    @param:Json(name = "when_empty") val whenEmpty: String = "stop",
    @param:Json(name = "max_items") val maxItems: Int = 500,
)

object QueueAction {
    const val ADD = "add"
    const val FILL = "fill"
    const val REORDER = "reorder"
    const val REMOVE = "remove"
    const val CLEAR = "clear"

    /**
     * Pops the head and tells the caller what to play, falling back to the oldest unplayed chapter
     * when the queue is empty and the account's `queue_when_empty` is `continue`.
     *
     * Deliberately unused by this client. Calling it would make the server queue decide what plays
     * after a chapter ends, which is the coupling the queue design in #38 explicitly avoids for
     * now — the queue is a surface you play *from*, not a replacement for reading order.
     */
    const val ADVANCE = "advance"
}

object QueueMode {
    const val END = "end"
    const val NEXT = "next"
}

/**
 * Every queue mutation goes through one POST with an [action], which is the server's design: it
 * keeps a client's retry logic to one code path and lets the server add actions without new URLs.
 */
data class QueueUpdateRequest(
    val action: String,
    @param:Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    @param:Json(name = "item_ids") val itemIds: List<Int> = emptyList(),
    val mode: String = QueueMode.END,
    val source: String? = null,
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    val count: Int = 5,
)
