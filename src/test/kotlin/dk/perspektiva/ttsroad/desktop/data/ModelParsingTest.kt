package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parses real server-1.4.0 payloads with exactly the Moshi configuration the repository uses.
 *
 * The point of these is twofold: pin the field mappings (`@param:Json` names are easy to break
 * silently, because every model field has a default and a mis-parse degrades to "Untitled"
 * rather than throwing), and prove that additive server changes do not break the client.
 */
class ModelParsingTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private inline fun <reified T> parse(json: String): T =
        assertNotNull(moshi.adapter(T::class.java).fromJson(json), "adapter returned null for ${T::class}")

    @Test
    fun `login response maps the token, user and server name`() {
        val response = parse<LoginResponse>(ServerFixtures.LOGIN_SUCCESS)
        assertEquals("ttsr_Zm9vYmFyYmF6cXV1eA", response.token)
        assertEquals("bearer", response.tokenType)
        assertEquals(1, response.user.id)
        assertEquals("admin", response.user.username)
        assertTrue(response.user.isAdmin)
        assertEquals("Perspektiva TTSRoad", response.server?.name)
        assertEquals("https://ttsroad.example.com", response.server?.baseUrl)
    }

    @Test
    fun `login response ignores device_id and expires_at, which the client does not model yet`() {
        // Both are real 1.4.0 fields. Decoding must not fail over them; they are simply dropped.
        val response = parse<LoginResponse>(ServerFixtures.LOGIN_SUCCESS)
        assertEquals("ttsr_Zm9vYmFyYmF6cXV1eA", response.token)
    }

    @Test
    fun `library fictions map every progress counter`() {
        val library = parse<LibraryResponse>(ServerFixtures.LIBRARY)
        assertEquals(1, library.fictions.size)
        val fiction = library.fictions.single()
        assertEquals(7, fiction.id)
        assertEquals("A Test Serial", fiction.title)
        assertEquals("Someone", fiction.author)
        assertEquals("a-test-serial", fiction.slug)
        assertEquals("https://cdn.royalroadcdn.com/covers/12345.jpg", fiction.coverImageUrl)
        assertEquals(listOf("LitRPG", "Progression"), fiction.tags)
        assertEquals(4.72, fiction.rating)
        assertEquals(318, fiction.ratingCount)
        assertEquals(10, fiction.totalChapters)
        assertEquals(6, fiction.doneChapters)
        assertEquals(2, fiction.pendingChapters)
        assertEquals(1, fiction.errorChapters)
        assertEquals(1, fiction.processingChapters)
        assertEquals(0.6f, fiction.readyFraction)
    }

    @Test
    fun `continue_listening uses the flat chapter shape and resolves through the fallbacks`() {
        val library = parse<LibraryResponse>(ServerFixtures.LIBRARY)
        val item = library.continueListening.single()
        // No `id`/`title`/`playback` on this shape at all — everything comes from the fallbacks.
        assertEquals(0, item.id)
        assertEquals(101, item.resolvedChapterId)
        assertEquals(7, item.resolvedFictionId)
        assertEquals("Chapter 3 — The Descent", item.resolvedTitle)
        assertEquals("A Test Serial", item.resolvedFictionTitle)
        assertEquals("/cover/a-test-serial.jpg", item.resolvedCoverUrl)
        assertNull(item.playback)
        assertEquals(412.5, item.resolvedPositionSeconds)
        assertEquals(1200.0, item.audioDuration)
        assertEquals(
            "https://ttsroad.example.com/audio/a-test-serial/0003.mp3",
            item.audio?.url,
        )
        assertTrue(item.audio?.requiresBearerAuth == true)
    }

    @Test
    fun `recent_chapters has no resume information, so position reads zero`() {
        val library = parse<LibraryResponse>(ServerFixtures.LIBRARY)
        val recent = library.recentChapters.single()
        assertEquals(106, recent.resolvedChapterId)
        assertEquals("Chapter 6", recent.resolvedTitle)
        assertEquals(0.0, recent.resolvedPositionSeconds)
        assertEquals("done", recent.status)
    }

    @Test
    fun `chapters endpoint uses the nested shape and maps the playback object`() {
        val response = parse<ChaptersResponse>(ServerFixtures.CHAPTERS)
        assertEquals(7, response.fiction.id)
        assertEquals(2, response.total)
        assertEquals(2, response.chapters.size)

        val first = response.chapters.first()
        assertEquals(101, first.id)
        assertEquals(101, first.resolvedChapterId)
        assertEquals("Chapter 3 — The Descent", first.resolvedTitle)
        assertEquals(3.0, first.displayNumber)
        assertTrue(first.playable)
        assertEquals(1200.0, first.audioDuration)
        assertEquals("20:00", first.audioDurationLabel)
        assertEquals(412.5, first.playback?.positionSeconds)
        assertFalse(first.playback?.isPlayed == true)
        assertEquals("13:07 left", first.playback?.remainingLabel)
        assertEquals(412.5, first.resolvedPositionSeconds)
    }

    @Test
    fun `a not-yet-converted chapter decodes with a null audio object`() {
        val response = parse<ChaptersResponse>(ServerFixtures.CHAPTERS)
        val pending = response.chapters[1]
        assertEquals("processing", pending.status)
        assertFalse(pending.playable)
        assertNull(pending.audio)
        assertEquals(0.0, pending.resolvedPositionSeconds)
    }

    @Test
    fun `unknown additive fields anywhere in the payload are ignored`() {
        // api_version 2, a new top-level delta_token, a new nested `series` object inside the
        // fiction, new keys inside `audio` and `playback`, and a whole new `bookmarks` array.
        val response = parse<ChaptersResponse>(ServerFixtures.CHAPTERS_WITH_UNKNOWN_ADDITIVE_FIELDS)
        assertEquals(2, response.apiVersion)
        assertEquals(7, response.fiction.id)
        val chapter = response.chapters.single()
        assertEquals(101, chapter.resolvedChapterId)
        assertEquals("/audio/a-test-serial/0003.mp3", chapter.audio?.url)
        assertEquals(10.0, chapter.resolvedPositionSeconds)
    }

    @Test
    fun `playback progress and mark responses map their status fields`() {
        val progress = parse<PlaybackProgressResponse>(ServerFixtures.PROGRESS_SAVED)
        assertEquals("saved", progress.status)
        assertEquals(101, progress.chapterId)

        val mark = parse<PlaybackMarkResponse>(ServerFixtures.MARK_OK)
        assertEquals("ok", mark.status)
        assertTrue(mark.played)
        assertEquals(listOf(101), mark.chapterIds)
        assertEquals(1, mark.count)
    }

    @Test
    fun `an empty library decodes to empty lists rather than nulls`() {
        val library = parse<LibraryResponse>("""{"api_version": 1}""")
        assertEquals(emptyList(), library.fictions)
        assertEquals(emptyList(), library.continueListening)
        assertEquals(emptyList(), library.recentChapters)
    }
}
