package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackInfo
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferences
import dk.perspektiva.ttsroad.desktop.data.VolumeBoost
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * That the listening preferences and the sleep timer actually reach the engine.
 *
 * The issue's requirement is that they apply "in the playback service/engine, not only the visible
 * player" — so these drive the controller, not a screen, and assert on what the *engine* was told.
 */
class PlaybackPreferencesApplyTest {

    private class FakeClock(var nowMs: Long = 500_000L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private fun chapter(id: Int, durationSeconds: Double = 600.0) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = "Chapter $id",
        audioDuration = durationSeconds,
        playable = true,
        audio = AudioInfo(url = "/audio/serial/$id.mp3"),
        playback = PlaybackInfo(positionSeconds = 0.0),
    )

    private fun controller(
        engine: FakePlaybackEngine,
        preferences: InMemoryPlaybackPreferencesStore = InMemoryPlaybackPreferencesStore(),
        history: InMemoryPlaybackHistoryStore = InMemoryPlaybackHistoryStore(),
        sleepTimer: SleepTimer = SleepTimer(),
        clock: () -> Long = System::currentTimeMillis,
    ) = QueuePlaybackController(
        repository = FakeRepository(),
        sources = FakeMediaSourceFactory(),
        engine = engine,
        ioDispatcher = Dispatchers.Default,
        preferencesStore = preferences,
        historyStore = history,
        sleepTimer = sleepTimer,
        clock = clock,
        retryDelaysMs = emptyList(),
        tickIntervalMs = 10,
    )

    /**
     * Waits for a published state, with a bound.
     *
     * A bare `state.first { }` that never matches hangs the whole test run rather than failing
     * this one test, which is a much worse way to find out something regressed.
     */
    private suspend fun PlaybackController.await(
        description: String,
        predicate: (PlayerUiState) -> Boolean,
    ): PlayerUiState = try {
        withTimeout(10_000) { state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        fail("timed out waiting for: $description; last state was ${state.value}")
    }

    private suspend fun awaitCondition(what: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            delay(10)
        }
        fail("timed out waiting for: $what")
    }

    // --- Preferences reaching the engine --------------------------------------------------------

    @Test
    fun `a saved speed reaches the engine before the first chapter, not one chapter late`() = runBlocking {
        val engine = FakePlaybackEngine()
        val preferences = InMemoryPlaybackPreferencesStore(PlaybackPreferences(speed = 1.5f))
        controller(engine, preferences)

        awaitCondition("the restored rate to be applied") { engine.requestedRates.contains(1.5f) }
    }

    @Test
    fun `a saved volume boost reaches the engine as a gain`() = runBlocking {
        val engine = FakePlaybackEngine()
        val preferences = InMemoryPlaybackPreferencesStore(
            PlaybackPreferences(volumeBoost = VolumeBoost.Medium),
        )
        controller(engine, preferences)

        awaitCondition("the boost to be applied") {
            engine.gains.any { kotlin.math.abs(it - VolumeBoost.Medium.gain) < 0.001 }
        }
    }

    @Test
    fun `changing skip silence reaches the engine`() = runBlocking {
        val engine = FakePlaybackEngine(
            capabilities = EngineCapabilities(variableSpeed = true, skipSilence = true),
        )
        val preferences = InMemoryPlaybackPreferencesStore()
        controller(engine, preferences)
        awaitCondition("the initial value") { !engine.skipSilenceEnabled }

        preferences.update { it.copy(skipSilence = true) }
        awaitCondition("skip silence to be enabled") { engine.skipSilenceEnabled }
    }

    @Test
    fun `setting the speed persists it rather than only telling the engine`() = runBlocking {
        // The preference is the source of truth; without this a restart loses the choice.
        val engine = FakePlaybackEngine()
        val preferences = InMemoryPlaybackPreferencesStore()
        val playback = controller(engine, preferences)

        playback.setSpeed(2f)
        awaitCondition("the speed to be stored") { preferences.preferences.value.speed == 2f }
        awaitCondition("the engine to be told") { engine.requestedRates.contains(2f) }
    }

    @Test
    fun `an engine that cannot resample still stores the wish`() = runBlocking {
        // So a listener who set 1.5x without GStreamer finds it waiting once they install it.
        val engine = FakePlaybackEngine(capabilities = EngineCapabilities.FixedSpeed)
        val preferences = InMemoryPlaybackPreferencesStore()
        val playback = controller(engine, preferences)

        playback.setSpeed(1.5f)
        awaitCondition("the wish to be stored") { preferences.preferences.value.speed == 1.5f }
        // ...but what is *shown* stays honest about what is playing.
        awaitCondition("the reported speed to stay at 1x") { playback.state.value.speed == 1f }
    }

    // --- Skip interval --------------------------------------------------------------------------

    @Test
    fun `skip forward and back use the configured interval`() = runBlocking {
        val engine = FakePlaybackEngine()
        val preferences = InMemoryPlaybackPreferencesStore(PlaybackPreferences(skipIntervalSeconds = 15))
        val playback = controller(engine, preferences)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("media to load") { it.hasMedia }
        engine.setDuration(600_000)
        awaitCondition("a duration") { playback.state.value.durationMs > 0 }

        playback.skipForward()
        awaitCondition("a 15 second skip") { engine.seeks.contains(15_000L) }

        playback.seekTo(100_000)
        playback.skipBackward()
        awaitCondition("a 15 second skip back") { engine.seeks.contains(85_000L) }
    }

    @Test
    fun `the skip interval is published so the buttons can label themselves`() = runBlocking {
        val preferences = InMemoryPlaybackPreferencesStore(PlaybackPreferences(skipIntervalSeconds = 45))
        val playback = controller(FakePlaybackEngine(), preferences)
        awaitCondition("the interval to reach the state") { playback.state.value.skipIntervalMs == 45_000L }
    }

    // --- Sleep timer ----------------------------------------------------------------------------

    @Test
    fun `the fade multiplies with the boost rather than replacing it`() = runBlocking {
        val engine = FakePlaybackEngine()
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        val preferences = InMemoryPlaybackPreferencesStore(
            PlaybackPreferences(volumeBoost = VolumeBoost.Low),
        )
        val playback = controller(engine, preferences, sleepTimer = timer, clock = clock)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("media to load") { it.hasMedia }

        playback.setSleepTimer(SleepTimerMode.Duration(5))
        // Into the fade: half way through the last 30 seconds.
        clock.nowMs += 5 * 60_000L - SleepTimer.FadeMs / 2

        val expected = VolumeBoost.Low.gain * 0.5
        awaitCondition("boost times fade") {
            engine.gains.any { kotlin.math.abs(it - expected) < 0.05 }
        }
    }

    @Test
    fun `an expiring timer pauses without dropping the queue`() = runBlocking {
        // A stop would mean hunting for the chapter in the morning; a pause means one keypress.
        val engine = FakePlaybackEngine()
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        val playback = controller(engine, sleepTimer = timer, clock = clock)

        playback.playQueue(listOf(chapter(1), chapter(2)), startChapterId = 1, fiction = null)
        playback.await("media to load") { it.hasMedia }

        playback.setSleepTimer(SleepTimerMode.Duration(5))
        clock.nowMs += 5 * 60_000L + 1_000

        playback.await("playback to pause") { !it.isPlaying }
        assertTrue(engine.pauseCount.get() > 0, "the engine was never paused")
        assertEquals(2, playback.state.value.queue.size, "the queue was cleared by the sleep timer")
        assertTrue(playback.state.value.hasMedia)
    }

    @Test
    fun `end of chapter stops instead of auto-advancing`() = runBlocking {
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val timer = SleepTimer()
        val playback = controller(engine, sleepTimer = timer)

        // Armed before playback starts, so the first boundary is the one that stops.
        playback.setSleepTimer(SleepTimerMode.EndOfChapter)
        playback.playQueue(listOf(chapter(1), chapter(2), chapter(3)), startChapterId = 1, fiction = null)

        playback.await("playback to stop at the chapter boundary") { !it.isPlaying && it.hasMedia }
        // Give any (incorrect) auto-advance a chance to happen before asserting it did not.
        delay(200)
        assertEquals(1, engine.prepareCount.get(), "the queue advanced past the sleep boundary")
    }

    @Test
    fun `a queue with no sleep timer still auto-advances`() = runBlocking {
        // The control for the test above: proves the assertion above is about the timer.
        val engine = FakePlaybackEngine(completeOnPlay = true)
        val playback = controller(engine)

        playback.playQueue(listOf(chapter(1), chapter(2)), startChapterId = 1, fiction = null)
        awaitCondition("the second chapter to start") { engine.prepareCount.get() >= 2 }
    }

    @Test
    fun `a manual pause freezes the timer and resuming continues it`() = runBlocking {
        val engine = FakePlaybackEngine()
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        val playback = controller(engine, sleepTimer = timer, clock = clock)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("playback to start") { it.hasMedia && it.isPlaying }

        playback.setSleepTimer(SleepTimerMode.Duration(30))
        clock.nowMs += 10 * 60_000L
        playback.togglePlayPause()

        // An hour of being paused must not run the timer down.
        clock.nowMs += 60 * 60_000L
        delay(100)
        assertTrue(playback.state.value.sleepTimer.isArmed, "the timer expired while paused")
        assertEquals(20 * 60_000L, playback.state.value.sleepTimer.remainingMs)
    }

    @Test
    fun `a timer armed while already paused does not count down`() = runBlocking {
        val engine = FakePlaybackEngine()
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        val playback = controller(engine, sleepTimer = timer, clock = clock)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("playback to start") { it.hasMedia && it.isPlaying }

        // Pause *first*, then decide to set a timer. The freeze-on-pause path already ran, back
        // when there was no deadline to freeze, and the tick loop keeps running through a pause —
        // so without arming into a frozen state this counts down against silence.
        playback.togglePlayPause()
        playback.await("the pause to land") { !it.isPlaying }
        playback.setSleepTimer(SleepTimerMode.Duration(30))

        clock.nowMs += 60 * 60_000L
        delay(100)
        assertTrue(playback.state.value.sleepTimer.isArmed, "the timer expired while paused")
        assertEquals(30 * 60_000L, playback.state.value.sleepTimer.remainingMs)

        // And it starts running only once audio actually resumes.
        playback.togglePlayPause()
        playback.await("playback to resume") { it.isPlaying }
        clock.nowMs += 10 * 60_000L
        delay(100)
        assertEquals(20 * 60_000L, playback.state.value.sleepTimer.remainingMs)
    }

    @Test
    fun `starting another chapter unfreezes a timer armed while paused`() = runBlocking {
        // Only togglePlayPause used to resume a frozen countdown, but audio also starts when a new
        // chapter begins or a failed one is retried. Without unfreezing there, the timer sat still
        // while playback ran on indefinitely — the display frozen and the sleep never arriving.
        val engine = FakePlaybackEngine()
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        val playback = controller(engine, sleepTimer = timer, clock = clock)

        playback.playQueue(listOf(chapter(1), chapter(2)), startChapterId = 1, fiction = null)
        playback.await("playback to start") { it.hasMedia && it.isPlaying }

        playback.togglePlayPause()
        playback.await("the pause to land") { !it.isPlaying }
        playback.setSleepTimer(SleepTimerMode.Duration(30))
        // Read from the timer rather than the published state: the controller mirrors it through a
        // collector, which has not necessarily run yet on this line.
        assertEquals(30 * 60_000L, timer.state.value.remainingMs)

        // Starting a different chapter is a resume as far as the timer is concerned.
        playback.skipToNextChapter()
        playback.await("the next chapter to play") { it.isPlaying && it.currentIndex == 1 }

        clock.nowMs += 10 * 60_000L
        delay(100)
        assertEquals(
            20 * 60_000L,
            playback.state.value.sleepTimer.remainingMs,
            "the timer stayed frozen while a new chapter played",
        )
    }

    @Test
    fun `stopping disarms the timer so the next chapter is not silenced`() = runBlocking {
        val engine = FakePlaybackEngine()
        val playback = controller(engine)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("media to load") { it.hasMedia }
        playback.setSleepTimer(SleepTimerMode.Duration(15))
        awaitCondition("the timer to arm") { playback.state.value.sleepTimer.isArmed }

        playback.stop()
        awaitCondition("the timer to disarm") { !playback.state.value.sleepTimer.isArmed }
    }

    // --- History --------------------------------------------------------------------------------

    @Test
    fun `pausing files a history snapshot`() = runBlocking {
        val engine = FakePlaybackEngine()
        val history = InMemoryPlaybackHistoryStore()
        val clock = FakeClock()
        val playback = controller(engine, history = history, clock = clock)

        playback.playQueue(listOf(chapter(1)), startChapterId = 1, fiction = null)
        playback.await("playback to start") { it.hasMedia && it.isPlaying }
        engine.setDuration(600_000)
        engine.setPosition(120_000)
        awaitCondition("a position") { playback.state.value.positionMs > 0 }

        playback.togglePlayPause()
        awaitCondition("a snapshot") { history.history.value.isNotEmpty() }

        val snapshot = history.history.value.single()
        assertEquals(7, snapshot.fictionId)
        assertEquals(1, snapshot.chapterId)
        assertEquals(clock.nowMs, snapshot.recordedAtMs)
    }

    @Test
    fun `a chapter change files a snapshot for the chapter being left`() = runBlocking {
        val engine = FakePlaybackEngine()
        val history = InMemoryPlaybackHistoryStore()
        val playback = controller(engine, history = history)

        playback.playQueue(listOf(chapter(1), chapter(2)), startChapterId = 1, fiction = null)
        playback.await("media to load") { it.hasMedia }
        playback.skipToNextChapter()

        awaitCondition("a snapshot for chapter 1") {
            history.history.value.any { it.chapterId == 1 }
        }
    }

    @Test
    fun `switching to another fiction records the chapter being left, not one from the new queue`() =
        runBlocking {
            // playQueue replaces the queue before begin() runs, so anything that reads
            // queue[queueIndex] afterwards is describing the *new* queue with the *old* index —
            // which is a different chapter, or none at all when the new queue is shorter.
            val engine = FakePlaybackEngine()
            val history = InMemoryPlaybackHistoryStore()
            val playback = controller(engine, history = history)

            // Start deep enough into the first serial that the old index does not exist in the new
            // queue: index 2 here, and the serial we switch to has a single chapter.
            playback.playQueue(
                listOf(chapter(1), chapter(2), chapter(3)),
                startChapterId = 3,
                fiction = null,
            )
            playback.await("the third chapter to load") { it.hasMedia }
            engine.setDuration(600_000)
            engine.setPosition(90_000)
            awaitCondition("a position") { playback.state.value.positionMs > 0 }

            playback.play(chapter(99), fiction = null)
            playback.await("the new chapter to load") { it.queue.singleOrNull()?.chapterId == 99 }

            val left = history.history.value.firstOrNull { it.chapterId == 3 }
            assertTrue(
                left != null,
                "the chapter being left was never recorded; history was ${history.history.value}",
            )
            assertEquals(90.0, left.positionSeconds, 0.001)
            // And the position must not have been filed against the chapter we switched *to*.
            assertTrue(
                history.history.value.none { it.chapterId == 99 && it.positionSeconds > 0 },
                "the old position was recorded against the new chapter",
            )
        }

    @Test
    fun `nothing is recorded when nothing has loaded`() = runBlocking {
        val history = InMemoryPlaybackHistoryStore()
        val playback = controller(FakePlaybackEngine(), history = history)
        playback.stop()
        delay(100)
        assertTrue(history.history.value.isEmpty())
    }

    // --- Capability plumbing --------------------------------------------------------------------

    @Test
    fun `the engine's skip-silence capability reaches the UI state`() = runBlocking {
        val without = controller(FakePlaybackEngine())
        assertFalse(without.state.value.canSkipSilence)

        val with = controller(
            FakePlaybackEngine(capabilities = EngineCapabilities(variableSpeed = true, skipSilence = true)),
        )
        assertTrue(with.state.value.canSkipSilence)
    }
}
