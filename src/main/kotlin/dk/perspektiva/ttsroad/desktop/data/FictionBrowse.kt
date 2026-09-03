package dk.perspektiva.ttsroad.desktop.data

/**
 * How the shelf can be ordered.
 *
 * The web console has had most of these since before this client existed; the desktop offered a
 * text filter and the server's own order, which meant finding the book that gained a chapter
 * yesterday required remembering its name.
 *
 * Every order here is a *view* over a list that was already fetched. None of them changes what is
 * requested — the library endpoint has no ordering parameter, and inventing one client-side that
 * only worked on the loaded page would be worse than no ordering at all.
 */
enum class FictionSort(
    val label: String,
    /**
     * The line under the option, where the label alone would mislead. Null where it would not.
     *
     * [RecentlyUpdated] is the reason this exists: it follows the fiction row, and the poller
     * touches that row whether or not a check found anything, so it means *recently active* rather
     * than *new chapters*. Saying so under the option is cheaper than the support question.
     */
    val detail: String? = null,
) {
    RecentlyUpdated("Recently updated", "Server activity, which is not the same as new chapters"),
    RecentlyAdded("Recently added"),
    Title("Title"),
    Author("Author"),
    Rating("Rating"),
    MostLeft("Most left to hear", "Listening time remaining for you"),
    LeastFinished("Least finished", "Smallest share of the book heard"),
    MostChapters("Most chapters"),
    PercentConverted("% converted", "How far the server is through making the audio"),
    ;

    companion object {
        val Default: FictionSort = RecentlyUpdated

        /** Parsed leniently, because this arrives from a file an older or newer build wrote. */
        fun fromStorage(value: String?): FictionSort =
            entries.firstOrNull { it.name == value } ?: Default
    }
}

/**
 * Applies an order to a shelf.
 *
 * **Nulls sort last in every order, without exception.** A server that never sent a date, a fiction
 * with no rating, a row with no progress aggregate — each is saying *we were not told*, which is
 * not "a long time ago" or "zero". Letting an absent answer read as the smallest one buries the
 * book that actually just arrived underneath every book nobody has rated.
 *
 * Ties break on title so the order is total: a lazy grid whose items change places between two
 * recompositions of the same data is a scroll position that will not stay still.
 */
fun sortFictions(fictions: List<FictionSummary>, sort: FictionSort): List<FictionSummary> {
    val byTitle = compareBy<FictionSummary> { it.title.lowercase() }
    return when (sort) {
        FictionSort.RecentlyUpdated -> fictions.sortedWith(descendingNullsLast(byTitle) { it.updatedAt })
        FictionSort.RecentlyAdded -> fictions.sortedWith(descendingNullsLast(byTitle) { it.createdAt })
        FictionSort.Title -> fictions.sortedWith(byTitle)
        FictionSort.Author -> fictions.sortedWith(
            ascendingNullsLast(byTitle) { it.author?.trim()?.lowercase()?.takeIf(String::isNotEmpty) },
        )
        FictionSort.Rating -> fictions.sortedWith(descendingNullsLast(byTitle) { it.rating })
        FictionSort.MostLeft -> fictions.sortedWith(
            descendingNullsLast(byTitle) { it.progress?.takeIf(FictionProgress::isMeaningful)?.remainingSeconds },
        )
        FictionSort.LeastFinished -> fictions.sortedWith(
            ascendingNullsLast(byTitle) { it.progress?.listenedFraction },
        )
        FictionSort.MostChapters -> fictions.sortedWith(
            descendingNullsLast(byTitle) { it.totalChapters.takeIf { count -> count > 0 } },
        )
        FictionSort.PercentConverted -> fictions.sortedWith(
            descendingNullsLast(byTitle) { it.takeIf { f -> f.totalChapters > 0 }?.readyFraction },
        )
    }
}

private fun <T : Comparable<T>> descendingNullsLast(
    tieBreak: Comparator<FictionSummary>,
    key: (FictionSummary) -> T?,
): Comparator<FictionSummary> =
    compareBy<FictionSummary> { key(it) == null }.thenByDescending(NullsFirstNaturalOrder(), key).then(tieBreak)

private fun <T : Comparable<T>> ascendingNullsLast(
    tieBreak: Comparator<FictionSummary>,
    key: (FictionSummary) -> T?,
): Comparator<FictionSummary> =
    compareBy<FictionSummary> { key(it) == null }.thenBy(NullsFirstNaturalOrder(), key).then(tieBreak)

/**
 * Natural order that tolerates the nulls the "is it null" key has already sorted to the back.
 *
 * The leading `compareBy { key(it) == null }` guarantees a null is never compared against a
 * non-null, so the value this returns for a null pair never affects the result — it only has to
 * exist so `thenBy` can take a comparator rather than requiring a non-null key.
 */
private class NullsFirstNaturalOrder<T : Comparable<T>> : Comparator<T?> {
    override fun compare(a: T?, b: T?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        else -> a.compareTo(b)
    }
}

/**
 * Every tag carried by the loaded shelf, in one alphabetical list.
 *
 * Drawn from the fictions rather than from a fixed vocabulary, because the server's tags are
 * whatever the source happened to publish and there is no endpoint that enumerates them. Compared
 * case-insensitively, keeping the first spelling seen, for the same reason [cleanFictionTags] does.
 */
fun availableTags(fictions: List<FictionSummary>): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (fiction in fictions) {
        for (tag in fiction.tags) {
            val key = tag.trim().lowercase()
            if (key.isNotEmpty()) seen.putIfAbsent(key, tag.trim())
        }
    }
    return seen.values.sortedBy(String::lowercase)
}

/**
 * Narrows a shelf to the fictions carrying **all** of [tags].
 *
 * Intersection rather than union, matching `ttsroadApplyLibrary` in the web client: a filter that
 * widens the list as you add to it is one nobody uses twice.
 */
fun filterByTags(fictions: List<FictionSummary>, tags: Set<String>): List<FictionSummary> {
    if (tags.isEmpty()) return fictions
    val wanted = tags.map(String::lowercase).toSet()
    return fictions.filter { fiction ->
        val carried = fiction.tags.map { it.trim().lowercase() }.toSet()
        carried.containsAll(wanted)
    }
}

/**
 * Tags in [selected] that nothing on the shelf carries any more.
 *
 * Worth naming rather than silently intersecting: a stored tag whose fiction was deleted would
 * otherwise empty the grid with no box left on screen to un-tick.
 */
fun staleTags(fictions: List<FictionSummary>, selected: Set<String>): Set<String> {
    if (selected.isEmpty()) return emptySet()
    val available = availableTags(fictions).map(String::lowercase).toSet()
    return selected.filterNot { it.lowercase() in available }.toSet()
}

/**
 * The whole browse pipeline, in the order the user thinks about it: scope, then tags, then text,
 * then order.
 *
 * One function so the screen cannot apply them in a different order than the tests do — sorting
 * before filtering gives the same set, but naming an intermediate count ("14 of 200") requires
 * knowing which stage produced which number.
 */
data class BrowseResult(
    val fictions: List<FictionSummary>,
    /** How many survived the tag filter, before the text query. What "N of M" is counted against. */
    val taggedCount: Int,
    val totalCount: Int,
) {
    val isEmpty: Boolean get() = fictions.isEmpty()
}

fun browseFictions(
    fictions: List<FictionSummary>,
    query: String,
    tags: Set<String>,
    sort: FictionSort,
): BrowseResult {
    val tagged = filterByTags(fictions, tags)
    val searched = filterFictionsByText(tagged, query)
    return BrowseResult(
        fictions = sortFictions(searched, sort),
        taggedCount = tagged.size,
        totalCount = fictions.size,
    )
}

/** Case-insensitive across title, author and tags — the same three fields as the mobile client. */
fun filterFictionsByText(fictions: List<FictionSummary>, query: String): List<FictionSummary> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return fictions
    return fictions.filter { fiction ->
        fiction.title.lowercase().contains(q) ||
            fiction.author?.lowercase()?.contains(q) == true ||
            fiction.tags.any { it.lowercase().contains(q) }
    }
}

/**
 * Why the grid is empty, so the screen can say the thing the reader can act on.
 *
 * "No fictions found" is a lie with a tag ticked or on an empty shelf: it reads as the server
 * having lost the library rather than as something one click undoes.
 */
enum class EmptyBrowseReason { NothingOnServer, NothingFollowed, TagFilter, TextQuery }

fun emptyBrowseReason(
    result: BrowseResult,
    query: String,
    tags: Set<String>,
    browsingAll: Boolean,
): EmptyBrowseReason? = when {
    !result.isEmpty -> null
    result.totalCount == 0 && browsingAll -> EmptyBrowseReason.NothingOnServer
    result.totalCount == 0 -> EmptyBrowseReason.NothingFollowed
    // Order matters: with both a tag and a query in force, the tag is the one hiding the most, and
    // it is also the one a reader is most likely to have forgotten is on.
    tags.isNotEmpty() && result.taggedCount == 0 -> EmptyBrowseReason.TagFilter
    query.isNotBlank() -> EmptyBrowseReason.TextQuery
    tags.isNotEmpty() -> EmptyBrowseReason.TagFilter
    else -> EmptyBrowseReason.NothingOnServer
}
