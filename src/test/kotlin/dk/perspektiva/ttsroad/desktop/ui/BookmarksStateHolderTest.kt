package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.BookmarkKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The bookmark state machine.
 *
 * Everything the screens rely on is decided here — the manual-only filter, "no API" versus "no
 * bookmarks", the optimistic delete and its rollback — so none of it needs a display to assert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksStateHolderTest {

    private fun holder(repository: FakeRepository) =
        BookmarksStateHolder(repository, UnconfinedTestDispatcher())

    private fun bookmark(id: Int, createdAt: String = "2027-01-0${id}T09:00:00Z") =
        Bookmark(id = id, chapterId = 100 + id, fictionId = 7, createdAt = createdAt)

    @Test
    fun `loading asks only for manual marks`() = runTest {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))

        holder(repository).refresh()

        // The web player's jump-back breadcrumbs share this table as `auto`; a list that mixed
        // them in would bury the handful of marks somebody actually chose.
        assertEquals(listOf<Pair<String?, Int?>>(BookmarkKind.Manual to null), repository.bookmarkListCalls)
    }

    @Test
    fun `ensureLoaded loads once and a second visit costs nothing`() = runTest {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))
        val holder = holder(repository)

        holder.ensureLoaded()
        holder.ensureLoaded()

        assertEquals(1, repository.bookmarkListCalls.size)
    }

    @Test
    fun `tombstones are dropped and the newest mark is first`() = runTest {
        val repository = FakeRepository(
            bookmarksResult = Result.success(
                listOf(
                    bookmark(1, "2027-01-01T09:00:00Z"),
                    bookmark(2, "2027-01-03T09:00:00Z"),
                    bookmark(3, "2027-01-02T09:00:00Z").copy(deletedAt = "2027-01-04T09:00:00Z"),
                ),
            ),
        )
        val holder = holder(repository)

        holder.refresh()

        assertEquals(listOf(2, 1), holder.state.value.bookmarks.map { it.id })
    }

    @Test
    fun `a null answer is an older server rather than an empty list`() = runTest {
        val holder = holder(FakeRepository(bookmarksResult = Result.success(null)))

        holder.refresh()

        val state = holder.state.value
        assertTrue(state.unsupported)
        // Kept out of `error` on purpose: a Retry button on this would never succeed.
        assertNull(state.error)
        assertTrue(state.loaded)
    }

    @Test
    fun `a failed refresh keeps the list already on screen`() = runTest {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))
        val holder = holder(repository)
        holder.refresh()

        repository.bookmarksResult = Result.failure(java.io.IOException("offline"))
        holder.refresh()

        val state = holder.state.value
        assertNotNull(state.error)
        // Same rule as a failed library refresh: a banner over retained content explains more than
        // a screen that has been blanked.
        assertEquals(listOf(1), state.bookmarks.map { it.id })
    }

    @Test
    fun `adding sends the position in seconds and prepends the result`() = runTest {
        val repository = FakeRepository(
            bookmarksResult = Result.success(listOf(bookmark(1))),
            createBookmarkResult = Result.success(Bookmark(id = 50, positionLabel = "1:01")),
        )
        val holder = holder(repository)
        holder.refresh()

        holder.add(chapterId = 101, positionMs = 61_250L, label = "  A passage  ")

        val request = repository.createdBookmarks.single()
        assertEquals(101, request.chapterId)
        assertEquals(61.25, request.positionSeconds)
        assertEquals("A passage", request.label)
        assertEquals(BookmarkKind.Manual, request.kind)
        val state = holder.state.value
        // Prepended rather than re-fetched: the list is already right, and a round trip would make
        // the confirmation arrive after it.
        assertEquals(listOf(50, 1), state.bookmarks.map { it.id })
        assertEquals("Bookmarked at 1:01", state.notice)
    }

    @Test
    fun `a blank label is sent as no label at all`() = runTest {
        val repository = FakeRepository()

        holder(repository).add(chapterId = 101, positionMs = 1_000L, label = "   ")

        // Null, not "": an empty string is the server's instruction to *clear* a value, which is a
        // different thing from never having set one.
        assertNull(repository.createdBookmarks.single().label)
    }

    @Test
    fun `adding does nothing without a chapter`() = runTest {
        val repository = FakeRepository()

        holder(repository).add(chapterId = 0, positionMs = 1_000L)

        assertTrue(repository.createdBookmarks.isEmpty())
    }

    @Test
    fun `a failed add says so instead of pretending it saved`() = runTest {
        val repository = FakeRepository(createBookmarkResult = Result.failure(java.io.IOException("offline")))
        val holder = holder(repository)

        holder.add(chapterId = 101, positionMs = 1_000L)

        assertTrue(holder.state.value.bookmarks.isEmpty())
        assertNotNull(holder.state.value.notice)
    }

    @Test
    fun `editing sends both fields so a cleared note stays cleared`() = runTest {
        val repository = FakeRepository(
            bookmarksResult = Result.success(listOf(bookmark(1))),
            updateBookmarkResult = Result.success(Bookmark(id = 1)),
        )
        val holder = holder(repository)
        holder.refresh()

        holder.edit(bookmarkId = 1, label = "Renamed", note = "")

        val (id, patch) = repository.patchedBookmarks.single()
        assertEquals(1, id)
        assertEquals("Renamed", patch.label)
        // Present-and-empty, never absent: absent means "leave it alone" on the server, so a note
        // edited to nothing would come straight back.
        assertEquals("", patch.note)
        assertEquals("Renamed", holder.state.value.bookmarks.single().label)
    }

    @Test
    fun `removing takes the row out immediately`() = runTest {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1), bookmark(2))))
        val holder = holder(repository)
        holder.refresh()

        holder.remove(1)

        assertEquals(listOf(2), holder.state.value.bookmarks.map { it.id })
        assertEquals(listOf(1), repository.deletedBookmarks)
        assertEquals("Bookmark removed", holder.state.value.notice)
    }

    @Test
    fun `a failed remove puts the row back where it was`() = runTest {
        val repository = FakeRepository(
            bookmarksResult = Result.success(listOf(bookmark(3), bookmark(2), bookmark(1))),
            deleteBookmarkResult = Result.failure(java.io.IOException("offline")),
        )
        val holder = holder(repository)
        holder.refresh()

        holder.remove(2)

        // Back in its own place, not appended: a failed delete must not also reorder the list
        // under the user's cursor.
        assertEquals(listOf(3, 2, 1), holder.state.value.bookmarks.map { it.id })
        assertNotNull(holder.state.value.notice)
    }

    @Test
    fun `signing out drops the account's marks`() = runTest {
        val repository = FakeRepository(bookmarksResult = Result.success(listOf(bookmark(1))))
        val holder = holder(repository)
        holder.refresh()

        holder.sessionEnded()

        val state = holder.state.value
        assertTrue(state.bookmarks.isEmpty())
        // Not "loaded and empty" — the next account to sign in on this desktop must trigger a load
        // rather than be shown the previous one's empty state.
        assertFalse(state.loaded)
    }
}
