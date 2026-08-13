package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
import org.junit.jupiter.api.assertThrows
import retrofit2.HttpException

/**
 * The cross-library queue: the payload, the pure ordering rules, and the wire.
 *
 * The distinction pinned hardest here is the one the UI cannot recover from on its own — a 404 is
 * "this server has no shared queue" and answers null, while a 401 is still a dead credential and
 * must end the session exactly as it does everywhere else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerQueueTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "ttsr_token",
                username = "admin",
                deviceId = 42,
            ),
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

    // --- Parsing -----------------------------------------------------------------------------

    @Test
    fun `parses a real cross-fiction queue payload`() {
        val queue = ParsedFixtures.queue

        assertEquals(3, queue.items.size)
        assertEquals(3, queue.total)
        assertEquals("stop", queue.whenEmpty)
        assertEquals(500, queue.maxItems)

        val first = queue.items.first()
        assertEquals(4801, first.id)
        assertEquals(101, first.chapterId)
        assertEquals("A Practical Guide to Sorcery", first.chapterTitle)
        assertEquals("The Wandering Inn", first.fictionTitle)
        assertEquals(1523.4, first.audioDuration)
        assertEquals("25:23", first.audioDurationLabel)
        assertTrue(first.hasTimings)
        assertEquals(240.5, first.positionSeconds)
        assertEquals("https://ttsroad.example.com/audio/the-wandering-inn/0001.mp3", first.audio?.url)
    }

    /** The whole point of a *cross-library* queue: one list, more than one book. */
    @Test
    fun `queue rows span more than one fiction`() {
        val fictionIds = ParsedFixtures.queue.items.map { it.fictionId }.distinct()

        assertEquals(listOf(1, 2), fictionIds)
    }

    /** Row id and chapter id are different namespaces, and a mutation addresses the row. */
    @Test
    fun `row id is not the chapter id`() {
        val first = ParsedFixtures.queue.items.first()

        assertEquals(4801, first.id)
        assertEquals(101, first.chapterId)
    }

    /**
     * The fiction link has to work for a book the user has never opened, which is the normal case
     * for a cross-library queue — so the row carries enough to open it without the library cache.
     */
    @Test
    fun `a row can name its own fiction well enough to open it`() {
        val summary = ParsedFixtures.queue.items[1].toFictionSummary()

        assertEquals(2, summary.id)
        assertEquals("Mother of Learning", summary.title)
        assertEquals("mother-of-learning", summary.slug)
    }

    @Test
    fun `a missing title degrades rather than throwing`() {
        val bare = ServerQueueItem(id = 1, chapterId = 9)

        assertEquals("Untitled chapter", bare.resolvedTitle)
        assertEquals("Unknown fiction", bare.resolvedFictionTitle)
    }

    // --- Ordering ----------------------------------------------------------------------------

    @Test
    fun `moving a row down produces the complete new order`() {
        val items = ParsedFixtures.queue.items

        val moved = items.movedTo(0, 2)

        assertEquals(listOf(4802, 4803, 4801), moved.itemIds())
        assertEquals(items.size, moved.size)
    }

    @Test
    fun `moving a row up produces the complete new order`() {
        val items = ParsedFixtures.queue.items

        val moved = items.movedTo(2, 0)

        assertEquals(listOf(4803, 4801, 4802), moved.itemIds())
    }

    /**
     * An index that has moved underneath the click must answer the *same list*, not a truncated
     * one — a reorder built from a short list would delete the missing rows server-side.
     */
    @Test
    fun `an out-of-range move changes nothing`() {
        val items = ParsedFixtures.queue.items

        assertSame(items, items.movedTo(0, 9))
        assertSame(items, items.movedTo(-1, 0))
        assertSame(items, items.movedTo(1, 1))
    }

    /** A played row has no time left in it, and a partly-heard one has only its remainder. */
    @Test
    fun `remaining time counts only what is left to hear`() {
        val remaining = ParsedFixtures.queue.items.remainingSeconds()

        // (1523.4 - 240.5) + (2044.0 - 0) + 0 for the played row.
        assertEquals(1282.9 + 2044.0, remaining, 0.01)
    }

    // --- The wire ----------------------------------------------------------------------------

    @Test
    fun `queue reads the shared list with the bearer token`() = runTest {
        enqueue(200, ServerFixtures.QUEUE)

        val queue = repository.serverQueue()

        assertEquals(3, queue?.items?.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/queue", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    /** Additive endpoint, so 404 is the only available signal that the server predates it. */
    @Test
    fun `a 404 means this server has no shared queue and is not an error`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        assertNull(repository.serverQueue())
        // "Old server" must never be mistaken for "dead credential".
        assertNotNull(sessionStore.current().token)
    }

    @Test
    fun `a 401 on the queue still ends the session`() = runTest {
        enqueue(401, ServerFixtures.UNAUTHORIZED_TOKEN_REVOKED)

        assertThrows<HttpException> { repository.serverQueue() }

        assertNull(sessionStore.current().token)
    }

    /** An outage is not a revocation, and it is not "the feature does not exist" either. */
    @Test
    fun `a 500 on the queue throws and keeps the session`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        assertThrows<HttpException> { repository.serverQueue() }

        assertNotNull(sessionStore.current().token)
    }

    @Test
    fun `add posts the action and the chapter ids`() = runTest {
        enqueue(200, ServerFixtures.QUEUE)

        repository.updateServerQueue(
            ServerQueueRequest(
                action = ServerQueueAction.Add,
                chapterIds = listOf(101, 102),
                mode = ServerQueueMode.Next,
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/queue", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"action\":\"add\""), body)
        assertTrue(body.contains("\"chapter_ids\":[101,102]"), body)
        assertTrue(body.contains("\"mode\":\"next\""), body)
    }

    /** Reorder sends the whole desired order, addressed by row id — that is what the endpoint takes. */
    @Test
    fun `reorder posts row ids in the desired order`() = runTest {
        enqueue(200, ServerFixtures.QUEUE)

        repository.updateServerQueue(
            ServerQueueRequest(action = ServerQueueAction.Reorder, itemIds = listOf(4803, 4801, 4802)),
        )

        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"action\":\"reorder\""), body)
        assertTrue(body.contains("\"item_ids\":[4803,4801,4802]"), body)
    }

    @Test
    fun `fill names its source and fiction`() = runTest {
        enqueue(200, ServerFixtures.QUEUE)

        repository.updateServerQueue(
            ServerQueueRequest(
                action = ServerQueueAction.Fill,
                source = ServerQueueSource.FictionUnplayed,
                fictionId = 7,
                count = 5,
            ),
        )

        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"action\":\"fill\""), body)
        assertTrue(body.contains("\"source\":\"fiction_unplayed\""), body)
        assertTrue(body.contains("\"fiction_id\":7"), body)
    }

    @Test
    fun `a mutation answers the queue as the server now holds it`() = runTest {
        enqueue(200, ServerFixtures.QUEUE_EMPTY)

        val after = repository.updateServerQueue(ServerQueueRequest(action = ServerQueueAction.Clear))

        assertEquals(emptyList(), after?.items)
        assertEquals("ok", after?.status)
        assertEquals("continue", after?.whenEmpty)
    }

    @Test
    fun `a mutation against a server with no queue answers null`() = runTest {
        enqueue(404, ServerFixtures.NOT_FOUND)

        assertNull(repository.updateServerQueue(ServerQueueRequest(action = ServerQueueAction.Clear)))
    }
}
