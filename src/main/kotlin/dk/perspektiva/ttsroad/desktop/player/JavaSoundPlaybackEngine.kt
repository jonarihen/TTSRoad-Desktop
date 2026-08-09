package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.describeNetworkFailure
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream

/**
 * The fallback backend: `javax.sound.sampled` with the mp3spi/JLayer SPI, i.e. what the app played
 * through before Phase 5.
 *
 * It exists for machines with no GStreamer — Windows and macOS by default. Those platforms keep
 * exactly the behaviour they had, **including no speed control**, which is why
 * [capabilities] reports [EngineCapabilities.FixedSpeed]: the UI reads that and does not draw a
 * speed control it cannot honour. That is the difference from the old `setSpeed`, which accepted a
 * number, showed it, and changed nothing.
 *
 * `SourceDataLine` cannot resample and the decoder has no random-access index, so two of the four
 * defects Phase 5 set out to fix are unfixable here by construction: seeking re-decodes from the
 * start of the local file, and the chapter is materialised in full before it plays. Those are
 * accepted costs on a fallback path; see docs/adr/0002-playback-engine.md.
 */
class JavaSoundPlaybackEngine(
    private val engine: AudioEngine = JavaSoundAudioEngine(),
) : PlaybackEngine {

    /**
     * No rate control and no silence removal, but gain *is* honoured — see [applyGain].
     *
     * That asymmetry is deliberate. Speed needs a resampler this backend does not have, but gain
     * is a multiply over PCM this loop is already copying, and the sleep timer's fade depends on
     * it working everywhere.
     */
    override val capabilities = EngineCapabilities(
        variableSpeed = false,
        volumeBoost = true,
        skipSilence = false,
    )

    @Volatile private var listener: PlaybackEngineListener? = null

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    private fun emit(event: EngineEvent) {
        listener?.onEngineEvent(event)
    }

    private val lock = Any()
    private var worker: Thread? = null
    private var temp: File? = null

    @Volatile private var format: AudioFormat? = null
    @Volatile private var durationMs: Long = 0
    @Volatile private var positionMs: Long = 0
    @Volatile private var wantsPlaying = false
    @Volatile private var seekRequestMs: Long? = null

    /** Boost × fade, as one multiplier. Survives across chapters, like the GStreamer engine's. */
    @Volatile private var gain: Double = 1.0

    private val stopping = AtomicBoolean(false)

    override fun prepare(source: MediaSource, startPositionMs: Long) {
        stop()
        stopping.set(false)
        positionMs = startPositionMs
        // This backend needs a File: AudioSystem's MP3 SPI has to be able to re-read from the
        // start to seek, so the stream is materialised rather than played as it arrives.
        val file = try {
            materialise(source)
        } catch (e: SessionExpiredException) {
            emit(EngineEvent.Failed(PlaybackFailure.SessionExpired(e.sessionEnd)))
            return
        } catch (e: IOException) {
            emit(EngineEvent.Failed(PlaybackFailure.Transient(describeNetworkFailure(e))))
            return
        }
        synchronized(lock) { temp = file }

        val decoded = runCatching { engine.decode(file) }.getOrElse {
            emit(EngineEvent.Failed(PlaybackFailure.Fatal("This chapter's audio could not be decoded")))
            return
        }
        format = decoded.format
        durationMs = durationOf(decoded)
        runCatching { decoded.close() }
        if (durationMs > 0) emit(EngineEvent.DurationKnown(durationMs))

        val thread = Thread({ run(file) }, "ttsroad-javasound-playback").apply { isDaemon = true }
        synchronized(lock) { worker = thread }
        thread.start()
    }

    override fun play() {
        wantsPlaying = true
    }

    override fun pause() {
        wantsPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        seekRequestMs = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        this.positionMs = seekRequestMs ?: 0
    }

    /** Always 1.0: this backend has no rate control, and saying so is the point. */
    override fun setRate(rate: Float): Float = 1f

    override fun setGain(gain: Double) {
        this.gain = gain.coerceIn(EngineCapabilities.GainRange)
    }

    override fun positionMs(): Long = positionMs

    override fun durationMs(): Long = durationMs

    override fun stop() {
        stopping.set(true)
        wantsPlaying = false
        val (thread, file) = synchronized(lock) {
            val t = worker
            val f = temp
            worker = null
            temp = null
            t to f
        }
        thread?.join(STOP_JOIN_MILLIS)
        // Deleted here rather than by deleteOnExit, which accumulated one temp file per chapter
        // for the life of the process.
        file?.delete()
        positionMs = 0
        durationMs = 0
    }

    override fun close() = stop()

    private fun materialise(source: MediaSource): File {
        val file = File.createTempFile("ttsroad-", ".audio")
        try {
            source.open().use { stream ->
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer, 0, buffer.size)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    private fun run(file: File) {
        // Nullable only so the finally below can close whatever was opened; the loop itself works
        // with the non-null locals.
        var decoded: AudioInputStream? = null
        var openLine: AudioLine? = null
        try {
            var stream = engine.decode(file)
            decoded = stream
            val activeFormat = stream.format
            val line = engine.open(activeFormat)
            openLine = line

            var bytesPlayed = skipTo(stream, positionMs, activeFormat)
            val buffer = ByteArray(8192)

            while (!stopping.get()) {
                seekRequestMs?.let { target ->
                    seekRequestMs = null
                    // No random-access index, so this is a re-decode from the start of the local
                    // file. GStreamer does not have to do this; see the class docs.
                    runCatching { stream.close() }
                    stream = engine.decode(file)
                    decoded = stream
                    line.flush()
                    bytesPlayed = skipTo(stream, target, activeFormat)
                }

                if (!wantsPlaying) {
                    if (line.isRunning) line.stop()
                    Thread.sleep(PAUSE_POLL_MILLIS)
                    continue
                }
                if (!line.isRunning) line.start()

                val n = stream.read(buffer)
                if (n < 0) {
                    line.drain()
                    if (!stopping.get()) emit(EngineEvent.Completed)
                    return
                }
                applyGain(buffer, n, gain, activeFormat)
                line.write(buffer, 0, n)
                bytesPlayed += n
                positionMs = bytesToMs(bytesPlayed, activeFormat)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!stopping.get()) {
                emit(EngineEvent.Failed(PlaybackFailure.Fatal(describeNetworkFailure(e))))
            }
        } finally {
            runCatching { openLine?.stop() }
            runCatching { openLine?.close() }
            runCatching { decoded?.close() }
        }
    }

    private fun skipTo(stream: AudioInputStream, positionMs: Long, format: AudioFormat): Long {
        if (positionMs <= 0) return 0
        val target = msToBytes(positionMs, format)
        var remaining = target
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
        this.positionMs = positionMs
        return target
    }

    /**
     * Scales [length] bytes of PCM in place by [gain], saturating instead of wrapping.
     *
     * Saturation is the whole reason this is hand-written rather than a `MASTER_GAIN` control:
     * an overflowing 16-bit sample wraps from full positive to full negative, which is not "loud",
     * it is a click on every peak. `MASTER_GAIN` also has a device-dependent range that is
     * frequently absent altogether, so a fade built on it would silently do nothing on some
     * machines — the one outcome the sleep timer cannot have.
     *
     * Anything that is not 16-bit signed PCM is left alone. [JavaSoundAudioEngine] always decodes
     * to that, but [AudioEngine] is a seam and a substitute is not required to.
     */
    private fun applyGain(buffer: ByteArray, length: Int, gain: Double, format: AudioFormat) {
        if (gain == 1.0) return
        if (format.sampleSizeInBits != 16 || format.encoding != AudioFormat.Encoding.PCM_SIGNED) return

        val bigEndian = format.isBigEndian
        var i = 0
        while (i + 1 < length) {
            val lowIndex = if (bigEndian) i + 1 else i
            val highIndex = if (bigEndian) i else i + 1
            // The high byte keeps Byte.toInt()'s sign extension, so the shift rebuilds a signed
            // 16-bit value without a separate sign fix-up.
            val sample = (buffer[highIndex].toInt() shl 8) or (buffer[lowIndex].toInt() and 0xFF)
            val scaled = (sample * gain).toInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE)
            buffer[lowIndex] = (scaled and 0xFF).toByte()
            buffer[highIndex] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun durationOf(stream: AudioInputStream): Long {
        val frames = stream.frameLength
        if (frames <= 0) return 0
        val rate = stream.format.frameRate
        if (rate <= 0f) return 0
        return (frames / rate * 1000).toLong()
    }

    private fun msToBytes(ms: Long, format: AudioFormat): Long {
        val bytesPerSecond = format.frameRate * format.frameSize
        val raw = (ms / 1000.0 * bytesPerSecond).toLong()
        return raw - (raw % format.frameSize)
    }

    private fun bytesToMs(bytes: Long, format: AudioFormat): Long {
        val bytesPerSecond = format.frameRate * format.frameSize
        if (bytesPerSecond <= 0f) return 0
        return (bytes / bytesPerSecond * 1000).toLong()
    }

    private companion object {
        const val PAUSE_POLL_MILLIS = 80L
        const val STOP_JOIN_MILLIS = 2_000L
        const val MIN_SAMPLE = -32_768
        const val MAX_SAMPLE = 32_767
    }
}
