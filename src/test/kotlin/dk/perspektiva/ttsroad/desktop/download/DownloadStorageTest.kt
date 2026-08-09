package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.StorageIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The download root, and the rule that nothing may reach outside it.
 *
 * The acceptance criterion is that "cleanup cannot escape the owned roots" — so these are mostly
 * tests that a hostile or corrupted name is refused rather than sanitised into something adjacent.
 */
class DownloadStorageTest {

    @TempDir
    lateinit var tempDir: File

    private fun storage(): DownloadStorage =
        DownloadStorage(File(tempDir, "downloads")).also { it.prepare() }

    // --- Layout ---------------------------------------------------------------------------------

    @Test
    fun `two accounts on one server get separate roots`() {
        val alice = DownloadStorage.forIdentity(
            StorageIdentity.of("https://host.example", username = "alice"),
            tempDir,
        )
        val bob = DownloadStorage.forIdentity(
            StorageIdentity.of("https://host.example", username = "bob"),
            tempDir,
        )

        assertFalse(alice.root.path == bob.root.path)
        // And both are inside the one downloads directory, so a total is one tree walk.
        assertTrue(alice.root.path.startsWith(DownloadStorage.downloadsRoot(tempDir).path))
        assertTrue(bob.root.path.startsWith(DownloadStorage.downloadsRoot(tempDir).path))
    }

    @Test
    fun `two servers with the same account name do not share a root`() {
        val one = DownloadStorage.forIdentity(StorageIdentity.of("https://a.example", username = "alice"), tempDir)
        val two = DownloadStorage.forIdentity(StorageIdentity.of("https://b.example", username = "alice"), tempDir)

        assertFalse(one.root.path == two.root.path)
    }

    // --- Directory permissions ------------------------------------------------------------------

    @Test
    fun `the root is owner-only but still traversable`() {
        val storage = storage()
        val view = Files.getFileAttributeView(storage.root.toPath(), PosixFileAttributeView::class.java)
        assumeTrue(view != null, "POSIX permissions are not available on this filesystem")

        val permissions = view.readAttributes().permissions()
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                // Without this the app cannot create a file inside its own download directory.
                PosixFilePermission.OWNER_EXECUTE,
            ),
            permissions,
        )

        // The property the mask exists for: writing inside actually works.
        val file = storage.resolve("1.mp3")
        file.writeBytes(ByteArray(4))
        assertTrue(file.isFile)
    }

    // --- Path safety ----------------------------------------------------------------------------

    @Test
    fun `a traversing name is refused`() {
        val storage = storage()
        for (name in listOf("../escape.mp3", "../../etc/passwd", "sub/dir.mp3", "..")) {
            assertFailsWith<IllegalArgumentException>("accepted $name") { storage.resolve(name) }
        }
    }

    @Test
    fun `an absolute path is refused`() {
        assertFailsWith<IllegalArgumentException> { storage().resolve("/etc/passwd") }
    }

    @Test
    fun `a symlinked download is refused rather than followed`() {
        val storage = storage()
        val outside = File(tempDir, "secret.txt").apply { writeText("private") }
        val link = storage.root.toPath().resolve("9.mp3")
        val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }
        assumeTrue(created.isSuccess, "this filesystem does not allow symlinks")

        // Following it would let a planted link have this app truncate or delete an arbitrary file.
        assertFailsWith<IllegalArgumentException> { storage.resolve("9.mp3") }
    }

    @Test
    fun `a symlinked storage root is refused before any file can be written through it`() {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val marker = File(outside, "keep.txt").apply { writeText("keep") }
        val link = File(tempDir, "linked-downloads")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        assumeTrue(created.isSuccess, "this filesystem does not allow symlinks")

        val storage = DownloadStorage(link)
        assertFalse(storage.prepare())
        storage.deleteAll()
        assertEquals("keep", marker.readText(), "delete-all followed the refused storage root")
    }

    @Test
    fun `resolving a normal name gives a path inside the root`() {
        val storage = storage()
        val resolved = storage.resolve("512-a1b2.mp3")
        assertEquals(storage.root.toPath().toAbsolutePath().normalize(), resolved.toPath().parent)
    }

    // --- Deletion -------------------------------------------------------------------------------

    @Test
    fun `deleting a download removes its part file too`() {
        val storage = storage()
        storage.resolve("1.mp3").writeBytes(ByteArray(16))
        File(storage.root, "1.mp3.part").writeBytes(ByteArray(8))

        storage.delete("1.mp3")

        assertFalse(File(storage.root, "1.mp3").exists())
        assertFalse(File(storage.root, "1.mp3.part").exists())
    }

    @Test
    fun `deleting something that is not there is not an error`() {
        storage().delete("404.mp3")
    }

    @Test
    fun `delete all clears the root and reports what it reclaimed`() {
        val storage = storage()
        storage.resolve("1.mp3").writeBytes(ByteArray(1000))
        storage.resolve("2.mp3").writeBytes(ByteArray(2000))

        val freed = storage.deleteAll()

        assertEquals(3000L, freed)
        assertFalse(storage.root.exists())
    }

    @Test
    fun `delete all removes a planted symlink without touching its target`() {
        val storage = storage()
        val outside = File(tempDir, "keep-me.txt").apply { writeText("important") }
        val created = runCatching {
            Files.createSymbolicLink(storage.root.toPath().resolve("evil.mp3"), outside.toPath())
        }
        assumeTrue(created.isSuccess, "this filesystem does not allow symlinks")

        storage.deleteAll()

        assertTrue(outside.isFile, "delete-all followed a symlink out of its root")
        assertEquals("important", outside.readText())
    }

    // --- Totals and space -------------------------------------------------------------------------

    @Test
    fun `the measured total is what is actually on disk`() {
        val storage = storage()
        storage.resolve("1.mp3").writeBytes(ByteArray(1500))
        storage.resolve("2.mp3").writeBytes(ByteArray(2500))

        assertEquals(4000L, storage.bytesOnDisk())
    }

    @Test
    fun `an impossible size is refused and a plausible one is allowed`() {
        val storage = storage()
        assertFalse(storage.hasRoomFor(Long.MAX_VALUE / 2), "a size no disk has must be refused")
        assertTrue(storage.hasRoomFor(1024), "a small chapter should fit on a working temp disk")
    }

    @Test
    fun `space is checked even before the directory exists`() {
        // A fresh install asks before it has created anything; refusing every download then would
        // make the feature look broken on first use.
        val fresh = DownloadStorage(File(tempDir, "not/created/yet"))
        assertTrue(fresh.hasRoomFor(1024))
    }
}
