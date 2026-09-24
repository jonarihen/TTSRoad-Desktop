package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.EpubExportSession
import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.SessionEndReason
import dk.perspektiva.ttsroad.desktop.data.parseSessionEnd
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class EpubExportDownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `a complete EPUB is downloaded and atomically promoted`() = runBlocking {
        val bytes = epubBytes(2048)
        server.enqueue(binaryResponse(bytes))
        val target = File(tempDir, "book.epub")
        val operation = operation()

        val result = downloader().download(operation, target) { _, _ -> }

        assertIs<EpubDownloadResult.Success>(result)
        assertTrue(target.isFile)
        assertTrue(target.readBytes().contentEquals(bytes))
        assertEquals(2048L, result.bytes)
    }

    @Test
    fun `a 401 ends the session and reports failure`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Token expired"}"""))
        val session = fakeSession()
        val operation = EpubExportOperation(session)

        val result = downloader().download(operation, File(tempDir, "book.epub")) { _, _ -> }

        assertIs<EpubDownloadResult.Failed>(result)
        assertTrue(session.ended)
    }

    @Test
    fun `a 409 returns the server explanation`() = runBlocking {
        server.enqueue(MockResponse(code = 409, body = """{"detail":"This fiction has no text"}"""))
        val operation = operation()

        val result = downloader().download(operation, File(tempDir, "book.epub")) { _, _ -> }

        assertIs<EpubDownloadResult.Failed>(result)
        assertTrue(result.message.contains("no text"))
    }

    @Test
    fun `a non-EPUB body is rejected`() = runBlocking {
        val html = "<html>error</html>".toByteArray()
        server.enqueue(binaryResponse(html))
        val target = File(tempDir, "book.epub")
        val operation = operation()

        val result = downloader().download(operation, target) { _, _ -> }

        assertIs<EpubDownloadResult.Failed>(result)
        assertFalse(target.exists())
    }

    @Test
    fun `cancellation removes partial and does not leave zero-byte destination`() {
        val bytes = epubBytes(256 * 1024)
        server.enqueue(binaryResponse(bytes))
        val target = File(tempDir, "book.epub")
        val operation = operation()

        assertThrows<CancellationException> {
            runBlocking {
                downloader().download(operation, target) { downloaded, _ ->
                    if (downloaded > 0L) throw CancellationException("cancel")
                }
            }
        }

        assertFalse(target.exists())
    }

    @Test
    fun `existing destination is unchanged until promotion`() = runBlocking {
        val original = "original-content".toByteArray()
        val target = File(tempDir, "book.epub")
        target.writeBytes(original)

        val html = "<html>bad</html>".toByteArray()
        server.enqueue(binaryResponse(html))
        val operation = operation()

        val result = downloader().download(operation, target) { _, _ -> }

        assertIs<EpubDownloadResult.Failed>(result)
        assertTrue(target.readBytes().contentEquals(original))
    }

    @Test
    fun `a cancelled session does not publish success`() {
        val bytes = epubBytes(2048)
        server.enqueue(binaryResponse(bytes))
        val session = fakeSession()
        val target = File(tempDir, "book.epub")
        val operation = EpubExportOperation(session)

        runBlocking {
            val result = downloader().download(operation, target) { downloaded, _ ->
                if (downloaded > 0L) session.invalidate()
            }

            assertIs<EpubDownloadResult.Failed>(result)
        }
    }

    private fun operation(): EpubExportOperation = EpubExportOperation(fakeSession())

    private fun fakeSession(): FakeEpubSession = FakeEpubSession(client, server.url("/api/fictions/1/export.epub").toString())

    private fun downloader() = HttpEpubExportDownloader()

    private fun epubBytes(size: Int = 2048): ByteArray = ByteArray(size).also { bytes ->
        "PK".encodeToByteArray().copyInto(bytes, 0)
        bytes[2] = 0x03
        bytes[3] = 0x04
    }

    private fun binaryResponse(bytes: ByteArray, code: Int = 200): MockResponse = MockResponse.Builder()
        .code(code)
        .body(Buffer().write(bytes))
        .build()
}

private class FakeEpubSession(
    private val client: OkHttpClient,
    private val url: String,
) : EpubExportSession {
    @Volatile
    var ended = false
    @Volatile
    private var current = true

    fun invalidate() {
        current = false
    }

    override fun newCall(): Call = client.newCall(Request.Builder().url(url).build())

    override fun isCurrent(): Boolean = current

    override fun publish(block: () -> Unit): Boolean {
        if (!current) return false
        block()
        return true
    }

    override suspend fun endSession(end: SessionEnd) {
        ended = true
        current = false
    }
}
