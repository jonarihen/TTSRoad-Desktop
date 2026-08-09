package dk.perspektiva.ttsroad.desktop.download

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The download index's rules.
 *
 * Targets the acceptance criteria about interrupted downloads never appearing as complete, a stale
 * index recovering safely, selection of the next ten, and cleanup that cannot escape its root.
 */
class DownloadIndexTest {

    private fun entry(
        chapterId: Int,
        state: DownloadState,
        bytes: Long = 0L,
        total: Long = 0L,
        updatedAtMs: Long = chapterId.toLong(),
        fileName: String = "$chapterId.mp3",
    ) = DownloadEntry(
        chapterId = chapterId,
        fictionId = 7,
        fictionTitle = "A Serial",
        chapterTitle = "Chapter $chapterId",
        state = state,
        bytesDownloaded = bytes,
        totalBytes = total,
        fileName = fileName,
        updatedAtMs = updatedAtMs,
    )

    // --- Restart recovery -----------------------------------------------------------------------

    @Test
    fun `an interrupted download is requeued, not left showing a dead progress bar`() {
        // The process died mid-transfer. There is no worker now, so a Downloading row would render
        // a bar that never moves.
        val recovered = DownloadIndex.recoverAfterRestart(listOf(entry(1, DownloadState.Downloading, bytes = 500)))

        assertEquals(DownloadState.Queued, recovered.single().state)
        // The bytes already fetched are kept so the transfer resumes rather than restarting.
        assertEquals(500L, recovered.single().bytesDownloaded)
    }

    @Test
    fun `an interrupted delete is dropped`() {
        val recovered = DownloadIndex.recoverAfterRestart(listOf(entry(1, DownloadState.Removing)))
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `completed and failed rows survive a restart unchanged`() {
        val before = listOf(
            entry(1, DownloadState.Downloaded, bytes = 900),
            entry(2, DownloadState.Failed),
            entry(3, DownloadState.Queued),
        )
        assertEquals(before, DownloadIndex.recoverAfterRestart(before))
    }

    // --- Reconciliation with the filesystem -----------------------------------------------------

    @Test
    fun `a downloaded row whose file is gone is dropped`() {
        // A cache cleaner, a manual delete, a dying disk. The index is not the source of truth.
        val reconciled = DownloadIndex.reconcile(
            listOf(entry(1, DownloadState.Downloaded, bytes = 900)),
            fileExists = { false },
        )
        assertTrue(reconciled.isEmpty(), "a missing file must not stay marked offline")
    }

    @Test
    fun `a truncated file does not count as downloaded`() {
        val reconciled = DownloadIndex.reconcile(
            listOf(entry(1, DownloadState.Downloaded, bytes = 900)),
            fileExists = { true },
            fileLength = { 100 },
        )
        assertTrue(reconciled.isEmpty(), "a short file must not be served as a complete chapter")
    }

    @Test
    fun `a complete file is kept`() {
        val reconciled = DownloadIndex.reconcile(
            listOf(entry(1, DownloadState.Downloaded, bytes = 900)),
            fileExists = { true },
            fileLength = { 900 },
        )
        assertEquals(1, reconciled.size)
    }

    @Test
    fun `rows that never claimed a file are left alone`() {
        // A queued row has no file yet, so a missing one proves nothing.
        val reconciled = DownloadIndex.reconcile(
            listOf(entry(1, DownloadState.Queued), entry(2, DownloadState.Failed)),
            fileExists = { false },
        )
        assertEquals(2, reconciled.size)
    }

    // --- Basic index operations -----------------------------------------------------------------

    @Test
    fun `put replaces rather than duplicates`() {
        val first = DownloadIndex.put(emptyList(), entry(1, DownloadState.Queued))
        val second = DownloadIndex.put(first, entry(1, DownloadState.Downloaded, bytes = 10))

        assertEquals(1, second.size)
        assertEquals(DownloadState.Downloaded, second.single().state)
    }

    @Test
    fun `pending drains in the order the queue was filled`() {
        val entries = listOf(
            entry(3, DownloadState.Queued, updatedAtMs = 300),
            entry(1, DownloadState.Queued, updatedAtMs = 100),
            entry(2, DownloadState.Downloading, updatedAtMs = 200),
            entry(4, DownloadState.Downloaded, updatedAtMs = 50),
        )
        assertEquals(listOf(1, 2, 3), DownloadIndex.pending(entries).map { it.chapterId })
    }

    @Test
    fun `only completed downloads count toward the total on disk`() {
        val entries = listOf(
            entry(1, DownloadState.Downloaded, bytes = 1_000),
            entry(2, DownloadState.Downloading, bytes = 500),
            entry(3, DownloadState.Downloaded, bytes = 2_000),
        )
        assertEquals(3_000L, DownloadIndex.bytesOnDisk(entries))
    }

    @Test
    fun `find returns null for an unknown chapter`() {
        assertNull(DownloadIndex.find(listOf(entry(1, DownloadState.Downloaded)), 99))
    }

    // --- "Download next 10" ---------------------------------------------------------------------

    @Test
    fun `the next ten start at the resume position, not at the beginning`() {
        val order = (1..30).toList()
        val next = DownloadIndex.nextToDownload(order, startChapterId = 5, entries = emptyList())

        assertEquals((5..14).toList(), next)
    }

    @Test
    fun `chapters already downloaded or queued are skipped, so the batch is ten new ones`() {
        val order = (1..30).toList()
        val entries = listOf(
            entry(5, DownloadState.Downloaded),
            entry(6, DownloadState.Queued),
            entry(7, DownloadState.Downloading),
        )
        val next = DownloadIndex.nextToDownload(order, startChapterId = 5, entries = entries)

        assertEquals(10, next.size)
        assertFalse(next.any { it in setOf(5, 6, 7) })
        assertEquals((8..17).toList(), next)
    }

    @Test
    fun `a failed chapter is offered again`() {
        // Failure is exactly the case the user wants swept up by the next batch.
        val next = DownloadIndex.nextToDownload(
            order = (1..30).toList(),
            startChapterId = 1,
            entries = listOf(entry(1, DownloadState.Failed)),
        )
        assertTrue(1 in next)
    }

    @Test
    fun `no resume position starts from the beginning`() {
        assertEquals((1..10).toList(), DownloadIndex.nextToDownload((1..30).toList(), null, emptyList()))
    }

    @Test
    fun `an unknown resume chapter falls back to the beginning rather than returning nothing`() {
        assertEquals((1..10).toList(), DownloadIndex.nextToDownload((1..30).toList(), 999, emptyList()))
    }

    @Test
    fun `near the end of a serial the batch is short rather than wrapping`() {
        val next = DownloadIndex.nextToDownload((1..12).toList(), startChapterId = 10, entries = emptyList())
        assertEquals(listOf(10, 11, 12), next)
    }

    @Test
    fun `an empty serial asks for nothing`() {
        assertEquals(emptyList(), DownloadIndex.nextToDownload(emptyList(), 1, emptyList()))
    }

    // --- Path safety in the index ---------------------------------------------------------------

    @Test
    fun `a traversal in a stored file name is rejected`() {
        // The index is a plain file. A hostile or corrupt name would otherwise be resolved against
        // the download root and deleted "as a download".
        val hostile = listOf(
            "../../../.ssh/id_rsa",
            "..",
            "sub/dir.mp3",
            "back\\slash.mp3",
            ".hidden",
            "",
            "  ",
            "with space.mp3",
        )
        for (name in hostile) {
            assertFalse(FileDownloadIndexStore.isSafeName(name), "accepted hostile name: $name")
        }
    }

    @Test
    fun `the names this app generates are accepted`() {
        assertTrue(FileDownloadIndexStore.isSafeName("512.mp3"))
        assertTrue(FileDownloadIndexStore.isSafeName("512-a1b2c3d4.mp3"))
        assertTrue(FileDownloadIndexStore.isSafeName("512.mp3.part"))
    }
}
