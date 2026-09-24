package dk.perspektiva.ttsroad.desktop.update

import dk.perspektiva.ttsroad.desktop.ui.UpdateStateHolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The download half of the updater, against a real HTTP server.
 *
 * The load-bearing case is the mismatch: a file whose digest does not match the published one must
 * be deleted and must never be handed to the desktop, because handing it over is what would get it
 * executed.
 */
class UpdateDownloaderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @TempDir
    lateinit var directory: File

    private val payload = "a packaged installer"
    private val payloadDigest: String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun releaseWith(checksums: String): Pair<LatestRelease, ReleaseAsset> {
        // Enqueued in the order the downloader asks for them: checksums first, then the asset.
        server.enqueue(MockResponse(code = 200, body = checksums))
        server.enqueue(MockResponse(code = 200, body = payload))
        val asset = ReleaseAsset(
            name = "ttsroad_1.0.2-1_amd64.deb",
            browserDownloadUrl = server.url("/asset").toString(),
            sizeBytes = payload.length.toLong(),
        )
        val sums = ReleaseAsset(
            name = ChecksumAssetName,
            browserDownloadUrl = server.url("/SHA256SUMS").toString(),
            sizeBytes = checksums.length.toLong(),
        )
        val release = LatestRelease(
            tag = "v1.0.2",
            version = "1.0.2",
            notes = "",
            htmlUrl = server.url("/release").toString(),
            assets = listOf(asset, sums),
        )
        return release to asset
    }

    @Test
    fun `a matching checksum yields a saved file that is handed to the desktop`() = runTest {
        val (release, asset) = releaseWith("$payloadDigest  ./${"ttsroad_1.0.2-1_amd64.deb"}")
        val opened = mutableListOf<File>()
        val downloader = UpdateDownloader(client, directory) { opened += it }

        val outcome = downloader.download(release, asset)

        val verified = assertIs<DownloadOutcome.Verified>(outcome)
        assertEquals(payload, verified.file.readText())
        assertEquals(listOf(verified.file), opened)
        assertEquals(directory.canonicalFile, verified.file.parentFile.parentFile.canonicalFile)
        assertEquals(listOf(verified.file), verified.file.parentFile.listFiles().orEmpty().toList())
        assertTrue(verified.file.isFile)
    }

    @Test
    fun `network reads and desktop handoff use the injected IO dispatcher`() = runBlocking {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val ioThread = withContext(io) { Thread.currentThread() }
            val requestThreads = mutableListOf<Thread>()
            val observedClient = client.newBuilder()
                .addInterceptor { chain ->
                    requestThreads += Thread.currentThread()
                    chain.proceed(chain.request())
                }
                .build()
            var openThread: Thread? = null
            val downloader = UpdateDownloader(observedClient, directory, io) { openThread = Thread.currentThread() }

            assertIs<DownloadOutcome.Verified>(downloader.download(release, asset))
            assertEquals(listOf(ioThread, ioThread), requestThreads)
            assertEquals(ioThread, openThread)
        }
    }

    @Test
    fun `a mismatched checksum deletes the download and never opens it`() = runTest {
        val (release, asset) = releaseWith("${"b".repeat(64)}  ttsroad_1.0.2-1_amd64.deb")
        val opened = mutableListOf<File>()
        val downloader = UpdateDownloader(client, directory) { opened += it }

        val outcome = downloader.download(release, asset)

        assertIs<DownloadOutcome.Failed>(outcome)
        assertTrue(opened.isEmpty(), "a file that failed verification must not be opened")
        assertTrue(
            directory.listFiles().orEmpty().isEmpty(),
            "the rejected download must not be left on disk",
        )
    }

    @Test
    fun `a release that publishes no checksums downloads nothing at all`() = runTest {
        val asset = ReleaseAsset("ttsroad_1.0.2-1_amd64.deb", server.url("/asset").toString(), 1)
        val release = LatestRelease("v1.0.2", "1.0.2", "", "", listOf(asset))
        val opened = mutableListOf<File>()

        val outcome = UpdateDownloader(client, directory) { opened += it }.download(release, asset)

        val failed = assertIs<DownloadOutcome.Failed>(outcome)
        assertContains(failed.reason, "checksums")
        assertEquals(0, server.requestCount, "nothing should be fetched without checksums")
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `checksums that do not cover this asset stop the download`() = runTest {
        val (release, asset) = releaseWith("$payloadDigest  some-other-file.deb")
        val opened = mutableListOf<File>()

        val outcome = UpdateDownloader(client, directory) { opened += it }.download(release, asset)

        assertIs<DownloadOutcome.Failed>(outcome)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a failed transfer leaves no partial file behind`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "$payloadDigest  ttsroad_1.0.2-1_amd64.deb"))
        server.enqueue(MockResponse(code = 503))
        val asset = ReleaseAsset(
            "ttsroad_1.0.2-1_amd64.deb",
            server.url("/asset").toString(),
            payload.length.toLong(),
        )
        val sums = ReleaseAsset(ChecksumAssetName, server.url("/SHA256SUMS").toString(), 1)
        val release = LatestRelease("v1.0.2", "1.0.2", "", "", listOf(asset, sums))

        val outcome = UpdateDownloader(client, directory) { }.download(release, asset)

        assertIs<DownloadOutcome.Failed>(outcome)
        assertFalse(File(directory, "${asset.name}.part").exists())
        assertFalse(File(directory, asset.name).exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancelling a slow checksum request cancels the call without a download`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
                .headersDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        val (release, asset) = releaseForCancellation()
        val cancelled = CompletableDeferred<Call>()
        val observedClient = cancellationClient(cancelled)
        var opened = false
        var published = false
        val downloader = UpdateDownloader(observedClient, directory) { opened = true }
        val job = launch {
            downloader.download(release, asset)
            published = true
        }
        try {
            val request = withTimeout(5_000) {
                withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            }
            assertNotNull(request)
            job.cancel()
            assertTrue(cancelled.isCompleted)
            withTimeout(1_000) { job.join() }
            assertTrue(cancelled.await().isCanceled())
            assertFalse(opened)
            assertFalse(published)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(1, server.requestCount)
        } finally {
            job.cancel()
            observedClient.dispatcher.cancelAll()
        }
    }

    @Test
    fun `cancelling a stalled asset body removes its partial and never opens it`() = runBlocking {
        enqueueStalledAsset()
        val (release, asset) = releaseForCancellation()
        val cancelled = CompletableDeferred<Call>()
        val observedClient = cancellationClient(cancelled)
        var opened = false
        var published = false
        val job = launch {
            UpdateDownloader(observedClient, directory) { opened = true }.download(release, asset)
            published = true
        }
        try {
            awaitPartial()
            job.cancel()
            assertTrue(cancelled.isCompleted)
            withTimeout(1_000) { job.join() }
            assertTrue(cancelled.await().isCanceled())
            assertFalse(opened)
            assertFalse(published)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            job.cancel()
            observedClient.dispatcher.cancelAll()
        }
    }

    @Test
    fun `cancellation at EOF prevents verification and removes the completed partial`() = runBlocking {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        lateinit var job: Job
        val observedClient = client.newBuilder()
            .eventListener(object : EventListener() {
                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    if (call.request().url.encodedPath == "/asset") job.cancel()
                }
            })
            .build()
        var opened = false
        var published = false
        job = launch(start = CoroutineStart.LAZY) {
            UpdateDownloader(observedClient, directory) { opened = true }.download(release, asset)
            published = true
        }
        job.start()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(opened)
        assertFalse(published)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation from desktop handoff propagates and removes the unretained file`() = runTest {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        val downloader = UpdateDownloader(client, directory) { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { downloader.download(release, asset) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation after desktop handoff keeps the file alive without publishing success`() = runBlocking {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        lateinit var job: Job
        var handedOff: File? = null
        var published = false
        val downloader = UpdateDownloader(client, directory) {
            handedOff = it
            job.cancel()
        }
        job = launch(start = CoroutineStart.LAZY) {
            downloader.download(release, asset)
            published = true
        }
        job.start()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(published)
        assertEquals(payload, assertNotNull(handedOff).readText())
    }

    @Test
    fun `verified destinations survive subsequent attempts with the same filename`() = runTest {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        val downloader = UpdateDownloader(client, directory) { }
        val first = assertIs<DownloadOutcome.Verified>(downloader.download(release, asset)).file
        releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        val second = assertIs<DownloadOutcome.Verified>(downloader.download(release, asset)).file

        assertNotEquals(first, second)
        assertEquals(payload, first.readText())
        assertEquals(payload, second.readText())
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `a failed retry does not delete an installer already handed to the desktop`() = runTest {
        val (release, asset) = releaseWith("$payloadDigest  ttsroad_1.0.2-1_amd64.deb")
        val opened = mutableListOf<File>()
        val downloader = UpdateDownloader(client, directory) { opened += it }
        val verified = assertIs<DownloadOutcome.Verified>(downloader.download(release, asset)).file
        releaseWith("${"b".repeat(64)}  ttsroad_1.0.2-1_amd64.deb")

        assertIs<DownloadOutcome.Failed>(downloader.download(release, asset))
        assertEquals(payload, verified.readText())
        assertEquals(listOf(verified), opened)
        assertEquals(listOf(verified.parentFile), directory.listFiles().orEmpty().toList())
    }

    @Test
    fun `a new check cancels an obsolete download without publishing its outcome`() = runBlocking {
        enqueueStalledAsset()
        val (release, _) = releaseForCancellation()
        val cancelled = CompletableDeferred<Call>()
        val observedClient = cancellationClient(cancelled)
        val store = InMemoryUpdateSettingsStore()
        var opened = false
        val holder = UpdateStateHolder(
            UpdateChecker(
                source = { release },
                settingsStore = store,
                installedVersion = "1.0.1",
                osName = "Linux",
                architecture = "amd64",
            ),
            UpdateDownloader(observedClient, directory) { opened = true },
            store,
            coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        try {
            holder.checkNow()
            withTimeout(5_000) {
                while (holder.state.value.available == null) delay(10)
            }
            holder.download()
            awaitPartial()
            holder.checkNow()
            withTimeout(1_000) { cancelled.await() }
            withTimeout(5_000) {
                while (directory.listFiles().orEmpty().isNotEmpty() || holder.state.value.available == null) delay(10)
            }

            assertFalse(opened)
            assertFalse(holder.state.value.isDownloading)
            assertNull(holder.state.value.downloadedName)
            assertNull(holder.state.value.downloadError)
        } finally {
            holder.clear()
            observedClient.dispatcher.cancelAll()
        }
    }

    private fun releaseForCancellation(): Pair<LatestRelease, ReleaseAsset> {
        val asset = ReleaseAsset("ttsroad_1.0.2-1_amd64.deb", server.url("/asset").toString(), 100_000)
        val sums = ReleaseAsset(ChecksumAssetName, server.url("/SHA256SUMS").toString(), 100)
        return LatestRelease("v1.0.2", "1.0.2", "", "", listOf(asset, sums)) to asset
    }

    private fun cancellationClient(cancelled: CompletableDeferred<Call>): OkHttpClient =
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.complete(call)
                }
            })
            .build()

    private fun enqueueStalledAsset() {
        server.enqueue(MockResponse(code = 200, body = "$payloadDigest  ttsroad_1.0.2-1_amd64.deb"))
        server.enqueue(
            MockResponse.Builder()
                .body("a".repeat(100_000))
                .throttleBody(1024, 2, TimeUnit.SECONDS)
                .build(),
        )
    }

    private suspend fun awaitPartial() {
        withTimeout(5_000) {
            while (directory.walkTopDown().none { it.extension == "part" && it.length() > 0L }) delay(10)
        }
    }
}
