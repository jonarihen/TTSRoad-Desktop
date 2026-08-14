package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.authedClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookExportsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitTtsRoadRepository
    private val json = Headers.headersOf("Content-Type", "application/json")

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

    @Test
    fun `finished exports decode as authenticated downloads and never player items`() = runTest {
        server.enqueue(MockResponse(code = 200, headers = json, body = ExportPayload))

        val response = requireNotNull(repository.audiobookExports())

        assertTrue(response.ffmpegAvailable)
        val export = response.exports.single()
        assertEquals(17, export.id)
        assertEquals(2, export.partIndex)
        assertEquals(987654L, export.sizeBytes)
        assertTrue(export.downloadable)
        assertTrue(export.requiresBearerAuth)
        assertFalse(export.playableInApp)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/exports", request.url.encodedPath)
        assertEquals("Bearer ttsr_admin", request.headers["Authorization"])
    }

    @Test
    fun `an older server reports the optional endpoint as unavailable`() = runTest {
        server.enqueue(MockResponse(code = 404, headers = json, body = """{"detail":"Not Found"}"""))

        assertNull(repository.audiobookExports())
    }

    @Test
    fun `audiobook export capability requires a literal boolean`() {
        assertTrue(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("audiobook_export" to true)),
            ).audiobookExport,
        )
        assertFalse(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("audiobook_export" to 1)),
            ).audiobookExport,
        )
    }

    private companion object {
        val ExportPayload = """
            {
              "api_version": 1,
              "ffmpeg_available": true,
              "exports": [{
                "id": 17,
                "fiction_id": 7,
                "fiction_title": "A Test Serial",
                "batch_id": "deadbeef",
                "part_index": 2,
                "part_count": 3,
                "title": "A Test Serial — Part 2",
                "filename": "a-test-serial-part-2.m4b",
                "status": "done",
                "progress": 100,
                "chapter_count": 12,
                "duration_seconds": 3600.0,
                "duration_label": "1h 00m",
                "size_bytes": 987654,
                "size_label": "964.5 KB",
                "created_at": "2026-08-14T10:00:00Z",
                "completed_at": "2026-08-14T10:03:00Z",
                "download_url": "https://ttsroad.example.com/api/exports/17/download",
                "downloadable": true,
                "requires_bearer_auth": true,
                "playable_in_app": false
              }]
            }
        """.trimIndent()
    }
}
