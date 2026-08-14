package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeltaSyncTest {

    @Test
    fun `a library delta replaces changed rows consumes tombstones and replaces the complete rails`() {
        val before = LibraryResponse(
            scope = "followed",
            serverTime = "2026-08-14T10:00:00Z",
            fictions = listOf(FictionSummary(id = 1, title = "Delete me"), FictionSummary(id = 2, title = "Old")),
            continueListening = listOf(ChapterSummary(id = 10)),
        )
        val delta = LibraryResponse(
            scope = "followed",
            serverTime = "2026-08-14T11:00:00Z",
            updatedSince = before.serverTime,
            delta = true,
            deleted = listOf(1),
            fictions = listOf(FictionSummary(id = 2, title = "Updated"), FictionSummary(id = 3, title = "Added")),
            continueListening = listOf(ChapterSummary(id = 30)),
        )

        val merged = before.mergedWith(delta)

        assertEquals(listOf(2, 3), merged.fictions.map { it.id })
        assertEquals("Updated", merged.fictions.first().title)
        assertEquals(listOf(30), merged.continueListening.map { it.id })
        assertEquals("2026-08-14T11:00:00Z", merged.serverTime)
        assertFalse(merged.delta)
        assertTrue(merged.deleted.isEmpty())
    }

    @Test
    fun `a full response from an older library endpoint replaces rather than merges`() {
        val before = LibraryResponse(fictions = listOf(FictionSummary(id = 1)))
        val full = LibraryResponse(fictions = listOf(FictionSummary(id = 9)))

        assertEquals(listOf(9), before.mergedWith(full).fictions.map { it.id })
    }

    @Test
    fun `complete follow membership removes an unfollowed row and detects a newly followed unknown row`() {
        val before = LibraryResponse(
            scope = "followed",
            followingIds = listOf(1, 2),
            fictions = listOf(FictionSummary(id = 1), FictionSummary(id = 2)),
        )
        val unfollow = LibraryResponse(scope = "followed", delta = true, followingIds = listOf(1))
        val newFollow = LibraryResponse(scope = "followed", delta = true, followingIds = listOf(1, 2, 3))

        assertEquals(listOf(1), before.mergedWith(unfollow).fictions.map { it.id })
        assertFalse(before.deltaNeedsFullFollowPull(unfollow))
        assertTrue(before.deltaNeedsFullFollowPull(newFollow))
    }

    @Test
    fun `a chapter delta replaces adds deletes and keeps canonical reading order`() {
        val fiction = FictionSummary(id = 7, title = "Serial")
        val before = ChaptersResponse(
            fiction = fiction,
            serverTime = "2026-08-14T10:00:00Z",
            total = 3,
            chapters = listOf(
                ChapterSummary(id = 101, fictionId = 7, title = "One", displayNumber = 1.0),
                ChapterSummary(id = 102, fictionId = 7, title = "Old two", displayNumber = 2.0),
                ChapterSummary(id = 103, fictionId = 7, title = "Delete", displayNumber = 3.0),
            ),
        )
        val delta = ChaptersResponse(
            fiction = fiction.copy(title = "Renamed serial"),
            serverTime = "2026-08-14T11:00:00Z",
            updatedSince = before.serverTime,
            delta = true,
            deleted = listOf(103),
            total = 2,
            chapters = listOf(
                ChapterSummary(id = 102, fictionId = 7, title = "New two", displayNumber = 2.0),
                ChapterSummary(id = 104, fictionId = 7, title = "Four", displayNumber = 4.0),
            ),
        )

        val merged = before.mergedWith(delta)

        assertEquals(listOf(101, 102, 104), merged.chapters.map { it.id })
        assertEquals("New two", merged.chapters[1].title)
        assertEquals("Renamed serial", merged.fiction.title)
        assertEquals(3, merged.total)
        assertFalse(merged.delta)
        assertTrue(merged.deleted.isEmpty())
    }

    @Test
    fun `a chapter delta for another fiction is rejected instead of corrupting the cache`() {
        val before = ChaptersResponse(fiction = FictionSummary(id = 7))
        val wrong = ChaptersResponse(fiction = FictionSummary(id = 8), delta = true)

        assertThrows<IllegalArgumentException> { before.mergedWith(wrong) }
    }

    @Test
    fun `the index distinguishes bookmark-only work from library work`() {
        val bookmarks = DeltaSyncResponse(delta = true, changed = DeltaSyncChanged(bookmarks = 1))
        val playback = DeltaSyncResponse(delta = true, changed = DeltaSyncChanged(playback = 1))

        assertFalse(bookmarks.changesLibrary)
        assertTrue(playback.changesLibrary)
    }
}
