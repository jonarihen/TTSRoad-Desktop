package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.player.MediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaStream
import java.io.File
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The streaming cache must never turn a partial or rearranged stream into reusable audio. */
class StreamingCacheTest {

    @TempDir
    lateinit var tempDir: File

    private class BytesSource(
        private val bytes: ByteArray,
        private val reportedLength: Long = bytes.size.toLong(),
    ) : MediaSource {
        override fun open(): MediaStream = object : MediaStream {
            private var position = 0
            override val length: Long = reportedLength
            override val isSeekable: Boolean = true

            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                if (position >= bytes.size) return -1
                val amount = min(count, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + amount)
                position += amount
                return amount
            }

            override fun seek(position: Long): Boolean {
                if (position !in 0..bytes.size.toLong()) return false
                this.position = position.toInt()
                return true
            }

            override fun close() = Unit
        }
    }

    private fun cache(maxBytes: Long = 1024L): StreamingCache = StreamingCache(
        root = File(tempDir, "cache"),
        validator = DownloadValidator { it.length() > 0L },
        maxBytes = maxBytes,
    )

    private fun consume(source: MediaSource): ByteArray {
        source.open().use { stream ->
            val result = ArrayList<Byte>()
            val buffer = ByteArray(7)
            while (true) {
                val read = stream.read(buffer, 0, buffer.size)
                if (read < 0) break
                repeat(read) { result += buffer[it] }
            }
            return result.toByteArray()
        }
    }

    @Test
    fun `a stream is reusable only after clean eof`() {
        val cache = cache()
        val bytes = ByteArray(64) { it.toByte() }

        assertEquals(bytes.toList(), consume(cache.retaining(7, BytesSource(bytes))).toList())

        val retained = assertNotNull(cache.sourceFor(7))
        assertEquals(bytes.toList(), consume(retained).toList())
        assertEquals(StreamingCacheStats(bytes = 64, files = 1), cache.stats())
    }

    @Test
    fun `closing early deletes the partial and advertises no cache hit`() {
        val cache = cache()
        cache.retaining(7, BytesSource(ByteArray(64) { 1 })).open().use { stream ->
            assertEquals(8, stream.read(ByteArray(8), 0, 8))
        }

        assertNull(cache.sourceFor(7))
        assertFalse(File(cache.root, "7.mp3.part").exists())
    }

    @Test
    fun `seeking abandons retention but leaves playback seekable`() {
        val cache = cache()
        cache.retaining(7, BytesSource(ByteArray(64) { 1 })).open().use { stream ->
            assertEquals(8, stream.read(ByteArray(8), 0, 8))
            assertTrue(stream.seek(32))
            val buffer = ByteArray(8)
            do {
                val read = stream.read(buffer, 0, buffer.size)
            } while (read >= 0)
        }

        assertNull(cache.sourceFor(7), "a stream with an unwritten hole was promoted")
    }

    @Test
    fun `the cache evicts completed files down to its byte bound`() {
        val cache = cache(maxBytes = 80)
        consume(cache.retaining(1, BytesSource(ByteArray(64) { 1 })))
        consume(cache.retaining(2, BytesSource(ByteArray(64) { 2 })))

        assertTrue(cache.stats().bytes <= 80L)
        assertEquals(1, cache.stats().files)
    }

    @Test
    fun `a known stream larger than the cache cap is played without retaining bytes`() {
        val cache = cache(maxBytes = 10)
        val bytes = ByteArray(64) { it.toByte() }

        assertEquals(bytes.toList(), consume(cache.retaining(7, BytesSource(bytes))).toList())

        assertEquals(0L, cache.root.listFiles().orEmpty().sumOf(File::length))
        assertNull(cache.sourceFor(7))
    }

    @Test
    fun `an unknown-length stream never writes beyond the hard cache cap`() {
        val cache = cache(maxBytes = 10)
        val bytes = ByteArray(64) { it.toByte() }
        val retained = cache.retaining(7, BytesSource(bytes, reportedLength = -1L))

        retained.open().use { stream ->
            val buffer = ByteArray(7)
            while (stream.read(buffer, 0, buffer.size) >= 0) {
                val bytesOnDisk = cache.root.listFiles().orEmpty().sumOf(File::length)
                assertTrue(bytesOnDisk <= 10L, "streaming cache grew to $bytesOnDisk bytes")
            }
        }

        assertNull(cache.sourceFor(7))
        assertFalse(File(cache.root, "7.mp3.part").exists())
    }

    @Test
    fun `clear reports and removes only this rebuildable root`() {
        val cache = cache()
        consume(cache.retaining(1, BytesSource(ByteArray(64) { 1 })))
        val explicit = File(tempDir, "downloads/1.mp3").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(12))
        }

        assertEquals(64L, cache.clear())
        assertEquals(StreamingCacheStats(), cache.stats())
        assertTrue(explicit.isFile, "clearing streamed audio crossed into explicit downloads")
    }
}
