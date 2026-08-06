package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.AudioInfo
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import java.io.File
import javax.sound.sampled.AudioFormat
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Mapping between "what is playing" and "which row is that on screen".
 *
 * The chapter list has to answer that question every frame, from nothing but the published player
 * state — so the answer lives in a pure function over [PlayerUiState] rather than in the screen.
 */
class QueueMappingTest {

    private fun state(fictionId: Int, index: Int, vararg ids: Int) = PlayerUiState(
        fictionId = fictionId,
        queue = ids.map { QueueItem(it, "Chapter $it") },
        currentIndex = index,
        hasMedia = true,
    )

    @Test
    fun `the playing chapter is the queue entry at the current index`() {
        assertEquals(102, state(7, 1, 101, 102, 103).playingChapterIdIn(7))
    }

    @Test
    fun `a queue from another fiction highlights nothing`() {
        // Two serials open in one session: the one that is not playing must show no highlight at
        // all rather than a highlight on whatever row happens to share an index.
        assertNull(state(7, 1, 101, 102).playingChapterIdIn(9))
    }

    @Test
    fun `nothing loaded means nothing to highlight`() {
        assertNull(PlayerUiState().playingChapterIdIn(7))
        assertNull(state(0, 0, 101).playingChapterIdIn(7), "a queue with no fiction is not this fiction")
        assertNull(state(7, 0, 101).playingChapterIdIn(0), "and no fiction is never asking about one")
    }

    @Test
    fun `an index past the end of the queue is not a crash and not a match`() {
        assertNull(state(7, 5, 101, 102).playingChapterIdIn(7))
    }

    @Test
    fun `a queue entry that decoded without an id never matches an id-less row`() {
        assertNull(state(7, 0, 0).playingChapterIdIn(7))
    }
}

/**
 * The queue the controller actually builds.
 *
 * Two properties matter to the chapter list: it is always in reading order regardless of how the
 * screen was sorted, and it never contains a chapter with no audio.
 *
 * Every case here stops at metadata publication — the fakes below refuse to download or decode, so
 * the state under assertion is exactly what `publishMetadata` produced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueConstructionTest {

    private fun chapter(number: Int, audio: Boolean = true) = ChapterSummary(
        id = 100 + number,
        fictionId = 7,
        title = "Chapter $number",
        displayNumber = number.toDouble(),
        audioDuration = 60.0,
        audio = if (audio) AudioInfo(url = "/audio/x/000$number.mp3") else null,
    )

    private val refusingDownloads = object : AudioDownloadStore {
        override fun download(url: String): File = throw java.io.IOException("no download in this test")
        override fun release(file: File?) = Unit
    }

    private val refusingEngine = object : AudioEngine {
        override fun decode(file: File) = error("no decoding expected")
        override fun open(format: AudioFormat): AudioLine = error("no audio device expected")
    }

    private fun controller() = Mp3PlaybackController(
        repository = FakeRepository(),
        downloads = refusingDownloads,
        engine = refusingEngine,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `the queue is built in reading order even when the screen is sorted newest first`() = runTest {
        val controller = controller()
        val newestFirst = listOf(chapter(3), chapter(2), chapter(1))

        controller.playQueue(newestFirst, startChapterId = 102, fiction = FictionSummary(id = 7))

        val state = controller.state.value
        assertEquals(listOf(101, 102, 103), state.queue.map { it.chapterId })
        assertEquals(1, state.currentIndex, "chapter 2 is second in reading order")
        assertEquals(7, state.fictionId)
        assertEquals(102, state.playingChapterIdIn(7))
        controller.release()
    }

    @Test
    fun `chapters with no audio are never queued`() = runTest {
        val controller = controller()

        controller.playQueue(
            listOf(chapter(1), chapter(2, audio = false), chapter(3)),
            startChapterId = 101,
            fiction = FictionSummary(id = 7),
        )

        assertEquals(listOf(101, 103), controller.state.value.queue.map { it.chapterId })
        controller.release()
    }

    @Test
    fun `queue rows carry the chapter's own number, not their queue position`() = runTest {
        // Chapter 2 is still converting, so "the second playable row" and "Chapter 2" are
        // different things and the up-next panel must say the second one.
        val controller = controller()

        controller.playQueue(
            listOf(chapter(1), chapter(2, audio = false), chapter(3)),
            startChapterId = 101,
            fiction = FictionSummary(id = 7),
        )

        assertEquals(listOf(1.0, 3.0), controller.state.value.queue.map { it.displayNumber })
        controller.release()
    }

    @Test
    fun `a fiction played from a library shelf still names itself`() = runTest {
        val controller = controller()

        // The flat shelf payload carries no FictionSummary at all — only fiction_id.
        controller.playQueue(listOf(chapter(1)), startChapterId = 101, fiction = null)

        assertEquals(7, controller.state.value.fictionId)
        controller.release()
    }
}
