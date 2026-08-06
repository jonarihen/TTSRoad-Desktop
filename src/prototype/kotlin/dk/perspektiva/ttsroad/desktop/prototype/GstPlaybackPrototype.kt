package dk.perspektiva.ttsroad.desktop.prototype

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import org.freedesktop.gstreamer.Buffer
import org.freedesktop.gstreamer.Caps
import org.freedesktop.gstreamer.Element
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSink
import org.freedesktop.gstreamer.elements.AppSrc
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType

/**
 * Phase 5 playback-engine prototype — the working Linux prototype issue #4 asks for before the
 * engine is replaced. See `docs/adr/0002-playback-engine.md` for the decision it supports.
 *
 * It is not part of the shipped app: it lives in its own source set, `main` cannot see it, and
 * `check` only compiles it. Run it on a machine with GStreamer installed:
 *
 *     ./gradlew runPlaybackPrototype
 *
 * It answers the three questions the current `Mp3PlaybackController` fails:
 *
 *  1. **Can we play at 0.5×–3.0× without shifting pitch?** A 440 Hz tone goes through the chain at
 *     each rate and the dominant frequency of the *output* is recovered by zero-crossing rate. The
 *     pitch is measured, not taken on trust.
 *  2. **Can audio start before the whole chapter has downloaded?** Time to the first output sample
 *     is measured along with how many source bytes had been read by then. Today the answer is
 *     "100% of them".
 *  3. **Can we seek near the end of a long chapter without decoding from zero?**
 *
 * The byte source is a plain `ByteArray` fed through `appsrc`, which is the shape the production
 * engine uses: in the app those bytes come from the existing authenticated `OkHttpClient` (and, in
 * Phase 7, from the offline cache), never from GStreamer's own HTTP source.
 */

private const val SAMPLE_RATE = 44100
private const val TONE_HZ = 440
private const val NANOS_PER_SECOND = 1_000_000_000L

fun main(args: Array<String>) {
    println("JVM ${System.getProperty("java.version")}")
    Gst.init("ttsroad-playback-prototype")
    println("GStreamer ${Gst.getVersionString()}")

    val fixture = args.firstOrNull()?.let(::File) ?: generateFixture()
    val media = fixture.readBytes()
    println("fixture: ${fixture.name}, ${media.size} bytes\n")

    println("Pitch-preserving rate (scaletempo)")
    println("%-8s %14s %12s %12s   %s".format("rate", "out samples", "out sec", "expected", "dominant freq"))
    println("-".repeat(72))

    var baseSeconds = -1.0
    for (rate in listOf(1.0, 0.5, 1.5, 2.0, 3.0)) {
        val result = decodeFully(media, rate)
        if (baseSeconds < 0) baseSeconds = result.seconds
        val expected = baseSeconds / rate
        println(
            "%-8.2f %14d %12.3f %12.3f %10.1f Hz  %s".format(
                rate,
                result.samples,
                result.seconds,
                expected,
                result.dominantHz,
                verdict(result, expected),
            ),
        )
    }

    println()
    measureStreamingAndSeek(media)

    Gst.deinit()
}

private fun verdict(result: Measurement, expectedSeconds: Double): String {
    val tempoOk = kotlin.math.abs(result.seconds - expectedSeconds) / expectedSeconds < 0.08
    val pitchOk = kotlin.math.abs(result.dominantHz - TONE_HZ) < 15.0
    return "tempo ${if (tempoOk) "OK" else "FAIL"}, pitch ${if (pitchOk) "OK" else "FAIL"}"
}

private class Measurement(val samples: Long, val seconds: Double, val dominantHz: Double)

/**
 * A self-contained fixture so the prototype is one command with nothing to download.
 *
 * MP3 encoding lives in `gst-plugins-ugly` on Debian/Mint, which we do not want to require just to
 * make a test tone, so this falls back to WAV. What is being measured is `scaletempo`, and the
 * decoder in front of it does not change that.
 */
private fun generateFixture(): File {
    val encoder = if (ElementFactory.find("lamemp3enc") != null) "lamemp3enc" else "wavenc"
    val suffix = if (encoder == "lamemp3enc") ".mp3" else ".wav"
    val target = File.createTempFile("ttsroad-prototype-", suffix)
    target.deleteOnExit()

    // 1400 * 1024 samples at 44.1 kHz ≈ 32.5 s — long enough for a "seek near the end" to mean
    // something.
    //
    // The encoded bytes are pulled out through an appsink rather than written by a filesink: a
    // null sample *is* end-of-stream, so there is no bus message to wait for and no main loop this
    // prototype would otherwise have to run.
    val description = "audiotestsrc num-buffers=1400 freq=$TONE_HZ ! audioconvert ! audioresample " +
        "! audio/x-raw,rate=$SAMPLE_RATE,channels=2 ! $encoder ! appsink name=out sync=false"
    val pipeline = Gst.parseLaunch(description) as Pipeline
    val out = pipeline.getElementByName("out") as AppSink
    pipeline.setState(State.PLAYING)

    target.outputStream().buffered().use { sink ->
        while (true) {
            val sample = out.pullSample() ?: break
            val buffer = sample.buffer
            val mapped = buffer.map(false)
            if (mapped != null) {
                val chunk = ByteArray(mapped.remaining())
                mapped.get(chunk)
                sink.write(chunk)
                buffer.unmap()
            }
            sample.dispose()
        }
    }

    pipeline.setState(State.NULL)
    pipeline.getState(2 * NANOS_PER_SECOND)
    pipeline.dispose()
    println("generated fixture with $encoder")
    return target
}

/** Builds the chain the ADR proposes. Kept in one place so both measurements use the same one. */
private class ProbePipeline(media: ByteArray, realTime: Boolean) {
    val pipeline = Pipeline("prototype")
    val sink: AppSink

    /** Highest source offset ever read — how much of the "chapter" the engine actually pulled. */
    @Volatile var bytesRead: Long = 0
        private set

    init {
        val src = ElementFactory.make("appsrc", "src") as AppSrc
        src.streamType = AppSrc.StreamType.SEEKABLE
        src.size = media.size.toLong()
        src.set("format", Format.BYTES)

        val decode = ElementFactory.make("decodebin", "decode")
        val convertIn = ElementFactory.make("audioconvert", "convert-in")
        val tempo = ElementFactory.make("scaletempo", "tempo")
        val convertOut = ElementFactory.make("audioconvert", "convert-out")
        val resample = ElementFactory.make("audioresample", "resample")
        sink = ElementFactory.make("appsink", "sink") as AppSink

        // Mono S16LE at a known rate: the zero-crossing measurement needs one unambiguous channel.
        sink.caps = Caps.fromString(
            "audio/x-raw,format=S16LE,channels=1,rate=$SAMPLE_RATE,layout=interleaved",
        )
        // sync=false decodes as fast as the CPU allows; sync=true plays in real time, which is the
        // only way the latency numbers below mean anything.
        sink.set("sync", realTime)
        sink.set("max-buffers", 200)
        sink.set("drop", false)

        pipeline.addMany(src, decode, convertIn, tempo, convertOut, resample, sink)
        src.link(decode)
        Element.linkMany(convertIn, tempo, convertOut, resample, sink)
        // decodebin cannot expose its audio pad until it has sniffed the stream.
        decode.connect(
            Element.PAD_ADDED { _, pad ->
                val target = convertIn.getStaticPad("sink")
                if (!target.isLinked) pad.link(target)
            },
        )

        var cursor = 0
        val sentEos = AtomicBoolean(false)
        src.connect(
            AppSrc.NEED_DATA { elem, size ->
                if (cursor >= media.size) {
                    if (sentEos.compareAndSet(false, true)) elem.endOfStream()
                    return@NEED_DATA
                }
                val n = minOf(if (size <= 0) 8192 else size, media.size - cursor)
                val buffer = Buffer(n)
                buffer.map(true).put(media, cursor, n)
                buffer.unmap()
                cursor += n
                bytesRead = maxOf(bytesRead, cursor.toLong())
                elem.pushBuffer(buffer)
            },
        )
        src.connect(
            AppSrc.SEEK_DATA { _, position ->
                if (position < 0 || position > media.size) {
                    false
                } else {
                    cursor = position.toInt()
                    sentEos.set(false)
                    true
                }
            },
        )
    }

    fun preroll() {
        pipeline.setState(State.PAUSED)
        pipeline.getState(5 * NANOS_PER_SECOND)
    }

    /** Rate is applied by a seek carrying it — `scaletempo` has no rate property. */
    fun setRate(rate: Double, positionNanos: Long = 0L): Boolean = pipeline.seek(
        rate,
        Format.TIME,
        EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
        SeekType.SET,
        positionNanos,
        SeekType.NONE,
        -1L,
    )

    fun play() {
        pipeline.setState(State.PLAYING)
    }

    fun close() {
        pipeline.setState(State.NULL)
        pipeline.getState(2 * NANOS_PER_SECOND)
        pipeline.dispose()
    }
}

/** Plays the whole fixture at [rate] and measures the output's length and dominant frequency. */
private fun decodeFully(media: ByteArray, rate: Double): Measurement {
    val probe = ProbePipeline(media, realTime = false)
    probe.preroll()
    if (rate != 1.0 && !probe.setRate(rate)) {
        error("pipeline rejected rate $rate")
    }
    probe.play()

    var samples = 0L
    var crossings = 0L
    var previous: Short = 0
    var havePrevious = false

    while (true) {
        val sample = probe.sink.pullSample() ?: break
        val buffer = sample.buffer
        // A flushing rate-seek can emit gap buffers that carry no mappable memory.
        val mapped: ByteBuffer? = buffer.map(false)
        if (mapped != null) {
            val bytes = mapped.order(ByteOrder.LITTLE_ENDIAN)
            while (bytes.remaining() >= 2) {
                val value = bytes.short
                if (havePrevious && (previous < 0) != (value < 0)) crossings++
                previous = value
                havePrevious = true
                samples++
            }
            buffer.unmap()
        }
        sample.dispose()
    }
    probe.close()

    val seconds = samples / SAMPLE_RATE.toDouble()
    // A full period of a sine crosses zero twice.
    val dominantHz = if (seconds > 0) (crossings / 2.0) / seconds else 0.0
    return Measurement(samples, seconds, dominantHz)
}

/**
 * The other two defects: "downloads the whole chapter before playback" and "seeks by
 * decoding/discarding from the beginning". Measured in real time, so the milliseconds are real.
 */
private fun measureStreamingAndSeek(media: ByteArray) {
    val probe = ProbePipeline(media, realTime = true)

    val started = System.nanoTime()
    probe.play()
    val first = probe.sink.pullSample()
    val firstAudioMs = (System.nanoTime() - started) / 1_000_000
    val bytesAtFirstAudio = probe.bytesRead
    first?.dispose()

    println(
        "time to first audio : %d ms after %d of %d bytes (%.1f%% of the chapter)".format(
            firstAudioMs,
            bytesAtFirstAudio,
            media.size,
            100.0 * bytesAtFirstAudio / media.size,
        ),
    )

    // Play a moment, then jump near the end — the case that is slowest today.
    Thread.sleep(1000)
    val targetNanos = 30 * NANOS_PER_SECOND
    val seekStarted = System.nanoTime()
    val accepted = probe.setRate(1.0, targetNanos)

    var arrivedNanos = -1L
    while (accepted) {
        val sample = probe.sink.pullSample() ?: break
        val presentation = sample.buffer.presentationTimestamp
        sample.dispose()
        if (presentation >= targetNanos - 200_000_000L) {
            arrivedNanos = presentation
            break
        }
    }
    val seekMs = (System.nanoTime() - seekStarted) / 1_000_000

    println(
        "seek 1s -> 30s      : accepted=%s, first sample at %.3fs, took %d ms".format(
            accepted,
            arrivedNanos / 1e9,
            seekMs,
        ),
    )
    println("bytes ever read     : %d of %d".format(probe.bytesRead, media.size))
    probe.close()
}
