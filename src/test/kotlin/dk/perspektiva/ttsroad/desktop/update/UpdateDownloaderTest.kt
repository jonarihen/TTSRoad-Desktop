package dk.perspektiva.ttsroad.desktop.update

import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
    }
}
