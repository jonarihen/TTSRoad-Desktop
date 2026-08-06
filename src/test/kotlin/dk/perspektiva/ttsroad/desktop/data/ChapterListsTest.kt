package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChapterListsTest {

    private val ready = ChapterSummary(
        id = 101,
        fictionId = 7,
        title = "Ready",
        audio = AudioInfo(url = "/audio/x/0001.mp3"),
        audioDuration = 600.0,
    )
    private val played = ChapterSummary(
        id = 102,
        fictionId = 7,
        title = "Played",
        audio = AudioInfo(url = "/audio/x/0002.mp3"),
        playback = PlaybackInfo(isPlayed = true),
    )
    private val converting = ChapterSummary(id = 103, fictionId = 7, title = "Converting", status = "processing")

    // --- Filters -------------------------------------------------------------------------------

    @Test
    fun `All keeps everything, including chapters with no audio yet`() {
        val all = listOf(ready, played, converting)
        assertEquals(all, all.chapterView(ChapterFilter.All))
    }

    @Test
    fun `Unplayed drops what the server says is finished`() {
        assertEquals(
            listOf(ready, converting),
            listOf(ready, played, converting).chapterView(ChapterFilter.Unplayed),
        )
    }

    @Test
    fun `Ready means there is audio, not that the status string says done`() {
        // `status` and the `playable` flag both exist, but only `audio` proves the player can open
        // something — a chapter can report done with no audio object attached.
        val doneWithoutAudio = ChapterSummary(id = 104, status = "done", playable = true)

        assertEquals(
            listOf(ready, played),
            listOf(ready, played, converting, doneWithoutAudio).chapterView(ChapterFilter.Ready),
        )
    }

    // --- withPlayed ----------------------------------------------------------------------------

    @Test
    fun `marking played sets the flag and the position the server will report`() {
        val patched = listOf(ready).withPlayed(listOf(101), played = true).first().playback

        assertNotNull(patched)
        assertTrue(patched.isPlayed)
        assertEquals(600.0, patched.positionSeconds)
    }

    @Test
    fun `un-marking clears the position, as the server does`() {
        val patched = listOf(played).withPlayed(listOf(102), played = false).first().playback

        assertNotNull(patched)
        assertFalse(patched.isPlayed)
        assertEquals(0.0, patched.positionSeconds)
    }

    @Test
    fun `rows that were not marked keep their identity`() {
        // Not cosmetic: Compose skips an item whose input is the same instance, so this is the
        // difference between redrawing one row and redrawing five hundred.
        val list = listOf(ready, played, converting)

        val patched = list.withPlayed(listOf(101), played = true)

        assertSame(list[1], patched[1])
        assertSame(list[2], patched[2])
    }

    @Test
    fun `an empty id set returns the very same list`() {
        val list = listOf(ready, played)
        assertSame(list, list.withPlayed(emptyList(), played = true))
    }

    // --- Lazy keys -----------------------------------------------------------------------------

    @Test
    fun `chapter keys are stable across a refresh that returns the same rows`() {
        val first = chapterKeys(listOf(ready, played, converting))
        val second = chapterKeys(listOf(ready.copy(title = "Ready (renamed)"), played, converting))

        assertEquals(first, second)
    }

    @Test
    fun `duplicate chapter ids still produce unique keys`() {
        // The library's two shelves are different server payloads whose ids can repeat, and a row
        // that carries no id at all resolves to 0 — a duplicate key is a hard crash in a lazy list.
        val idless = ChapterSummary(title = "No id")
        val keys = chapterKeys(listOf(ready, ready, idless, idless))

        assertEquals(keys.size, keys.toSet().size, "keys were $keys")
        assertEquals("7:101", keys.first(), "the first occurrence keeps the plain, stable key")
    }

    @Test
    fun `the same chapter under two fictions does not collide`() {
        val elsewhere = ready.copy(fictionId = 9)

        val keys = chapterKeys(listOf(ready, elsewhere))

        assertEquals(listOf("7:101", "9:101"), keys)
    }

    @Test
    fun `fiction keys are unique even when a payload decodes to id zero`() {
        val malformed = FictionSummary()
        val keys = fictionKeys(listOf(FictionSummary(id = 7), malformed, malformed))

        assertEquals(keys.size, keys.toSet().size, "keys were $keys")
        assertEquals("fiction:7", keys.first())
    }
}
