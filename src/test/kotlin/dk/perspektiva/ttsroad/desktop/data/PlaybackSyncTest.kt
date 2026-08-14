package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** The outbox, in memory, so a test never touches the user's real config directory. */
private class RecordingOutbox(initial: List<PendingProgress> = emptyList()) : ProgressOutboxStore {
    private val _entries = MutableStateFlow(initial)
    override val entries: StateFlow<List<PendingProgress>> = _entries
    var cleared: Boolean = false
        private set

    override fun record(entry: PendingProgress) {
        _entries.value = ProgressOutbox.record(_entries.value, entry)
    }

    override fun drop(chapterIds: Collection<Int>) {
        _entries.value = ProgressOutbox.drop(_entries.value, chapterIds)
    }

    override fun clear() {
        cleared = true
        _entries.value = emptyList()
    }
}

/**
 * `/playback/sync` on the wire: what gets sent, what gets kept, and what a losing write does.
 *
 * The point of the endpoint is that an offline position must not overwrite a newer one, so these
 * assert the ordering guarantees rather than just that a request was made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var outbox: RecordingOutbox

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
            ),
        )
        outbox = RecordingOutbox()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse(code = code, headers = jsonHeaders, body = body))
    }

    private fun capabilities(batchProgress: Boolean, maxItems: Int? = null): String {
        val limits = buildString {
            append("""{"max_chapters_per_page": 200""")
            if (maxItems != null) append(""", "max_playback_sync_items": $maxItems""")
            append("}")
        }
        return """
            {
              "api_version": 1,
              "server": {"name": "TTSRoad", "version": "1.5.0", "base_url": "${server.url("/")}"},
              "capabilities": {"batch_progress": $batchProgress},
              "limits": $limits
            }
        """.trimIndent()
    }

    private suspend fun repositoryWith(
        batchProgress: Boolean,
        maxItems: Int? = null,
        stamp: String = "2026-08-11T10:00:00.000Z",
    ): RetrofitTtsRoadRepository {
        val repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
            progressOutbox = outbox,
            stamp = { stamp },
        )
        enqueue(200, capabilities(batchProgress, maxItems))
        repository.refreshCurrentCapabilities(forceRefresh = true)
        server.takeRequest()
        return repository
    }

    @Test
    fun `a capable server gets a timestamped batch`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        enqueue(
            200,
            """{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}""",
        )

        repository.saveProgress(fictionId = 1, chapterId = 7, positionSeconds = 412.5, isPlayed = false)

        val request = server.takeRequest()
        assertEquals("/api/mobile/playback/sync", request.url.encodedPath)
        val body = request.bodyText()
        assertContains(body, """"chapter_id":7""")
        assertContains(body, """"position_seconds":412.5""")
        assertContains(body, """"client_updated_at":"2026-08-11T10:00:00.000Z"""")
    }

    @Test
    fun `an accepted position leaves the queue`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        enqueue(200, """{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}""")

        repository.saveProgress(1, 7, 412.5, false)

        assertTrue(outbox.entries.value.isEmpty())
    }

    /**
     * Every rejection reason is terminal for that item, so a rejected write is dropped rather than
     * retried — otherwise the queue would grow forever re-sending something guaranteed to be
     * refused.
     */
    @Test
    fun `a rejected position also leaves the queue`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        enqueue(
            200,
            """{"accepted": [], "rejected": [{"chapter_id": 7, "reason": "stale"}], "server_state": []}""",
        )

        repository.saveProgress(1, 7, 100.0, false)

        assertTrue(outbox.entries.value.isEmpty())
    }

    /** The user-visible half of #36: the browser's newer position wins and is what resumes. */
    @Test
    fun `a losing write picks up the server's newer position`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        enqueue(
            200,
            """
            {
              "accepted": [],
              "rejected": [{"chapter_id": 7, "reason": "stale"}],
              "server_state": [{"chapter_id": 7, "position_seconds": 4820.0, "is_played": false}]
            }
            """.trimIndent(),
        )

        repository.saveProgress(1, 7, 100.0, false)

        assertEquals(4820.0, repository.serverPlaybackState.value[7]?.positionSeconds)
    }

    @Test
    fun `an older server gets the single-item endpoint instead`() = runTest {
        val repository = repositoryWith(batchProgress = false)
        enqueue(200, """{"status": "saved", "chapter_id": 7}""")

        repository.saveProgress(1, 7, 412.5, false)

        assertEquals("/api/mobile/playback/progress", server.takeRequest().url.encodedPath)
        assertTrue(outbox.entries.value.isEmpty())
    }

    /** An oversized batch is answered with a 400 rather than truncated, so the limit is obeyed. */
    @Test
    fun `batches are split at the server's published limit`() = runTest {
        val repository = repositoryWith(batchProgress = true, maxItems = 2)
        repeat(3) { enqueue(200, """{"accepted": [], "rejected": [], "server_state": []}""") }
        repeat(5) { outbox.record(PendingProgress(1, it + 1, 10.0, false, "2026-08-11T10:00:00.000Z")) }

        repository.flushProgress()

        val sizes = (1..3).map { server.takeRequest().bodyText().split("\"chapter_id\"").size - 1 }
        assertEquals(listOf(2, 2, 1), sizes)
    }

    /** Transport failure is the one case the queue must survive — that is the whole point. */
    @Test
    fun `a server error keeps the position queued for the next attempt`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        enqueue(500, """{"detail": "boom"}""")

        // The failure propagates — the playback controller treats it as non-fatal — but the
        // position it was carrying is still on the queue for the next attempt.
        assertThrows<HttpException> { repository.saveProgress(1, 7, 412.5, false) }

        assertEquals(listOf(7), outbox.entries.value.map { it.chapterId })
        assertTrue(!outbox.cleared)
    }

    @Test
    fun `a dead credential drops the queue rather than carrying it forever`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        outbox.record(PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z"))
        enqueue(401, """{"detail": "Not authenticated"}""")

        assertThrows<HttpException> { repository.flushProgress() }

        assertTrue(outbox.cleared)
        assertTrue(outbox.entries.value.isEmpty())
    }

    @Test
    fun `flushing an empty queue makes no request at all`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        val afterDiscovery = server.requestCount

        repository.flushProgress()

        assertEquals(afterDiscovery, server.requestCount)
    }
}
