package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.player.FileMediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaSourceFactory
import dk.perspektiva.ttsroad.desktop.player.MediaStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Which bytes a chapter plays from.
 *
 * The acceptance criterion is that a completely downloaded chapter plays with the server
 * unreachable — so the network factory here *throws* rather than returning something usable, which
 * is the only honest way to assert that it was never consulted.
 */
class OfflineFirstMediaSourceTest {

    @TempDir
    lateinit var tempDir: File

    private class UnreachableNetwork : MediaSourceFactory {
        var calls = 0
        override fun create(chapterId: Int, url: String): MediaSource {
            calls++
            return object : MediaSource {
                override fun open(): MediaStream = error("the server is unreachable")
            }
        }
    }

    private fun entry(chapterId: Int, state: DownloadState, fileName: String = "$chapterId.mp3") =
        DownloadEntry(
            chapterId = chapterId,
            fictionId = 7,
            fictionTitle = "A Serial",
            chapterTitle = "Chapter $chapterId",
            state = state,
            bytesDownloaded = 2048,
            fileName = fileName,
            updatedAtMs = 1,
        )

    private fun storage(): DownloadStorage =
        DownloadStorage(File(tempDir, "downloads")).also { it.prepare() }

    // --- Offline playback -----------------------------------------------------------------------

    @Test
    fun `a downloaded chapter plays from disk with the server unreachable`() {
        val storage = storage()
        val audio = ByteArray(2048) { 7 }.also {
            it[0] = 'I'.code.toByte()
            it[1] = 'D'.code.toByte()
            it[2] = '3'.code.toByte()
        }
        storage.resolve("1.mp3").writeBytes(audio)
        val index = InMemoryDownloadIndexStore(listOf(entry(1, DownloadState.Downloaded)))
        val network = UnreachableNetwork()

        val source = OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertIs<FileMediaSource>(source)
        assertEquals(0, network.calls, "the network was consulted for a downloaded chapter")
        // And it really reads: the bytes come back through the same interface the engine uses.
        source.open().use { stream ->
            val buffer = ByteArray(16)
            assertEquals(16, stream.read(buffer, 0, 16))
            assertEquals("ID3", buffer.copyOfRange(0, 3).toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun `a chapter that is not downloaded streams`() {
        val index = InMemoryDownloadIndexStore(emptyList())
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage() }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls)
    }

    @Test
    fun `a chapter still downloading streams rather than playing a part file`() {
        // The .part file exists and is growing. Handing it to the engine would play a truncated
        // chapter that ends wherever the transfer had reached.
        val storage = storage()
        File(storage.root, "1.mp3.part").writeBytes(ByteArray(8))
        val index = InMemoryDownloadIndexStore(listOf(entry(1, DownloadState.Downloading)))
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls, "only a completed download may play from disk")
    }

    // --- When the index and the disk disagree ----------------------------------------------------

    @Test
    fun `a row claiming to be downloaded with no file falls back and corrects itself`() {
        // A cache cleaner or a manual delete. Skipping quietly would leave the UI offering
        // "Offline" for a chapter that silently streams on every play.
        val storage = storage()
        val index = InMemoryDownloadIndexStore(listOf(entry(1, DownloadState.Downloaded)))
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls)
        assertNull(DownloadIndex.find(index.entries.value, 1), "the stale row should have been dropped")
    }

    @Test
    fun `an empty file is treated as missing`() {
        val storage = storage()
        storage.resolve("1.mp3").writeBytes(ByteArray(0))
        val index = InMemoryDownloadIndexStore(listOf(entry(1, DownloadState.Downloaded)))
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls)
    }

    @Test
    fun `a corrupt completed file is removed and streamed rather than repeatedly advertised offline`() {
        val storage = storage()
        storage.resolve("1.mp3").writeBytes(ByteArray(2048) { '<'.code.toByte() })
        val index = InMemoryDownloadIndexStore(listOf(entry(1, DownloadState.Downloaded)))
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls)
        assertFalse(storage.resolve("1.mp3").exists())
        assertNull(DownloadIndex.find(index.entries.value, 1))
    }

    @Test
    fun `a hostile file name in the index cannot reach outside the root`() {
        val storage = storage()
        val index = InMemoryDownloadIndexStore(
            listOf(entry(1, DownloadState.Downloaded, fileName = "../../secret.mp3")),
        )
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ index }, { storage }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls, "an unusable name must stream, not resolve outside the root")
    }

    // --- No signed-in account --------------------------------------------------------------------

    @Test
    fun `with no download storage everything streams`() {
        // Signed out, or a machine where the data directory could not be created.
        val network = UnreachableNetwork()

        OfflineFirstMediaSourceFactory({ null }, { null }, network).create(1, "/audio/s/1.mp3")

        assertEquals(1, network.calls)
    }
}
