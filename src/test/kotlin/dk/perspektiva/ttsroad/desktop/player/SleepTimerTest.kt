package dk.perspektiva.ttsroad.desktop.player

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sleep timer, driven by a clock the test owns.
 *
 * Every assertion here would be a race against a scheduler if the timer ran on a real `delay`, and
 * "fade/extension is deterministic under a fake clock" is an acceptance criterion rather than a
 * nicety — which is why [SleepTimer] takes `now` as a parameter and is ticked by its caller.
 */
class SleepTimerTest {

    /** A clock the test advances by hand. */
    private class FakeClock(var nowMs: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = nowMs

        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private fun minutes(n: Int) = n * 60_000L

    @Test
    fun `an unarmed timer reports nothing and never expires`() {
        val timer = SleepTimer(FakeClock())
        assertFalse(timer.state.value.isArmed)
        assertNull(timer.tick())
        assertEquals(1f, timer.state.value.fadeGain)
    }

    @Test
    fun `every offered duration counts down and expires exactly once`() {
        SleepTimerMode.OfferedMinutes.forEach { chosen ->
            val clock = FakeClock()
            val timer = SleepTimer(clock)
            timer.arm(SleepTimerMode.Duration(chosen))

            clock.advance(minutes(chosen) - 1_000)
            assertNull(timer.tick(), "$chosen-minute timer expired early")

            clock.advance(1_000)
            assertEquals(SleepTimerEvent.Expired, timer.tick(), "$chosen-minute timer did not expire")
            // Disarmed by the expiry, so a caller that ticks again is not told twice.
            assertNull(timer.tick())
            assertFalse(timer.state.value.isArmed)
        }
    }

    @Test
    fun `a manual pause freezes the countdown and resuming continues it`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(30))

        clock.advance(minutes(10))
        timer.tick()
        timer.onPlaybackPaused()

        // An hour goes by with nothing playing. The timer must not have run down.
        clock.advance(minutes(60))
        assertNull(timer.tick())
        assertEquals(minutes(20), timer.state.value.remainingMs)

        timer.onPlaybackResumed()
        clock.advance(minutes(19))
        assertNull(timer.tick())
        clock.advance(minutes(1))
        assertEquals(SleepTimerEvent.Expired, timer.tick())
    }

    @Test
    fun `the fade covers the last thirty seconds and ramps to silence`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(5))

        // Just outside the fade window: full volume.
        clock.advance(minutes(5) - SleepTimer.FadeMs - 1_000)
        timer.tick()
        assertFalse(timer.state.value.isFading)
        assertEquals(1f, timer.state.value.fadeGain)

        // Half way through the fade: half gain.
        clock.advance(1_000 + SleepTimer.FadeMs / 2)
        timer.tick()
        assertTrue(timer.state.value.isFading)
        assertEquals(0.5f, timer.state.value.fadeGain, 0.01f)

        // Near the end: almost silent, and never negative.
        clock.advance(SleepTimer.FadeMs / 2 - 100)
        timer.tick()
        assertTrue(timer.state.value.fadeGain in 0f..0.05f)
    }

    @Test
    fun `expiring restores full volume so the next chapter does not start silent`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(5))
        clock.advance(minutes(5) - 5_000)
        timer.tick()
        assertTrue(timer.state.value.fadeGain < 1f)

        clock.advance(5_000)
        assertEquals(SleepTimerEvent.Expired, timer.tick())
        assertEquals(1f, timer.state.value.fadeGain)
    }

    @Test
    fun `cancelling during the fade restores full volume`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(5))
        clock.advance(minutes(5) - 10_000)
        timer.tick()
        assertTrue(timer.state.value.isFading)

        timer.cancel()
        assertFalse(timer.state.value.isArmed)
        assertFalse(timer.state.value.isFading)
        assertEquals(1f, timer.state.value.fadeGain)
    }

    @Test
    fun `plus five minutes during the fade restores volume and pushes the deadline out`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(15))
        clock.advance(minutes(15) - 10_000)
        timer.tick()
        assertTrue(timer.state.value.isFading)

        timer.extendBy(SleepTimer.ExtensionMinutes)
        // Immediately, not on the next tick: the audio has to come back as the button is pressed.
        assertFalse(timer.state.value.isFading)
        assertEquals(1f, timer.state.value.fadeGain)
        assertEquals(minutes(5) + 10_000, timer.state.value.remainingMs)

        clock.advance(minutes(5) + 9_000)
        assertNull(timer.tick())
        clock.advance(1_000)
        assertEquals(SleepTimerEvent.Expired, timer.tick())
    }

    @Test
    fun `extending keeps the mode, so the chosen duration stays selected in the UI`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(30))
        timer.extendBy(5)
        assertEquals(SleepTimerMode.Duration(30), timer.state.value.mode)
    }

    @Test
    fun `extending a frozen timer adds to what is left rather than to a running deadline`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(30))
        clock.advance(minutes(10))
        timer.tick()
        timer.onPlaybackPaused()

        timer.extendBy(5)
        assertEquals(minutes(25), timer.state.value.remainingMs)

        // And the extension survives the resume rather than being discarded by it.
        timer.onPlaybackResumed()
        clock.advance(minutes(25) - 1_000)
        assertNull(timer.tick())
    }

    @Test
    fun `extending an unarmed timer does nothing`() {
        val timer = SleepTimer(FakeClock())
        timer.extendBy(5)
        assertFalse(timer.state.value.isArmed)
    }

    @Test
    fun `end of chapter stops once and then disarms`() {
        val timer = SleepTimer(FakeClock())
        timer.arm(SleepTimerMode.EndOfChapter)
        assertTrue(timer.state.value.isArmed)

        assertTrue(timer.shouldStopAtChapterEnd())
        // The next chapter boundary must not also stop, or a still-loaded queue would never play.
        assertFalse(timer.shouldStopAtChapterEnd())
        assertFalse(timer.state.value.isArmed)
    }

    @Test
    fun `a duration timer does not stop at a chapter boundary`() {
        val timer = SleepTimer(FakeClock())
        timer.arm(SleepTimerMode.Duration(30))
        assertFalse(timer.shouldStopAtChapterEnd())
        assertTrue(timer.state.value.isArmed)
    }

    @Test
    fun `end of chapter never fades, because the chapter's own end is the boundary`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.EndOfChapter)
        clock.advance(minutes(90))
        assertNull(timer.tick())
        assertFalse(timer.state.value.isFading)
        assertEquals(1f, timer.state.value.fadeGain)
    }

    @Test
    fun `extending end of chapter turns it into a countdown`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.EndOfChapter)
        timer.extendBy(5)

        assertEquals(SleepTimerMode.Duration(5), timer.state.value.mode)
        // And it is no longer waiting for a boundary that may be an hour away.
        assertFalse(timer.shouldStopAtChapterEnd())
        clock.advance(minutes(5))
        assertEquals(SleepTimerEvent.Expired, timer.tick())
    }

    @Test
    fun `re-arming replaces the countdown rather than adding to it`() {
        val clock = FakeClock()
        val timer = SleepTimer(clock)
        timer.arm(SleepTimerMode.Duration(60))
        clock.advance(minutes(30))
        timer.arm(SleepTimerMode.Duration(5))
        assertEquals(minutes(5), timer.state.value.remainingMs)
    }

    @Test
    fun `formatting pads seconds and rounds up so a countdown never shows a stale zero`() {
        assertEquals("5:00", formatRemaining(minutes(5)))
        assertEquals("0:30", formatRemaining(30_000))
        assertEquals("0:05", formatRemaining(4_100))
        assertEquals("0:00", formatRemaining(0))
    }
}
