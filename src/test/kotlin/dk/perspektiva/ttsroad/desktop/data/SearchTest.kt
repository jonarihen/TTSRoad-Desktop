package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import dk.perspektiva.ttsroad.desktop.authedClient
import kotlin.test.assertEquals
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
import org.junit.jupiter.api.assertThrows
import retrofit2.HttpException

/** `GET /api/mobile/search`: the wire, the decode, and the snippet offsets. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitTtsRoadRepository

    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val sessionStore = InMemorySessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "ttsr_token",
                username = "admin",
                isAdmin = true,
            ),
        )
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-host" },
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse(code = code, headers = jsonHeaders, body = body))
    }

    // --- The wire --------------------------------------------------------------------------------

    @Test
    fun `a search sends the query and the bearer header`() = runTest {
        enqueue(200, ServerFixtures.SEARCH)

        repository.search("ashfall gate")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mobile/search?q=ashfall%20gate&limit=20&offset=0", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    @Test
    fun `an over-long query is bounded before it is sent, not rejected as a 422`() = runTest {
        enqueue(200, ServerFixtures.SEARCH_EMPTY)

        repository.search("x".repeat(SearchLimits.MaxQueryLength + 50))

        assertEquals(
            SearchLimits.MaxQueryLength,
            server.takeRequest().url.queryParameter("q")?.length,
        )
    }

    @Test
    fun `a limit above what the server accepts is clamped rather than 422'd`() = runTest {
        enqueue(200, ServerFixtures.SEARCH_EMPTY)

        repository.search("gate", limit = 500)

        assertEquals("${SearchLimits.Max}", server.takeRequest().url.queryParameter("limit"))
    }

    @Test
    fun `a server without the endpoint answers null rather than throwing`() = runTest {
        // The distinction the UI depends on: null is "cannot search", an empty response is
        // "searched, nothing matched", and the two want different words on screen.
        enqueue(404, ServerFixtures.NOT_FOUND)

        assertNull(repository.search("gate"))
    }

    @Test
    fun `a 500 still propagates, because the server can search and simply failed`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        assertThrows<HttpException> { repository.search("gate") }
    }

    // --- The decode ------------------------------------------------------------------------------

    @Test
    fun `the three groups decode into one hit type`() {
        val result = ParsedFixtures.search

        assertEquals(3, result.total)
        assertEquals("fiction", result.fictions.items.single().kind)
        assertEquals("chapter", result.chapters.items.single().kind)
        assertEquals("text", result.text.items.single().kind)
    }

    @Test
    fun `a fiction hit carries no chapter, and a text hit does`() {
        val result = ParsedFixtures.search

        assertEquals(0, result.fictions.items.single().resolvedChapterId)
        assertTrue(!result.fictions.items.single().isChapterHit)
        assertEquals(103, result.text.items.single().resolvedChapterId)
        assertTrue(result.text.items.single().isChapterHit)
    }

    @Test
    fun `char_offset is decoded but is a text offset, never a position`() {
        // Kept so the field is not lost, and named here so nobody feeds it to seekTo: it indexes
        // `clean_text`, and converting it to a timestamp needs the read-along timings.
        assertEquals(4180, ParsedFixtures.search.text.items.single().charOffset)
        assertNull(ParsedFixtures.search.chapters.items.single().charOffset)
    }

    @Test
    fun `an empty result is still three groups, not a missing key`() {
        val result = requireNotNull(
            com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
                .adapter(SearchResponse::class.java)
                .fromJson(ServerFixtures.SEARCH_EMPTY),
        )

        assertTrue(result.isEmpty)
        assertEquals(0, result.total)
    }

    // --- Highlight offsets -----------------------------------------------------------------------

    @Test
    fun `a plain snippet's spans pass straight through`() {
        val hit = ParsedFixtures.search.text.items.single()

        assertEquals(listOf(SnippetSpan(26, 38)), snippetSpans(hit.snippet, hit.highlights))
        assertEquals("ashfall gate", hit.snippet.substring(26, 38))
    }

    @Test
    fun `an emoji before the match shifts the span, and it is corrected`() {
        // The server counts code points; Kotlin counts UTF-16 units. Without the conversion the
        // highlight would land one unit early for every astral character ahead of it — and this is
        // exactly the payload where "one unit early" becomes "inside a surrogate pair".
        val snippet = "🔥 the ashfall gate"
        val codePointStart = snippet.codePointCount(0, snippet.indexOf("ashfall"))

        val spans = snippetSpans(snippet, listOf(listOf(codePointStart, codePointStart + 12)))

        assertEquals("ashfall gate", snippet.substring(spans.single().start, spans.single().end))
    }

    @Test
    fun `a span past the end of the snippet is dropped rather than thrown`() {
        // An out-of-range index is a crash when it reaches an AnnotatedString, so a malformed
        // payload has to cost one missing highlight, not the whole results list.
        assertEquals(emptyList(), snippetSpans("short", listOf(listOf(40, 50))))
    }

    @Test
    fun `a span running past the end is clamped to it`() {
        assertEquals(listOf(SnippetSpan(2, 5)), snippetSpans("short", listOf(listOf(2, 900))))
    }

    @Test
    fun `inverted, negative and malformed spans are dropped`() {
        assertEquals(
            emptyList(),
            snippetSpans("a snippet", listOf(listOf(5, 2), listOf(-1, 3), listOf(4), emptyList())),
        )
    }

    @Test
    fun `no highlights and an empty snippet are both simply nothing to draw`() {
        assertEquals(emptyList(), snippetSpans("a snippet", emptyList()))
        assertEquals(emptyList(), snippetSpans("", listOf(listOf(0, 2))))
    }
}
