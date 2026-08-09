package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionState
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The transfer half of offline downloads.
 *
 * Targets the acceptance criteria about interrupted downloads resuming or restarting safely and
 * never appearing as complete, and about a synthetic disk-full, corrupt file, 401 and 404 each
 * producing a recoverable state.
 */
class ChapterDownloaderTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var storage: DownloadStorage

    /** A body that passes the MP3 header check: an ID3 tag plus enough bytes to be plausible. */
    private fun mp3Bytes(size: Int = 4096): ByteArray =
        ByteArray(size).also {
            it[0] = 'I'.code.toByte()
            it[1] = 'D'.code.toByte()
            it[2] = '3'.code.toByte()
        }

    /** MockResponse's String body would mangle binary, so audio goes through a Buffer. */
    private fun audioResponse(
        bytes: ByteArray,
        code: Int = 200,
        headers: Headers = Headers.headersOf(),
    ): MockResponse = MockResponse.Builder()
        .code(code)
        .headers(headers)
        .body(Buffer().write(bytes))
        .build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "ttsr_token", username = "alice"),
        )
        storage = DownloadStorage(File(tempDir, "downloads"))
        storage.prepare()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun downloader(validator: DownloadValidator = Mp3HeaderValidator): ChapterDownloader {
        val repository = object : FakeRepository(serverUrl = server.url("/").toString()) {
            override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader
            override fun resolveUrl(url: String): String =
                if (url.startsWith("http")) url else server.url("/").toString().trimEnd('/') + url

            override suspend fun endSession(end: dk.perspektiva.ttsroad.desktop.data.SessionEnd) {
                super.endSession(end)
                sessionStore.clearToken()
            }
        }
        return ChapterDownloader(authedClient(sessionStore), repository, storage, validator)
    }

    // --- The happy path -------------------------------------------------------------------------

    @Test
    fun `a complete download is validated and renamed into place`() = runBlocking {
        val audio = mp3Bytes()
        server.enqueue(audioResponse(audio))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = audio.size.toLong())

        assertIs<DownloadResult.Success>(result)
        assertEquals(audio.size.toLong(), result.bytes)
        assertTrue(storage.resolve("1.mp3").isFile, "the file was not renamed into place")
        assertFalse(File(storage.root, "1.mp3.part").exists(), "the part file was left behind")
    }

    @Test
    fun `the download carries the bearer token from the shared interceptor`() = runBlocking {
        server.enqueue(audioResponse(mp3Bytes()))

        downloader().download("/audio/s/1.mp3", "1.mp3")

        assertEquals("Bearer ttsr_token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `progress is reported as bytes arrive`() = runBlocking {
        val audio = mp3Bytes()
        server.enqueue(audioResponse(audio))

        var lastSeen = 0L
        downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = audio.size.toLong()) { soFar, _ ->
            lastSeen = soFar
        }

        assertEquals(audio.size.toLong(), lastSeen)
    }

    // --- Resume ---------------------------------------------------------------------------------

    @Test
    fun `an interrupted download resumes from the part file rather than restarting`() = runBlocking {
        val whole = mp3Bytes()
        val alreadyHave = 1024
        File(storage.root, "1.mp3.part").writeBytes(whole.copyOfRange(0, alreadyHave))

        server.enqueue(
            audioResponse(
                bytes = whole.copyOfRange(alreadyHave, whole.size),
                code = 206,
                headers = Headers.headersOf("Content-Range", "bytes $alreadyHave-${whole.size - 1}/${whole.size}"),
            ),
        )

        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = whole.size.toLong())

        assertIs<DownloadResult.Success>(result)
        assertEquals("bytes=$alreadyHave-", server.takeRequest().headers["Range"])
        assertEquals(whole.size, storage.resolve("1.mp3").length().toInt())
    }

    @Test
    fun `a server that ignores the range header restarts instead of corrupting the file`() = runBlocking {
        // The dangerous case: we asked to resume, the server sent the whole file with a 200.
        // Appending would produce a file of the right length made of the wrong bytes.
        val whole = mp3Bytes()
        File(storage.root, "1.mp3.part").writeBytes(whole.copyOfRange(0, 1024))

        server.enqueue(audioResponse(whole))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = whole.size.toLong())

        assertIs<DownloadResult.Success>(result)
        assertEquals(whole.size.toLong(), storage.resolve("1.mp3").length())
        assertContentEquals(whole, storage.resolve("1.mp3").readBytes())
    }

    @Test
    fun `a part file longer than the chapter is discarded rather than resumed`() = runBlocking {
        // Debris from a different encoding. Resuming past the end would never complete.
        val whole = mp3Bytes()
        File(storage.root, "1.mp3.part").writeBytes(ByteArray(whole.size * 2))

        server.enqueue(audioResponse(whole))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = whole.size.toLong())

        assertIs<DownloadResult.Success>(result)
        assertEquals(whole.size.toLong(), storage.resolve("1.mp3").length())
        // No Range header, because there was nothing legitimate to resume from.
        assertEquals(null, server.takeRequest().headers["Range"])
    }

    // --- Never complete when it is not -----------------------------------------------------------

    @Test
    fun `a truncated transfer does not become an offline chapter`() = runBlocking {
        // HTTP does not report a cleanly-closed short body as an error, so the length check is the
        // only thing standing between this and a chapter marked Offline that will not play.
        val audio = mp3Bytes()
        server.enqueue(audioResponse(audio.copyOfRange(0, 2048)))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = audio.size.toLong())

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.Transient>(result.failure)
        assertFalse(storage.resolve("1.mp3").exists(), "a short download must not be renamed into place")
    }

    @Test
    fun `an html error page is rejected rather than stored as audio`() = runBlocking {
        // A captive portal or a misconfigured proxy answers 200 with HTML. The bytes arrive intact,
        // so only a content check catches it — otherwise it fails to play days later.
        val html = "<html><body>Sign in to the wifi</body></html>".toByteArray().copyOf(2048)
        server.enqueue(audioResponse(html))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.Corrupt>(result.failure)
        assertFalse(storage.resolve("1.mp3").exists())
        // The part file goes too: the bytes are wrong, so resuming would resume garbage.
        assertFalse(File(storage.root, "1.mp3.part").exists())
    }

    // --- Typed failures ---------------------------------------------------------------------------

    @Test
    fun `a 401 is reported as a session end, not as something to retry`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Token expired"}"""))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.SessionExpired>(result.failure)
        assertFalse(sessionStore.current().isLoggedIn, "the authoritative 401 left the session live")
    }

    @Test
    fun `a 404 is gone rather than transient`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.Gone>(result.failure)
        assertFalse(DownloadFailure.isWorthAutoRetry(result.failure), "a 404 must not be auto-retried")
    }

    @Test
    fun `a 500 is transient and worth retrying`() = runBlocking {
        server.enqueue(MockResponse(code = 503))

        val result = downloader().download("/audio/s/1.mp3", "1.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.Transient>(result.failure)
        assertTrue(DownloadFailure.isWorthAutoRetry(result.failure))
    }

    @Test
    fun `signing out mid-queue stops the download rather than fetching a 401`() = runBlocking {
        sessionStore.clearToken()

        val result = downloader().download("/audio/s/1.mp3", "1.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.SessionExpired>(result.failure)
        assertEquals(0, server.requestCount, "no request should be made without a credential")
    }

    // --- Disk space -------------------------------------------------------------------------------

    @Test
    fun `a chapter larger than the free space is refused before any bytes are fetched`() = runBlocking {
        // A size no disk has free, so the margin check must refuse it before opening a connection.
        val result = downloader().download("/audio/s/1.mp3", "1.mp3", expectedBytes = Long.MAX_VALUE / 2)

        assertIs<DownloadResult.Failed>(result)
        assertIs<DownloadFailure.OutOfSpace>(result.failure)
        assertEquals(0, server.requestCount, "no bytes should be fetched when there is no room")
        assertFalse(storage.resolve("1.mp3").exists())
    }

    // --- Path safety ------------------------------------------------------------------------------

    @Test
    fun `a hostile file name is refused before anything is opened`() = runBlocking {
        val result = downloader().download("/audio/s/1.mp3", "../../escape.mp3")

        assertIs<DownloadResult.Failed>(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the validator sees a real file, and the header check is what it sounds like`() {
        val good = File(tempDir, "good.mp3").apply { writeBytes(mp3Bytes()) }
        val bare = File(tempDir, "bare.mp3").apply {
            writeBytes(ByteArray(2048).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() })
        }
        val html = File(tempDir, "bad.mp3").apply { writeBytes(ByteArray(2048) { '<'.code.toByte() }) }
        val tiny = File(tempDir, "tiny.mp3").apply { writeBytes(mp3Bytes(10)) }

        assertTrue(Mp3HeaderValidator.looksDecodable(good), "an ID3-tagged file is audio")
        assertTrue(Mp3HeaderValidator.looksDecodable(bare), "a bare MPEG frame sync is audio")
        assertFalse(Mp3HeaderValidator.looksDecodable(html), "HTML is not audio")
        assertFalse(Mp3HeaderValidator.looksDecodable(tiny), "a file this short is not a chapter")
        assertFalse(Mp3HeaderValidator.looksDecodable(File(tempDir, "missing.mp3")))
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "length differs")
        assertTrue(expected.contentEquals(actual), "content differs")
    }

    @Test
    fun `the error message names the chapter problem rather than an http code`() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        val result = downloader().download("/audio/s/1.mp3", "1.mp3")
        assertIs<DownloadResult.Failed>(result)
        assertContains(result.failure.message.lowercase(), "chapter")
    }
}
