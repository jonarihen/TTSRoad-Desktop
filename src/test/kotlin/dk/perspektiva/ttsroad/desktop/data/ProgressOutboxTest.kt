package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressOutboxTest {

    private fun entry(chapterId: Int, position: Double, stamp: String = "2026-08-11T10:00:00.000Z") =
        PendingProgress(
            fictionId = 1,
            chapterId = chapterId,
            positionSeconds = position,
            isPlayed = false,
            clientUpdatedAt = stamp,
        )

    @Test
    fun `a second position for the same chapter replaces the first`() {
        val queued = ProgressOutbox.record(
            listOf(entry(7, 120.0, "2026-08-11T10:00:00.000Z")),
            entry(7, 340.0, "2026-08-11T10:05:00.000Z"),
        )

        assertEquals(1, queued.size)
        assertEquals(340.0, queued.single().positionSeconds)
        assertEquals("2026-08-11T10:05:00.000Z", queued.single().clientUpdatedAt)
    }

    @Test
    fun `replacing keeps the chapter where it was in the queue`() {
        val queued = ProgressOutbox.record(
            listOf(entry(7, 10.0), entry(8, 20.0), entry(9, 30.0)),
            entry(8, 999.0),
        )

        assertEquals(listOf(7, 8, 9), queued.map { it.chapterId })
    }

    @Test
    fun `different chapters queue independently`() {
        val queued = ProgressOutbox.record(listOf(entry(7, 120.0)), entry(8, 60.0))

        assertEquals(listOf(7, 8), queued.map { it.chapterId })
    }

    /**
     * A rejection is as final as an acceptance — every reason `/playback/sync` can give is terminal
     * for that item, so both sets are dropped together and neither is retried.
     */
    @Test
    fun `dropping acknowledged chapters leaves the rest queued`() {
        val queued = ProgressOutbox.drop(
            listOf(entry(7, 1.0), entry(8, 2.0), entry(9, 3.0)),
            listOf(7, 9),
        )

        assertEquals(listOf(8), queued.map { it.chapterId })
    }

    @Test
    fun `dropping nothing changes nothing`() {
        val before = listOf(entry(7, 1.0))

        assertEquals(before, ProgressOutbox.drop(before, emptyList()))
    }

    @Test
    fun `batches respect the server limit`() {
        val entries = (1..5).map { entry(it, it.toDouble()) }

        val batches = ProgressOutbox.batches(entries, maxItems = 2)

        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(entries, batches.flatten())
    }

    @Test
    fun `an empty queue produces no batches`() {
        assertTrue(ProgressOutbox.batches(emptyList(), maxItems = 500).isEmpty())
    }

    /** A zero limit would otherwise mean infinitely many empty batches. */
    @Test
    fun `a nonsensical limit still makes progress`() {
        val batches = ProgressOutbox.batches(listOf(entry(1, 1.0), entry(2, 2.0)), maxItems = 0)

        assertEquals(listOf(1, 1), batches.map { it.size })
    }

    /**
     * The backend hands `client_updated_at` to `datetime.fromisoformat`, which before Python 3.11
     * accepts only three or six fractional digits, and its stated floor is 3.10. A stamp it cannot
     * parse is rejected as `invalid_client_updated_at` and the write is dropped — the exact data
     * loss this path exists to prevent. Repeated because the digit count varies with the clock, so
     * a single sample can pass by luck.
     */
    @Test
    fun `now stamp is parseable by the backend`() {
        val pattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$""")

        repeat(200) {
            val stamp = nowStamp()
            assertTrue(pattern.matches(stamp), "unparseable stamp: $stamp")
        }
    }
}

class FileProgressOutboxStoreTest {
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

    private fun entry(chapterId: Int, position: Double) = PendingProgress(
        fictionId = 1,
        chapterId = chapterId,
        positionSeconds = position,
        isPlayed = false,
        clientUpdatedAt = "2026-08-11T10:00:00.000Z",
    )

    /** The whole reason the queue is on disk: the app closing is the case that loses data. */
    @Test
    fun `a queued position survives a restart`() {
        FileProgressOutboxStore(file).record(entry(7, 120.0))

        val reopened = FileProgressOutboxStore(file).entries.value

        assertEquals(1, reopened.size)
        assertEquals(7, reopened.single().chapterId)
        assertEquals(120.0, reopened.single().positionSeconds)
        assertEquals("2026-08-11T10:00:00.000Z", reopened.single().clientUpdatedAt)
    }

    @Test
    fun `a drop is persisted, not just applied in memory`() {
        val store = FileProgressOutboxStore(file)
        store.record(entry(7, 120.0))
        store.record(entry(8, 60.0))

        store.drop(listOf(7))

        assertEquals(listOf(8), FileProgressOutboxStore(file).entries.value.map { it.chapterId })
    }

    @Test
    fun `clear empties the file too`() {
        val store = FileProgressOutboxStore(file)
        store.record(entry(7, 120.0))

        store.clear()

        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
    }

    /**
     * Refusing to construct the repository — and so refusing to play anything — because of one
     * malformed file would be worse than losing the few positions it held.
     */
    @Test
    fun `an unreadable file starts empty rather than throwing`() {
        file.parentFile.mkdirs()
        file.writeText("{ this is not the json you are looking for")

        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
    }

    @Test
    fun `a file from a future version is discarded rather than half-read`() {
        file.parentFile.mkdirs()
        file.writeText("""{"version":99,"entries":[{"fictionId":1,"chapterId":7,"positionSeconds":5.0,"isPlayed":false,"clientUpdatedAt":"2026-08-11T10:00:00.000Z"}]}""")

        assertTrue(FileProgressOutboxStore(file).entries.value.isEmpty())
    }
}
