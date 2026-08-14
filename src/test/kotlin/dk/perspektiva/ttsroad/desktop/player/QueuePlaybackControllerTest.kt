package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * The playback state machine, driven against a fake engine.
 *
 * These use `runBlocking` rather than `runTest`: the controller runs on a real dispatcher and the
 * assertions wait on real emissions, so virtual time would be measuring the wrong clock.
 */
class QueuePlaybackControllerTest {

    private fun chapter(id: Int, title: String, durationSeconds: Double, position: Double = 0.0) =
        ChapterSummary(
            id = id,
            fictionId = 7,
            title = title,
            audioDuration = durationSeconds,
            playable = true,
            audio = AudioInfo(url = "/audio/a-test-serial/$id.mp3"),
            playback = dk.perspektiva.ttsroad.desktop.data.PlaybackInfo(positionSeconds = position),
        )

    private fun controllerFor(
        engine: FakePlaybackEngine,
        sources: FakeMediaSourceFactory = FakeMediaSourceFactory(),
        repository: FakeRepository = FakeRepository(),
        retryDelaysMs: List<Long> = emptyList(),
        history: InMemoryPlaybackHistoryStore = InMemoryPlaybackHistoryStore(),
        ownerKey: () -> String = { "" },
        historyRecordIntervalMs: Long = 5 * 60_000L,
    ) = QueuePlaybackController(
        repository = repository,
        sources = sources,
        engine = engine,
        ioDispatcher = Dispatchers.Default,
        historyStore = history,
        ownerKey = ownerKey,
        retryDelaysMs = retryDelaysMs,
        tickIntervalMs = 10,
        historyRecordIntervalMs = historyRecordIntervalMs,
    )

    private suspend fun PlaybackController.await(
        description: String,
        predicate: (PlayerUiState) -> Boolean,
    ): PlayerUiState = try {
        withTimeout(15_000) { state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        fail("timed out waiting for: $description; last state was ${state.value}")
    }

    /**
     * Waits on something that is *not* published through [PlaybackController.state].
     *
     * `state.first { }` cannot do this job: a StateFlow conflates equal values, so once the player
     * has settled it stops emitting and a predicate over the repository is never re-evaluated. The
     * saves below are also launched asynchronously, so asserting on them straight after the call
     * that triggers them is a race either way.
     */
    private suspend fun awaitCondition(description: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(10)
        }
        fail("timed out waiting for: $description")
    }

    private fun finished(state: PlayerUiState) = state.hasMedia && !state.isPlaying && state.error == null

    @Test
    fun `playing a single chapter prepares it, plays it and reports completion`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val sources = FakeMediaSourceFactory()
        val repository = FakeRepository()
        val controller = controllerFor(engine, sources, repository)

        controller.play(
            chapter(101, "Chapter 3", durationSeconds = 1.0),
            FictionSummary(id = 7, title = "A Test Serial"),
        )
        controller.await("playback to finish", ::finished)

        assertEquals(listOf("/audio/a-test-serial/101.mp3"), sources.requested.toList())
        assertEquals(1, engine.prepareCount.get())
        assertEquals(1, engine.playCount.get())
        // Reaching end-of-stream marks the chapter played at its full duration.
        assertTrue(
            repository.savedProgress.contains(Triple(101, 1.0, true)),
            "expected an is_played save; got ${repository.savedProgress}",
        )
        controller.release()
    }

    @Test
    fun `metadata is published before any audio is fetched`() = runBlocking {
        val engine = FakePlaybackEngine(blockPrepare = true)
        val controller = controllerFor(engine)

        controller.play(
            chapter(101, "Chapter 3 — The Descent", durationSeconds = 1200.0),
            FictionSummary(id = 7, title = "A Test Serial", coverImageUrl = "/cover/a.jpg"),
        )

        val state = controller.state.value
        assertEquals("Chapter 3 — The Descent", state.title)
        assertEquals("A Test Serial", state.fictionTitle)
        assertEquals(1_200_000L, state.durationMs, "duration comes from server metadata, not the decoder")
        // Covers are resolved against the session server before they ever reach Coil.
        assertEquals("https://ttsroad.example.com/cover/a.jpg", state.coverImageUrl)
        assertFalse(state.hasMedia, "nothing has been prepared yet")

        engine.releasePrepare()
        controller.release()
    }

    @Test
    fun `a queue auto-advances to the next chapter`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val sources = FakeMediaSourceFactory()
        val repository = FakeRepository()
        val controller = controllerFor(engine, sources, repository)

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", 1.0), chapter(102, "Chapter 4", 1.0)),
            startChapterId = 101,
            fiction = FictionSummary(id = 7, title = "A Test Serial"),
        )
        controller.await("the queue to reach its last chapter") { it.currentIndex == 1 && finished(it) }

        assertEquals(2, sources.requested.size)
        assertEquals(2, engine.prepareCount.get())
        assertFalse(controller.state.value.hasNext, "the last queue entry has no next")
        assertTrue(controller.state.value.hasPrevious)
        assertTrue(repository.savedProgress.any { it.first == 101 && it.third })
        assertTrue(repository.savedProgress.any { it.first == 102 && it.third })
        controller.release()
    }

    @Test
    fun `resuming asks the engine to start at the stored position rather than at zero`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val controller = controllerFor(engine)

        controller.play(chapter(101, "Chapter 3", durationSeconds = 4.0, position = 2.0), null)
        controller.await("playback to finish", ::finished)

        assertEquals(listOf(2_000L), engine.preparedPositions.toList())
        controller.release()
    }

    @Test
    fun `opening a bookmark starts at the mark rather than at the saved position`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val controller = controllerFor(engine)

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", durationSeconds = 900.0, position = 30.0)),
            startChapterId = 101,
            fiction = FictionSummary(id = 7, title = "A Test Serial"),
            startPositionMs = 742_500L,
        )
        controller.await("playback to finish", ::finished)

        // Resuming at 0:30 here would silently ignore the mark the user just clicked.
        assertEquals(listOf(742_500L), engine.preparedPositions.toList())
        controller.release()
    }

    @Test
    fun `a bookmark on a chapter that is no longer playable falls back to the saved position`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val controller = controllerFor(engine)

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", durationSeconds = 900.0, position = 30.0)),
            // The chapter the mark points at has lost its audio, so the queue starts elsewhere —
            // and seeking *that* chapter to the missing one's offset would be nonsense.
            startChapterId = 999,
            fiction = FictionSummary(id = 7, title = "A Test Serial"),
            startPositionMs = 742_500L,
        )
        controller.await("playback to finish", ::finished)

        assertEquals(listOf(30_000L), engine.preparedPositions.toList())
        controller.release()
    }

    @Test
    fun `a chapter with no audio at all reports a clear error instead of playing`() = runBlocking {
        val controller = controllerFor(FakePlaybackEngine())

        controller.play(ChapterSummary(id = 100, title = "Pending"), null)

        assertEquals("This chapter has no audio yet", controller.state.value.error)
        assertFalse(controller.state.value.hasMedia)
        controller.release()
    }

    @Test
    fun `a queue with nothing playable reports an error`() = runBlocking {
        val controller = controllerFor(FakePlaybackEngine())

        controller.playQueue(listOf(ChapterSummary(id = 100, title = "Pending")), startChapterId = 100, fiction = null)

        assertEquals("No playable chapters yet", controller.state.value.error)
        controller.release()
    }

    @Test
    fun `transport controls are inert until something is loaded`() = runBlocking {
        val engine = FakePlaybackEngine(blockPrepare = true)
        val controller = controllerFor(engine)

        controller.togglePlayPause()
        controller.seekTo(5_000)
        controller.skipBy(30_000)

        assertEquals(PlayerUiState(canChangeSpeed = true), controller.state.value)
        assertEquals(0, engine.seeks.size)
        controller.release()
    }

    // --- speed ------------------------------------------------------------------------------

    @Test
    fun `setSpeed reports what the engine applied, not what was asked for`() = runBlocking {
        // The engine clamps to its own range; the UI must show the played rate, never the wish.
        val engine = FakePlaybackEngine(
            capabilities = EngineCapabilities(variableSpeed = true, speedRange = 0.5f..2.0f),
            blockPrepare = true,
        )
        val controller = controllerFor(engine)

        controller.setSpeed(3.0f)

        // Asynchronous since Phase 6: `setSpeed` writes the preference, and the collector in the
        // controller is what pushes it to the engine and back into the state. The assertion is
        // unchanged — the UI shows the rate the engine accepted, never the wish.
        controller.await("the clamped rate to be published") { it.speed == 2.0f }
        // 3.0 was asked for and 2.0 came back; the request itself is not pre-clamped by the caller.
        assertTrue(engine.requestedRates.contains(3.0f), "the engine was never asked for 3.0x")
        controller.release()
    }

    @Test
    fun `an engine without rate control advertises that and always plays at 1x`() = runBlocking {
        val engine = FakePlaybackEngine(capabilities = EngineCapabilities.FixedSpeed, blockPrepare = true)
        val controller = controllerFor(engine)

        assertFalse(controller.state.value.canChangeSpeed, "the UI must not draw a control that does nothing")

        controller.setSpeed(1.5f)

        assertEquals(1f, controller.state.value.speed)
        controller.release()
    }

    // --- failures and recovery --------------------------------------------------------------

    @Test
    fun `a transient failure retries and a recovery clears the error`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        engine.prepareFailure = transientFailure("Connection reset")
        // One quick attempt in the ladder, so the recovery below happens on the retry.
        val controller = controllerFor(engine, retryDelaysMs = listOf(10))

        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("the first failure to surface") { it.error != null }

        // The connection comes back before the ladder is exhausted.
        engine.prepareFailure = null
        controller.await("the retry to succeed and clear the error") { it.error == null && it.hasMedia }

        assertTrue(engine.prepareCount.get() >= 2, "the failure must have been retried")
        assertFalse(controller.state.value.canRetry)
        controller.release()
    }

    @Test
    fun `once the ladder is exhausted the UI is offered a Retry`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.prepareFailure = transientFailure("Connection reset")
        val controller = controllerFor(engine, retryDelaysMs = listOf(5, 5))

        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("the ladder to be exhausted") { it.canRetry }

        // Three attempts: the first, plus one for each rung.
        assertEquals(3, engine.prepareCount.get())
        assertEquals("Connection reset", controller.state.value.error)
        assertFalse(controller.state.value.isPlaying)
        controller.release()
    }

    @Test
    fun `a failure is never mistaken for a finished chapter`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.prepareFailure = transientFailure("Connection reset")
        val sources = FakeMediaSourceFactory()
        val controller = controllerFor(engine, sources)

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", 1.0), chapter(102, "Chapter 4", 1.0)),
            startChapterId = 101,
            fiction = null,
        )
        controller.await("the failure to surface") { it.canRetry }

        // Auto-advancing past a chapter that failed to load would silently skip it.
        assertEquals(0, controller.state.value.currentIndex)
        assertTrue(sources.requested.all { it.endsWith("101.mp3") }, "got ${sources.requested}")
        controller.release()
    }

    // --- progress ---------------------------------------------------------------------------

    @Test
    fun `pausing saves progress at the position the engine reported`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.durationOnPrepare = 600_000
        val repository = FakeRepository()
        val controller = controllerFor(engine, repository = repository)

        controller.play(chapter(101, "Chapter 3", 600.0), null)
        controller.await("playback to start") { it.isPlaying }
        engine.setPosition(42_000)
        controller.await("the tick to pick the position up") { it.positionMs == 42_000L }

        controller.togglePlayPause()
        controller.await("the pause to land") { !it.isPlaying }

        awaitCondition("a save on pause at the reported position") {
            repository.savedProgress.any { it.first == 101 && it.second == 42.0 && !it.third }
        }
        controller.release()
    }

    @Test
    fun `playing continuously records a jump-back breadcrumb every five minutes`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.durationOnPrepare = 600_000
        val history = InMemoryPlaybackHistoryStore()
        val controller = controllerFor(
            engine = engine,
            history = history,
            ownerKey = { "owner" },
            // Thirty milliseconds in the test represents the production five-minute cadence.
            historyRecordIntervalMs = 30,
        )

        controller.play(chapter(101, "Chapter 3", 600.0), FictionSummary(id = 7, title = "A Test Serial"))
        controller.await("playback to start") { it.isPlaying }
        engine.setPosition(45_000)

        awaitCondition("a periodic history record while playback continues") { history.history.value.isNotEmpty() }

        val snapshot = history.history.value.single()
        assertEquals(101, snapshot.chapterId)
        assertEquals(45.0, snapshot.positionSeconds)
        assertEquals("owner", snapshot.ownerKey)
        controller.release()
    }

    @Test
    fun `a chapter counts as played at 96 percent without a byte-perfect end of stream`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.durationOnPrepare = 100_000
        val repository = FakeRepository()
        val controller = controllerFor(engine, repository = repository)

        controller.play(chapter(101, "Chapter 3", 100.0), null)
        controller.await("playback to start") { it.isPlaying }
        // 97% in, then the user closes the window — the old controller saved this as unplayed.
        engine.setPosition(97_000)
        controller.await("the tick to pick the position up") { it.positionMs == 97_000L }

        controller.togglePlayPause()
        controller.await("the pause to land") { !it.isPlaying }

        awaitCondition("an is_played save without a byte-perfect end of stream") {
            repository.savedProgress.any { it.first == 101 && it.third }
        }
        controller.release()
    }

    @Test
    fun `seeking saves progress so another client resumes in the right place`() = runBlocking {
        val engine = FakePlaybackEngine()
        engine.durationOnPrepare = 600_000
        val repository = FakeRepository()
        val controller = controllerFor(engine, repository = repository)

        controller.play(chapter(101, "Chapter 3", 600.0), null)
        controller.await("playback to start") { it.isPlaying }

        controller.seekTo(120_000)

        assertEquals(listOf(120_000L), engine.seeks.toList())
        awaitCondition("the seek to be saved") {
            repository.savedProgress.any { saved -> saved.first == 101 && saved.second == 120.0 }
        }
        controller.release()
    }

    @Test
    fun `stop clears the session and releases the engine's source`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val controller = controllerFor(engine)
        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("playback to finish", ::finished)

        controller.stop()
        controller.await("the player to clear") { !it.hasMedia && it.title == "Nothing playing" }

        assertEquals(PlayerUiState(canChangeSpeed = true), controller.state.value)
        assertTrue(engine.stopCount.get() > 0, "the engine must be told to release its source")
        controller.release()
    }

    @Test
    fun `release closes the engine so no native handle outlives the window`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val controller = controllerFor(engine)
        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("playback to finish", ::finished)

        controller.release()

        assertEquals(1, engine.closeCount.get())
    }

    // --- Starting one chapter starts the serial --------------------------------------------

    @Test
    fun `playing a single chapter still queues the rest of the fiction`() = runBlocking {
        // The library's continue-listening rows call play() with one chapter. Before this, that
        // built a queue of exactly one: Next was disabled and the chapter's end stopped playback,
        // while the same chapter started from the fiction screen carried the whole serial.
        val chapters = listOf(
            chapter(1, "One", 10.0),
            chapter(2, "Two", 10.0),
            chapter(3, "Three", 10.0),
        )
        val repository = FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(fiction = FictionSummary(id = 7), chapters = chapters),
            ),
        )
        val controller = controllerFor(FakePlaybackEngine(), repository = repository)

        controller.play(chapters[1], FictionSummary(id = 7))

        val state = controller.await("the queue holds the whole fiction") { it.queue.size == 3 }
        assertEquals(1, state.currentIndex)
        assertTrue(state.hasNext, "a middle chapter must be able to advance")
    }

    @Test
    fun `a fiction whose chapters cannot be loaded still plays the one chapter`() = runBlocking {
        // Offline, or a chapter the fiction no longer lists. One chapter with nothing after it is
        // the honest state; refusing to play would be worse.
        val repository = FakeRepository(chaptersResult = Result.failure(IllegalStateException("offline")))
        val controller = controllerFor(FakePlaybackEngine(), repository = repository)

        controller.play(chapter(1, "Alone", 10.0), FictionSummary(id = 7))

        val state = controller.await("the single chapter is queued") { it.queue.size == 1 }
        assertFalse(state.hasNext, "nothing was loaded to advance to")
    }
}
