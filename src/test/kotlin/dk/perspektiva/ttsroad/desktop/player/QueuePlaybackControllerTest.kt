package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
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
    ) = QueuePlaybackController(
        repository = repository,
        sources = sources,
        engine = engine,
        ioDispatcher = Dispatchers.Default,
        retryDelaysMs = retryDelaysMs,
        tickIntervalMs = 10,
    )

    private suspend fun PlaybackController.await(
        description: String,
        predicate: (PlayerUiState) -> Boolean,
    ): PlayerUiState = try {
        withTimeout(15_000) { state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        fail("timed out waiting for: $description; last state was ${state.value}")
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

        assertEquals(2.0f, controller.state.value.speed)
        assertEquals(listOf(3.0f), engine.requestedRates.toList())
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

        assertTrue(
            repository.savedProgress.any { it.first == 101 && it.second == 42.0 && !it.third },
            "expected a save on pause; got ${repository.savedProgress}",
        )
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

        assertTrue(
            repository.savedProgress.any { it.first == 101 && it.third },
            "expected an is_played save; got ${repository.savedProgress}",
        )
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
        controller.await("the seek to be saved") {
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
}
