package dk.perspektiva.ttsroad.desktop.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the listener asked the timer to do.
 *
 * [EndOfChapter] is not a duration in disguise: a chapter's remaining time changes under a seek and
 * a rate change, and expressing it as "stop at the boundary" rather than "stop in 14 minutes" is
 * what keeps it correct when either happens.
 */
sealed interface SleepTimerMode {
    data object Off : SleepTimerMode

    /** A wall-clock countdown. [minutes] is the *initial* length; extensions do not change it. */
    data class Duration(val minutes: Int) : SleepTimerMode

    data object EndOfChapter : SleepTimerMode

    companion object {
        /** The durations the UI offers. "+5 min" can push an armed timer past the last of them. */
        val OfferedMinutes: List<Int> = listOf(5, 15, 30, 45, 60)
    }
}

/**
 * The timer as the UI and the engine see it.
 *
 * [fadeGain] is a multiplier the engine applies *on top of* the volume-boost preference, so a fade
 * and a boost compose instead of one overwriting the other — and cancelling a fade restores the
 * boost rather than resetting the output to unity.
 */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    /** Milliseconds left, for a [SleepTimerMode.Duration]. Zero in every other mode. */
    val remainingMs: Long = 0L,
    val isFading: Boolean = false,
    val fadeGain: Float = 1f,
) {
    val isArmed: Boolean get() = mode != SleepTimerMode.Off
}

/** What a [SleepTimer.tick] found. */
enum class SleepTimerEvent {
    /** The countdown reached zero. The controller pauses and disarms. */
    Expired,
}

/**
 * The sleep timer, as a state machine over an injected clock.
 *
 * Deliberately *not* a coroutine with a `delay`. The acceptance criteria require deterministic
 * behaviour across pause, resume, seek, chapter boundary and manual stop, and a real timer makes
 * every one of those a race against a scheduler. Here the controller's existing 250 ms tick drives
 * it, `now` is a parameter, and a test can step a whole hour in one line.
 *
 * Threading: every method is synchronised on the instance. The controller ticks from its own
 * coroutine while the UI arms and cancels from the Compose thread, and both mutate the deadline.
 */
class SleepTimer(private val now: () -> Long = System::currentTimeMillis) {

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** Absolute deadline while running; null when disarmed or frozen by a pause. */
    private var deadlineMs: Long? = null

    /** What is left while frozen, so a resume continues rather than restarts. */
    private var frozenRemainingMs: Long? = null

    private var mode: SleepTimerMode = SleepTimerMode.Off

    @Synchronized
    fun arm(mode: SleepTimerMode) {
        this.mode = mode
        when (mode) {
            is SleepTimerMode.Off -> disarmInternal()

            is SleepTimerMode.Duration -> {
                deadlineMs = now() + mode.minutes * MillisPerMinute
                frozenRemainingMs = null
                publish()
            }

            is SleepTimerMode.EndOfChapter -> {
                // Nothing to count down: the chapter's own end is the deadline.
                deadlineMs = null
                frozenRemainingMs = null
                publish()
            }
        }
    }

    fun cancel() = arm(SleepTimerMode.Off)

    /**
     * Pushes an armed countdown out by [minutes] and restores full volume.
     *
     * Works during the fade — that is its main use, and the reason it clears [SleepTimerState
     * .isFading] explicitly rather than waiting for the next tick to notice: a listener who reaches
     * for "+5 min" while the audio is already fading should hear it come back immediately.
     *
     * Extending a [SleepTimerMode.EndOfChapter] timer converts it to a countdown, because "end of
     * chapter, plus five minutes" is not a thing the boundary can express.
     */
    @Synchronized
    fun extendBy(minutes: Int) {
        if (!_state.value.isArmed) return
        val extra = minutes * MillisPerMinute
        when {
            frozenRemainingMs != null -> frozenRemainingMs = frozenRemainingMs!! + extra
            deadlineMs != null -> deadlineMs = deadlineMs!! + extra
            else -> {
                // End-of-chapter: becomes a countdown of exactly the extension.
                mode = SleepTimerMode.Duration(minutes)
                deadlineMs = now() + extra
            }
        }
        publish()
    }

    /**
     * Freezes a countdown.
     *
     * Called for a *manual* pause only. A pause the timer itself caused must not freeze anything —
     * it has already expired and disarmed by then, so there is no deadline left to freeze.
     */
    @Synchronized
    fun onPlaybackPaused() {
        val deadline = deadlineMs ?: return
        frozenRemainingMs = (deadline - now()).coerceAtLeast(0L)
        deadlineMs = null
        publish()
    }

    @Synchronized
    fun onPlaybackResumed() {
        val remaining = frozenRemainingMs ?: return
        deadlineMs = now() + remaining
        frozenRemainingMs = null
        publish()
    }

    /**
     * Whether the chapter that just finished should be the last one.
     *
     * Returns true exactly once per armed [SleepTimerMode.EndOfChapter] — the timer disarms itself
     * here, so a controller that keeps the queue loaded does not stop every subsequent chapter too.
     */
    @Synchronized
    fun shouldStopAtChapterEnd(): Boolean {
        if (mode != SleepTimerMode.EndOfChapter) return false
        disarmInternal()
        return true
    }

    /**
     * Recomputes the countdown and the fade.
     *
     * Returns [SleepTimerEvent.Expired] once, on the tick that crosses zero; the timer disarms
     * itself first so a caller that ticks again before acting cannot be told twice.
     */
    @Synchronized
    fun tick(): SleepTimerEvent? {
        val deadline = deadlineMs ?: run {
            // Frozen, or a mode with no countdown. Publishing keeps the frozen remainder visible.
            publish()
            return null
        }
        val remaining = deadline - now()
        if (remaining <= 0) {
            disarmInternal()
            return SleepTimerEvent.Expired
        }
        publish()
        return null
    }

    private fun disarmInternal() {
        mode = SleepTimerMode.Off
        deadlineMs = null
        frozenRemainingMs = null
        // Full volume on the way out, whether the timer was cancelled or expired: the next thing
        // the listener plays must not start silent because a fade was in progress when it ended.
        _state.value = SleepTimerState()
    }

    private fun publish() {
        val remaining = remainingMs()
        val fading = mode is SleepTimerMode.Duration && deadlineMs != null && remaining <= FadeMs
        _state.value = SleepTimerState(
            mode = mode,
            remainingMs = remaining,
            isFading = fading,
            fadeGain = if (fading) (remaining.toFloat() / FadeMs).coerceIn(0f, 1f) else 1f,
        )
    }

    private fun remainingMs(): Long = when {
        frozenRemainingMs != null -> frozenRemainingMs!!
        deadlineMs != null -> (deadlineMs!! - now()).coerceAtLeast(0L)
        else -> 0L
    }

    companion object {
        const val MillisPerMinute: Long = 60_000L

        /** How long the fade lasts. The last 30 s, as the issue specifies. */
        const val FadeMs: Long = 30_000L

        /** What the "+5 min" action adds. */
        const val ExtensionMinutes: Int = 5
    }
}

/** Human label for a countdown, e.g. `4:05`. Minutes are not zero-padded; seconds always are. */
fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
