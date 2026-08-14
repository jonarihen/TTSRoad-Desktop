package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryDiskCacheTest {

    @TempDir
    lateinit var tempDir: File

    private val fiction = FictionSummary(id = 7, title = "Private serial")
    private val library = LibraryResponse(fictions = listOf(fiction))
    private val chapters = ChaptersResponse(
        fiction = fiction,
        total = 1,
        chapters = listOf(
            ChapterSummary(
                id = 101,
                fictionId = 7,
                title = "One",
                audio = AudioInfo(url = "/audio/private/101.mp3"),
            ),
        ),
    )

    private fun cache(name: String = "metadata") = LibraryDiskCache(File(tempDir, name))

    @Test
    fun `library and chapter snapshots survive restart with their refresh times`() {
        cache().storeLibrary(library, 1_000)
        cache().storeChapters(7, chapters, 2_000)

        val reopened = cache()
        assertEquals(DiskCached(library, 1_000), reopened.loadLibrary())
        assertEquals(DiskCached(chapters, 2_000), reopened.loadChapters(7))
    }

    @Test
    fun `corrupt metadata is ignored rather than breaking startup`() {
        val cache = cache()
        cache.root.mkdirs()
        File(cache.root, LibraryDiskCache.LibraryFileName).writeText("not json")

        assertNull(cache.loadLibrary())
    }

    @Test
    fun `cached metadata strips server-local paths and backend errors`() {
        val cache = cache()
        val private = chapters.copy(
            chapters = listOf(
                chapters.chapters.single().copy(
                    errorMessage = "failed under /srv/ttsroad/private/alice",
                    audio = AudioInfo(
                        filename = "secret.mp3",
                        path = "/srv/ttsroad/private/alice/secret.mp3",
                        url = "/audio/private/101.mp3",
                    ),
                ),
            ),
        )

        cache.storeChapters(7, private, 1)

        val diskText = File(cache.root, "chapters-7.json").readText()
        assertTrue("/srv/ttsroad" !in diskText)
        assertTrue("secret.mp3" !in diskText)
        assertEquals("/audio/private/101.mp3", cache.loadChapters(7)?.value?.chapters?.single()?.audio?.url)
    }

    @Test
    fun `a chapter file cannot be relabelled as another fiction`() {
        val cache = cache()
        cache.storeChapters(7, chapters, 1)
        Files.copy(File(cache.root, "chapters-7.json").toPath(), File(cache.root, "chapters-8.json").toPath())

        assertNull(cache.loadChapters(8))
    }

    @Test
    fun `confirmed fiction deletion removes its cached chapter metadata`() {
        val cache = cache()
        cache.storeChapters(7, chapters, 1)

        cache.removeChapters(7)

        assertNull(cache.loadChapters(7))
        assertTrue(!File(cache.root, "chapters-7.json").exists())
    }

    @Test
    fun `a planted metadata symlink is refused and its target remains untouched`() {
        val cache = cache()
        cache.root.mkdirs()
        val outside = File(tempDir, "outside.json").apply { writeText("private") }
        val created = runCatching {
            Files.createSymbolicLink(
                File(cache.root, LibraryDiskCache.LibraryFileName).toPath(),
                outside.toPath(),
            )
        }
        assumeTrue(created.isSuccess, "this filesystem does not allow symlinks")

        assertNull(cache.loadLibrary())
        assertTrue(outside.isFile)
        assertEquals("private", outside.readText())
    }
}
