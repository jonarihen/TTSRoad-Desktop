package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakePlaybackController
import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.ServerQueueAction
import dk.perspektiva.ttsroad.desktop.data.ServerQueueItem
import dk.perspektiva.ttsroad.desktop.data.ServerQueueMode
import dk.perspektiva.ttsroad.desktop.data.ServerQueueResponse
import dk.perspektiva.ttsroad.desktop.data.ServerQueueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The queue holder, driven from a plain `runTest` against the fake repository.
 *
 * Two rules get most of the attention because both are invisible in a screenshot: "the server owns
 * the result" (every mutation republishes what came back rather than what was asked for), and the
 * null/empty distinction that decides whether the surface appears at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerQueueStateHolderTest {
    private fun holder(
        repository: FakeRepository,
        playback: FakePlaybackController = FakePlaybackController(),
    ) = ServerQueueStateHolder(repository, playback, UnconfinedTestDispatcher())

    private fun queueOf(vararg items: ServerQueueItem) =
        ServerQueueResponse(items = items.toList(), total = items.size, maxItems = 500)

    private fun item(id: Int, chapterId: Int = id, title: String = "Chapter $id") =
        ServerQueueItem(id = id, chapterId = chapterId, chapterTitle = title, fictionId = 1)

    // --- Loading -----------------------------------------------------------------------------

    @Test
    fun `loads the queue on first entry`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val holder = holder(repository)

        holder.ensureLoaded()

        assertEquals(3, holder.state.value.items.size)
        assertEquals("stop", holder.state.value.whenEmpty)
        assertEquals(500, holder.state.value.maxItems)
        assertEquals(1, repository.queueCalls)
    }

    @Test
    fun `a second visit reuses what is already loaded`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val holder = holder(repository)

        holder.ensureLoaded()
        holder.ensureLoaded()

        assertEquals(1, repository.queueCalls)
    }

    @Test
    fun `refresh always re-asks`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val holder = holder(repository)

        holder.ensureLoaded()
        holder.refresh()

        assertEquals(2, repository.queueCalls)
    }

    /**
     * The distinction the whole surface hangs on: a server with no queue API hides the feature,
     * an empty queue shows it with actions that can fill it.
     */
    @Test
    fun `a null answer is unsupported, not an empty queue`() = runTest {
        val holder = holder(FakeRepository(queueResult = Result.success(null)))

        holder.ensureLoaded()

        assertTrue(holder.state.value.unsupported)
        assertNull(holder.state.value.loaded)
        assertNull(holder.state.value.error)
    }

    @Test
    fun `an empty queue is loaded and supported`() = runTest {
        val holder = holder(FakeRepository(queueResult = Result.success(queueOf())))

        holder.ensureLoaded()

        assertFalse(holder.state.value.unsupported)
        assertEquals(emptyList(), holder.state.value.loaded)
    }

    /** An unsupported answer must not be re-asked on every visit to the screen. */
    @Test
    fun `unsupported is not re-asked`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(null))
        val holder = holder(repository)

        holder.ensureLoaded()
        holder.ensureLoaded()

        assertEquals(1, repository.queueCalls)
    }

    @Test
    fun `a failed load surfaces an error and keeps what was on screen`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(ParsedFixtures.queue))
        val holder = holder(repository)
        holder.ensureLoaded()

        repository.queueResult = Result.failure(java.io.IOException("network down"))
        holder.refresh()

        assertEquals(3, holder.state.value.items.size)
        assertTrue(holder.state.value.error!!.isNotBlank())
    }

    // --- Mutations ---------------------------------------------------------------------------

    @Test
    fun `add posts the chapter ids at the end`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)

        holder.addChapters(listOf(101, 102))

        val request = repository.queueRequests.single()
        assertEquals(ServerQueueAction.Add, request.action)
        assertEquals(listOf(101, 102), request.chapterIds)
        assertEquals(ServerQueueMode.End, request.mode)
    }

    @Test
    fun `play next posts the next mode`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)

        holder.addChapters(listOf(101), ServerQueueMode.Next)

        assertEquals(ServerQueueMode.Next, repository.queueRequests.single().mode)
    }

    @Test
    fun `adding nothing sends no request`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf()))
        val holder = holder(repository)

        holder.addChapters(emptyList())

        assertTrue(repository.queueRequests.isEmpty())
    }

    @Test
    fun `fill asks the server to choose this fiction's unplayed chapters`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)

        holder.fillFromFiction(fictionId = 7)

        val request = repository.queueRequests.single()
        assertEquals(ServerQueueAction.Fill, request.action)
        assertEquals(ServerQueueSource.FictionUnplayed, request.source)
        assertEquals(7, request.fictionId)
        // The ids are the server's business here — sending any would be this client second-guessing it.
        assertTrue(request.chapterIds.isEmpty())
    }

    /** Removal addresses the queue row, never the chapter: the same chapter can be queued twice. */
    @Test
    fun `remove addresses the queue row id`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf()))
        val holder = holder(repository)

        holder.remove(ServerQueueItem(id = 4802, chapterId = 101, chapterTitle = "Chapter one"))

        val request = repository.queueRequests.single()
        assertEquals(ServerQueueAction.Remove, request.action)
        assertEquals(listOf(4802), request.itemIds)
        assertTrue(request.chapterIds.isEmpty())
    }

    @Test
    fun `move posts the complete resulting order`() = runTest {
        val repository = FakeRepository(
            queueResult = Result.success(queueOf(item(11), item(22), item(33))),
        )
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.move(0, 2)

        val request = repository.queueRequests.single()
        assertEquals(ServerQueueAction.Reorder, request.action)
        assertEquals(listOf(22, 33, 11), request.itemIds)
    }

    @Test
    fun `a move that changes nothing sends no request`() = runTest {
        val repository = FakeRepository(
            queueResult = Result.success(queueOf(item(11), item(22))),
        )
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.move(0, 5)

        assertTrue(repository.queueRequests.isEmpty())
    }

    /**
     * The server caps, de-duplicates and drops unknown ids, so the notice reports what the queue
     * actually gained. "Added 3 chapters" beside a queue that grew by none is a visible lie.
     */
    @Test
    fun `the notice reports what the queue actually gained`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)
        holder.ensureLoaded()

        // The answer still holds one row: nothing was added.
        holder.addChapters(listOf(101, 102, 103))

        assertEquals("Those chapters are already in the queue", holder.state.value.notice)
    }

    @Test
    fun `the notice counts a real addition`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)
        holder.ensureLoaded()

        repository.queueResult = Result.success(queueOf(item(1), item(2), item(3)))
        holder.addChapters(listOf(101, 102))

        assertEquals("Added 2 chapters to the queue", holder.state.value.notice)
    }

    @Test
    fun `a mutation replaces the list with what the server answered`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1), item(2))))
        val holder = holder(repository)
        holder.ensureLoaded()

        repository.queueResult = Result.success(queueOf(item(9)))
        holder.remove(item(1))

        assertEquals(listOf(9), holder.state.value.items.map { it.id })
    }

    @Test
    fun `a failed mutation surfaces an error and keeps the list`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1), item(2))))
        val holder = holder(repository)
        holder.ensureLoaded()

        repository.queueResult = Result.failure(java.io.IOException("network down"))
        holder.remove(item(1))

        assertEquals(2, holder.state.value.items.size)
        assertTrue(holder.state.value.error!!.isNotBlank())
        assertFalse(holder.state.value.isBusy)
    }

    /** At the cap the server drops further adds, so the screen has to be able to say so. */
    @Test
    fun `a queue at its cap reports itself full`() = runTest {
        val full = ServerQueueResponse(items = listOf(item(1), item(2)), total = 2, maxItems = 2)
        val holder = holder(FakeRepository(queueResult = Result.success(full)))

        holder.ensureLoaded()

        assertTrue(holder.state.value.isFull)
    }

    @Test
    fun `a queue below its cap is not full`() = runTest {
        val room = ServerQueueResponse(items = listOf(item(1)), total = 1, maxItems = 500)
        val holder = holder(FakeRepository(queueResult = Result.success(room)))

        holder.ensureLoaded()

        assertFalse(holder.state.value.isFull)
    }

    /** A server that does not report a cap must not be treated as a queue with no room. */
    @Test
    fun `no advertised cap is never full`() = runTest {
        val holder = holder(
            FakeRepository(queueResult = Result.success(ServerQueueResponse(items = listOf(item(1))))),
        )

        holder.ensureLoaded()

        assertFalse(holder.state.value.isFull)
    }

    // --- Clearing ----------------------------------------------------------------------------

    @Test
    fun `clearing asks first and only then posts`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.askClear()
        assertTrue(holder.state.value.confirmingClear)
        assertTrue(repository.queueRequests.isEmpty())

        holder.confirmClear()
        assertFalse(holder.state.value.confirmingClear)
        assertEquals(ServerQueueAction.Clear, repository.queueRequests.single().action)
    }

    @Test
    fun `cancelling the confirmation clears nothing`() = runTest {
        val repository = FakeRepository(queueResult = Result.success(queueOf(item(1))))
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.askClear()
        holder.dismissConfirmation()
        holder.confirmClear()

        assertTrue(repository.queueRequests.isEmpty())
    }

    @Test
    fun `there is nothing to confirm on an empty queue`() = runTest {
        val holder = holder(FakeRepository(queueResult = Result.success(queueOf())))
        holder.ensureLoaded()

        holder.askClear()

        assertFalse(holder.state.value.confirmingClear)
    }

    // --- Playing -----------------------------------------------------------------------------

    /**
     * The design decision, pinned: playing a row goes through the ordinary player with that row's
     * own fiction, and never through the server's `advance` action.
     */
    @Test
    fun `playing a row starts its fiction through the ordinary player`() = runTest {
        val chapters = ParsedFixtures.chapters
        val repository = FakeRepository(
            chaptersResult = Result.success(chapters),
            queueResult = Result.success(queueOf()),
        )
        val playback = FakePlaybackController()
        val holder = holder(repository, playback)

        holder.play(ServerQueueItem(id = 4801, chapterId = 102, fictionId = 1))

        assertEquals(listOf("playQueue(102)"), playback.calls)
        // No queue mutation at all — in particular, no `advance`.
        assertTrue(repository.queueRequests.isEmpty())
    }

    @Test
    fun `playing does not remove the row from the queue`() = runTest {
        val repository = FakeRepository(
            chaptersResult = Result.success(ChaptersResponse(fiction = FictionSummary(id = 1))),
            queueResult = Result.success(queueOf(item(1))),
        )
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.play(item(1))

        assertEquals(listOf(1), holder.state.value.items.map { it.id })
        assertTrue(repository.queueRequests.isEmpty())
    }

    @Test
    fun `a chapter list that cannot be loaded becomes an error, not a crash`() = runTest {
        val repository = FakeRepository(
            chaptersResult = Result.failure(java.io.IOException("network down")),
            queueResult = Result.success(queueOf(item(1))),
        )
        val holder = holder(repository)
        holder.ensureLoaded()

        holder.play(item(1))

        assertTrue(holder.state.value.error!!.isNotBlank())
        assertFalse(holder.state.value.isBusy)
    }

    // --- Session end -------------------------------------------------------------------------

    @Test
    fun `the queue is dropped when the session ends`() = runTest {
        val holder = holder(FakeRepository(queueResult = Result.success(ParsedFixtures.queue)))
        holder.ensureLoaded()

        holder.sessionEnded()

        assertNull(holder.state.value.loaded)
        assertFalse(holder.state.value.unsupported)
    }
}

/** The screen's pure label rules. */
class ServerQueueLabelsTest {
    private fun state(
        loaded: List<ServerQueueItem>? = null,
        unsupported: Boolean = false,
    ) = ServerQueueUiState(loaded = loaded, unsupported = unsupported)

    @Test
    fun `an unloaded queue does not claim a count`() {
        assertEquals("Up next", queueCountLabel(state()))
    }

    @Test
    fun `an unsupported server says so rather than showing zero`() {
        assertEquals("Up next — unavailable", queueCountLabel(state(unsupported = true)))
    }

    @Test
    fun `one chapter is singular`() {
        val one = listOf(ServerQueueItem(id = 1, chapterId = 1))

        assertEquals("Up next — 1 chapter", queueCountLabel(state(loaded = one)))
    }

    @Test
    fun `more than one chapter is plural`() {
        val two = listOf(ServerQueueItem(id = 1, chapterId = 1), ServerQueueItem(id = 2, chapterId = 2))

        assertEquals("Up next — 2 chapters", queueCountLabel(state(loaded = two)))
    }

    @Test
    fun `an empty loaded queue is plural zero, not unavailable`() {
        assertEquals("Up next — 0 chapters", queueCountLabel(state(loaded = emptyList())))
    }

    /**
     * The honesty requirement: this client does not call `advance`, so the preference governs the
     * other clients. Saying "other clients" is what stops a user reading it as a promise here.
     */
    @Test
    fun `when-empty is explained as other clients' behaviour`() {
        assertTrue(whenEmptyExplanation("continue").contains("other clients"))
        assertTrue(whenEmptyExplanation("stop").contains("other clients"))
    }

    /** An unknown value from a newer server is shown verbatim rather than guessed at. */
    @Test
    fun `an unknown when-empty value is passed through`() {
        assertEquals("When this queue empties: shuffle", whenEmptyExplanation("shuffle"))
    }
}

/** The pure notice rule, which is where all the "do not claim more than happened" logic lives. */
class AddedNoticeTest {
    @Test
    fun `nothing added to one requested`() {
        assertEquals(
            "That chapter is already in the queue",
            addedNotice(requested = 1, added = 0, mode = ServerQueueMode.End),
        )
    }

    @Test
    fun `nothing added to several requested`() {
        assertEquals(
            "Those chapters are already in the queue",
            addedNotice(requested = 3, added = 0, mode = ServerQueueMode.End),
        )
    }

    /** A partial add reports the truth, not the request. */
    @Test
    fun `fewer added than requested reports the smaller number`() {
        assertEquals(
            "Added 2 chapters to the queue",
            addedNotice(requested = 5, added = 2, mode = ServerQueueMode.End),
        )
    }

    @Test
    fun `play next says where it went`() {
        assertEquals("Added 1 chapter next", addedNotice(requested = 1, added = 1, mode = ServerQueueMode.Next))
    }
}
