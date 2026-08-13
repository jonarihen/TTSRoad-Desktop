package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * The account's server-side cross-library queue — the same rows the web player reads.
 *
 * Named for the server throughout to keep it distinct from
 * [QueueItem][dk.perspektiva.ttsroad.desktop.player.QueueItem], which is a different thing that
 * happens to share the word. The player's queue is *derived* reading order for one fiction, rebuilt
 * whenever playback starts and gone when the process exits. This one is an explicit list the user
 * curated, spanning fictions, stored on the server and shared with every other client.
 *
 * `/api/mobile/queue` is additive and capability-gated (`queue`), so every model here tolerates a
 * field the server has not sent — an older build must degrade to "no queue", never to a parse
 * failure.
 */
data class ServerQueueItem(
    /**
     * The queue *row* id, which is what every mutation addresses.
     *
     * Not the chapter id, and the distinction matters: the same chapter can legitimately sit in the
     * queue twice, so removing "chapter 12" is ambiguous where removing row 4801 is not.
     */
    val id: Int = 0,
    val position: Int = 0,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Int? = null,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double = 0.0,
    @param:Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    /**
     * The bearer-authenticated audio descriptor the mobile surface adds on top of the web shape.
     *
     * Parsed but not used to start playback: a queue row is played by opening its fiction and
     * handing the chapter to the existing player, so the audio URL that actually gets fetched is
     * the one on the chapter — which is also the one the offline-first source knows how to
     * substitute a downloaded file for.
     */
    val audio: AudioInfo? = null,
) {
    val resolvedTitle: String get() = chapterTitle?.takeIf { it.isNotBlank() } ?: "Untitled chapter"

    val resolvedFictionTitle: String get() = fictionTitle?.takeIf { it.isNotBlank() } ?: "Unknown fiction"

    /**
     * Enough of a [FictionSummary] for the detail header to paint while the real one loads.
     *
     * A cross-library queue routinely names a fiction the user has never opened, so the library
     * cache is often the *wrong* place to look for it — and a row that silently did nothing when
     * the lookup missed would be a dead end. The counters are zero because a queue row does not
     * carry them; the chapter list replaces this wholesale as soon as it arrives.
     */
    fun toFictionSummary(): FictionSummary = FictionSummary(
        id = fictionId,
        title = resolvedFictionTitle,
        slug = fictionSlug,
        coverImageUrl = coverImageUrl,
    )
}

/**
 * A queue as the server currently holds it.
 *
 * Every mutation answers with this whole object, which is why the client never predicts the result
 * of an action: the server owns ordering, de-duplication and the cap, and a second client may have
 * changed the list between two requests here.
 */
data class ServerQueueResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val items: List<ServerQueueItem> = emptyList(),
    val total: Int = 0,
    /**
     * The account's `queue_when_empty` preference, as the server resolved it.
     *
     * Read and displayed, deliberately not acted on. Honouring it means calling `advance` to decide
     * what plays next, and that is the coupling this client is not taking on yet — see the class
     * comment on [ServerQueueAction].
     */
    @param:Json(name = "when_empty") val whenEmpty: String? = null,
    @param:Json(name = "max_items") val maxItems: Int = 0,
    /** Present on a mutation response ("ok"), absent on a plain read. */
    val status: String? = null,
)

/**
 * Every queue mutation goes through one POST with an `action`, which is the shape the backend
 * chose so a client's retry logic stays on one code path rather than five.
 *
 * [Advance] is modelled for completeness but is not sent by this client. It *pops the head* and
 * decides what plays next, which would put the network in the path of end-of-chapter behaviour —
 * exactly the coupling that keeps the local queue working when the server is unreachable. The
 * visible consequence is that `when_empty` is shown and not honoured, which the queue screen says
 * outright instead of hiding.
 */
object ServerQueueAction {
    const val Add: String = "add"
    const val Fill: String = "fill"
    const val Reorder: String = "reorder"
    const val Remove: String = "remove"
    const val Clear: String = "clear"
    const val Advance: String = "advance"
}

/** Where an `add` puts the chapters: at the end, or immediately after the head. */
object ServerQueueMode {
    const val End: String = "end"
    const val Next: String = "next"
}

/** What a `fill` draws from. */
object ServerQueueSource {
    const val FictionUnplayed: String = "fiction_unplayed"
    const val Backlog: String = "backlog"
}

/**
 * One request body for every action.
 *
 * The unused fields are sent as their defaults rather than omitted because the server models them
 * with defaults too (`MobileQueueRequest`), so this is the shape it already accepts — and keeping
 * one body type means an action added server-side needs a constant here, not a new model.
 */
data class ServerQueueRequest(
    val action: String,
    @param:Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    @param:Json(name = "item_ids") val itemIds: List<Int> = emptyList(),
    val mode: String = ServerQueueMode.End,
    val source: String? = null,
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    val count: Int = 5,
)

/**
 * The order [items] would have with the entry at [from] moved to [to].
 *
 * Pure, and separate from the screen, because `reorder` takes the **complete desired order** rather
 * than a delta: the client has to compute the whole list anyway, and computing it here means the
 * rule is testable without a display. An out-of-range index answers the unchanged order, so a row
 * that moved underneath the click cannot produce a truncated queue.
 */
fun List<ServerQueueItem>.movedTo(from: Int, to: Int): List<ServerQueueItem> {
    if (from !in indices || to !in indices || from == to) return this
    val reordered = toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return reordered
}

/** Row ids in their current order — the payload `reorder` expects. */
fun List<ServerQueueItem>.itemIds(): List<Int> = map { it.id }

/** Total seconds left across the queue, counting only what has not been listened to. */
fun List<ServerQueueItem>.remainingSeconds(): Double = sumOf { item ->
    if (item.isPlayed) 0.0 else (item.audioDuration - item.positionSeconds).coerceAtLeast(0.0)
}
