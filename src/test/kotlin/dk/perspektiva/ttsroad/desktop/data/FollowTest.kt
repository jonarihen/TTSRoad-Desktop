package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ServerFixtures
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
import org.junit.jupiter.api.assertThrows
import retrofit2.HttpException

/** Per-user libraries: the two follow routes, the `scope` parameter, and the `following` key. */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowTest {
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

    // --- The scope parameter ----------------------------------------------------------------------

    @Test
    fun `the shelf is what the library asks for by default`() = runTest {
        enqueue(200, ServerFixtures.LIBRARY)

        repository.library()

        assertEquals("followed", server.takeRequest().url.queryParameter("scope"))
    }

    @Test
    fun `browse-all asks for the whole server`() = runTest {
        enqueue(200, ServerFixtures.LIBRARY_BROWSE_ALL)

        val response = repository.library(LibraryScope.All)

        assertEquals("all", server.takeRequest().url.queryParameter("scope"))
        assertEquals("all", response.scope)
        assertEquals(2, response.fictions.size)
    }

    @Test
    fun `an older server that ignores the parameter still decodes, with no scope`() = runTest {
        // The 1.4.0 payload has no `scope` and no `following`. Sending the parameter is safe
        // precisely because an unknown query parameter is ignored rather than rejected.
        enqueue(200, ServerFixtures.LIBRARY)

        val response = repository.library(LibraryScope.All)

        assertNull(response.scope)
        assertNull(response.fictions.single().following)
    }

    // --- The following key ------------------------------------------------------------------------

    @Test
    fun `the library says which fictions are on the shelf`() = runTest {
        enqueue(200, ServerFixtures.LIBRARY_BROWSE_ALL)

        val fictions = repository.library(LibraryScope.All).fictions

        assertEquals(true, fictions.first { it.id == 7 }.following)
        assertEquals(false, fictions.first { it.id == 9 }.following)
    }

    @Test
    fun `the chapters endpoint says nothing about follow state, and null is how that reads`() = runTest {
        // The trap: `_fiction_payload()` has no `following` key, so a screen that re-read follow
        // state from its own chapters response would unfollow every book on screen. Null is the
        // modelling that makes "this payload does not say" impossible to confuse with "not
        // followed".
        enqueue(200, ServerFixtures.CHAPTERS)

        assertNull(repository.chapters(fictionId = 7).fiction.following)
    }

    // --- The two routes ---------------------------------------------------------------------------

    @Test
    fun `following posts to the follow path and reports what the server now holds`() = runTest {
        enqueue(200, ServerFixtures.FOLLOWED)

        assertEquals(true, repository.setFollowing(7, following = true))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/fictions/7/follow", request.url.encodedPath)
        assertEquals("Bearer ttsr_token", request.headers["Authorization"])
    }

    @Test
    fun `unfollowing deletes on the same path`() = runTest {
        enqueue(200, ServerFixtures.UNFOLLOWED)

        assertEquals(false, repository.setFollowing(7, following = false))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/fictions/7/follow", request.url.encodedPath)
    }

    @Test
    fun `the server's answer is read, not the request that was made`() = runTest {
        // A follow that came back saying "not following" must render as not following. Assuming
        // success is how a toggle ends up disagreeing with the shelf it just changed.
        enqueue(200, ServerFixtures.UNFOLLOWED)

        assertFalse(repository.setFollowing(7, following = true) == true)
    }

    @Test
    fun `a fiction that no longer exists answers null rather than success`() = runTest {
        enqueue(404, """{"detail": "Fiction not found"}""")

        assertNull(repository.setFollowing(999, following = true))
    }

    @Test
    fun `a 500 propagates, because the server has the endpoint and simply failed`() = runTest {
        enqueue(500, """{"detail": "boom"}""")

        assertThrows<HttpException> { repository.setFollowing(7, following = true) }
    }

    // --- The capability ---------------------------------------------------------------------------

    @Test
    fun `follows is parsed from the capability payload`() {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val response = requireNotNull(
            moshi.adapter(CapabilitiesResponse::class.java).fromJson(ServerFixtures.CAPABILITIES_WITH_FOLLOWS),
        )

        assertTrue(ServerCapabilities.from(response).follows)
    }

    @Test
    fun `a server that never mentions follows is not assumed to have them`() {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val response = requireNotNull(
            moshi.adapter(CapabilitiesResponse::class.java).fromJson(ServerFixtures.CAPABILITIES_1_4_0),
        )

        assertFalse(ServerCapabilities.from(response).follows)
        assertFalse(ServerCapabilities.Baseline.follows)
    }
}
