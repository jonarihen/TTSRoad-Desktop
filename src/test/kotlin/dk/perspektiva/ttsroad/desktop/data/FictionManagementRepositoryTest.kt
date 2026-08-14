package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** Admin fiction-management request/response shapes on the stable mobile routes. */
@OptIn(ExperimentalCoroutinesApi::class)
class FictionManagementRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitTtsRoadRepository
    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = InMemorySessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "ttsr_admin",
                username = "admin",
                isAdmin = true,
            ),
        )
        repository = RetrofitTtsRoadRepository(
            sessionStore = session,
            client = authedClient(session),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun enqueue(body: String) {
        server.enqueue(MockResponse(code = 200, headers = jsonHeaders, body = body))
    }

    @Test
    fun `add posts a Royal Road id and optional voice`() = runTest {
        server.enqueue(
            MockResponse(
                code = 201,
                headers = jsonHeaders,
                body = mutationBody(id = 12, title = "New serial", voice = "en-GB-RyanNeural"),
            ),
        )

        val added = repository.createFiction(FictionCreateRequest("424242", "en-GB-RyanNeural"))

        assertEquals(12, added.id)
        assertEquals("en-GB-RyanNeural", added.voice)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/fictions", request.url.encodedPath)
        assertEquals("Bearer ttsr_admin", request.headers["Authorization"])
        val body = request.bodyText()
        assertTrue(body.contains("\"fiction_url\":\"424242\""), body)
        assertTrue(body.contains("\"voice\":\"en-GB-RyanNeural\""), body)
    }

    @Test
    fun `edit patches only the supported shared metadata fields`() = runTest {
        enqueue(mutationBody(id = 7, title = "Better", voice = "en-US-AriaNeural"))

        repository.updateFiction(
            7,
            FictionUpdateRequest(title = "Better", author = "Writer", voice = "en-US-AriaNeural"),
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/mobile/fictions/7", request.url.encodedPath)
        val body = request.bodyText()
        assertTrue(body.contains("\"title\":\"Better\""), body)
        assertTrue(body.contains("\"author\":\"Writer\""), body)
        assertTrue(body.contains("\"voice\":\"en-US-AriaNeural\""), body)
        assertFalse(body.contains("slug"), body)
    }

    @Test
    fun `delete requires the response to confirm the same fiction id`() = runTest {
        enqueue("""{"api_version":1,"status":"ok","fiction_id":7,"deleted":true}""")
        assertTrue(repository.deleteFiction(7))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/fictions/7", request.url.encodedPath)

        enqueue("""{"api_version":1,"status":"ok","fiction_id":8,"deleted":true}""")
        assertFalse(repository.deleteFiction(7), "a mismatched acknowledgement cannot confirm deletion")
    }

    @Test
    fun `an EPUB is sent as multipart with its filename and an optional voice`() = runTest {
        val epub = java.nio.file.Files.createTempDirectory("ttsroad-epub").resolve("A Book.epub").toFile()
        epub.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        enqueue(mutationBody(202, "A Book", "en-US-AriaNeural"))

        val fiction = repository.uploadEpub(epub, voice = "en-US-AriaNeural")

        assertEquals(202, fiction.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/fictions/upload-epub", request.url.encodedPath)
        assertTrue(
            request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data"),
            request.headers["Content-Type"].orEmpty(),
        )
        val body = request.bodyText()
        // The server checks the *filename* for a `.epub` extension, so the name is part of the
        // request rather than decoration — and only the leaf is sent, never a path.
        assertTrue(body.contains("""filename="A Book.epub""""), body)
        assertFalse(body.contains(epub.parent), "a local path is not the server's business")
        assertTrue(body.contains("""name="voice""""), body)
        assertTrue(body.contains("en-US-AriaNeural"), body)
    }

    @Test
    fun `an upload without a voice sends no voice part at all`() = runTest {
        val epub = java.nio.file.Files.createTempDirectory("ttsroad-epub").resolve("Plain.epub").toFile()
        epub.writeBytes(byteArrayOf(0x50, 0x4B))
        enqueue(mutationBody(203, "Plain", "default"))

        repository.uploadEpub(epub, voice = "   ")

        // Blank is "did not choose", not "choose the empty voice": the server's own default is a
        // better answer than an empty string it would have to interpret.
        assertFalse(server.takeRequest().bodyText().contains("""name="voice""""))
    }

    private fun mutationBody(id: Int, title: String, voice: String): String =
        """{"api_version":1,"status":"ok","fiction":{"id":$id,"title":"$title","voice":"$voice"}}"""
}
