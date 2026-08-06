package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.SessionEnd

/**
 * What a backend can actually do, so the UI can gate controls instead of showing dead ones.
 *
 * The old `setSpeed` wrote a number into the UI state that no backend acted on, and the README had
 * to carry a footnote explaining that the speed control did nothing. A capability the UI reads is
 * how that stops being possible: a backend that cannot resample reports [variableSpeed] false and
 * the control is not drawn at all.
 */
data class EngineCapabilities(
    val variableSpeed: Boolean,
    val speedRange: ClosedFloatingPointRange<Float> = 1f..1f,
) {
    /** Nearest speed this engine can actually deliver — what the UI should display. */
    fun coerceSpeed(speed: Float): Float =
        if (!variableSpeed) 1f else speed.coerceIn(speedRange)

    companion object {
        /** A backend with no rate control, e.g. the `SourceDataLine` fallback. */
        val FixedSpeed = EngineCapabilities(variableSpeed = false)
    }
}

/**
 * Why playback stopped.
 *
 * Typed rather than a string because the three cases are handled differently and the difference is
 * not cosmetic: [SessionExpired] must end the session app-wide, [Transient] is worth retrying on a
 * timer, and [Fatal] is not — retrying a corrupt file or a missing decoder only fails again.
 */
sealed interface PlaybackFailure {
    val message: String

    /** The server refused the credential. Goes through the same door as a 401 on an API call. */
    data class SessionExpired(val sessionEnd: SessionEnd) : PlaybackFailure {
        override val message: String get() = sessionEnd.message
    }

    /** A dropped connection, a timeout, a 5xx — worth another attempt. */
    data class Transient(override val message: String) : PlaybackFailure

    /** A 404, undecodable audio, or no usable output device. Retrying cannot help. */
    data class Fatal(override val message: String) : PlaybackFailure
}

/** Everything the engine reports that the controller cannot simply ask for. */
sealed interface EngineEvent {
    /** The stream ended on its own. Distinct from a stop the controller asked for. */
    data object Completed : EngineEvent

    /** Duration is only known once the decoder has read enough of the stream to say. */
    data class DurationKnown(val durationMs: Long) : EngineEvent

    data class Failed(val failure: PlaybackFailure) : EngineEvent
}

/**
 * How an engine reports [EngineEvent]s.
 *
 * A callback rather than a `Flow` on purpose. `prepare` can fail — or discover the duration —
 * before it returns, and a hot flow drops anything emitted while nobody is collecting yet. That
 * race is not theoretical: it is exactly the window between "the controller launched its collector"
 * and "the collector actually started", and losing a `Failed` in it would hang playback on a
 * chapter that had already given up. Registration here is synchronous, so there is no window.
 *
 * Called from whichever thread the backend happens to be on — a GStreamer bus thread, a decode
 * thread. Implementations must not block in it.
 */
fun interface PlaybackEngineListener {
    fun onEngineEvent(event: EngineEvent)
}

/**
 * The audio backend: one chapter at a time, and nothing above it.
 *
 * This replaces the `AudioEngine`/`AudioLine` pair, which assumed the *app* owns the decode loop
 * and pushes PCM at an output line. That shape cannot express a backend that owns its own clock,
 * which every real media framework does — so GStreamer could not have been put behind it.
 *
 * Queue, progress-saving, retry and session expiry deliberately live above this, in
 * [PlaybackController], so all of that logic stays testable against a fake engine with no sound
 * card and no network. That property is the one thing this refactor must not lose.
 *
 * Implementations are **not** required to be thread-safe against concurrent transport calls; the
 * controller serialises them. They must never block the caller for longer than a state change
 * takes, because the caller can be the Compose UI thread.
 */
interface PlaybackEngine : AutoCloseable {

    val capabilities: EngineCapabilities

    /** Registers the sink for [EngineEvent]s. Set it before the first [prepare]. */
    fun setListener(listener: PlaybackEngineListener?)

    /**
     * Loads [source] and holds at [startPositionMs] without playing. Any previously prepared
     * source is torn down first.
     *
     * The one call here that may block: it returns once the backend has read enough of the stream
     * to know its format, which is network I/O. Call it from an IO context, never from the UI
     * thread. Everything else on this interface returns immediately.
     */
    fun prepare(source: MediaSource, startPositionMs: Long)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /**
     * Requests a playback rate and returns the one actually applied — an engine may clamp it, and
     * an engine with no rate control always returns 1.0. The UI shows the returned value, so what
     * it displays is what is being played.
     */
    fun setRate(rate: Float): Float

    /** Current position. Cheap enough to poll; never blocks on I/O. */
    fun positionMs(): Long

    /** Duration, or 0 when not yet known. */
    fun durationMs(): Long

    /** Stops and releases the current source, but leaves the engine usable for another [prepare]. */
    fun stop()
}
