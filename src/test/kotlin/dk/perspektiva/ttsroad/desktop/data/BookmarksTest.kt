package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `/api/mobile/bookmarks`: request shape, the manual filter, and what an older server means. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val sessionStore = InMemorySessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "ttsr_token", username = "admin"),
        )
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse(code = code, headers = jsonHeaders, body = body))
    }

    @Test
    fun `listing asks for manual marks only and sends the bearer header`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARKS)

        val bookmarks = repository.bookmarks()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/bookmarks", request.url.encodedPath)
        // The whole reason the default is not null: the same table holds the web player's `auto`
        // jump-back breadcrumbs, and a day of listening writes hundreds of them.
        assertEquals("manual", request.url.queryParameter("kind"))
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
        assertEquals(3, bookmarks?.size)
    }

    @Test
    fun `a fiction filter is sent only when asked for`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARKS)

        repository.bookmarks(fictionId = 7)

        val request = server.takeRequest()
        assertEquals("7", request.url.queryParameter("fiction_id"))
    }

    @Test
    fun `unknown additive fields and a null chapter link both survive parsing`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARKS)

        val bookmarks = repository.bookmarks().orEmpty()

        val orphan = bookmarks.first { it.id == 11 }
        // A chapter can be hard-deleted, and the server clears the link rather than cascading the
        // row away. A non-null assertion anywhere on this path is a crash waiting for that day.
        assertNull(orphan.chapterId)
        assertNull(orphan.fictionId)
        assertFalse(orphan.isPlayable)
        assertTrue(bookmarks.first { it.id == 9 }.isPlayable)
    }

    @Test
    fun `a 404 is an older server, not an error`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        // Null rather than empty: "this server has no bookmarks API" and "you have no bookmarks"
        // are different answers and the UI shows different things for them.
        assertNull(repository.bookmarks())
    }

    @Test
    fun `creating sends the position in seconds and defaults to a manual mark`() = runTest {
        enqueue(201, ServerFixtures.BOOKMARK_CREATED)

        val created = repository.createBookmark(
            BookmarkCreateRequest(chapterId = 101, positionSeconds = 61.25),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/bookmarks", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"chapter_id\":101"), body)
        assertTrue(body.contains("\"position_seconds\":61.25"), body)
        assertTrue(body.contains("\"kind\":\"manual\""), body)
        assertEquals(21, created?.id)
    }

    @Test
    fun `an over-long label is shortened rather than rejected`() = runTest {
        enqueue(201, ServerFixtures.BOOKMARK_CREATED)

        repository.createBookmark(
            BookmarkCreateRequest(
                chapterId = 101,
                positionSeconds = 1.0,
                label = "x".repeat(BookmarkLimits.MaxLabelChars + 50),
            ),
        )

        // The server truncates to exactly this limit anyway, so trimming here turns what would be
        // a rejected request into a slightly shorter bookmark.
        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"label\":\"${"x".repeat(BookmarkLimits.MaxLabelChars)}\""), body)
    }

    @Test
    fun `a negative position is clamped before it is sent`() = runTest {
        enqueue(201, ServerFixtures.BOOKMARK_CREATED)

        repository.createBookmark(BookmarkCreateRequest(chapterId = 101, positionSeconds = -12.0))

        assertTrue(server.takeRequest().bodyText().contains("\"position_seconds\":0.0"))
    }

    @Test
    fun `a patch omits the fields it was not given`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARK_CREATED)

        repository.updateBookmark(9, BookmarkPatchRequest(label = "Renamed"))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/mobile/bookmarks/9", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"label\":\"Renamed\""), body)
        // Absent means "leave it alone" on the server, so a rename must not blank the note.
        assertFalse(body.contains("note"), body)
        assertFalse(body.contains("position_seconds"), body)
    }

    @Test
    fun `an empty string is how a field is cleared`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARK_CREATED)

        repository.updateBookmark(9, BookmarkPatchRequest(label = "", note = ""))

        // `_clean_text` turns a blank into null server-side, which keeps "clear it" expressible
        // without serialising an explicit JSON null that Moshi would drop.
        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"label\":\"\""), body)
        assertTrue(body.contains("\"note\":\"\""), body)
    }

    @Test
    fun `deleting reports success, and a second delete is still a success`() = runTest {
        enqueue(200, ServerFixtures.BOOKMARK_DELETED)
        enqueue(200, ServerFixtures.BOOKMARK_DELETED)

        assertTrue(repository.deleteBookmark(9))
        assertEquals("DELETE", server.takeRequest().method)
        // Idempotent by design: the server answers the first delete's tombstone rather than 404,
        // so the retry after a dropped connection cannot error about an already-gone row.
        assertTrue(repository.deleteBookmark(9))
    }

    @Test
    fun `deleting on a server with no bookmark API reports failure rather than throwing`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        assertFalse(repository.deleteBookmark(9))
    }

    @Test
    fun `the visible list drops tombstones and puts the newest first`() {
        val rows = listOf(
            Bookmark(id = 1, createdAt = "2027-01-01T09:00:00Z"),
            Bookmark(id = 2, createdAt = "2027-01-03T09:00:00Z"),
            Bookmark(id = 3, createdAt = "2027-01-02T09:00:00Z", deletedAt = "2027-01-04T09:00:00Z"),
        )

        // Filtered here rather than trusted from the shape: the server's list query decides whether
        // a soft-deleted row comes back, and a mark that reappears after a refresh is a bug the
        // user reads as "delete does not work".
        assertEquals(listOf(2, 1), rows.visibleBookmarks().map { it.id })
    }

    @Test
    fun `a row falls back through label, chapter title and position for its name`() {
        assertEquals("Chosen", Bookmark(label = "Chosen", chapterTitle = "Chapter 3").displayLabel)
        assertEquals("Chapter 3", Bookmark(label = "  ", chapterTitle = "Chapter 3").displayLabel)
        assertEquals("12:22", Bookmark(positionLabel = "12:22").displayLabel)
        assertEquals("Bookmark", Bookmark().displayLabel)
    }

    @Test
    fun `position converts to whole milliseconds and never goes negative`() {
        assertEquals(742_500L, Bookmark(positionSeconds = 742.5).positionMs)
        assertEquals(0L, Bookmark(positionSeconds = -1.0).positionMs)
    }
}
