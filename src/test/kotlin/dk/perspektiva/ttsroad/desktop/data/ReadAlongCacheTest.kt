package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.io.TempDir

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
