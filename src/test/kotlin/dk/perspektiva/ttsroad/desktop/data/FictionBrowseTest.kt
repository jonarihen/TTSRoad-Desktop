package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ordering and filtering, away from the composable that draws them.
 *
 * The recurring rule here is what an *absent* answer means. A server that never sent `updated_at`,
 * a fiction nobody rated, a row with no progress aggregate — each is saying "we were not told",
 * which is not "a long time ago" or "zero". Most of these cases exist to pin that nulls sort last.
 */
class FictionBrowseTest {

    private fun fiction(
        id: Int,
        title: String = "Fiction $id",
        author: String? = null,
        tags: List<String> = emptyList(),
        rating: Double? = null,
        createdAt: String? = null,
        lastChapterAt: String? = null,
        total: Int = 0,
        done: Int = 0,
        progress: FictionProgress? = null,
    ) = FictionSummary(
        id = id,
        title = title,
        author = author,
        tags = tags,
        rating = rating,
        createdAt = createdAt,
        lastChapterAt = lastChapterAt,
        totalChapters = total,
        doneChapters = done,
        progress = progress,
    )

    @Test
    fun `new chapters puts the newest first and the undated last`() {
        val fictions = listOf(
            fiction(1, lastChapterAt = "2026-01-05T00:00:00Z"),
            fiction(2, lastChapterAt = null),
            fiction(3, lastChapterAt = "2026-09-01T00:00:00Z"),
        )

        // The undated row is an older server saying nothing, not a book from 1970.
        assertContentEquals(
            listOf(3, 1, 2),
            sortFictions(fictions, FictionSort.NewChapters).map { it.id },
        )
    }

    @Test
    fun `every order sorts nulls last`() {
        val known = fiction(
            1, title = "Zebra", author = "Writer", rating = 4.5,
            createdAt = "2026-01-01T00:00:00Z", lastChapterAt = "2026-01-01T00:00:00Z", total = 10, done = 5,
            progress = FictionProgress(
                chaptersTotal = 10, chaptersReady = 10, chaptersPlayed = 2, chaptersUnplayed = 8,
                remainingSeconds = 900.0, lastListenedAt = "2026-01-01T00:00:00Z",
            ),
        )
        val unknown = fiction(2, title = "Alpha")

        for (sort in FictionSort.entries) {
            val ordered = sortFictions(listOf(unknown, known), sort)
            if (sort == FictionSort.Title) continue
            assertEquals(
                1,
                ordered.first().id,
                "$sort put the row with no answer ahead of the row with one",
            )
        }
    }

    @Test
    fun `ties break on title so the order is total`() {
        // A grid whose items swap places between two recompositions of the same data is a scroll
        // position that will not stay still.
        val fictions = listOf(
            fiction(1, title = "Zebra", lastChapterAt = "2026-01-01T00:00:00Z"),
            fiction(2, title = "Alpha", lastChapterAt = "2026-01-01T00:00:00Z"),
        )

        assertContentEquals(
            listOf(2, 1),
            sortFictions(fictions, FictionSort.NewChapters).map { it.id },
        )
    }

    @Test
    fun `least finished ranks by the share heard, not the time left`() {
        val longBookBarelyStarted = fiction(
            1,
            progress = FictionProgress(chaptersReady = 400, chaptersPlayed = 4, chaptersUnplayed = 396, remainingSeconds = 400_000.0),
        )
        val shortBookHalfDone = fiction(
            2,
            progress = FictionProgress(chaptersReady = 12, chaptersPlayed = 6, chaptersUnplayed = 6, remainingSeconds = 6_000.0),
        )

        assertContentEquals(
            listOf(1, 2),
            sortFictions(listOf(shortBookHalfDone, longBookBarelyStarted), FictionSort.LeastFinished).map { it.id },
        )
        // Most left to hear is about absolute time, which is why the two orders are both offered.
        assertContentEquals(
            listOf(1, 2),
            sortFictions(listOf(shortBookHalfDone, longBookBarelyStarted), FictionSort.MostLeft).map { it.id },
        )
    }

    @Test
    fun `percent converted is about the pipeline, not about listening`() {
        val twoOfFourHundred = fiction(1, total = 400, done = 2)
        val twelveOfTwelve = fiction(2, total = 12, done = 12)

        assertContentEquals(
            listOf(2, 1),
            sortFictions(listOf(twoOfFourHundred, twelveOfTwelve), FictionSort.PercentConverted).map { it.id },
        )
        // ...while the same pair is ordered the other way by size.
        assertContentEquals(
            listOf(1, 2),
            sortFictions(listOf(twelveOfTwelve, twoOfFourHundred), FictionSort.MostChapters).map { it.id },
        )
    }

    @Test
    fun `two ticked tags mean both, not either`() {
        val both = fiction(1, tags = listOf("LitRPG", "Fantasy"))
        val one = fiction(2, tags = listOf("LitRPG"))
        val neither = fiction(3, tags = listOf("Romance"))
        val fictions = listOf(both, one, neither)

        // A filter that widens the list as you add to it is one nobody uses twice.
        assertContentEquals(
            listOf(1),
            filterByTags(fictions, setOf("LitRPG", "Fantasy")).map { it.id },
        )
        assertContentEquals(listOf(1, 2), filterByTags(fictions, setOf("LitRPG")).map { it.id })
        assertEquals(fictions, filterByTags(fictions, emptySet()))
    }

    @Test
    fun `tag matching and the tag list ignore case and spacing`() {
        val fictions = listOf(
            fiction(1, tags = listOf(" LitRPG ")),
            fiction(2, tags = listOf("litrpg")),
        )

        assertContentEquals(listOf(1, 2), filterByTags(fictions, setOf("LITRPG")).map { it.id })
        // One entry, keeping the first spelling seen, like `cleanFictionTags`.
        assertContentEquals(listOf("LitRPG"), availableTags(fictions))
    }

    @Test
    fun `a ticked tag nothing carries any more is reported as stale`() {
        val fictions = listOf(fiction(1, tags = listOf("LitRPG")))

        // Otherwise it empties the grid with no box left on screen to un-tick.
        assertEquals(setOf("Romance"), staleTags(fictions, setOf("LitRPG", "Romance")))
        assertTrue(staleTags(fictions, emptySet()).isEmpty())
    }

    @Test
    fun `the pipeline counts the tag stage separately from the text stage`() {
        val fictions = listOf(
            fiction(1, title = "Inn", tags = listOf("LitRPG")),
            fiction(2, title = "Tower", tags = listOf("LitRPG")),
            fiction(3, title = "Romance", tags = listOf("Romance")),
        )

        val result = browseFictions(fictions, query = "inn", tags = setOf("LitRPG"), sort = FictionSort.Title)

        assertContentEquals(listOf(1), result.fictions.map { it.id })
        assertEquals(2, result.taggedCount, "\"N of M\" counts against the tag stage")
        assertEquals(3, result.totalCount)
    }

    @Test
    fun `an empty grid names the thing that emptied it`() {
        val tagged = listOf(fiction(1, tags = listOf("LitRPG")))

        assertEquals(
            EmptyBrowseReason.TagFilter,
            emptyBrowseReason(
                browseFictions(tagged, "", setOf("Romance"), FictionSort.Title),
                query = "",
                tags = setOf("Romance"),
                browsingAll = true,
            ),
        )
        assertEquals(
            EmptyBrowseReason.TextQuery,
            emptyBrowseReason(
                browseFictions(tagged, "nothing", emptySet(), FictionSort.Title),
                query = "nothing",
                tags = emptySet(),
                browsingAll = true,
            ),
        )
        // An empty shelf and an empty server read very differently to the person looking at them.
        assertEquals(
            EmptyBrowseReason.NothingFollowed,
            emptyBrowseReason(
                browseFictions(emptyList(), "", emptySet(), FictionSort.Title),
                query = "",
                tags = emptySet(),
                browsingAll = false,
            ),
        )
        assertEquals(
            EmptyBrowseReason.NothingOnServer,
            emptyBrowseReason(
                browseFictions(emptyList(), "", emptySet(), FictionSort.Title),
                query = "",
                tags = emptySet(),
                browsingAll = true,
            ),
        )
        assertNull(
            emptyBrowseReason(
                browseFictions(tagged, "", emptySet(), FictionSort.Title),
                query = "",
                tags = emptySet(),
                browsingAll = true,
            ),
        )
    }

    @Test
    fun `date orders parse offsets fractions and naive UTC and put malformed dates last`() {
        val dates = listOf(
            1 to "2026-09-01T12:00:00+02:00",
            2 to "2026-09-01T10:30:00Z",
            3 to "2026-09-01T10:30:00.123456Z",
            4 to "2026-09-01T10:45:00",
            5 to "not-a-date",
            6 to null,
            7 to " ",
            8 to "2026-13-01T00:00:00Z",
        )
        for (sort in listOf(FictionSort.NewChapters, FictionSort.RecentlyListened, FictionSort.RecentlyAdded)) {
            val fictions = dates.map { (id, date) ->
                fiction(
                    id, lastChapterAt = date, createdAt = date,
                    progress = FictionProgress(lastListenedAt = date),
                )
            }.reversed()
            assertEquals(listOf(4, 3, 2, 1, 5, 6, 7, 8), sortFictions(fictions, sort).map { it.id }, sort.name)
        }
    }

    @Test
    fun `new chapters ignores polling and recently listened reads only caller progress`() {
        val fictions = listOf(
            fiction(1, lastChapterAt = "2026-09-01T00:00:00Z")
                .copy(updatedAt = "2026-09-24T00:00:00Z", lastPolledAt = "2026-09-24T00:00:00Z"),
            fiction(
                2, lastChapterAt = "2026-09-20T00:00:00Z",
                progress = FictionProgress(lastListenedAt = "2026-09-21T00:00:00Z"),
            ),
            fiction(3, progress = FictionProgress(lastListenedAt = "2026-09-23T00:00:00Z")),
            fiction(4).copy(updatedAt = "2099-01-01T00:00:00Z"),
        )
        assertEquals(listOf(2, 1, 3, 4), sortFictions(fictions, FictionSort.NewChapters).map { it.id })
        assertEquals(listOf(3, 2, 1, 4), sortFictions(fictions, FictionSort.RecentlyListened).map { it.id })
    }

    @Test
    fun `matching titles break ties by id regardless of payload order`() {
        val fictions = listOf(fiction(3, "ALPHA"), fiction(1, "alpha"), fiction(2, "Alpha"))
        for (sort in FictionSort.entries) {
            assertEquals(listOf(1, 2, 3), sortFictions(fictions, sort).map { it.id }, sort.name)
            assertEquals(listOf(1, 2, 3), sortFictions(fictions.reversed(), sort).map { it.id }, sort.name)
        }
    }

    @Test
    fun `sources prefer server labels and legacy names without changing adapter identity`() {
        val fictions = listOf(
            fiction(1).copy(sourceType = "ao3", sourceLabel = "ao3"),
            fiction(2).copy(sourceType = "ao3", sourceLabel = " Archive of Our Own "),
            fiction(3).copy(sourceType = "royalroad", sourceLabel = "Royal Road Fiction"),
            fiction(4).copy(sourceType = "epub", sourceLabel = " "),
            fiction(5).copy(sourceType = "patreon"),
            fiction(6).copy(sourceType = "new-adapter"),
            fiction(7),
            fiction(8).copy(sourceType = " ", sourceLabel = "Unidentified site"),
        )
        val expected = listOf(
            SourceOption("ao3", "Archive of Our Own"),
            SourceOption("epub", "EPUB"),
            SourceOption("new-adapter", "new-adapter"),
            SourceOption("patreon", "Patreon"),
            SourceOption("royalroad", "Royal Road Fiction"),
            SourceOption(UnknownSourceKey, "Unknown"),
        )
        assertEquals(expected, availableSources(fictions))
        assertEquals(expected, availableSources(fictions.reversed()))
        assertEquals("Archive of Our Own", fictions[1].sourceTypeLabel)
        assertEquals("Royal Road Fiction", fictions[2].sourceTypeLabel)
        assertEquals("EPUB", fictions[3].sourceTypeLabel)
        assertEquals("Patreon", fictions[4].sourceTypeLabel)
        assertEquals("new-adapter", fictions[5].sourceTypeLabel)
        assertNull(fictions[6].sourceTypeLabel)
        assertEquals("Royal Road", fiction(9).copy(sourceType = "royalroad").sourceTypeLabel)
    }

    @Test
    fun `source selection is a union with a reachable unknown bucket`() {
        val fictions = listOf(
            fiction(1).copy(sourceType = "royalroad"),
            fiction(2).copy(sourceType = "epub"),
            fiction(3),
            fiction(4).copy(sourceType = "unknown"),
            fiction(5).copy(sourceType = " \t"),
        )
        assertEquals(fictions, filterBySources(fictions, emptySet()))
        assertEquals(listOf(1, 2), filterBySources(fictions, setOf("royalroad", "epub")).map { it.id })
        assertEquals(listOf(3, 5), filterBySources(fictions, setOf(UnknownSourceKey)).map { it.id })
        assertEquals(listOf(4), filterBySources(fictions, setOf("unknown")).map { it.id })
        assertTrue(filterBySources(fictions, setOf("EPUB")).isEmpty())
        assertEquals(fictions, filterBySources(fictions, availableSources(fictions).map { it.key }.toSet()))
    }

    @Test
    fun `missing selections stay reachable and renamed labels keep their selected key`() {
        val selected = setOf("ao3", UnknownSourceKey)
        val shelf = listOf(fiction(1, tags = listOf("Fantasy")).copy(sourceType = "epub"))
        assertEquals(setOf("epub", "ao3", UnknownSourceKey), availableSources(shelf, selected).map { it.key }.toSet())
        assertEquals(setOf("Fantasy", "Romance"), availableTags(shelf, setOf("Romance")).toSet())
        assertEquals(listOf("Romance"), availableTags(emptyList(), setOf("Romance")))
        assertEquals(selected, availableSources(emptyList(), selected).map { it.key }.toSet())
        val renamed = fiction(2).copy(sourceType = "ao3", sourceLabel = "New server label")
        assertEquals(listOf(renamed), filterBySources(listOf(renamed), selected))
        assertEquals(SourceOption("ao3", "New server label"), availableSources(listOf(renamed), selected).first())
    }

    @Test
    fun `sources compose with tag intersection text and order while retaining stage counts`() {
        val fictions = listOf(
            fiction(1, "Tower B", tags = listOf("Fantasy", "LitRPG")).copy(sourceType = "royalroad"),
            fiction(2, "Tower A", tags = listOf("Fantasy", "LitRPG")).copy(sourceType = "epub"),
            fiction(3, "Tower C", tags = listOf("Fantasy")).copy(sourceType = "epub"),
            fiction(4, "Tower D", tags = listOf("Fantasy", "LitRPG")).copy(sourceType = "ao3"),
            fiction(5, "Inn", tags = listOf("Fantasy", "LitRPG")).copy(sourceType = "epub"),
        )
        val result = browseFictions(
            fictions, "tower", setOf("Fantasy", "LitRPG"), FictionSort.Title, setOf("royalroad", "epub"),
        )
        assertEquals(listOf(2, 1), result.fictions.map { it.id })
        assertEquals(5, result.totalCount)
        assertEquals(4, result.taggedCount)
        assertEquals(3, result.sourcedCount)
        assertNull(emptyBrowseReason(result, "tower", setOf("Fantasy", "LitRPG"), true, setOf("epub")))
        assertEquals(
            EmptyBrowseReason.SourceFilter,
            emptyBrowseReason(
                browseFictions(fictions, "", emptySet(), FictionSort.Title, setOf(UnknownSourceKey)),
                "", emptySet(), true, setOf(UnknownSourceKey),
            ),
        )
        assertEquals(
            EmptyBrowseReason.TagFilter,
            emptyBrowseReason(
                browseFictions(fictions, "", setOf("Romance"), FictionSort.Title, setOf("epub")),
                "", setOf("Romance"), true, setOf("epub"),
            ),
        )
        assertEquals(
            EmptyBrowseReason.TextQuery,
            emptyBrowseReason(
                browseFictions(fictions, "nothing", emptySet(), FictionSort.Title, setOf("epub")),
                "nothing", emptySet(), true, setOf("epub"),
            ),
        )
    }

    @Test
    fun `an unconverted book has no listened fraction rather than zero`() {
        // "0% listened" and "there is nothing to listen to yet" are different sentences, and only
        // one of them is about the reader.
        assertNull(FictionProgress(chaptersTotal = 40, chaptersReady = 0).listenedFraction)
        assertEquals(0.5f, FictionProgress(chaptersReady = 10, chaptersPlayed = 5).listenedFraction)
    }
}
