package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The cache is what makes browsing survive navigation, so these tests are about *timing and
 * destruction* rather than about parsing: who gets asked, how often, and what is still on screen
 * afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCacheTest {

    @TempDir
    lateinit var tempDir: File

    private fun library(vararg titles: String) = LibraryResponse(
        fictions = titles.mapIndexed { index, title -> FictionSummary(id = index + 1, title = title) },
    )

    // --- Library ------------------------------------------------------------------------------

    @Test
    fun `the first ensure loads, and the state carries the time it succeeded`() = runTest {
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler)) { 1_000L }

        cache.ensureLibrary()
        runCurrent()

        val state = cache.library.value
        assertEquals(1, state.value?.fictions?.size)
        assertEquals(1_000L, state.lastSuccessMillis)
        assertFalse(state.isRefreshing)
        assertNull(state.error)
        assertEquals(1, repository.libraryCalls)
        cache.close()
    }

    @Test
    fun `a second ensure returns the cached library without asking again`() = runTest {
        // This is the whole point of the phase: Library to Fiction to Back costs no requests.
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        runCurrent()

        cache.ensureLibrary()
        cache.ensureLibrary()
        runCurrent()

        assertEquals(1, repository.libraryCalls)
        cache.close()
    }

    @Test
    fun `a restart can browse cached library and chapters while the server is offline`() = runTest {
        val fiction = FictionSummary(id = 7, title = "Cached serial")
        val library = LibraryResponse(fictions = listOf(fiction))
        val chapters = ChaptersResponse(
            fiction = fiction,
            total = 1,
            chapters = listOf(
                ChapterSummary(
                    id = 101,
                    fictionId = 7,
                    title = "Cached chapter",
                    audio = AudioInfo(url = "/audio/101.mp3"),
                ),
            ),
        )
        val disk = LibraryDiskCache(File(tempDir, "metadata"))
        disk.storeLibrary(library, 1_000)
        disk.storeChapters(7, chapters, 2_000)
        val repository = FakeRepository(
            libraryResult = Result.failure(IllegalStateException("server offline")),
            chaptersResult = Result.failure(IllegalStateException("server offline")),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler)) { 3_000L }
            .attachDiskCache { disk }

        cache.ensureLibrary()
        cache.ensureChapters(7)
        runCurrent()

        assertEquals("Cached serial", cache.library.value.value?.fictions?.single()?.title)
        assertEquals(1_000L, cache.library.value.lastSuccessMillis)
        assertEquals("server offline", cache.library.value.error)
        assertEquals("Cached chapter", cache.chapters(7).value.value?.chapters?.single()?.title)
        assertEquals(2_000L, cache.chapters(7).value.lastSuccessMillis)
        assertEquals("server offline", cache.chapters(7).value.error)
        cache.close()
    }

    @Test
    fun `duplicate ensures while a load is in flight are coalesced into one request`() = runTest {
        val gate = CompletableDeferred<LibraryResponse>()
        val repository = object : FakeRepository() {
            var calls = 0
            override suspend fun library(): LibraryResponse {
                calls++
                return gate.await()
            }
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))

        cache.ensureLibrary()
        cache.ensureLibrary()
        cache.ensureLibrary()
        runCurrent()

        assertEquals(1, repository.calls)
        gate.complete(library("A"))
        runCurrent()
        assertEquals(1, cache.library.value.value?.fictions?.size)
        cache.close()
    }

    @Test
    fun `an explicit refresh always re-asks`() = runTest {
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        runCurrent()

        cache.refreshLibrary()
        runCurrent()

        assertEquals(2, repository.libraryCalls)
        cache.close()
    }

    @Test
    fun `a superseded load never publishes its answer`() = runTest {
        // Two refreshes in quick succession: the first must not overwrite the second's result just
        // because it happened to come back later.
        val first = CompletableDeferred<LibraryResponse>()
        val second = CompletableDeferred<LibraryResponse>()
        val repository = object : FakeRepository() {
            var calls = 0
            override suspend fun library(): LibraryResponse {
                calls++
                return if (calls == 1) first.await() else second.await()
            }
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))

        cache.refreshLibrary()
        runCurrent()
        cache.refreshLibrary()
        runCurrent()

        second.complete(library("second"))
        runCurrent()
        first.complete(library("first"))
        runCurrent()

        assertEquals("second", cache.library.value.value?.fictions?.first()?.title)
        cache.close()
    }

    @Test
    fun `an initial failure has no content, so the screen owes an error and a retry`() = runTest {
        val repository = FakeRepository(libraryResult = Result.failure(IllegalStateException("no route to host")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))

        cache.ensureLibrary()
        runCurrent()

        val state = cache.library.value
        assertNull(state.value)
        assertEquals("no route to host", state.error)
        assertFalse(state.isInitialLoad, "an error is an answer; it must not keep spinning")
        cache.close()
    }

    @Test
    fun `a failed refresh keeps the content it could not replace`() = runTest {
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler)) { 5_000L }
        cache.ensureLibrary()
        runCurrent()

        repository.libraryResult = Result.failure(IllegalStateException("connection reset"))
        cache.refreshLibrary()
        runCurrent()

        val state = cache.library.value
        assertEquals("A", state.value?.fictions?.first()?.title, "the list on screen must survive")
        assertEquals("connection reset", state.error)
        assertTrue(state.isStale)
        assertEquals(5_000L, state.lastSuccessMillis, "how old the content is, so it is not called current")
        assertFalse(state.isRefreshing)
        cache.close()
    }

    @Test
    fun `a successful refresh clears a previous failure`() = runTest {
        val repository = FakeRepository(libraryResult = Result.failure(IllegalStateException("down")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        runCurrent()

        repository.libraryResult = Result.success(library("A"))
        cache.refreshLibrary()
        runCurrent()

        assertNull(cache.library.value.error)
        assertNotNull(cache.library.value.value)
        cache.close()
    }

    // --- Chapters -----------------------------------------------------------------------------

    @Test
    fun `chapters are cached per fiction`() = runTest {
        val repository = FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(fiction = FictionSummary(id = 7), chapters = listOf(ChapterSummary(id = 101))),
            ),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))

        cache.ensureChapters(7)
        runCurrent()
        cache.ensureChapters(7)
        runCurrent()
        cache.ensureChapters(8)
        runCurrent()

        assertEquals(2, repository.chaptersCalls, "one request per fiction, not per visit")
        assertEquals(1, cache.chapters(7).value.value?.chapters?.size)
        cache.close()
    }

    @Test
    fun `marking a chapter played patches the cached list instead of refetching it`() = runTest {
        val repository = FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = listOf(
                        ChapterSummary(id = 101, title = "One", audioDuration = 600.0),
                        ChapterSummary(id = 102, title = "Two"),
                    ),
                ),
            ),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()
        val before = cache.chapters(7).value.value!!.chapters

        cache.setPlayed(7, listOf(101), played = true)
        runCurrent()

        val after = cache.chapters(7).value.value!!.chapters
        assertEquals(listOf(listOf(101) to true), repository.markedPlayed)
        assertEquals(1, repository.chaptersCalls, "one checkmark must not re-download the fiction")
        assertTrue(after[0].playback?.isPlayed == true)
        assertEquals(600.0, after[0].playback?.positionSeconds)
        // Untouched rows keep their identity so Compose can skip them.
        assertSame(before[1], after[1])
        cache.close()
    }

    @Test
    fun `one bulk mark is one request, not one per chapter`() = runTest {
        val repository = FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = (1..40).map { ChapterSummary(id = 100 + it, fictionId = 7, displayNumber = it.toDouble()) },
                ),
            ),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()
        val ids = cache.chapters(7).value.value!!.chapters.markableIds(played = true)

        cache.setPlayed(7, ids, played = true)
        runCurrent()

        assertEquals(1, repository.markedPlayed.size, "40 chapters must cost one request")
        assertEquals(40, repository.markedPlayed.single().first.size)
        assertTrue(cache.chapters(7).value.value!!.chapters.all { it.isPlayed })
        cache.close()
    }

    @Test
    fun `the patch lands before the server answers, and survives the answer`() = runTest {
        // Optimism is the point: the checkmark moves in the frame the user clicked it, not one
        // round trip later.
        val gate = CompletableDeferred<PlaybackMarkResponse>()
        val repository = object : FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = listOf(ChapterSummary(id = 101, fictionId = 7, audioDuration = 600.0)),
                ),
            ),
        ) {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean) = gate.await()
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()

        val marking = launch { cache.setPlayed(7, listOf(101), played = true) }
        runCurrent()
        assertTrue(
            cache.chapters(7).value.value!!.chapters.first().isPlayed,
            "the row must be patched while the request is still in flight",
        )

        gate.complete(PlaybackMarkResponse(status = "ok", played = true, chapterIds = listOf(101), count = 1))
        marking.join()
        assertTrue(cache.chapters(7).value.value!!.chapters.first().isPlayed)
        cache.close()
    }

    @Test
    fun `an id the server silently dropped is rolled back on its own`() = runTest {
        // `playback/mark` echoes only the ids it actually touched: excluded or unknown chapters are
        // dropped. Optimistically ticking one of those and leaving it ticked would be a lie that
        // survives until the next refresh.
        val repository = object : FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = listOf(
                        ChapterSummary(id = 101, fictionId = 7, audioDuration = 600.0),
                        ChapterSummary(id = 102, fictionId = 7, excluded = true),
                    ),
                ),
            ),
        ) {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean) =
                PlaybackMarkResponse(status = "ok", played = played, chapterIds = listOf(101), count = 1)
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()

        cache.setPlayed(7, listOf(101, 102), played = true)
        runCurrent()

        val after = cache.chapters(7).value.value!!.chapters
        assertTrue(after[0].isPlayed, "the confirmed id stays marked")
        assertFalse(after[1].isPlayed, "the dropped id goes back to what it was")
        cache.close()
    }

    @Test
    fun `a rollback restores real progress instead of zeroing it`() = runTest {
        val repository = object : FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = listOf(
                        ChapterSummary(
                            id = 101,
                            fictionId = 7,
                            audioDuration = 1200.0,
                            playback = PlaybackInfo(positionSeconds = 412.5),
                        ),
                    ),
                ),
            ),
        ) {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
                throw IllegalStateException("connection reset")
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()

        val failure = runCatching { cache.setPlayed(7, listOf(101), played = true) }
        runCurrent()

        assertEquals("connection reset", failure.exceptionOrNull()?.message)
        val row = cache.chapters(7).value.value!!.chapters.single()
        assertFalse(row.isPlayed)
        assertEquals(412.5, row.playback?.positionSeconds, "6:52 in must still be 6:52 in")
        cache.close()
    }

    @Test
    fun `marking from the detail screen also updates the library shelves`() = runTest {
        val repository = FakeRepository(
            libraryResult = Result.success(
                LibraryResponse(
                    fictions = listOf(FictionSummary(id = 7)),
                    continueListening = listOf(
                        ChapterSummary(
                            apiChapterId = 101,
                            fictionId = 7,
                            resumeSeconds = 412.5,
                            playedCount = 0,
                            remainingCount = 2,
                        ),
                    ),
                ),
            ),
            chaptersResult = Result.success(
                ChaptersResponse(
                    fiction = FictionSummary(id = 7),
                    chapters = listOf(
                        ChapterSummary(id = 101, fictionId = 7, audioDuration = 600.0, audio = AudioInfo(url = "/a.mp3")),
                        ChapterSummary(id = 102, fictionId = 7, audioDuration = 600.0, audio = AudioInfo(url = "/b.mp3")),
                    ),
                ),
            ),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        cache.ensureChapters(7)
        runCurrent()

        cache.setPlayed(7, listOf(101), played = true)
        runCurrent()

        val shelfRow = cache.library.value.value!!.continueListening.single()
        assertTrue(shelfRow.isPlayed, "the same chapter on the shelf must stop offering a resume")
        // Counted from this client's own chapter list rather than guessed from a delta.
        assertEquals(1, shelfRow.playedCount)
        assertEquals(1, shelfRow.remainingCount)
        assertEquals(1, repository.libraryCalls, "patching the shelf must not refetch the library")
        cache.close()
    }

    // --- Per-fiction browsing options -----------------------------------------------------------

    @Test
    fun `filter and sort are remembered per fiction for the whole session`() = runTest {
        val cache = LibraryCache(FakeRepository(), UnconfinedTestDispatcher(testScheduler))

        cache.setChapterOptions(7, ChapterListOptions(ChapterFilter.Unplayed, ChapterSort.Newest))

        assertEquals(ChapterFilter.Unplayed, cache.chapterOptions(7).value.filter)
        assertEquals(ChapterSort.Newest, cache.chapterOptions(7).value.sort)
        // A different serial is a different choice, and a fresh one starts at the defaults.
        assertEquals(ChapterListOptions(), cache.chapterOptions(8).value)
        cache.close()
    }

    @Test
    fun `browsing options do not outlive the session that made them`() = runTest {
        val cache = LibraryCache(FakeRepository(), UnconfinedTestDispatcher(testScheduler))
        cache.setChapterOptions(7, ChapterListOptions(ChapterFilter.Ready, ChapterSort.Newest))

        cache.clear()

        assertEquals(ChapterListOptions(), cache.chapterOptions(7).value)
        cache.close()
    }

    @Test
    fun `a failed mark throws and leaves the cached list untouched`() = runTest {
        val repository = object : FakeRepository(
            chaptersResult = Result.success(
                ChaptersResponse(fiction = FictionSummary(id = 7), chapters = listOf(ChapterSummary(id = 101))),
            ),
        ) {
            override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean) =
                throw IllegalStateException("server said no")
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureChapters(7)
        runCurrent()

        val failure = runCatching { cache.setPlayed(7, listOf(101), played = true) }

        assertEquals("server said no", failure.exceptionOrNull()?.message)
        assertFalse(cache.chapters(7).value.value!!.chapters.first().playback?.isPlayed == true)
        cache.close()
    }

    // --- Session end --------------------------------------------------------------------------

    @Test
    fun `clearing drops everything so the next account sees its own library`() = runTest {
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        cache.ensureChapters(7)
        runCurrent()

        cache.clear()

        assertNull(cache.library.value.value)
        assertNull(cache.chapters(7).value.value)

        // …and the next ensure is a real load again, not a cache hit.
        cache.ensureLibrary()
        runCurrent()
        assertEquals(2, repository.libraryCalls)
        cache.close()
    }

    @Test
    fun `closing cancels the scope so a later refresh does nothing`() = runTest {
        val repository = FakeRepository(libraryResult = Result.success(library("A")))
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        cache.ensureLibrary()
        runCurrent()

        cache.close()
        cache.refreshLibrary()
        runCurrent()

        assertEquals(1, repository.libraryCalls)
    }
}
