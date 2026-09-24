package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import retrofit2.HttpException
import retrofit2.Response

class ReadAlongCacheTest {
    private val response = ReadAlongResponse(
        chapter = ReadAlongChapter(10, 7, "Chapter 1", audioDuration = 60.0, hasTimings = true),
        text = "One two.",
        paragraphs = listOf(listOf(0.0, 8.0)),
        cues = listOf(listOf(0.0, 3.0, 0.0), listOf(4.0, 7.0, 1.0)),
    )

    @Test
    fun `first fetch persists and 304 returns the same parsed instance`(@TempDir root: java.io.File) =
        kotlinx.coroutines.test.runTest {
            val repository = FakeRepository(
                readAlongResult = Result.success(ReadAlongFetchResult.Modified(response, "\"abc\"")),
            )
            val cache = ReadAlongCache(repository).attachDiskCache { ReadAlongDiskCache(root) }
            val first = cache.load(10)
            repository.readAlongResult = Result.success(ReadAlongFetchResult.NotModified)

            val second = cache.load(10)

            assertSame(first, second)
            assertEquals(listOf(null, "\"abc\""), repository.readAlongEtags)
        }

    @Test
    fun `a previous-launch document reads through a network outage`(@TempDir root: java.io.File) =
        kotlinx.coroutines.test.runTest {
            ReadAlongDiskCache(root).write(10, CachedReadAlong(etag = "\"abc\"", response = response))
            val repository = FakeRepository(
                readAlongResult = Result.failure(IOException("offline")),
            )
            val restarted = ReadAlongCache(repository).attachDiskCache { ReadAlongDiskCache(root) }

            assertEquals("One two.", restarted.load(10)?.text)
            assertEquals(listOf<String?>("\"abc\""), repository.readAlongEtags)
        }

    @Test
    fun `404 is a normal empty state and removes stale disk content`(@TempDir root: java.io.File) =
        kotlinx.coroutines.test.runTest {
            ReadAlongDiskCache(root).write(10, CachedReadAlong(response = response))
            val repository = FakeRepository(readAlongResult = Result.success(ReadAlongFetchResult.NotFound))
            val cache = ReadAlongCache(repository).attachDiskCache { ReadAlongDiskCache(root) }

            assertNull(cache.load(10))
            assertNull(ReadAlongDiskCache(root).read(10))
        }

    @Test
    fun `clear fences suspended modified missing unchanged and failed requests`(@TempDir root: java.io.File) = runTest {
        val oldResults = listOf<Result<ReadAlongFetchResult>>(
            Result.success(ReadAlongFetchResult.Modified(response, "old")),
            Result.success(ReadAlongFetchResult.NotFound),
            Result.success(ReadAlongFetchResult.NotModified),
            Result.failure(java.io.IOException("offline")),
        )
        oldResults.forEach { oldResult ->
            val disk = ReadAlongDiskCache(root)
            disk.write(10, CachedReadAlong(etag = "retained", response = response))
            val suspended = CompletableDeferred<ReadAlongFetchResult>()
            val repository = object : FakeRepository() {
                var requests = 0
                override suspend fun readAlong(chapterId: Int, ifNoneMatch: String?): ReadAlongFetchResult {
                    if (requests++ == 0) return suspended.await()
                    return super.readAlong(chapterId, ifNoneMatch)
                }
            }
            val cache = ReadAlongCache(repository).attachDiskCache { disk }
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.load(10) }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { cache.load(10) }
            val before = cache.currentGeneration()
            cache.clear()
            assertNotEquals(before, cache.currentGeneration())
            val replacement = response.copy(text = "New session text", paragraphs = emptyList(), cues = emptyList())
            repository.readAlongResult = Result.success(ReadAlongFetchResult.Modified(replacement, "new"))
            val current = cache.load(10)
            oldResult.fold(suspended::complete, suspended::completeExceptionally)

            assertFailsWith<CancellationException> { old.await() }
            assertFailsWith<CancellationException> { queued.await() }
            assertEquals(replacement, disk.read(10)?.response)
            repository.readAlongResult = Result.success(ReadAlongFetchResult.NotModified)
            assertSame(current, cache.load(10))
        }
    }

    @Test
    fun `an owner switch fences old text without relying on a clear call`(@TempDir root: java.io.File) = runTest {
        val alice = ReadAlongDiskCache(root.resolve("alice"))
        val bob = ReadAlongDiskCache(root.resolve("bob"))
        var currentDisk = alice
        val suspended = CompletableDeferred<ReadAlongFetchResult>()
        val repository = object : FakeRepository() {
            var requests = 0
            override suspend fun readAlong(chapterId: Int, ifNoneMatch: String?): ReadAlongFetchResult {
                if (requests++ == 0) return suspended.await()
                return super.readAlong(chapterId, ifNoneMatch)
            }
        }
        val cache = ReadAlongCache(repository).attachDiskCache { currentDisk }
        val old = async(start = CoroutineStart.UNDISPATCHED) { cache.load(10) }
        currentDisk = bob
        val replacement = response.copy(text = "Bob text")
        repository.readAlongResult = Result.success(ReadAlongFetchResult.Modified(replacement, "bob"))
        val current = cache.load(10)
        suspended.complete(ReadAlongFetchResult.Modified(response, "alice"))

        assertFailsWith<CancellationException> { old.await() }
        assertNull(alice.read(10))
        assertEquals(replacement, bob.read(10)?.response)
        repository.readAlongResult = Result.success(ReadAlongFetchResult.NotModified)
        assertSame(current, cache.load(10))
        assertEquals(listOf(null, "bob"), repository.readAlongEtags)
    }

    @Test
    fun `session credential changes fence requests even with no disk`() = runTest {
        val session = MutableStateFlow(SessionState("https://example.test", "first", "alice"))
        val suspended = CompletableDeferred<ReadAlongFetchResult>()
        val repository = object : FakeRepository() {
            var requests = 0
            override suspend fun readAlong(chapterId: Int, ifNoneMatch: String?): ReadAlongFetchResult {
                if (requests++ == 0) return suspended.await()
                return super.readAlong(chapterId, ifNoneMatch)
            }
        }
        val cache = ReadAlongCache(repository, session)
        val old = async(start = CoroutineStart.UNDISPATCHED) { cache.load(10) }
        session.value = session.value.copy(token = "second")
        repository.readAlongResult = Result.success(ReadAlongFetchResult.Modified(response.copy(text = "New text"), "new"))
        val current = cache.load(10)
        suspended.complete(ReadAlongFetchResult.Modified(response, "old"))

        assertFailsWith<CancellationException> { old.await() }
        repository.readAlongResult = Result.success(ReadAlongFetchResult.NotModified)
        assertSame(current, cache.load(10))
        session.value = session.value.copy(token = null)
        assertNull(cache.load(10))
        assertEquals(2, repository.readAlongCalls)
    }

    @Test
    fun `same account disk survives clear but retains raw code point units`(@TempDir root: java.io.File) = runTest {
        val raw = response.copy(
            text = "\uD83D\uDE00 e\u0301 \uD840\uDC00",
            paragraphs = listOf(listOf(0.0, 6.0)),
            cues = listOf(listOf(0.0, 1.0, 0.0), listOf(2.0, 4.0, 1.0), listOf(5.0, 6.0, 2.0)),
        )
        val repository = FakeRepository(readAlongResult = Result.success(ReadAlongFetchResult.Modified(raw, "unicode")))
        val disk = ReadAlongDiskCache(root)
        val cache = ReadAlongCache(repository).attachDiskCache { disk }
        val first = cache.load(10)
        cache.clear()
        repository.readAlongResult = Result.failure(IOException("offline"))

        assertEquals(raw, disk.read(10)?.response)
        assertEquals(first, cache.load(10))
        assertEquals(TextSpan(6, 8), cache.load(10)?.cues?.last()?.span)
        assertEquals(raw, disk.read(10)?.response)
    }

    @Test
    fun `401 neither falls back nor allows a subsequent offline fallback`(@TempDir root: java.io.File) = runTest {
        val disk = ReadAlongDiskCache(root)
        disk.write(10, CachedReadAlong(response = response))
        val denied = HttpException(Response.error<Any>(401, "unauthorized".toResponseBody()))
        val repository = FakeRepository(readAlongResult = Result.failure(denied))
        val cache = ReadAlongCache(repository).attachDiskCache { disk }

        assertSame(denied, assertFailsWith<HttpException> { cache.load(10) })
        repository.readAlongResult = Result.failure(IOException("offline"))
        assertNull(cache.load(10))
        assertEquals(1, repository.readAlongCalls)
        assertEquals(response, disk.read(10)?.response)
    }

    @Test
    fun `an old 401 cannot invalidate a new sessions document`() = runTest {
        val denied = HttpException(Response.error<Any>(401, "unauthorized".toResponseBody()))
        val suspended = CompletableDeferred<ReadAlongFetchResult>()
        val repository = object : FakeRepository() {
            var requests = 0
            override suspend fun readAlong(chapterId: Int, ifNoneMatch: String?): ReadAlongFetchResult {
                if (requests++ == 0) return suspended.await()
                return super.readAlong(chapterId, ifNoneMatch)
            }
        }
        val cache = ReadAlongCache(repository)
        val old = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.load(10) } }
        cache.clear()
        repository.readAlongResult = Result.success(ReadAlongFetchResult.Modified(response, "new"))
        val current = cache.load(10)
        suspended.completeExceptionally(denied)

        assertSame(denied, old.await().exceptionOrNull())
        repository.readAlongResult = Result.success(ReadAlongFetchResult.NotModified)
        assertSame(current, cache.load(10))
    }

    @Test
    fun `cancellation never becomes a cached success`(@TempDir root: java.io.File) = runTest {
        val disk = ReadAlongDiskCache(root)
        disk.write(10, CachedReadAlong(response = response))
        val repository = FakeRepository(readAlongResult = Result.failure(CancellationException("cancelled")))
        val cache = ReadAlongCache(repository).attachDiskCache { disk }

        assertFailsWith<CancellationException> { cache.load(10) }
    }

    @Test
    fun `a mismatched chapter response is never cached or rendered`(@TempDir root: java.io.File) =
        kotlinx.coroutines.test.runTest {
            val repository = FakeRepository(
                readAlongResult = Result.success(
                    ReadAlongFetchResult.Modified(
                        response.copy(chapter = response.chapter.copy(id = 99)),
                        null,
                    ),
                ),
            )
            val cache = ReadAlongCache(repository).attachDiskCache { ReadAlongDiskCache(root) }

            kotlin.test.assertFails { cache.load(10) }
            assertNull(ReadAlongDiskCache(root).read(10))
        }
}
