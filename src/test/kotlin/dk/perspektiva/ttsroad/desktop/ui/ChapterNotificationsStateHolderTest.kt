package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationState
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationsResponse
import dk.perspektiva.ttsroad.desktop.data.NotificationChapter
import dk.perspektiva.ttsroad.desktop.data.NotificationFiction
import dk.perspektiva.ttsroad.desktop.data.ReadyNotificationKey
import dk.perspektiva.ttsroad.desktop.data.detailLabel
import dk.perspektiva.ttsroad.desktop.data.newlyReady
import dk.perspektiva.ttsroad.desktop.data.noticeMessage
import dk.perspektiva.ttsroad.desktop.data.readyNotificationText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A notice is raised when a chapter is pulled and stays open until it plays.
 *
 * Two rules carry the feature and both are asserted as refusals: a converting chapter must not be
 * dismissible, and the system notification must fire **only** on the pulled → ready transition —
 * never on arrival, and never for a chapter that was already ready when the app started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterNotificationsStateHolderTest {

    private fun notice(
        id: Int,
        state: String,
        dismissible: Boolean = state == "ready",
        playable: Boolean = state == "ready",
        fictionTitle: String = "A Test Serial",
        chapterTitle: String = "Chapter $id",
        progress: Int? = null,
        kind: String = "chapter",
        backlogSeconds: Double? = null,
        message: String? = null,
        readyAt: String? = null,
        chapterStatus: String? = null,
    ) = ChapterNotification(
        id = id,
        state = state,
        dismissible = dismissible,
        playable = playable,
        fiction = NotificationFiction(id = 7, title = fictionTitle),
        chapter = NotificationChapter(id = 100 + id, title = chapterTitle, chapterNumber = id.toDouble(), ttsProgress = progress, status = chapterStatus),
        kind = kind,
        backlogSeconds = backlogSeconds,
        message = message,
        readyAt = readyAt,
    )

    private fun response(vararg notifications: ChapterNotification) = ChapterNotificationsResponse(
        notifications = notifications.toList(),
        unread = notifications.count { it.presentation != ChapterNotificationState.Dismissed },
        ready = notifications.count { it.presentation == ChapterNotificationState.Ready },
    )

    private class Announcements {
        val raised: MutableList<Pair<String, String>> = mutableListOf()
        operator fun invoke(title: String, body: String) {
            raised += title to body
        }
    }

    @Test
    fun `a chapter already ready at startup is not announced`() = runTest {
        // The app was closed when it happened. Announcing on the first look would re-announce the
        // whole backlog on every launch, which is how a feature like this gets muted.
        val announcements = Announcements()
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "ready")))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            notify = announcements::invoke,
        )

        holder.refresh()
        runCurrent()

        assertEquals(1, holder.state.value.unread)
        assertTrue(announcements.raised.isEmpty(), "raised ${announcements.raised}")
        holder.clear()
    }

    @Test
    fun `becoming ready announces exactly once`() = runTest {
        val announcements = Announcements()
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "pulled", progress = 40)))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            notify = announcements::invoke,
        )

        holder.refresh()
        runCurrent()
        // Arrival is deliberately silent: the badge and the list already carry it.
        assertTrue(announcements.raised.isEmpty())

        repository.chapterNotificationsResult = Result.success(response(notice(1, "ready")))
        holder.refresh()
        runCurrent()
        assertEquals(1, announcements.raised.size)

        // Polling again must not re-announce what is already known to be ready.
        holder.refresh()
        runCurrent()
        assertEquals(1, announcements.raised.size, "raised ${announcements.raised}")
        holder.clear()
    }

    @Test
    fun `a converting chapter cannot be dismissed`() = runTest {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "pulled")))
        }
        val holder = ChapterNotificationsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.refresh()
        runCurrent()

        holder.dismiss(holder.state.value.notifications.single())
        runCurrent()

        // Refused before it is sent. The server answers 409 anyway, but a request that looks like
        // it worked against a stale list is worse than no request.
        assertTrue(repository.dismissedNotifications.isEmpty())
        assertEquals(1, holder.state.value.unread)
        holder.clear()
    }

    @Test
    fun `clearing the read ones leaves what is still converting`() = runTest {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(
                response(notice(1, "ready"), notice(2, "pulled")),
            )
        }
        val holder = ChapterNotificationsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.refresh()
        runCurrent()

        assertTrue(holder.state.value.hasClearable)
        repository.chapterNotificationsResult = Result.success(response(notice(2, "pulled")))
        holder.dismissRead()
        runCurrent()

        assertEquals(1, repository.dismissReadCalls)
        assertContentEquals(listOf(2), holder.state.value.notifications.map { it.id })
        holder.clear()
    }

    @Test
    fun `a server that answers 404 hides the surface rather than showing an empty list`() = runTest {
        val repository = FakeRepository().apply { chapterNotificationsResult = Result.success(null) }
        val holder = ChapterNotificationsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))

        holder.refresh()
        runCurrent()

        assertTrue(holder.state.value.unsupported)
        assertTrue(holder.state.value.isEmpty)
        assertNull(holder.state.value.error, "an absent feature is not an error worth retrying")
        holder.clear()
    }

    @Test
    fun `a failed poll keeps what is on screen`() = runTest {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "pulled")))
        }
        val holder = ChapterNotificationsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.refresh()
        runCurrent()

        repository.chapterNotificationsResult = Result.failure(java.io.IOException("offline"))
        holder.refresh()
        runCurrent()

        // Blanking the list would lose the very thing being waited for.
        assertEquals(1, holder.state.value.notifications.size)
        assertNotNull(holder.state.value.error)
        holder.clear()
    }

    @Test
    fun `signing out forgets what was seen as well as what was shown`() = runTest {
        val announcements = Announcements()
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "ready")))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            notify = announcements::invoke,
        )
        holder.refresh()
        runCurrent()

        holder.sessionEnded()
        assertTrue(holder.state.value.isEmpty)

        // The next account starts from a clean seed: their already-ready chapters are not news
        // either, and the previous account's certainly are not.
        holder.refresh()
        runCurrent()
        assertTrue(announcements.raised.isEmpty(), "raised ${announcements.raised}")
        holder.clear()
    }

    @Test
    fun `starting twice does not double the polling`() = runTest {
        // The screen starts it on entry and App starts it on capability discovery; both are correct
        // and neither should mean two requests a minute.
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "pulled")))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            pollIntervalMs = 10_000,
        )

        holder.start()
        holder.start()
        runCurrent()

        assertEquals(1, repository.chapterNotificationCalls)
        holder.clear()
    }

    @Test
    fun `signing out stops the poll rather than leaving it running`() = runTest {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "pulled")))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            pollIntervalMs = 10_000,
        )
        holder.start()
        runCurrent()
        val whileSignedIn = repository.chapterNotificationCalls

        holder.sessionEnded()
        testScheduler.advanceTimeBy(60_000)
        runCurrent()

        // A request with no credential would fail every interval behind the login screen.
        assertEquals(whileSignedIn, repository.chapterNotificationCalls)
        holder.clear()
    }

    // --- the pure rules -------------------------------------------------------------------------

    @Test
    fun `an unknown state reads as still converting, never as ready`() {
        // Guessing Ready would offer a Play button for audio that may not exist.
        assertEquals(ChapterNotificationState.Pulled, ChapterNotificationState.fromWire("something-new"))
        assertEquals(ChapterNotificationState.Pulled, ChapterNotificationState.fromWire(null))
        assertEquals(ChapterNotificationState.Stalled, ChapterNotificationState.fromWire("stalled"))
    }

    @Test
    fun `the first look seeds and announces nothing`() {
        val ready = listOf(notice(1, "ready"), notice(2, "ready"))

        val (fresh, seen) = newlyReady(ready, alreadySeen = null)

        assertTrue(fresh.isEmpty())
        assertEquals(setOf(ReadyNotificationKey(1, null), ReadyNotificationKey(2, null)), seen)
    }

    @Test
    fun `several chapters at once collapse into one line`() {
        // A serial converting a backlog would otherwise stack a dozen system notifications.
        val single = readyNotificationText(listOf(notice(1, "ready")))
        assertEquals("A Test Serial", single?.first)

        val many = readyNotificationText(
            listOf(notice(1, "ready"), notice(2, "ready", fictionTitle = "Another Serial")),
        )
        assertEquals("2 chapters ready", many?.first)
        assertEquals("New audio in 2 serials", many?.second)

        assertNull(readyNotificationText(emptyList()))
    }

    @Test
    fun `a converting row says how far it has got`() {
        assertEquals("Chapter 1  ·  converting 62%", notice(1, "pulled", progress = 62).detailLabel())
        assertEquals("Chapter 1  ·  converting", notice(1, "pulled").detailLabel())
        assertEquals("Chapter 1  ·  ready to listen", notice(1, "ready").detailLabel())
        assertEquals("Chapter 1  ·  conversion failed", notice(1, "stalled", chapterStatus = "error").detailLabel())
        assertEquals("Chapter 1  ·  audio unavailable", notice(1, "stalled", chapterStatus = "excluded").detailLabel())
    }

    @Test
    fun `reused ready notice id with changed ready_at announces again post-baseline`() = runTest {
        val announcements = Announcements()
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "ready", readyAt = "2026-09-24T10:00:00Z")))
        }
        val holder = ChapterNotificationsStateHolder(
            repository,
            UnconfinedTestDispatcher(testScheduler),
            notify = announcements::invoke,
        )

        holder.refresh()
        runCurrent()
        assertTrue(announcements.raised.isEmpty(), "initial baseline announces nothing")

        repository.chapterNotificationsResult = Result.success(
            response(notice(1, "ready", readyAt = "2026-09-24T11:00:00Z")),
        )
        holder.refresh()
        runCurrent()
        assertEquals(1, announcements.raised.size, "changed ready_at announces")
        holder.clear()
    }

    @Test
    fun `backlog notifications format properly and display server message`() {
        val backlog = notice(10, "ready", kind = "backlog", backlogSeconds = 7200.0, message = "2 hours ready to listen")
        assertEquals("Backlog alert", backlog.detailLabel())
        assertEquals("2 hours ready to listen", backlog.noticeMessage())

        val notification = readyNotificationText(listOf(backlog))
        assertEquals("Backlog alert · A Test Serial", notification?.first)
        assertEquals("2 hours ready to listen", notification?.second)
    }

    @Test
    fun `refused dismissal sets error and busy guards concurrent dismissals`() = runTest {
        val repository = FakeRepository().apply {
            chapterNotificationsResult = Result.success(response(notice(1, "ready")))
            dismissedNotificationResult = Result.success(false)
        }
        val holder = ChapterNotificationsStateHolder(repository, UnconfinedTestDispatcher(testScheduler))
        holder.refresh()
        runCurrent()
        repository.chapterNotificationsResult = Result.success(response())

        holder.dismiss(holder.state.value.notifications.single())
        runCurrent()

        assertTrue(holder.state.value.notifications.isEmpty())
        assertEquals("The server refused to clear that notice. Refresh and try again.", holder.state.value.error)
        holder.clear()
    }
}
