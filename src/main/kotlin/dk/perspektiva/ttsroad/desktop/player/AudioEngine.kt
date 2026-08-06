package dk.perspektiva.ttsroad.desktop.player

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * An opened output line, i.e. the thing PCM bytes are written to.
 *
 * This mirrors the subset of [SourceDataLine] the decode loop actually uses, so tests can run the
 * whole loop against an in-memory sink on a machine with no sound card.
 */
interface AudioLine : AutoCloseable {
    val isRunning: Boolean
    fun start()
    fun stop()
    fun flush()
    fun drain()
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Seam for the audio backend: decoding a container to PCM and opening an output line.
 *
 * [JavaSoundAudioEngine] is the production implementation (mp3spi/JLayer via the
 * `javax.sound.sampled` SPI). Replacing the backend — e.g. with something that supports variable
 * rate playback, which [SourceDataLine] does not — means implementing this interface, not
 * touching the playback controller.
 */
interface AudioEngine {
    /** Opens [file] and converts it to 16-bit signed little-endian PCM. */
    fun decode(file: File): AudioInputStream

    /** Opens an output line able to play [format]. */
    fun open(format: AudioFormat): AudioLine
}

/** Default backend: `javax.sound.sampled` + the mp3spi/JLayer SPI on the classpath. */
class JavaSoundAudioEngine : AudioEngine {
    override fun decode(file: File): AudioInputStream {
        val fileStream = AudioSystem.getAudioInputStream(file)
        val base = fileStream.format
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            base.sampleRate,
            16,
            base.channels,
            base.channels * 2,
            base.sampleRate,
            false,
        )
        return AudioSystem.getAudioInputStream(target, fileStream)
    }

    override fun open(format: AudioFormat): AudioLine {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format)
        return SourceDataLineAudioLine(line)
    }

    private class SourceDataLineAudioLine(private val line: SourceDataLine) : AudioLine {
        override val isRunning: Boolean get() = line.isRunning
        override fun start() = line.start()
        override fun stop() = line.stop()
        override fun flush() = line.flush()
        override fun drain() = line.drain()
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int = line.write(buffer, offset, length)
        override fun close() = line.close()
    }
}
