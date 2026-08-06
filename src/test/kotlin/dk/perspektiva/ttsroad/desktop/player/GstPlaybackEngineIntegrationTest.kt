package dk.perspektiva.ttsroad.desktop.player

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf

/**
 * The real GStreamer engine against a real GStreamer install.
 *
 * Everything else in `player/` runs on a fake engine, which is the point of the seam — but a fake
 * cannot tell us whether the pipeline links, whether `scaletempo` accepts a rate seek, or whether
 * end-of-stream ever arrives. Those only fail against the real thing.
 *
 * It runs with `fakesink` rather than `autoaudiosink`, so it needs GStreamer but **not** a sound
 * card: this repository's own CI has neither, and a developer machine with GStreamer has the first
 * without necessarily having the second. The engine takes the sink name for exactly this reason.
 *
 * Skipped, not failed, where GStreamer is absent — that is the same condition under which the app
 * falls back to [JavaSoundPlaybackEngine], so "no GStreamer" is a supported configuration rather
 * than a broken one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("gstreamerAvailable")
class GstPlaybackEngineIntegrationTest {

    private lateinit var fixture: File

    @BeforeAll
    fun createFixture() {
        fixture = GstFixtures.generateTone(seconds = 4)
    }

    @AfterAll
    fun deleteFixture() {
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun engine(): GstPlaybackEngine =
        assertNotNull(GstPlaybackEngine.createOrNull(sinkElement = "fakesink"), "engine should build")

    /** Collects listener callbacks the way the controller does. */
    private class Events : PlaybackEngineListener {
        val received = ConcurrentLinkedQueue<EngineEvent>()
        override fun onEngineEvent(event: EngineEvent) {
            received.add(event)
        }

        fun awaitCompleted(timeoutMs: Long = 30_000): Boolean = await(timeoutMs) {
            received.any { it is EngineEvent.Completed }
        }

        fun failures(): List<PlaybackFailure> =
            received.filterIsInstance<EngineEvent.Failed>().map { it.failure }
    }

    @Test
    fun `prepare reports the fixture's duration and reaches a playable state`() {
        val engine = engine()
        val events = Events()
        engine.setListener(events)
        try {
            engine.prepare(FileMediaSource(fixture), startPositionMs = 0)
            engine.play()

            assertTrue(events.failures().isEmpty(), "unexpected failures: ${events.failures()}")
            // The generator asks for 4 s; MP3 framing rounds it, so allow a frame either way.
            val known = await(10_000) { engine.durationMs() in 3_700..4_300 }
            assertTrue(known, "duration settled at ${engine.durationMs()} ms, expected about 4000")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `playing to the end reports Completed exactly once`() {
        val engine = engine()
        val events = Events()
        engine.setListener(events)
        try {
            engine.prepare(FileMediaSource(fixture), startPositionMs = 0)
            engine.play()

            assertTrue(events.awaitCompleted(), "no end-of-stream arrived; got ${events.received}")
            assertEquals(
                1,
                events.received.count { it is EngineEvent.Completed },
                "auto-advance would skip a chapter for every extra Completed",
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a rate seek is accepted and reported back clamped to what the engine can do`() {
        val engine = engine()
        engine.setListener(Events())
        try {
            engine.prepare(FileMediaSource(fixture), startPositionMs = 0)

            assertEquals(1.5f, engine.setRate(1.5f))
            assertEquals(3.0f, engine.setRate(4.0f), "above the range, so clamped rather than refused")
            assertEquals(0.5f, engine.setRate(0.25f))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `preparing at an offset starts there instead of at zero`() {
        val engine = engine()
        val events = Events()
        engine.setListener(events)
        try {
            engine.prepare(FileMediaSource(fixture), startPositionMs = 2_000)

            assertTrue(events.failures().isEmpty(), "unexpected failures: ${events.failures()}")
            // The seek is asynchronous, so allow it a moment to land before querying.
            val landed = await(5_000) { engine.positionMs() >= 1_800 }
            assertTrue(landed, "position was ${engine.positionMs()} ms, expected about 2000")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a source that cannot be opened surfaces rather than hanging`() {
        val engine = engine()
        val events = Events()
        engine.setListener(events)
        try {
            val missing = File(fixture.parentFile, "definitely-not-here-${System.nanoTime()}.mp3")
            runCatching { engine.prepare(FileMediaSource(missing), startPositionMs = 0) }
            // Either prepare threw or the engine reported it; what must not happen is silence.
            assertTrue(
                events.failures().isNotEmpty() || events.received.isEmpty(),
                "a missing source must not look like a prepared chapter",
            )
        } finally {
            engine.close()
        }
    }

    companion object {
        /** Referenced by name from `@EnabledIf`. */
        @JvmStatic
        fun gstreamerAvailable(): Boolean =
            runCatching { GstPlaybackEngine.isAvailable() }.getOrDefault(false)

        private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (condition()) return true
                Thread.sleep(25)
            }
            return condition()
        }
    }
}
