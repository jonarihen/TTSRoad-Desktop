package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.data.AudiobookExport
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AudiobookExportDownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySessionStore

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = InMemorySessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "ttsr_admin", username = "admin"),
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `a complete M4B is authenticated validated and atomically promoted`() = runBlocking {
        val bytes = m4bBytes()
        server.enqueue(binaryResponse(bytes))
        val target = File(tempDir, "book.m4b")

        val result = downloader().download(export(bytes.size), target) { _, _ -> }

        assertIs<AudiobookDownloadResult.Success>(result)
        assertTrue(target.isFile)
        assertTrue(target.readBytes().contentEquals(bytes))
        assertFalse(part(target).exists())
        assertEquals("Bearer ttsr_admin", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an interrupted volume resumes from its export-specific partial`() = runBlocking {
        val bytes = m4bBytes(4096)
        val already = 1024
        val target = File(tempDir, "book.m4b")
        part(target).writeBytes(bytes.copyOfRange(0, already))
        server.enqueue(
            binaryResponse(
                bytes.copyOfRange(already, bytes.size),
                code = 206,
                headers = Headers.headersOf("Content-Range", "bytes $already-${bytes.lastIndex}/${bytes.size}"),
            ),
        )

        val result = downloader().download(export(bytes.size), target) { _, _ -> }

        assertIs<AudiobookDownloadResult.Success>(result)
        assertEquals("bytes=$already-", server.takeRequest().headers["Range"])
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `a server that ignores range restarts instead of appending corruption`() = runBlocking {
        val bytes = m4bBytes(4096)
        val target = File(tempDir, "book.m4b")
        part(target).writeBytes(bytes.copyOfRange(0, 1024))
        server.enqueue(binaryResponse(bytes))

        val result = downloader().download(export(bytes.size), target) { _, _ -> }

        assertIs<AudiobookDownloadResult.Success>(result)
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `pausing retains an export-specific partial that can be resumed`() = runBlocking {
        val bytes = m4bBytes(256 * 1024)
        val target = File(tempDir, "book.m4b")
        server.enqueue(binaryResponse(bytes))

        assertFailsWith<CancellationException> {
            downloader().download(export(bytes.size), target) { downloaded, _ ->
                if (downloaded > 0L) throw CancellationException("pause")
            }
        }

        assertFalse(target.exists())
        assertTrue(part(target).isFile)
        assertTrue(part(target).length() in 1 until bytes.size.toLong())
    }

    @Test
    fun `an HTML response is deleted instead of becoming an audiobook`() = runBlocking {
        val bytes = ByteArray(2048) { '<'.code.toByte() }
        server.enqueue(binaryResponse(bytes))
        val target = File(tempDir, "book.m4b")

        val result = downloader().download(export(bytes.size), target) { _, _ -> }

        assertIs<AudiobookDownloadResult.Failed>(result)
        assertIs<DownloadFailure.Corrupt>(result.failure)
        assertFalse(target.exists())
        assertFalse(part(target).exists())
    }

    @Test
    fun `a download 401 ends the shared session`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Token expired"}"""))

        val result = downloader().download(export(0), File(tempDir, "book.m4b")) { _, _ -> }

        assertIs<AudiobookDownloadResult.Failed>(result)
        assertIs<DownloadFailure.SessionExpired>(result.failure)
        assertFalse(session.current().isLoggedIn)
    }

    @Test
    fun `server filenames are reduced to a safe M4B leaf`() {
        assertEquals("private.m4b", suggestedAudiobookFileName("../../private.m4b"))
        assertEquals("private.m4b", suggestedAudiobookFileName("..\\..\\private"))
        assertEquals("audiobook.m4b", suggestedAudiobookFileName("\u0000"))
    }

    private fun export(size: Int): AudiobookExport = AudiobookExport(
        id = 17,
        title = "A Test Serial",
        filename = "book.m4b",
        sizeBytes = size.toLong(),
        downloadUrl = server.url("/api/exports/17/download").toString(),
        downloadable = true,
        requiresBearerAuth = true,
        playableInApp = false,
    )

    private fun downloader(): HttpAudiobookExportDownloader {
        val repository = object : FakeRepository(serverUrl = server.url("/").toString()) {
            override fun authHeaderValue(): String? = session.current().authorizationHeader
            override fun resolveUrl(url: String): String = url
            override suspend fun endSession(end: dk.perspektiva.ttsroad.desktop.data.SessionEnd) {
                super.endSession(end)
                session.clearToken()
            }
        }
        return HttpAudiobookExportDownloader(authedClient(session), repository)
    }

    private fun part(target: File): File = File(target.parentFile, ".${target.name}.ttsroad-17.part")

    private fun m4bBytes(size: Int = 2048): ByteArray = ByteArray(size).also { bytes ->
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 0
        bytes[3] = 24
        "ftyp".encodeToByteArray().copyInto(bytes, destinationOffset = 4)
        "M4B ".encodeToByteArray().copyInto(bytes, destinationOffset = 8)
    }

    private fun binaryResponse(
        bytes: ByteArray,
        code: Int = 200,
        headers: Headers = Headers.headersOf(),
    ): MockResponse = MockResponse.Builder()
        .code(code)
        .headers(headers)
        .body(Buffer().write(bytes))
        .build()
}
