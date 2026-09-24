package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import retrofit2.HttpException

/** The outbox, in memory, so a test never touches the user's real config directory. */
private class RecordingOutbox(initial: List<PendingProgress> = emptyList()) : ProgressOutboxStore {
    private val _entries = MutableStateFlow(initial)
    override val entries: StateFlow<List<PendingProgress>> = _entries
    var cleared: Boolean = false
        private set

    override var owner: String? = null
        private set

    @Synchronized
    override fun bindOwner(owner: String) {
        if (this.owner != owner) {
            _entries.value = emptyList()
            this.owner = owner
        }
    }

    @Synchronized
    override fun migrateOwner(previousOwner: String, owner: String): Boolean {
        if (this.owner != previousOwner) return false
        this.owner = owner
        return true
    }

    @Synchronized
    override fun record(entry: PendingProgress) {
        check(owner != null)
        _entries.value = ProgressOutbox.record(_entries.value, entry)
    }

    @Synchronized
    override fun drop(sent: Collection<PendingProgress>) {
        _entries.value = ProgressOutbox.drop(_entries.value, sent)
    }

    @Synchronized
    override fun clear() {
        cleared = true
        owner = null
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

    @TempDir
    lateinit var tempDir: File

    private class RequestGate(private val path: String, private val beforeSend: Boolean = false) : Interceptor {
        val entered = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            if (chain.request().url.encodedPath != path || !first.compareAndSet(true, false)) {
                return chain.proceed(chain.request())
            }
            if (beforeSend) awaitRelease()
            val response = chain.proceed(chain.request())
            if (!beforeSend) {
                try {
                    awaitRelease()
                } catch (e: Exception) {
                    response.close()
                    throw e
                }
            }
            return response
        }

        private fun awaitRelease() {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Request gate timed out" }
        }

        fun open() = release.countDown()
    }

    private fun clientWith(gate: RequestGate): OkHttpClient = authedClient(sessionStore).newBuilder().apply {
        interceptors().add(0, gate)
    }.build()

    private suspend fun signIn(
        repository: RetrofitTtsRoadRepository,
        username: String = "other",
        token: String = "ttsr_new",
        batchProgress: Boolean = true,
    ) {
        enqueue(200, """{"token":"$token","user":{"id":1,"username":"$username","is_admin":false}}""")
        enqueue(200, capabilities(batchProgress))
        assertEquals(LoginResult.Success, repository.login(server.url("/").toString(), username, "password"))
        assertEquals("/api/mobile/login", server.takeRequest().url.encodedPath)
        assertEquals("/api/mobile/capabilities", server.takeRequest().url.encodedPath)
    }

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
        outbox = RecordingOutbox().apply {
            bindOwner(StorageIdentity.of(sessionStore.current().serverUrl, username = "admin").relativePath)
        }
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
        client: OkHttpClient = authedClient(sessionStore),
        progressOutbox: ProgressOutboxStore = outbox,
        stampProvider: () -> String = { stamp },
    ): RetrofitTtsRoadRepository {
        val repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = client,
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
            progressOutbox = progressOutbox,
            stamp = stampProvider,
        )
        if (sessionStore.current().isLoggedIn) {
            enqueue(200, capabilities(batchProgress, maxItems))
            repository.refreshCurrentCapabilities(forceRefresh = true)
            server.takeRequest()
        }
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
    fun `batch acknowledgements never erase a newer generation or publish its stale server state`() = runTest {
        val responses = listOf(
            """{"accepted":[{"chapter_id":7}]}""",
            """{"rejected":[{"chapter_id":7,"reason":"stale"}]}""",
            """{"server_state":[{"chapter_id":7,"position_seconds":800.0}]}""",
        )
        for (response in responses) {
            val gate = RequestGate("/api/mobile/playback/sync")
            val repository = repositoryWith(true, client = clientWith(gate))
            val sent = PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z")
            outbox.record(sent)
            enqueue(200, response)
            val flush = async(UnconfinedTestDispatcher(testScheduler)) { repository.flushProgress() }
            try {
                gate.entered.await()
                server.takeRequest()
                val replacement = sent.copy(positionSeconds = 20.0, generation = "next-${sent.generation}")
                outbox.record(replacement)
                gate.open()
                flush.await()
                assertEquals(listOf(replacement), outbox.entries.value)
                assertTrue(repository.serverPlaybackState.value.isEmpty())
            } finally {
                gate.open()
            }
        }
    }

    @Test
    fun `legacy acknowledgement preserves a newer position recorded during send`() = runTest {
        val gate = RequestGate("/api/mobile/playback/progress")
        val repository = repositoryWith(false, client = clientWith(gate))
        val sent = PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z")
        outbox.record(sent)
        enqueue(200, """{"status":"saved"}""")
        val flush = async(UnconfinedTestDispatcher(testScheduler)) { repository.flushProgress() }
        try {
            gate.entered.await()
            server.takeRequest()
            val replacement = sent.copy(positionSeconds = 20.0, generation = "next")
            outbox.record(replacement)
            gate.open()
            flush.await()
            assertEquals(listOf(replacement), outbox.entries.value)
        } finally {
            gate.open()
        }
    }

    @Test
    fun `parallel flushes send each generation only once on either API`() = runTest {
        for (batch in listOf(true, false)) {
            val path = if (batch) "/api/mobile/playback/sync" else "/api/mobile/playback/progress"
            val gate = RequestGate(path)
            val repository = repositoryWith(batch, client = clientWith(gate))
            outbox.record(PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z"))
            enqueue(200, if (batch) """{"accepted":[{"chapter_id":7}]}""" else """{"status":"saved"}""")
            val first = async(UnconfinedTestDispatcher(testScheduler)) { repository.flushProgress() }
            try {
                gate.entered.await()
                server.takeRequest()
                val count = server.requestCount
                val second = async(UnconfinedTestDispatcher(testScheduler)) { repository.flushProgress() }
                assertFalse(second.isCompleted)
                gate.open()
                first.await()
                second.await()
                assertEquals(count, server.requestCount)
                assertTrue(outbox.entries.value.isEmpty())
            } finally {
                gate.open()
            }
        }
    }

    @Test
    fun `a concurrent save queues immediately and retries its own generation after the drain`() = runTest {
        for (batch in listOf(true, false)) {
            val path = if (batch) "/api/mobile/playback/sync" else "/api/mobile/playback/progress"
            val gate = RequestGate(path)
            val repository = repositoryWith(batch, client = clientWith(gate))
            enqueue(200, if (batch) """{"accepted":[{"chapter_id":7}]}""" else """{"status":"saved"}""")
            val first = async(UnconfinedTestDispatcher(testScheduler)) { repository.saveProgress(1, 7, 10.0, false) }
            try {
                gate.entered.await()
                assertContains(server.takeRequest().bodyText(), "\"position_seconds\":10.0")
                enqueue(500, """{"detail":"offline"}""")
                val second = async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { repository.saveProgress(1, 7, 20.0, false) }
                }
                assertEquals(20.0, outbox.entries.value.single().positionSeconds)
                assertFalse(second.isCompleted)
                gate.open()
                first.await()
                assertIs<HttpException>(second.await().exceptionOrNull())
                assertContains(server.takeRequest().bodyText(), "\"position_seconds\":20.0")
                assertEquals(20.0, outbox.entries.value.single().positionSeconds)
            } finally {
                gate.open()
            }
        }
    }

    @Test
    fun `server state alone acknowledges only chapters in the sent batch`() = runTest {
        val repository = repositoryWith(true, maxItems = 1)
        outbox.record(PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z"))
        outbox.record(PendingProgress(1, 8, 20.0, false, "2026-08-11T10:00:00.000Z"))
        enqueue(200, """{"accepted":[{"chapter_id":8}],"server_state":[{"chapter_id":7,"position_seconds":100.0},{"chapter_id":8,"position_seconds":200.0}]}""")
        enqueue(500, """{"detail":"offline"}""")

        assertThrows<HttpException> { repository.flushProgress() }

        assertEquals(listOf(8), outbox.entries.value.map { it.chapterId })
        assertEquals(setOf(7), repository.serverPlaybackState.value.keys)
        assertEquals(100.0, repository.serverPlaybackState.value[7]?.positionSeconds)
    }

    @Test
    fun `logout and external session end discard pending progress on disk`() = runTest {
        val file = tempDir.resolve("progress-outbox.json")
        val store = FileProgressOutboxStore(file)
        val repository = repositoryWith(true, progressOutbox = store)
        enqueue(500, """{"detail":"offline"}""")
        assertThrows<HttpException> { repository.saveProgress(1, 7, 10.0, false) }
        server.takeRequest()
        enqueue(500, """{"detail":"offline"}""")

        repository.logout()

        server.takeRequest()
        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
        assertFalse(sessionStore.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
        signIn(repository)
        enqueue(500, """{"detail":"offline"}""")
        assertThrows<HttpException> { repository.saveProgress(1, 7, 20.0, false) }
        server.takeRequest()

        repository.endSession(parseSessionEnd("""{"detail":"Not authenticated"}"""))

        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
        assertFalse(sessionStore.current().isLoggedIn)
    }

    @Test
    fun `current 401 clears persisted progress on both APIs`() = runTest {
        val original = sessionStore.current()
        for (batch in listOf(true, false)) {
            sessionStore.save(original)
            val file = tempDir.resolve("expired-$batch.json")
            val repository = repositoryWith(batch, progressOutbox = FileProgressOutboxStore(file))
            enqueue(401, """{"detail":"Not authenticated"}""")

            assertThrows<HttpException> { repository.saveProgress(1, 7, 10.0, false) }

            server.takeRequest()
            assertFalse(sessionStore.current().isLoggedIn)
            assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
        }
    }

    @Test
    fun `same owner reauthentication retains offline progress despite discovery metadata changes`() = runTest {
        val file = tempDir.resolve("same-owner.json")
        val store = FileProgressOutboxStore(file)
        val repository = repositoryWith(true, progressOutbox = store)
        enqueue(500, """{"detail":"offline"}""")
        assertThrows<HttpException> { repository.saveProgress(1, 7, 10.0, false) }
        server.takeRequest()
        val saved = store.entries.value.single()
        sessionStore.rememberAdvertisedBaseUrl("https://advertised.example/")

        signIn(repository, username = "admin")

        assertEquals(listOf(saved), store.entries.value)
        enqueue(200, """{"accepted":[{"chapter_id":7}]}""")
        repository.flushProgress()
        assertEquals("Bearer ttsr_new", server.takeRequest().headers["Authorization"])
        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
    }

    @Test
    fun `missing authenticated username never claims or creates persisted progress`() = runTest {
        val file = tempDir.resolve("unknown-owner.json")
        val original = sessionStore.current()
        val store = FileProgressOutboxStore(file)
        store.bindOwner(StorageIdentity.of(original.serverUrl, username = original.username).relativePath)
        val saved = PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z")
        store.record(saved)
        sessionStore.save(original.copy(username = null))
        val repository = repositoryWith(true, progressOutbox = FileProgressOutboxStore(file))
        val count = server.requestCount

        repository.saveProgress(1, 8, 20.0, false)
        repository.flushProgress()

        assertEquals(count, server.requestCount)
        assertEquals(listOf(saved), FileProgressOutboxStore(file).entries.value)
    }

    @Test
    fun `restart retries only the authenticated owner and never a different account or server`() = runTest {
        for (batch in listOf(true, false)) {
            val file = tempDir.resolve("progress-$batch.json")
            val original = sessionStore.current()
            val repository = repositoryWith(batch, progressOutbox = FileProgressOutboxStore(file))
            enqueue(500, """{"detail":"offline"}""")
            assertThrows<HttpException> { repository.saveProgress(1, 7, 10.0, false) }
            server.takeRequest()
            val saved = FileProgressOutboxStore(file).entries.value.single()
            sessionStore.clearToken()
            val restartedStore = FileProgressOutboxStore(file)
            val restarted = repositoryWith(batch, progressOutbox = restartedStore)
            val count = server.requestCount
            restarted.flushProgress()
            assertEquals(count, server.requestCount)
            assertEquals(listOf(saved), restartedStore.entries.value)
            sessionStore.save(original.copy(token = "ttsr_restored"))
            enqueue(200, capabilities(batch))
            restarted.refreshCurrentCapabilities(forceRefresh = true)
            server.takeRequest()
            enqueue(200, if (batch) """{"accepted":[{"chapter_id":7}]}""" else """{"status":"saved"}""")
            restarted.flushProgress()
            assertEquals("Bearer ttsr_restored", server.takeRequest().headers["Authorization"])
            assertTrue(restartedStore.entries.value.isEmpty())

            for (other in listOf(
                original.copy(username = "Other", token = "ttsr_other"),
                original.copy(serverUrl = server.url("/other/").toString(), token = "ttsr_other"),
            )) {
                sessionStore.save(original)
                val writer = repositoryWith(batch, progressOutbox = FileProgressOutboxStore(file))
                enqueue(500, """{"detail":"offline"}""")
                assertThrows<HttpException> { writer.saveProgress(1, 7, 10.0, false) }
                server.takeRequest()
                sessionStore.save(other)
                val readerStore = FileProgressOutboxStore(file)
                val reader = repositoryWith(batch, progressOutbox = readerStore)
                val before = server.requestCount
                reader.flushProgress()
                assertEquals(before, server.requestCount)
                assertTrue(readerStore.entries.value.isEmpty())
                assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
            }
            sessionStore.save(original)
        }
    }

    @Test
    fun `an old batch response or 401 cannot affect a newly signed in owner`() = runTest {
        for (code in listOf(200, 401)) {
            val gate = RequestGate("/api/mobile/playback/sync")
            val repository = repositoryWith(true, client = clientWith(gate))
            enqueue(code, if (code == 200) {
                """{"accepted":[{"chapter_id":7}],"server_state":[{"chapter_id":7,"position_seconds":800.0}]}"""
            } else {
                """{"detail":"Not authenticated"}"""
            })
            val oldSave = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { repository.saveProgress(1, 7, 10.0, false) }
            }
            try {
                gate.entered.await()
                assertEquals("Bearer ${sessionStore.current().token}", server.takeRequest().headers["Authorization"])
                enqueue(200, """{"status":"ok"}""")
                repository.logout()
                server.takeRequest()
                signIn(repository, username = "reader-$code", token = "ttsr_new_$code")
                assertTrue(outbox.entries.value.isEmpty())
                val next = PendingProgress(1, 7, 20.0, false, "2026-08-11T10:00:00.000Z")
                outbox.record(next)
                gate.open()
                assertTrue(oldSave.await().isFailure)
                assertEquals("ttsr_new_$code", sessionStore.current().token)
                assertNull(repository.sessionEnd.value)
                assertTrue(repository.currentCapabilities.value.batchProgress)
                assertTrue(repository.serverPlaybackState.value.isEmpty())
                assertEquals(listOf(next), outbox.entries.value)
            } finally {
                gate.open()
            }
        }
    }

    @Test
    fun `old active and waiting drains cannot send under a new account on either API`() = runTest {
        for (batch in listOf(true, false)) {
            val path = if (batch) "/api/mobile/playback/sync" else "/api/mobile/playback/progress"
            val gate = RequestGate(path)
            val repository = repositoryWith(batch, maxItems = 1, client = clientWith(gate))
            outbox.record(PendingProgress(1, 7, 10.0, false, "2026-08-11T10:00:00.000Z"))
            outbox.record(PendingProgress(1, 8, 20.0, false, "2026-08-11T10:00:00.000Z"))
            enqueue(200, if (batch) """{"accepted":[{"chapter_id":7}]}""" else """{"status":"saved"}""")
            val flush = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { repository.flushProgress() } }
            try {
                gate.entered.await()
                server.takeRequest()
                val waiting = async(UnconfinedTestDispatcher(testScheduler)) { repository.flushProgress() }
                assertFalse(waiting.isCompleted)
                signIn(repository, username = "reader-$batch", token = "ttsr_new_$batch", batchProgress = batch)
                assertTrue(outbox.entries.value.isEmpty())
                val next = PendingProgress(1, 7, 30.0, false, "2026-08-11T10:00:00.000Z")
                outbox.record(next)
                val count = server.requestCount
                gate.open()
                assertTrue(flush.await().isFailure)
                waiting.await()
                assertEquals(count, server.requestCount)
                assertEquals(listOf(next), outbox.entries.value)
            } finally {
                gate.open()
            }
        }
    }

    @Test
    fun `a request waiting before authentication cannot borrow a new account credential`() = runTest {
        val gate = RequestGate("/api/mobile/playback/sync", beforeSend = true)
        val repository = repositoryWith(true, client = clientWith(gate))
        val save = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { repository.saveProgress(1, 7, 10.0, false) } }
        try {
            gate.entered.await()
            signIn(repository)
            val count = server.requestCount
            gate.open()
            assertIs<IOException>(save.await().exceptionOrNull())
            assertEquals(count, server.requestCount)
            assertTrue(outbox.entries.value.isEmpty())
            assertEquals("ttsr_new", sessionStore.current().token)
        } finally {
            gate.open()
        }
    }

    @Test
    fun `a save captured before a session boundary cannot enqueue afterwards`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = repositoryWith(true, stampProvider = {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            "2026-08-11T10:00:00.000Z"
        })
        val save = async(Dispatchers.Default) { repository.saveProgress(1, 7, 10.0, false) }
        try {
            entered.await()
            signIn(repository)
            val count = server.requestCount
            release.countDown()
            save.await()
            assertEquals(count, server.requestCount)
            assertTrue(outbox.entries.value.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `old authenticated endpoint 401 cannot erase a new generation even with the same token`() = runTest {
        val gate = RequestGate("/api/mobile/library")
        val repository = repositoryWith(true, client = clientWith(gate))
        enqueue(401, """{"detail":"Not authenticated"}""")
        val request = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { repository.library() } }
        try {
            gate.entered.await()
            server.takeRequest()
            signIn(repository, username = "admin", token = "ttsr_token")
            gate.open()
            assertIs<HttpException>(request.await().exceptionOrNull())
            assertEquals("ttsr_token", sessionStore.current().token)
            assertNull(repository.sessionEnd.value)
            assertEquals(0, sessionStore.clearTokenCalls)
        } finally {
            gate.open()
        }
    }

    @Test
    fun `old capability discovery cannot publish into a newly signed in session`() = runTest {
        val gate = RequestGate("/api/mobile/capabilities")
        val repository = RetrofitTtsRoadRepository(
            sessionStore,
            clientWith(gate),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
            progressOutbox = outbox,
        )
        enqueue(200, capabilities(true))
        val refresh = async(UnconfinedTestDispatcher(testScheduler)) { repository.refreshCurrentCapabilities(true) }
        try {
            gate.entered.await()
            server.takeRequest()
            signIn(repository, batchProgress = false)
            gate.open()
            refresh.await()
            assertFalse(repository.currentCapabilities.value.batchProgress)
            assertEquals("ttsr_new", sessionStore.current().token)
        } finally {
            gate.open()
        }
    }

    @Test
    fun `old logout completion cannot clear a newly signed in session`() = runTest {
        val gate = RequestGate("/api/mobile/logout")
        val repository = repositoryWith(true, client = clientWith(gate))
        enqueue(200, """{"status":"ok"}""")
        val logout = async(UnconfinedTestDispatcher(testScheduler)) { repository.logout() }
        try {
            gate.entered.await()
            server.takeRequest()
            signIn(repository)
            val next = PendingProgress(1, 7, 20.0, false, "2026-08-11T10:00:00.000Z")
            outbox.record(next)
            gate.open()
            logout.await()
            assertEquals("ttsr_new", sessionStore.current().token)
            assertEquals(listOf(next), outbox.entries.value)
            assertNull(repository.sessionEnd.value)
        } finally {
            gate.open()
        }
    }

    @Test
    fun `flushing an empty queue makes no request at all`() = runTest {
        val repository = repositoryWith(batchProgress = true)
        val afterDiscovery = server.requestCount

        repository.flushProgress()

        assertEquals(afterDiscovery, server.requestCount)
    }
}
