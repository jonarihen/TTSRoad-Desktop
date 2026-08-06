package dk.perspektiva.ttsroad.desktop.data

/** The chapter-list filter, mirroring the mobile client so the two read the same way. */
enum class ChapterFilter(val label: String) {
    All("All"),
    Unplayed("Unplayed"),

    /** Has audio the player can actually open — not the `playable` flag, and not `status`. */
    Ready("Ready");

    fun matches(chapter: ChapterSummary): Boolean = when (this) {
        All -> true
        Unplayed -> chapter.playback?.isPlayed != true
        Ready -> chapter.audio != null
    }
}

fun List<ChapterSummary>.chapterView(filter: ChapterFilter): List<ChapterSummary> =
    if (filter == ChapterFilter.All) this else filter { filter.matches(it) }

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
                ),
            )
        }
    }
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
