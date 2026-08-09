package dk.perspektiva.ttsroad.desktop.data

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ReadAlongDiskCacheTest {
    private fun cached(chapterId: Int, text: String = "Chapter $chapterId") = CachedReadAlong(
        etag = "\"$chapterId\"",
        response = ReadAlongResponse(
            chapter = ReadAlongChapter(id = chapterId, fictionId = 7, title = text),
            text = text,
            paragraphs = listOf(listOf(0.0, text.length.toDouble())),
        ),
    )

    @Test
    fun `documents are isolated by stable server and account identity`(@TempDir root: java.io.File) {
        val alice = ReadAlongDiskCache.forIdentity(StorageIdentity("server-a", "alice"), root)
        val bob = ReadAlongDiskCache.forIdentity(StorageIdentity("server-a", "bob"), root)
        val elsewhere = ReadAlongDiskCache.forIdentity(StorageIdentity("server-b", "alice"), root)

        alice.write(10, cached(10, "Alice text"))

        assertEquals("Alice text", alice.read(10)?.response?.text)
        assertNull(bob.read(10))
        assertNull(elsewhere.read(10))
    }

    @Test
    fun `least recently used documents are evicted independently from audio`(@TempDir root: java.io.File) {
        var now = 1L
        val cache = ReadAlongDiskCache(root, maxBytes = Long.MAX_VALUE, maxEntries = 2, clock = { now++ })

        cache.write(1, cached(1))
        cache.write(2, cached(2))
        cache.read(1) // Chapter 1 becomes newer than 2.
        cache.write(3, cached(3))

        assertEquals(2, cache.size())
        assertTrue(cache.read(1) != null)
        assertNull(cache.read(2))
        assertTrue(cache.read(3) != null)
    }

    @Test
    fun `copied wrong-chapter and corrupt files are rejected`(@TempDir root: java.io.File) {
        val cache = ReadAlongDiskCache(root)
        cache.write(10, cached(10))
        Files.copy(root.resolve("chapter-10.json").toPath(), root.resolve("chapter-11.json").toPath())
        root.resolve("chapter-12.json").writeText("not json")

        assertNull(cache.read(11))
        assertNull(cache.read(12))
        assertFalse(root.resolve("chapter-11.json").exists())
    }

    @Test
    fun `a symlink root is never followed`(@TempDir parent: java.io.File) {
        val target = parent.toPath().resolve("target").createDirectory()
        val link = parent.toPath().resolve("link")
        Files.createSymbolicLink(link, target)
        val cache = ReadAlongDiskCache(link.toFile())

        cache.write(10, cached(10))

        assertNull(cache.read(10))
        assertEquals(0, cache.size())
        assertTrue(target.toFile().listFiles().orEmpty().isEmpty())
    }
}
