package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.describeNetworkFailure
import java.io.IOException
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import org.freedesktop.gstreamer.Buffer
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.Element
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSrc
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType

/**
 * The production audio backend: GStreamer, driven through gst1-java-core.
 *
 * See `docs/adr/0002-playback-engine.md` for why this backend and why the bytes are pushed in from
 * the JVM instead of letting GStreamer fetch the URL itself. In short: the app already owns an
 * authenticated `OkHttpClient` whose interceptor will only attach the bearer token to the signed-in
 * server's origin, and `souphttpsrc` is not dependably registered even where `plugins-good` is.
 *
 * ```
 * appsrc → decodebin → audioconvert → scaletempo → audioconvert → audioresample → autoaudiosink
 * ```
 *
 * `scaletempo` is what makes speed real rather than a number in the UI state: it scales tempo with
 * a WSOLA-style cross-correlation and leaves pitch where it was. It has to sit in the chain
 * explicitly — `playbin` does not insert it, and as `playbin`'s `audio-filter` it gets bypassed
 * whenever the sink can take the format in passthrough, which silently breaks every rate but 1.0.
 *
 * Construction is deliberately fallible. [createOrNull] returns null when GStreamer is absent or
 * too old, and the app falls back to [JavaSoundPlaybackEngine] rather than failing to start — a
 * missing system library must not be the difference between an app that runs and one that does
 * not.
 */
class GstPlaybackEngine private constructor(
    /**
     * The output element. `autoaudiosink` in production; an integration test passes `fakesink` so
     * the whole real pipeline — appsrc, decodebin, scaletempo, rate seeks, EOS — can be exercised
     * on a machine with no sound card, which is the only part of this class a fake engine cannot
     * stand in for.
     */
    private val sinkElement: String,
) : PlaybackEngine {

    override val capabilities = EngineCapabilities(variableSpeed = true, speedRange = 0.5f..3.0f)

    @Volatile private var listener: PlaybackEngineListener? = null

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    private fun emit(event: EngineEvent) {
        listener?.onEngineEvent(event)
    }

    private val lock = Any()
    private var pipeline: Pipeline? = null
    private var stream: MediaStream? = null

    /** Survives across chapters: a listener who chose 1.5× means it for the next chapter too. */
    @Volatile private var rate: Float = 1f

    /**
     * Once a read or the bus has reported a failure, the end-of-stream that follows is a
     * consequence of it, not a chapter that finished. Without this the controller would auto-
     * advance past a chapter that failed to download.
     */
    private val failed = AtomicBoolean(false)

    override fun prepare(source: MediaSource, startPositionMs: Long) {
        teardown()
        failed.set(false)

        val opened = source.open()
        val built = try {
            build(opened)
        } catch (e: Throwable) {
            runCatching { opened.close() }
            throw e
        }

        synchronized(lock) {
            stream = opened
            pipeline = built
        }

        // PAUSED prerolls: the decoder reads enough to negotiate caps and answer a duration query,
        // and the sink opens the output device. Blocking here is what the interface's contract
        // allows for.
        built.setState(State.PAUSED)
        val reached = built.getState(PREROLL_TIMEOUT_NANOS)

        // A pipeline that never reaches PAUSED has not failed — it is *stuck*, which is worse,
        // because nothing on the bus says so. That is what an absent or wedged output device looks
        // like: verified on a host with no PCM device, where the same pipeline sat in gst-launch
        // until it was killed rather than erroring. Turning it into a reported failure is the
        // difference between "Buffering…" forever and a message with a Retry next to it.
        if (reached != State.PAUSED && reached != State.PLAYING) {
            fail(PlaybackFailure.Fatal("Could not start audio playback — no usable output device"))
            return
        }

        // Best-effort, and often not yet answerable: an MP3 arriving through appsrc has no
        // container index, so GStreamer only estimates a duration once it has parsed enough frames
        // — measured here as shortly *after* playback starts, not at preroll. [durationMs] is
        // therefore the source of truth, and the controller polls it; this event is just the early
        // answer when there is one. Until then the controller shows the server's own metadata.
        built.queryDuration(Format.TIME).takeIf { it > 0 }?.let {
            emit(EngineEvent.DurationKnown(it / NANOS_PER_MILLI))
        }

        // Position and rate are both carried by a seek, so one event does both.
        if (startPositionMs > 0 || rate != 1f) {
            seekInternal(built, startPositionMs, rate)
        }
    }

    override fun play() {
        synchronized(lock) { pipeline }?.setState(State.PLAYING)
    }

    override fun pause() {
        // A real PAUSED state, not a flag polled by a loop that keeps the output line open. The
        // device is released and nothing spins.
        synchronized(lock) { pipeline }?.setState(State.PAUSED)
    }

    override fun seekTo(positionMs: Long) {
        val current = synchronized(lock) { pipeline } ?: return
        seekInternal(current, positionMs.coerceAtLeast(0), rate)
    }

    override fun setRate(rate: Float): Float {
        val applied = capabilities.coerceSpeed(rate)
        this.rate = applied
        val current = synchronized(lock) { pipeline }
        if (current != null) {
            seekInternal(current, positionMs(), applied)
        }
        return applied
    }

    override fun positionMs(): Long {
        val current = synchronized(lock) { pipeline } ?: return 0
        return current.queryPosition(Format.TIME).takeIf { it > 0 }?.div(NANOS_PER_MILLI) ?: 0
    }

    override fun durationMs(): Long {
        val current = synchronized(lock) { pipeline } ?: return 0
        return current.queryDuration(Format.TIME).takeIf { it > 0 }?.div(NANOS_PER_MILLI) ?: 0
    }

    override fun stop() = teardown()

    override fun close() = teardown()

    /**
     * Rate and position in one flushing, accurate seek — `scaletempo` has no rate property; a rate
     * only reaches it on a seek event.
     */
    private fun seekInternal(pipeline: Pipeline, positionMs: Long, rate: Float) {
        runCatching {
            pipeline.seek(
                rate.toDouble(),
                Format.TIME,
                EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                SeekType.SET,
                positionMs * NANOS_PER_MILLI,
                SeekType.NONE,
                -1L,
            )
        }
    }

    private fun build(stream: MediaStream): Pipeline {
        val pipeline = Pipeline("ttsroad-playback")
        val src = ElementFactory.make("appsrc", "source") as AppSrc
        src.streamType = if (stream.isSeekable) AppSrc.StreamType.SEEKABLE else AppSrc.StreamType.STREAM
        if (stream.length > 0) src.size = stream.length
        src.set("format", Format.BYTES)
        // Bounded, so a fast server cannot pull the whole chapter into memory ahead of the decoder.
        src.maxBytes = APPSRC_MAX_BYTES

        val decode = ElementFactory.make("decodebin", "decode")
        val convertIn = ElementFactory.make("audioconvert", "convert-in")
        val tempo = ElementFactory.make("scaletempo", "tempo")
        val convertOut = ElementFactory.make("audioconvert", "convert-out")
        val resample = ElementFactory.make("audioresample", "resample")
        // autoaudiosink resolves to pulsesink on Mint, which is also what PipeWire presents, so
        // PipeWire and PulseAudio are one code path. It also re-resolves the default device.
        val sink = ElementFactory.make(sinkElement, "sink")

        pipeline.addMany(src, decode, convertIn, tempo, convertOut, resample, sink)
        src.link(decode)
        Element.linkMany(convertIn, tempo, convertOut, resample, sink)
        // decodebin cannot expose its audio pad until it has sniffed the container.
        decode.connect(
            Element.PAD_ADDED { _, pad ->
                val target = convertIn.getStaticPad("sink")
                if (!target.isLinked) runCatching { pad.link(target) }
            },
        )

        val buffer = ByteArray(READ_CHUNK_BYTES)
        src.connect(
            AppSrc.NEED_DATA { elem, size ->
                // Runs on a GStreamer streaming thread. Reading the network here is deliberate:
                // appsrc's byte limit is the backpressure, so a slow decoder slows the download
                // instead of buffering the whole chapter.
                feed(elem, stream, buffer, size)
            },
        )
        src.connect(
            AppSrc.SEEK_DATA { _, position ->
                // The whole point of the range-request source: a seek moves the HTTP read head
                // instead of decoding and discarding from byte zero.
                runCatching { stream.seek(position) }.getOrDefault(false)
            },
        )

        pipeline.bus.connect(
            Bus.EOS { _ ->
                if (!failed.get()) emit(EngineEvent.Completed)
            },
        )
        pipeline.bus.connect(
            Bus.ERROR { _, _, message ->
                // Anything the pipeline itself rejects — undecodable audio, no usable output
                // device — cannot be fixed by asking again.
                fail(PlaybackFailure.Fatal(message ?: "Playback failed"))
            },
        )
        return pipeline
    }

    private fun feed(src: AppSrc, stream: MediaStream, buffer: ByteArray, requested: Int) {
        try {
            val want = requested.coerceIn(1, buffer.size)
            val read = stream.read(buffer, 0, want)
            if (read <= 0) {
                src.endOfStream()
                return
            }
            val gstBuffer = Buffer(read)
            val mapped: ByteBuffer? = gstBuffer.map(true)
            if (mapped == null) {
                src.endOfStream()
                return
            }
            mapped.put(buffer, 0, read)
            gstBuffer.unmap()
            src.pushBuffer(gstBuffer)
        } catch (e: SessionExpiredException) {
            // The credential was refused mid-chapter. Same event as a 401 on an API call, so it
            // takes the same door rather than looking like a network blip.
            fail(PlaybackFailure.SessionExpired(e.sessionEnd))
            src.endOfStream()
        } catch (e: IOException) {
            fail(PlaybackFailure.Transient(describeNetworkFailure(e)))
            src.endOfStream()
        }
    }

    private fun fail(failure: PlaybackFailure) {
        // First failure wins: an error usually cascades, and the first one is the useful one.
        if (failed.compareAndSet(false, true)) {
            emit(EngineEvent.Failed(failure))
        }
    }

    private fun teardown() {
        val (oldPipeline, oldStream) = synchronized(lock) {
            val p = pipeline
            val s = stream
            pipeline = null
            stream = null
            p to s
        }
        oldPipeline?.let {
            runCatching {
                it.setState(State.NULL)
                it.getState(TEARDOWN_TIMEOUT_NANOS)
                it.dispose()
            }
        }
        // Deterministic: the response body and its connection are released here, not whenever a
        // finaliser gets round to it, and there is no deleteOnExit temp file to accumulate.
        oldStream?.let { runCatching { it.close() } }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PREROLL_TIMEOUT_NANOS = 30L * 1_000_000_000L
        private const val TEARDOWN_TIMEOUT_NANOS = 2L * 1_000_000_000L
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val APPSRC_MAX_BYTES = 2L * 1024 * 1024

        /** Every element the pipeline names. A missing one means fall back, not crash. */
        private val REQUIRED_ELEMENTS = listOf(
            "appsrc", "decodebin", "audioconvert", "scaletempo", "audioresample",
        )

        @Volatile private var initialised: Boolean? = null

        /**
         * Builds the engine, or returns null if this machine cannot run it.
         *
         * Everything here is defensive on purpose. `Gst.init` links a native library through JNA:
         * if GStreamer is missing the failure arrives as an `UnsatisfiedLinkError`, not an
         * exception, so this catches [Throwable]. Letting that propagate would turn "no GStreamer
         * installed" into "the app does not start".
         */
        fun createOrNull(sinkElement: String = "autoaudiosink"): GstPlaybackEngine? = runCatching {
            if (!ensureInitialised()) return@runCatching null
            if (!hasEveryElement(REQUIRED_ELEMENTS + sinkElement)) return@runCatching null
            GstPlaybackEngine(sinkElement)
        }.getOrNull()

        /** Whether this machine can run the GStreamer backend at all. */
        fun isAvailable(): Boolean = runCatching {
            ensureInitialised() && hasEveryElement(REQUIRED_ELEMENTS)
        }.getOrDefault(false)

        /**
         * Whether every named element can be created here.
         *
         * `ElementFactory.find` **throws** `IllegalArgumentException` for an element that does not
         * exist rather than returning null, so a bare null check is not a check at all. That is not
         * hypothetical: a GitHub Ubuntu runner carries GStreamer's core library — enough for
         * `Gst.init` to succeed — but none of its plugins, and the resulting
         * "No such Gstreamer factory: appsrc" turned every UI test red. The whole point of this
         * class being fallible is that a machine without GStreamer falls back to
         * [JavaSoundPlaybackEngine]; a probe that throws defeats it.
         */
        private fun hasEveryElement(names: List<String>): Boolean = names.all { name ->
            runCatching { ElementFactory.find(name) != null }.getOrDefault(false)
        }

        @Synchronized
        private fun ensureInitialised(): Boolean {
            initialised?.let { return it }
            val ok = runCatching {
                Gst.init("TTSRoad")
                true
            }.getOrElse { false }
            initialised = ok
            return ok
        }
    }
}
