package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedPlaybackHistoryStoreTest {

    private val owner = PlaybackHistory.ownerKeyFor("https://ttsroad.example", "alice")

    private fun snapshot(
        positionSeconds: Double = 60.0,
        recordedAtMs: Long = 1_000L,
        ownerKey: String = owner,
    ) = PlaybackSnapshot(
        fictionId = 7,
        chapterId = 101,
        fictionTitle = "A Test Serial",
        chapterTitle = "Chapter 3",
        positionSeconds = positionSeconds,
        durationSeconds = 600.0,
        recordedAtMs = recordedAtMs,
        ownerKey = ownerKey,
    )

    @Test
    fun `recording stays local first and sends an auto bookmark`() = runTest {
        val repository = FakeRepository()
        val local = InMemoryPlaybackHistoryStore()
        val store = SyncedPlaybackHistoryStore(
            local,
            repository,
            UnconfinedTestDispatcher(testScheduler),
        ) { owner }

        store.record(snapshot(positionSeconds = 61.25))

        assertEquals(61.25, local.history.value.single().positionSeconds)
        val request = repository.createdBookmarks.single()
        assertEquals(101, request.chapterId)
        assertEquals(61.25, request.positionSeconds)
        assertEquals(BookmarkKind.Auto, request.kind)
    }

    @Test
    fun `a barely started chapter remains local without spending a server breadcrumb`() = runTest {
        val repository = FakeRepository()
        val local = InMemoryPlaybackHistoryStore()
        val store = SyncedPlaybackHistoryStore(
            local,
            repository,
            UnconfinedTestDispatcher(testScheduler),
        ) { owner }

        store.record(snapshot(positionSeconds = 12.0))

        assertEquals(12.0, local.history.value.single().positionSeconds)
        assertTrue(repository.createdBookmarks.isEmpty())
    }

    @Test
    fun `refresh asks for auto bookmarks and merges another device's newest position`() = runTest {
        val repository = FakeRepository(
            bookmarksResult = Result.success(
                listOf(
                    Bookmark(
                        id = 9,
                        chapterId = 101,
                        fictionId = 7,
                        positionSeconds = 240.0,
                        kind = BookmarkKind.Auto,
                        createdAt = "2026-08-14T10:15:30Z",
                        chapterTitle = "Chapter 3",
                        fictionTitle = "A Test Serial",
                    ),
                ),
            ),
        )
        val local = InMemoryPlaybackHistoryStore(listOf(snapshot(positionSeconds = 60.0, recordedAtMs = 1_000)))
        val store = SyncedPlaybackHistoryStore(
            local,
            repository,
            UnconfinedTestDispatcher(testScheduler),
        ) { owner }

        store.refresh(owner)

        assertEquals(listOf<Pair<String?, Int?>>(BookmarkKind.Auto to null), repository.bookmarkListCalls)
        val merged = local.history.value.single()
        assertEquals(240.0, merged.positionSeconds)
        assertEquals(600.0, merged.durationSeconds, "the server has no duration, so the local one survives")
    }

    @Test
    fun `a result for an account that has since signed out is discarded`() = runTest {
        var current = owner
        val repository = FakeRepository(
            bookmarksResult = Result.success(
                listOf(
                    Bookmark(
                        id = 9,
                        chapterId = 101,
                        fictionId = 7,
                        positionSeconds = 240.0,
                        kind = BookmarkKind.Auto,
                        createdAt = "2026-08-14T10:15:30Z",
                    ),
                ),
            ),
        )
        val local = InMemoryPlaybackHistoryStore()
        val store = SyncedPlaybackHistoryStore(
            local,
            repository,
            UnconfinedTestDispatcher(testScheduler),
        ) { current }

        current = ""
        store.refresh(owner)

        assertTrue(repository.bookmarkListCalls.isEmpty())
        assertTrue(local.history.value.isEmpty())
    }
}
