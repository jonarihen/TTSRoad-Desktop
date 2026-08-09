package dk.perspektiva.ttsroad.desktop.player

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test doubles for the Phase 5 playback seam.
 *
 * The whole reason [PlaybackEngine] is a narrow interface: everything worth testing about playback
 * — the queue, auto-advance, the retry ladder, the played threshold, session expiry — is above it,
 * and can therefore be driven here with no sound card, no network and no GStreamer.
 */

/** A byte source that never touches a network. [failWith] makes `open()` fail instead. */
class FakeMediaSource(
    val url: String,
    private val bytes: ByteArray = ByteArray(1024),
    private val failWith: Throwable? = null,
) : MediaSource {
    override fun open(): MediaStream {
        failWith?.let { throw it }
        return FakeMediaStream(bytes)
    }

    private class FakeMediaStream(private val bytes: ByteArray) : MediaStream {
        private var position = 0
        override val length: Long = bytes.size.toLong()
        override val isSeekable: Boolean = true

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(count, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }

        override fun seek(position: Long): Boolean {
            if (position < 0 || position > bytes.size) return false
            this.position = position.toInt()
            return true
        }

        override fun close() = Unit
    }
}

/** Records which chapter URLs playback asked for, in order. */
class FakeMediaSourceFactory(
    /** Applied to every source created — used to make a chapter fail to open. */
    var failWith: Throwable? = null,
) : MediaSourceFactory {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    override fun create(url: String): MediaSource {
        requested += url
        return FakeMediaSource(url, failWith = failWith)
    }
}

/**
 * A [PlaybackEngine] that reports whatever a test tells it to.
 *
 * Transport calls are counted rather than acted on; events are pushed through the listener, which
 * is how the real engines report too, so the controller is exercised on exactly the path it uses
 * in production.
 */
class FakePlaybackEngine(
    override val capabilities: EngineCapabilities =
        EngineCapabilities(variableSpeed = true, speedRange = 0.5f..3.0f),
    /** When true, `prepare` blocks until [releasePrepare] — models a slow first byte. */
    blockPrepare: Boolean = false,
    /** When true, `play` immediately reports end-of-stream, so a queue advances on its own. */
    private val completeOnPlay: Boolean = false,
) : PlaybackEngine {

    private val gate = CountDownLatch(if (blockPrepare) 1 else 0)

    @Volatile private var listener: PlaybackEngineListener? = null

    val prepareCount = AtomicInteger()
    val playCount = AtomicInteger()
    val pauseCount = AtomicInteger()
    val stopCount = AtomicInteger()
    val closeCount = AtomicInteger()

    /** Start positions passed to `prepare`, in order — how a resume is observed. */
    val preparedPositions: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
    val seeks: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
    val requestedRates: MutableList<Float> = Collections.synchronizedList(mutableListOf<Float>())

    /** Gains handed to the engine, in order — boost and the sleep fade multiplied together. */
    val gains: MutableList<Double> = Collections.synchronizedList(mutableListOf<Double>())

    @Volatile var skipSilenceEnabled: Boolean = false

    /** Thrown by `prepare`. Set to a [SessionExpiredException] to model a 401 on the audio path. */
    @Volatile var prepareFailure: Throwable? = null

    /** Reported through the listener during `prepare`, before it returns. */
    @Volatile var durationOnPrepare: Long = 0

    @Volatile private var position = 0L

    @Volatile private var duration = 0L

    fun releasePrepare() = gate.countDown()

    fun emit(event: EngineEvent) {
        listener?.onEngineEvent(event)
    }

    fun setPosition(ms: Long) {
        position = ms
    }

    fun setDuration(ms: Long) {
        duration = ms
    }

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override fun prepare(source: MediaSource, startPositionMs: Long) {
        prepareCount.incrementAndGet()
        preparedPositions += startPositionMs
        gate.await(10, TimeUnit.SECONDS)
        prepareFailure?.let { throw it }
        // Opening the source is what a real engine does, and it is where a source-level failure
        // surfaces, so the fake does it too.
        source.open().close()
        position = startPositionMs
        if (durationOnPrepare > 0) {
            duration = durationOnPrepare
            emit(EngineEvent.DurationKnown(durationOnPrepare))
        }
    }

    override fun play() {
        playCount.incrementAndGet()
        if (completeOnPlay) emit(EngineEvent.Completed)
    }

    override fun pause() {
        pauseCount.incrementAndGet()
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        position = positionMs
    }

    override fun setRate(rate: Float): Float {
        requestedRates += rate
        return capabilities.coerceSpeed(rate)
    }

    override fun setGain(gain: Double) {
        gains += gain
    }

    override fun setSkipSilence(enabled: Boolean) {
        skipSilenceEnabled = enabled
    }

    override fun positionMs(): Long = position

    override fun durationMs(): Long = duration

    override fun stop() {
        stopCount.incrementAndGet()
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

/** Convenience for the common "this chapter's bytes are gone" case. */
fun transientFailure(message: String = "Connection reset") = IOException(message)
