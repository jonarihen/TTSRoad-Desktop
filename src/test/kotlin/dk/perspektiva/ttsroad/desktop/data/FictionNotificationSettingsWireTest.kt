package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.authedClient
import dk.perspektiva.ttsroad.desktop.bodyText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
class FictionNotificationSettingsWireTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitTtsRoadRepository
    private lateinit var sessionStore: InMemorySessionStore
    private val jsonHeaders = Headers.headersOf("Content-Type", "application/json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = InMemorySessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "ttsr_token",
                username = "reader",
                isAdmin = false,
            ),
        )
        repository = RetrofitTtsRoadRepository(
            sessionStore = sessionStore,
            client = authedClient(sessionStore),
            ioDispatcher = UnconfinedTestDispatcher(),
            deviceNameProvider = { "test-device" },
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `backlog_notifications capability literal true enables and false disables`() {
        val capsTrue = ServerCapabilities.from(
            CapabilitiesResponse(
                server = CapabilityServerInfo(version = "1.5.0"),
                capabilities = mapOf("backlog_notifications" to true),
            ),
        )
        assertTrue(capsTrue.backlogNotifications)
        assertTrue(canConfigureFictionNotifications(capsTrue, isFollowed = true))
        assertFalse(canConfigureFictionNotifications(capsTrue, isFollowed = false))

        val capsString = ServerCapabilities.from(
            CapabilitiesResponse(
                server = CapabilityServerInfo(version = "1.5.0"),
                capabilities = mapOf("backlog_notifications" to "true"),
            ),
        )
        assertFalse(capsString.backlogNotifications)

        val capsMissing = ServerCapabilities.from(
            CapabilitiesResponse(
                server = CapabilityServerInfo(version = "1.5.0"),
                capabilities = emptyMap(),
            ),
        )
        assertFalse(capsMissing.backlogNotifications)
    }

    @Test
    fun `request validation enforces modes and finite positive hours up to 1000`() {
        FictionNotificationSettingsRequest(NotificationModeBacklog, 2.5)
        FictionNotificationSettingsRequest(NotificationModeEvery, 1000.0)
        FictionNotificationSettingsRequest(NotificationModeOff, 0.001)

        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest("invalid", 2.0)
        }
        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest(NotificationModeBacklog, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest(NotificationModeBacklog, -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest(NotificationModeBacklog, 1000.1)
        }
        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest(NotificationModeBacklog, Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            FictionNotificationSettingsRequest(NotificationModeBacklog, Double.NaN)
        }
    }

    @Test
    fun `get and patch notification settings send bearer and decode correctly`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = jsonHeaders,
                body = """{"capabilities":{"backlog_notifications":true},"server":{"version":"1.5.0"}}""",
            ),
        )
        repository.refreshCurrentCapabilities(forceRefresh = true)
        server.takeRequest()

        server.enqueue(
            MockResponse(
                code = 200,
                headers = jsonHeaders,
                body = """{"mode":"backlog","backlog_hours":2.0,"backlog_armed":true,"remaining_seconds":3600.0}""",
            ),
        )
        server.enqueue(
            MockResponse(
                code = 200,
                headers = jsonHeaders,
                body = """{"mode":"every","backlog_hours":2.0,"backlog_armed":null,"remaining_seconds":0.0}""",
            ),
        )

        val settings = repository.fictionNotificationSettings(42)
        assertEquals(NotificationModeBacklog, settings?.mode)
        assertEquals(2.0, settings?.backlogHours)
        assertEquals(true, settings?.backlogArmed)
        assertEquals(3600.0, settings?.remainingSeconds)

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("/api/fictions/42/notification-settings", getReq.url.encodedPath)
        assertEquals("Bearer ttsr_token", getReq.headers["Authorization"])

        val patched = repository.updateFictionNotificationSettings(
            42,
            FictionNotificationSettingsRequest(NotificationModeEvery, 2.0),
        )
        assertEquals(NotificationModeEvery, patched?.mode)
        assertNull(patched?.backlogArmed)
        assertEquals(0.0, patched?.remainingSeconds)

        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/fictions/42/notification-settings", patchReq.url.encodedPath)
        assertEquals("Bearer ttsr_token", patchReq.headers["Authorization"])
        val body = patchReq.bodyText()
        assertTrue(body.contains("\"mode\":\"every\""), body)
        assertTrue(body.contains("\"backlog_hours\":2"), body)
    }

    @Test
    fun `remaining backlog label formats zero and durations`() {
        assertEquals("Ready to listen (1x): 0 min", remainingBacklogLabel(0.0))
        assertEquals("Ready to listen (1x): under a minute", remainingBacklogLabel(45.0))
        assertEquals("Ready to listen (1x): 1 min", remainingBacklogLabel(60.0))
        assertEquals("Ready to listen (1x): 2 h", remainingBacklogLabel(7200.0))
        assertEquals("Ready to listen (1x): 2 h 30 min", remainingBacklogLabel(9000.0))
        assertNull(remainingBacklogLabel(-5.0))
        assertNull(remainingBacklogLabel(Double.NaN))
    }
}
