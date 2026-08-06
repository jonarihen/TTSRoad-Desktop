package dk.perspektiva.ttsroad.desktop.data

/** The chapter-list filter, mirroring the mobile client so the two read the same way. */
enum class ChapterFilter(val label: String) {
    All("All"),
    Unplayed("Unplayed"),

    /** Has audio the player can actually open — not the `playable` flag, and not `status`. */
    Ready("Ready");

    fun matches(chapter: ChapterSummary): Boolean = when (this) {
        All -> true
        Unplayed -> !chapter.isPlayed
        Ready -> chapter.hasAudio
    }
}

/**
 * Reading order.
 *
 * Named for what the reader sees rather than for the sort direction: "oldest first" is the order
 * the server already returns and the order the player queue always uses. Choosing [Newest] is a
 * *view* decision — it never changes what is sent to the server and never reorders the queue.
 */
enum class ChapterSort(val label: String) {
    Oldest("Oldest"),
    Newest("Newest");

    val ascending: Boolean get() = this == Oldest
}

/** The per-fiction browsing state that has to survive navigating away and back. */
data class ChapterListOptions(
    val filter: ChapterFilter = ChapterFilter.All,
    val sort: ChapterSort = ChapterSort.Oldest,
) {
    /** True when the list on screen is a subset — i.e. "n of m" is worth showing. */
    val isFiltered: Boolean get() = filter != ChapterFilter.All
}

fun List<ChapterSummary>.chapterView(filter: ChapterFilter): List<ChapterSummary> =
    if (filter == ChapterFilter.All) this else filter { filter.matches(it) }

/** Filter, then order. The two are independent, and neither mutates the server's own list. */
fun List<ChapterSummary>.chapterView(options: ChapterListOptions): List<ChapterSummary> =
    chapterView(options.filter).sortedByDisplayNumber(options.sort.ascending)

/**
 * Orders by [ChapterSummary.resolvedDisplayNumber], keeping unnumbered chapters at the end in
 * **both** directions.
 *
 * Unnumbered rows are the ones the server could not place (excluded chapters have a null
 * `display_number`); floating them to the top when the user asks for "newest first" would put the
 * least identifiable rows where the newest chapter should be. The sort is stable, so rows sharing a
 * number keep the server's relative order.
 */
fun List<ChapterSummary>.sortedByDisplayNumber(ascending: Boolean): List<ChapterSummary> {
    val (numbered, unnumbered) = partition { it.resolvedDisplayNumber != null }
    val ordered = numbered.sortedBy { it.resolvedDisplayNumber ?: 0.0 }
    return (if (ascending) ordered else ordered.asReversed()) + unnumbered
}

/**
 * The canonical order a queue is built in: oldest first, regardless of how the list is being
 * viewed.
 *
 * This is why sorting the screen newest-first does not make the player play backwards.
 */
fun List<ChapterSummary>.playbackOrder(): List<ChapterSummary> = sortedByDisplayNumber(ascending = true)

/**
 * Every chapter before [chapterId] in canonical reading order.
 *
 * Deliberately computed from [playbackOrder] rather than from the receiver's current order, so
 * "mark all previous as played" means the same thing whether the user is looking at the list
 * oldest-first, newest-first, or filtered down to three rows. An unknown id — or the very first
 * chapter — yields nothing rather than everything.
 */
fun List<ChapterSummary>.chaptersBefore(chapterId: Int): List<ChapterSummary> {
    if (chapterId <= 0) return emptyList()
    val ordered = playbackOrder()
    val index = ordered.indexOfFirst { it.resolvedChapterId == chapterId }
    return if (index <= 0) emptyList() else ordered.subList(0, index).toList()
}

/** Ids the server can act on: a row that decoded without one resolves to 0 and is dropped. */
fun List<ChapterSummary>.allChapterIds(): List<Int> =
    mapNotNull { it.resolvedChapterId.takeIf { id -> id > 0 } }

/**
 * The ids a bulk mark actually has to send.
 *
 * Rows already in the target state are dropped: `POST /api/mobile/playback/mark` overwrites
 * `position_seconds` with the full duration, so re-marking a finished chapter is a write with no
 * meaning, and an empty result is how the UI knows the action would do nothing at all.
 */
fun List<ChapterSummary>.markableIds(played: Boolean): List<Int> =
    filter { it.isPlayed != played }.allChapterIds()

/** Position of [chapterId] in this (already filtered and sorted) view, or -1. */
fun List<ChapterSummary>.indexOfChapter(chapterId: Int?): Int {
    if (chapterId == null || chapterId <= 0) return -1
    return indexOfFirst { it.resolvedChapterId == chapterId }
}

/**
 * Why a chapter cannot be played yet, in the client's own vocabulary.
 *
 * The server's `status`/`sub_status`/`error_message` triple is operator detail. A listener needs
 * exactly one of five answers, and [ChapterSummary.errorMessage] is never one of them.
 */
enum class ChapterAvailability {
    Ready,
    Excluded,
    Failed,
    Converting,
    Queued,
}

fun ChapterSummary.availability(): ChapterAvailability = when {
    excluded -> ChapterAvailability.Excluded
    hasAudio -> ChapterAvailability.Ready
    status.equals("error", ignoreCase = true) || !errorMessage.isNullOrBlank() -> ChapterAvailability.Failed
    status.equals("processing", ignoreCase = true) -> ChapterAvailability.Converting
    else -> ChapterAvailability.Queued
}

/** Short status chip text, or null when the chapter is ready and the row needs no chip. */
fun ChapterSummary.statusLabel(): String? = when (availability()) {
    ChapterAvailability.Ready -> null
    ChapterAvailability.Excluded -> "Excluded"
    ChapterAvailability.Failed -> "Failed"
    ChapterAvailability.Converting -> ttsProgress?.takeIf { it in 1..99 }?.let { "Converting $it%" } ?: "Converting"
    ChapterAvailability.Queued -> "Queued"
}

/**
 * Returns the list with [chapterIds] marked [played], **preserving the identity of every other
 * row**.
 *
 * That identity is not an optimisation detail: Compose skips a list item whose input is the same
 * instance, so patching one checkmark in a 500-chapter fiction redraws one row instead of five
 * hundred. It is also why marking played no longer refetches the whole list.
 */
fun List<ChapterSummary>.withPlayed(chapterIds: Collection<Int>, played: Boolean): List<ChapterSummary> {
    if (chapterIds.isEmpty()) return this
    val ids = chapterIds.toSet()
    return map { chapter ->
        if (chapter.resolvedChapterId !in ids) {
            chapter
        } else {
            chapter.copy(
                playback = (chapter.playback ?: PlaybackInfo()).copy(
                    isPlayed = played,
                    // The server sets position to the full duration when marking played and to 0
                    // when un-marking (app/routers/mobile.py), so the local patch says the same
                    // thing the next refresh will.
                    positionSeconds = if (played) chapter.audioDuration ?: 0.0 else 0.0,
                    remainingSeconds = if (played) 0.0 else chapter.audioDuration,
                ),
            )
        }
    }
}

/**
 * Restores the exact `playback` object each id had before an optimistic patch.
 *
 * Rollback has to be a *restore*, not an inverse mark: un-marking a chapter that was already
 * finished before the user pressed anything would silently destroy real progress, and a chapter
 * resumed at 6:52 must come back at 6:52 rather than at zero.
 */
fun List<ChapterSummary>.withRestoredPlayback(
    snapshot: Map<Int, PlaybackInfo?>,
): List<ChapterSummary> {
    if (snapshot.isEmpty()) return this
    return map { chapter ->
        val id = chapter.resolvedChapterId
        if (!snapshot.containsKey(id)) chapter else chapter.copy(playback = snapshot[id])
    }
}

/** The `playback` state of [chapterIds], for [withRestoredPlayback]. */
fun List<ChapterSummary>.playbackSnapshot(chapterIds: Collection<Int>): Map<Int, PlaybackInfo?> {
    if (chapterIds.isEmpty()) return emptyMap()
    val ids = chapterIds.toSet()
    return filter { it.resolvedChapterId in ids }.associate { it.resolvedChapterId to it.playback }
}

/**
 * Stable, unique keys for a list of chapters.
 *
 * `resolvedChapterId` alone is not safe as a lazy-list key: the library's `continue_listening` and
 * `recent_chapters` shelves are two different server payloads whose ids can repeat, and a payload
 * that fails to carry an id at all resolves to 0 for *every* row. Duplicate keys are a hard crash
 * in a lazy list, so a repeat gets an occurrence suffix rather than colliding — the first
 * occurrence keeps the plain key, so the common case stays stable across refreshes.
 */
fun chapterKeys(chapters: List<ChapterSummary>): List<String> {
    val seen = HashMap<String, Int>()
    return chapters.map { chapter ->
        val base = "${chapter.resolvedFictionId}:${chapter.resolvedChapterId}"
        val count = seen.getOrDefault(base, 0)
        seen[base] = count + 1
        if (count == 0) base else "$base#$count"
    }
}

/** Same contract as [chapterKeys], for the fictions grid. */
fun fictionKeys(fictions: List<FictionSummary>): List<String> {
    val seen = HashMap<String, Int>()
    return fictions.map { fiction ->
        val base = "fiction:${fiction.id}"
        val count = seen.getOrDefault(base, 0)
        seen[base] = count + 1
        if (count == 0) base else "$base#$count"
    }
}
