package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.fail

/**
 * The download queue.
 *
 * Drives the real [ChapterDownloader] against a [MockWebServer], because the interesting behaviour
 * is the interaction between the queue and what a transfer actually reports.
 */
class DownloadManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var storage: DownloadStorage
    private lateinit var index: InMemoryDownloadIndexStore

    private fun mp3Bytes(size: Int = 4096): ByteArray =
        ByteArray(size).also {
            it[0] = 'I'.code.toByte(); it[1] = 'D'.code.toByte(); it[2] = '3'.code.toByte()
        }

    private fun audioResponse(bytes: ByteArray, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).body(Buffer().write(bytes)).build()

    private fun chapter(id: Int, size: Long = 4096) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = "Chapter $id",
        playable = true,
        audioFilesize = size,
        audio = AudioInfo(url = "/audio/s/$id.mp3"),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "ttsr_token", username = "alice"),
        )
        storage = DownloadStorage(File(tempDir, "downloads")).also { it.prepare() }
        index = InMemoryDownloadIndexStore()
    }

    /**
     * Held for the whole test rather than wrapped in `use`: closing the manager cancels its scope,
     * and a `use` block that ends before the transfer does would cancel the very download the test
     * is waiting for.
     */
    private var manager: DownloadManager? = null

    @AfterEach
    fun tearDown() {
        manager?.close()
        server.close()
    }

    private fun manager(retryDelaysMs: List<Long> = emptyList()): DownloadManager {
        val repository = object : FakeRepository(serverUrl = server.url("/").toString()) {
            override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader
            override fun resolveUrl(url: String): String =
                if (url.startsWith("http")) url else server.url("/").toString().trimEnd('/') + url
        }
        val downloader = ChapterDownloader(authedClient(sessionStore), repository, storage)
        return DownloadManager(downloader, storage, index, retryDelaysMs = retryDelaysMs)
            .also { manager = it }
    }

    private suspend fun awaitState(chapterId: Int, state: DownloadState, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (DownloadIndex.find(index.entries.value, chapterId)?.state == state) return
            delay(10)
        }
        fail("timed out waiting for chapter $chapterId to reach $state; index=${index.entries.value}")
    }

    private suspend fun awaitGone(chapterId: Int, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (DownloadIndex.find(index.entries.value, chapterId) == null) return
            delay(10)
        }
        fail("timed out waiting for chapter $chapterId to disappear; index=${index.entries.value}")
    }

    // --- The queue ------------------------------------------------------------------------------

    @Test
    fun `a queued chapter reaches Downloaded and lands on disk`() = runBlocking {
        val audio = mp3Bytes()
        server.enqueue(audioResponse(audio))

        manager().download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Downloaded)

        val entry = DownloadIndex.find(index.entries.value, 1)!!
        assertEquals(audio.size.toLong(), entry.bytesDownloaded)
        assertTrue(entry.isOffline)
        assertTrue(storage.resolve("1.mp3").isFile)
    }

    @Test
    fun `a whole batch is queued and every chapter finishes`() = runBlocking {
        repeat(5) { server.enqueue(audioResponse(mp3Bytes())) }

        val manager = manager()
        manager.enqueue((1..5).map { chapter(it) }, "A Serial")
        for (id in 1..5) awaitState(id, DownloadState.Downloaded)

        assertEquals(5, index.entries.value.count { it.isOffline })
    }

    @Test
    fun `a chapter already downloaded is not fetched twice`() = runBlocking {
        server.enqueue(audioResponse(mp3Bytes()))

        val manager = manager()
        manager.download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Downloaded)
        // Asking again must be a no-op rather than a second transfer.
        manager.download(chapter(1), "A Serial")
        delay(200)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a chapter with no audio is skipped rather than queued forever`() = runBlocking {
        manager().download(chapter(1).copy(audio = null), "A Serial")
        delay(200)

        assertNull(DownloadIndex.find(index.entries.value, 1))
        assertEquals(0, server.requestCount)
    }

    // --- Failure and retry ------------------------------------------------------------------------

    @Test
    fun `a transient failure is retried and can then succeed`() = runBlocking {
        server.enqueue(MockResponse(code = 503))
        server.enqueue(audioResponse(mp3Bytes()))

        manager(retryDelaysMs = listOf(10)).download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Downloaded)

        assertEquals(2, server.requestCount, "the transient failure should have been retried")
    }

    @Test
    fun `a 404 is not retried, and the row explains why`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        manager(retryDelaysMs = listOf(10, 10, 10)).download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Failed)
        delay(200)

        assertEquals(1, server.requestCount, "a 404 must not be retried")
        val entry = DownloadIndex.find(index.entries.value, 1)!!
        assertTrue(entry.failureMessage?.isNotBlank() == true)
        assertFalse(entry.isOffline)
    }

    @Test
    fun `a run of transient failures gives up rather than retrying forever`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse(code = 503)) }

        manager(retryDelaysMs = listOf(5, 5)).download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Failed)

        // The initial attempt plus exactly the ladder, not an unbounded loop.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retry clears the failure and tries again`() = runBlocking {
        server.enqueue(MockResponse(code = 503))
        server.enqueue(audioResponse(mp3Bytes()))

        val manager = manager()
        manager.download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Failed)

        manager.retry(chapter(1), "A Serial")
        awaitState(1, DownloadState.Downloaded)

        assertNull(DownloadIndex.find(index.entries.value, 1)!!.failureMessage)
    }

    // --- Cancel and delete --------------------------------------------------------------------------

    @Test
    fun `deleting a download removes the row and the bytes`() = runBlocking {
        server.enqueue(audioResponse(mp3Bytes()))

        val manager = manager()
        manager.download(chapter(1), "A Serial")
        awaitState(1, DownloadState.Downloaded)

        manager.remove(1)
        awaitGone(1)

        assertFalse(storage.resolve("1.mp3").exists())
    }

    @Test
    fun `delete all clears the index and the directory`() = runBlocking {
        repeat(3) { server.enqueue(audioResponse(mp3Bytes())) }

        val manager = manager()
        manager.enqueue((1..3).map { chapter(it) }, "A Serial")
        for (id in 1..3) awaitState(id, DownloadState.Downloaded)
        val freed = manager.deleteAll()

        assertEquals(3 * 4096L, freed)
        assertTrue(index.entries.value.isEmpty())
        assertFalse(storage.root.exists())
    }

    // --- Restart ------------------------------------------------------------------------------------

    @Test
    fun `pending work from a previous run is picked up again`() = runBlocking {
        // What a restart looks like: the index has a Queued row that no worker knows about, and the
        // audio URL is not in the index by design, so the caller supplies live metadata.
        index.put(
            DownloadEntry(
                chapterId = 1,
                fictionId = 7,
                fictionTitle = "A Serial",
                chapterTitle = "Chapter 1",
                state = DownloadState.Queued,
                totalBytes = 4096,
                fileName = "1.mp3",
                updatedAtMs = 1,
            ),
        )
        server.enqueue(audioResponse(mp3Bytes()))

        manager().resumePending(mapOf(1 to chapter(1)), mapOf(7 to "A Serial"))
        awaitState(1, DownloadState.Downloaded)

        assertTrue(storage.resolve("1.mp3").isFile)
    }

    @Test
    fun `pending work with no matching metadata is left alone rather than dropped`() = runBlocking {
        index.put(
            DownloadEntry(
                chapterId = 1,
                fictionId = 7,
                fictionTitle = "A Serial",
                chapterTitle = "Chapter 1",
                state = DownloadState.Queued,
                fileName = "1.mp3",
                updatedAtMs = 1,
            ),
        )

        manager().resumePending(emptyMap(), emptyMap())
        delay(200)

        // Still queued: the library simply has not loaded yet, which is not a reason to forget it.
        assertEquals(DownloadState.Queued, DownloadIndex.find(index.entries.value, 1)?.state)
        assertEquals(0, server.requestCount)
    }
}
