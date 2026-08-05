package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.PlaybackInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * Drives the real playback state machine with a fake download store and a fake audio engine, so
 * the queue / auto-advance / progress-save logic is testable with no network and no sound card.
 * This is what the [AudioDownloadStore] and [AudioEngine] seams were introduced for.
 *
 * These use `runBlocking` rather than `runTest` on purpose: the controller runs on a real
 * dispatcher and the assertions wait on real emissions, so virtual time would be wrong.
 */
class Mp3PlaybackControllerTest {

    /** 16-bit signed stereo 44.1 kHz — the format the real decoder converts to. */
    private val pcmFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, 16, 2, 4, 44_100f, false)

    private fun silence(seconds: Int) = ByteArray(44_100 * 4 * seconds)

    private class FakeDownloadStore(
        private val failWith: IOException? = null,
        /** When true, `download` blocks until [open] is called, freezing the state machine. */
        blocking: Boolean = false,
    ) : AudioDownloadStore {
        private val gate = CountDownLatch(if (blocking) 1 else 0)
        val downloaded: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val released: MutableList<File> = java.util.Collections.synchronizedList(mutableListOf<File>())

        fun open() = gate.countDown()

        override fun download(url: String): File {
            failWith?.let { throw it }
            gate.await(10, TimeUnit.SECONDS)
            downloaded += url
            return File.createTempFile("ttsroad-test-", ".bin").also { it.deleteOnExit() }
        }

        override fun release(file: File?) {
            file?.let {
                released += it
                it.delete()
            }
        }
    }

    private class FakeAudioEngine(private val pcm: ByteArray, private val format: AudioFormat) : AudioEngine {
        val written = AtomicLong(0)
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)

        override fun decode(file: File): AudioInputStream =
            AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / format.frameSize).toLong())

        override fun open(format: AudioFormat): AudioLine {
            opened.incrementAndGet()
            return object : AudioLine {
                private var running = false
                override val isRunning: Boolean get() = running
                override fun start() {
                    running = true
                }

                override fun stop() {
                    running = false
                }

                override fun flush() = Unit
                override fun drain() = Unit
                override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
                    written.addAndGet(length.toLong())
                    return length
                }

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }
    }

    private fun chapter(id: Int, title: String, durationSeconds: Double, position: Double = 0.0) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = title,
        audioDuration = durationSeconds,
        playable = true,
        audio = AudioInfo(url = "/audio/a-test-serial/$id.mp3"),
        playback = PlaybackInfo(positionSeconds = position),
    )

    private fun controllerFor(
        downloads: AudioDownloadStore,
        engine: AudioEngine,
        repository: FakeRepository = FakeRepository(),
    ) = Mp3PlaybackController(repository, downloads, engine, Dispatchers.Default)

    private suspend fun Mp3PlaybackController.await(
        description: String,
        predicate: (PlayerUiState) -> Boolean,
    ): PlayerUiState = try {
        withTimeout(15_000) { state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        fail("timed out waiting for: $description; last state was ${state.value}")
    }

    private fun finished(state: PlayerUiState) = state.hasMedia && !state.isPlaying && state.error == null

    @Test
    fun `playing a single chapter downloads it, streams every byte and reports completion`() = runBlocking {
        val downloads = FakeDownloadStore()
        val engine = FakeAudioEngine(silence(1), pcmFormat)
        val repository = FakeRepository()
        val controller = controllerFor(downloads, engine, repository)

        controller.play(
            chapter(101, "Chapter 3", durationSeconds = 1.0),
            FictionSummary(id = 7, title = "A Test Serial"),
        )
        controller.await("playback to finish", ::finished)

        assertEquals(listOf("/audio/a-test-serial/101.mp3"), downloads.downloaded.toList())
        assertEquals(silence(1).size.toLong(), engine.written.get())
        assertEquals(1, engine.opened.get())
        assertEquals(1, engine.closed.get(), "the output line must be closed even on the happy path")
        // Reaching end-of-stream marks the chapter played at its full duration.
        assertTrue(
            repository.savedProgress.contains(Triple(101, 1.0, true)),
            "expected an is_played save; got ${repository.savedProgress}",
        )
        controller.release()
    }

    @Test
    fun `metadata is published before any audio is fetched`() = runBlocking {
        val downloads = FakeDownloadStore(blocking = true)
        val controller = controllerFor(downloads, FakeAudioEngine(silence(1), pcmFormat))

        controller.play(
            chapter(101, "Chapter 3 — The Descent", durationSeconds = 1200.0),
            FictionSummary(id = 7, title = "A Test Serial", coverImageUrl = "/cover/a.jpg"),
        )

        val state = controller.state.value
        assertEquals("Chapter 3 — The Descent", state.title)
        assertEquals("A Test Serial", state.fictionTitle)
        assertEquals(1_200_000L, state.durationMs, "duration comes from server metadata, not the decoder")
        // Covers are resolved against the session server before they ever reach Coil.
        assertEquals("https://ttsroad.example.com/cover/a.jpg", state.coverImageUrl)
        assertFalse(state.hasMedia, "nothing has been downloaded yet")

        downloads.open()
        controller.release()
    }

    @Test
    fun `a queue auto-advances to the next chapter`() = runBlocking {
        val downloads = FakeDownloadStore()
        val engine = FakeAudioEngine(silence(1), pcmFormat)
        val repository = FakeRepository()
        val controller = controllerFor(downloads, engine, repository)

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", 1.0), chapter(102, "Chapter 4", 1.0)),
            startChapterId = 101,
            fiction = FictionSummary(id = 7, title = "A Test Serial"),
        )
        controller.await("the queue to reach its last chapter") { it.currentIndex == 1 && finished(it) }

        assertEquals(2, downloads.downloaded.size)
        assertEquals(2, engine.opened.get())
        assertFalse(controller.state.value.hasNext, "the last queue entry has no next")
        assertTrue(controller.state.value.hasPrevious)
        assertTrue(repository.savedProgress.any { it.first == 101 && it.third })
        assertTrue(repository.savedProgress.any { it.first == 102 && it.third })
        controller.release()
    }

    @Test
    fun `starting mid-queue skips the earlier chapters`() = runBlocking {
        val downloads = FakeDownloadStore(blocking = true)
        val controller = controllerFor(downloads, FakeAudioEngine(silence(1), pcmFormat))

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", 1.0), chapter(102, "Chapter 4", 1.0), chapter(103, "Chapter 5", 1.0)),
            startChapterId = 102,
            fiction = null,
        )

        assertEquals(1, controller.state.value.currentIndex)
        assertEquals("Chapter 4", controller.state.value.title)
        assertTrue(controller.state.value.hasNext)
        assertTrue(controller.state.value.hasPrevious)

        downloads.open()
        controller.release()
    }

    @Test
    fun `an unknown start chapter falls back to the first playable one`() = runBlocking {
        val downloads = FakeDownloadStore(blocking = true)
        val controller = controllerFor(downloads, FakeAudioEngine(silence(1), pcmFormat))

        controller.playQueue(
            listOf(chapter(101, "Chapter 3", 1.0), chapter(102, "Chapter 4", 1.0)),
            startChapterId = 999,
            fiction = null,
        )

        assertEquals(0, controller.state.value.currentIndex)
        downloads.open()
        controller.release()
    }

    @Test
    fun `chapters without audio are filtered out of the queue`() = runBlocking {
        val downloads = FakeDownloadStore(blocking = true)
        val controller = controllerFor(downloads, FakeAudioEngine(silence(1), pcmFormat))

        controller.playQueue(
            listOf(
                ChapterSummary(id = 100, title = "Not converted yet", audio = null),
                chapter(101, "Chapter 3", 1.0),
            ),
            startChapterId = 101,
            fiction = null,
        )

        assertEquals(listOf(101), controller.state.value.queue.map { it.chapterId })
        downloads.open()
        controller.release()
    }

    @Test
    fun `a chapter with no audio at all reports a clear error instead of playing`() = runBlocking {
        val controller = controllerFor(FakeDownloadStore(), FakeAudioEngine(silence(1), pcmFormat))

        controller.play(ChapterSummary(id = 100, title = "Pending"), null)

        assertEquals("This chapter has no audio yet", controller.state.value.error)
        assertFalse(controller.state.value.hasMedia)
        controller.release()
    }

    @Test
    fun `a queue with nothing playable reports an error`() = runBlocking {
        val controller = controllerFor(FakeDownloadStore(), FakeAudioEngine(silence(1), pcmFormat))

        controller.playQueue(listOf(ChapterSummary(id = 100, title = "Pending")), startChapterId = 100, fiction = null)

        assertEquals("No playable chapters yet", controller.state.value.error)
        controller.release()
    }

    @Test
    fun `a download failure surfaces its message and stops playback`() = runBlocking {
        val controller = controllerFor(
            FakeDownloadStore(failWith = IOException("Failed to download audio (HTTP 401)")),
            FakeAudioEngine(silence(1), pcmFormat),
        )

        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("the download failure to reach the UI") { it.error != null }

        assertEquals("Failed to download audio (HTTP 401)", controller.state.value.error)
        assertFalse(controller.state.value.isPlaying)
        controller.release()
    }

    @Test
    fun `resuming starts at the stored position rather than at zero`() = runBlocking {
        val engine = FakeAudioEngine(silence(4), pcmFormat)
        val controller = controllerFor(FakeDownloadStore(), engine)

        controller.play(chapter(101, "Chapter 3", durationSeconds = 4.0, position = 2.0), null)
        controller.await("playback to finish", ::finished)

        // Only the un-skipped tail reaches the output line: 2 of the 4 seconds.
        assertEquals(44_100L * 4 * 2, engine.written.get())
        controller.release()
    }

    @Test
    fun `stop clears the session and releases the temp file`() = runBlocking {
        val downloads = FakeDownloadStore()
        val controller = controllerFor(downloads, FakeAudioEngine(silence(1), pcmFormat))
        controller.play(chapter(101, "Chapter 3", 1.0), null)
        controller.await("playback to finish", ::finished)

        controller.stop()

        assertEquals(PlayerUiState(), controller.state.value)
        assertTrue(downloads.released.isNotEmpty(), "the downloaded file must be released")
        controller.release()
    }

    @Test
    fun `setSpeed only records the value - the SourceDataLine backend cannot resample`() = runBlocking {
        val engine = FakeAudioEngine(silence(1), pcmFormat)
        val controller = controllerFor(FakeDownloadStore(blocking = true), engine)

        controller.setSpeed(1.5f)

        assertEquals(1.5f, controller.state.value.speed)
        assertEquals(0, engine.opened.get())
        controller.release()
    }

    @Test
    fun `transport controls are inert until something is loaded`() = runBlocking {
        val controller = controllerFor(FakeDownloadStore(blocking = true), FakeAudioEngine(silence(1), pcmFormat))

        controller.togglePlayPause()
        controller.seekTo(5_000)
        controller.skipBy(30_000)

        assertEquals(PlayerUiState(), controller.state.value)
        controller.release()
    }
}
