package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressOutboxTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ttsroad-outbox").toFile()
        file = dir.resolve("progress-outbox.json")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(chapterId: Int, position: Double, stamp: String) = PendingProgress(
        fictionId = 1,
        chapterId = chapterId,
        positionSeconds = position,
        isPlayed = false,
        clientUpdatedAt = stamp,
    )

    @Test
    fun `survives a restart`() {
        ProgressOutbox(file).record(entry(7, 120.0, "2026-08-11T10:00:00.000Z"))

        val reopened = ProgressOutbox(file).snapshot()

        assertEquals(1, reopened.size)
        assertEquals(7, reopened.first().chapterId)
        assertEquals(120.0, reopened.first().positionSeconds)
        assertEquals("2026-08-11T10:00:00.000Z", reopened.first().clientUpdatedAt)
    }

    @Test
    fun `keeps only the latest entry per chapter`() {
        val outbox = ProgressOutbox(file)
        outbox.record(entry(7, 120.0, "2026-08-11T10:00:00.000Z"))
        outbox.record(entry(7, 340.0, "2026-08-11T10:05:00.000Z"))

        val snapshot = outbox.snapshot()

        assertEquals(1, snapshot.size)
        assertEquals(340.0, snapshot.first().positionSeconds)
        assertEquals("2026-08-11T10:05:00.000Z", snapshot.first().clientUpdatedAt)
    }

    @Test
    fun `tracks chapters independently`() {
        val outbox = ProgressOutbox(file)
        outbox.record(entry(7, 120.0, "2026-08-11T10:00:00.000Z"))
        outbox.record(entry(8, 60.0, "2026-08-11T10:01:00.000Z"))

        assertEquals(setOf(7, 8), outbox.snapshot().map { it.chapterId }.toSet())
    }

    @Test
    fun `dropping an acknowledged chapter leaves the rest queued`() {
        val outbox = ProgressOutbox(file)
        outbox.record(entry(7, 120.0, "2026-08-11T10:00:00.000Z"))
        outbox.record(entry(8, 60.0, "2026-08-11T10:01:00.000Z"))

        outbox.drop(listOf(7))

        assertEquals(listOf(8), outbox.snapshot().map { it.chapterId })
        assertEquals(listOf(8), ProgressOutbox(file).snapshot().map { it.chapterId })
    }

    @Test
    fun `clear empties the queue on disk too`() {
        val outbox = ProgressOutbox(file)
        outbox.record(entry(7, 120.0, "2026-08-11T10:00:00.000Z"))

        outbox.clear()

        assertTrue(outbox.snapshot().isEmpty())
        assertTrue(ProgressOutbox(file).snapshot().isEmpty())
    }

    @Test
    fun `an unreadable file starts empty rather than throwing`() {
        file.parentFile.mkdirs()
        file.writeText("{ this is not the json you are looking for")

        assertTrue(ProgressOutbox(file).snapshot().isEmpty())
    }

    /**
     * The backend hands `client_updated_at` to `datetime.fromisoformat`, which before Python 3.11
     * accepts only three or six fractional digits. `Instant.now().toString()` can emit nine, and a
     * stamp it cannot parse comes back as `invalid_client_updated_at` — the write is dropped, which
     * is the exact data loss this whole path exists to prevent.
     */
    @Test
    fun `now stamp is parseable by the backend`() {
        val pattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$""")

        repeat(50) {
            val stamp = nowStamp()
            assertTrue(pattern.matches(stamp), "unparseable stamp: $stamp")
        }
    }
}
