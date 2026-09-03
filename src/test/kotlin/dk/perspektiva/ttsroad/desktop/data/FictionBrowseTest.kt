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
        updatedAt: String? = null,
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
        updatedAt = updatedAt,
        totalChapters = total,
        doneChapters = done,
        progress = progress,
    )

    @Test
    fun `recently updated puts the newest first and the undated last`() {
        val fictions = listOf(
            fiction(1, updatedAt = "2026-01-05T00:00:00Z"),
            fiction(2, updatedAt = null),
            fiction(3, updatedAt = "2026-09-01T00:00:00Z"),
        )

        // The undated row is an older server saying nothing, not a book from 1970.
        assertContentEquals(
            listOf(3, 1, 2),
            sortFictions(fictions, FictionSort.RecentlyUpdated).map { it.id },
        )
    }

    @Test
    fun `every order sorts nulls last`() {
        val known = fiction(1, title = "AAA", author = "Writer", rating = 4.5, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z", total = 10, done = 5, progress = FictionProgress(chaptersTotal = 10, chaptersReady = 10, chaptersPlayed = 2, chaptersUnplayed = 8, remainingSeconds = 900.0))
        val unknown = fiction(2, title = "AAB")

        for (sort in FictionSort.entries) {
            val ordered = sortFictions(listOf(unknown, known), sort)
            if (sort == FictionSort.Title) continue // Title is never null; AAA sorts before AAB anyway.
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
            fiction(1, title = "Zebra", updatedAt = "2026-01-01T00:00:00Z"),
            fiction(2, title = "Alpha", updatedAt = "2026-01-01T00:00:00Z"),
        )

        assertContentEquals(
            listOf(2, 1),
            sortFictions(fictions, FictionSort.RecentlyUpdated).map { it.id },
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
    fun `an unconverted book has no listened fraction rather than zero`() {
        // "0% listened" and "there is nothing to listen to yet" are different sentences, and only
        // one of them is about the reader.
        assertNull(FictionProgress(chaptersTotal = 40, chaptersReady = 0).listenedFraction)
        assertEquals(0.5f, FictionProgress(chaptersReady = 10, chaptersPlayed = 5).listenedFraction)
    }
}
