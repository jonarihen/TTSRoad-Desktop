package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Listening totals, which are read by exactly one person: the one they are about. Every rule here
 * exists because a wrong number would be noticed and a missing one would not.
 */
class ListeningStatsTest {

    private val alice = PlaybackHistory.ownerKeyFor("https://ttsroad.example", "alice")
    private val bob = PlaybackHistory.ownerKeyFor("https://ttsroad.example", "bob")
    private val today = LocalDate.parse("2026-08-14")

    private fun days(vararg rows: Pair<String, Double>, ownerKey: String = alice) =
        rows.map { (date, seconds) -> ListeningDay(ownerKey, date, seconds) }

    // --- Recording --------------------------------------------------------------------------------

    @Test
    fun `listening on the same day accumulates instead of stacking rows`() {
        var rows = ListeningStats.record(emptyList(), alice, "2026-08-14", seconds = 600.0)
        rows = ListeningStats.record(rows, alice, "2026-08-14", seconds = 300.0, chaptersFinished = 1)

        val day = rows.single()
        assertEquals(900.0, day.seconds)
        assertEquals(1, day.chaptersFinished)
    }

    @Test
    fun `two accounts on one machine keep separate totals`() {
        // "You have listened for 300 hours" is a claim about a person; pooling it would be wrong in
        // the direction that flatters.
        var rows = ListeningStats.record(emptyList(), alice, "2026-08-14", seconds = 600.0)
        rows = ListeningStats.record(rows, bob, "2026-08-14", seconds = 60.0)

        assertEquals(600.0, ListeningStats.summarise(rows, alice, today).seconds)
        assertEquals(60.0, ListeningStats.summarise(rows, bob, today).seconds)
    }

    @Test
    fun `a nonsense duration is dropped rather than banked`() {
        // It can only come from a clock that moved, and an afternoon that never happened is worse
        // than a minute that goes unrecorded.
        val rows = ListeningStats.record(emptyList(), alice, "2026-08-14", seconds = -400.0)
        assertTrue(rows.isEmpty())
        assertTrue(ListeningStats.record(rows, alice, "2026-08-14", seconds = Double.NaN).isEmpty())
        assertTrue(ListeningStats.record(rows, "", "2026-08-14", seconds = 60.0).isEmpty())
    }

    @Test
    fun `the day list is bounded, oldest first`() {
        val start = LocalDate.parse("2020-01-01")
        val rows = (0 until ListeningStats.MaxDays + 40).fold(emptyList<ListeningDay>()) { acc, offset ->
            ListeningStats.record(acc, alice, start.plusDays(offset.toLong()).toString(), seconds = 60.0)
        }

        assertEquals(ListeningStats.MaxDays, rows.size)
        assertEquals(start.plusDays(40).toString(), rows.first().date)
    }

    // --- Summaries --------------------------------------------------------------------------------

    @Test
    fun `nothing recorded is an empty summary rather than a pile of zeroes to render`() {
        assertFalse(ListeningStats.summarise(emptyList(), alice, today).hasAnything)
        assertFalse(ListeningStats.summarise(days("2026-08-14" to 0.0), alice, today).hasAnything)
    }

    @Test
    fun `a streak counts back from today`() {
        val rows = days("2026-08-12" to 600.0, "2026-08-13" to 600.0, "2026-08-14" to 600.0)

        assertEquals(3, ListeningStats.summarise(rows, alice, today).currentStreakDays)
    }

    @Test
    fun `yesterday keeps a streak alive, because mornings exist`() {
        // Counting only from today would report a broken streak every morning until the first
        // chapter of the day — wrong, and demoralising in a feature meant to encourage.
        val rows = days("2026-08-12" to 600.0, "2026-08-13" to 600.0)

        assertEquals(2, ListeningStats.summarise(rows, alice, today).currentStreakDays)
    }

    @Test
    fun `a whole day missed ends the streak but not the record`() {
        val rows = days("2026-08-01" to 600.0, "2026-08-02" to 600.0, "2026-08-03" to 600.0, "2026-08-12" to 600.0)

        val summary = ListeningStats.summarise(rows, alice, today)

        assertEquals(0, summary.currentStreakDays, "the 12th is two days ago")
        assertEquals(3, summary.longestStreakDays)
    }

    @Test
    fun `the windows are inclusive of today and of the seventh day back`() {
        val rows = days(
            "2026-08-08" to 100.0, // seven days back, inclusive
            "2026-08-07" to 999.0, // eight days back, outside the week
            "2026-08-14" to 200.0,
        )

        val summary = ListeningStats.summarise(rows, alice, today)

        assertEquals(300.0, summary.last7DaysSeconds)
        assertEquals(1299.0, summary.last30DaysSeconds)
        assertEquals(999.0, summary.bestDaySeconds)
        assertEquals(3, summary.daysListened)
    }

    @Test
    fun `a day with only a finished chapter does not count as a day of listening`() {
        // A chapter can finish on its last second; the streak is about time spent, and a row with
        // no seconds in it is not an evening.
        val rows = listOf(ListeningDay(alice, "2026-08-14", seconds = 0.0, chaptersFinished = 1))

        val summary = ListeningStats.summarise(rows, alice, today)

        assertEquals(0, summary.daysListened)
        assertEquals(0, summary.currentStreakDays)
        assertTrue(summary.hasAnything)
    }

    @Test
    fun `an unparseable date cannot take the whole summary down`() {
        val rows = days("2026-08-14" to 600.0) + ListeningDay(alice, "not-a-date", 60.0)

        val summary = ListeningStats.summarise(rows, alice, today)

        assertEquals(660.0, summary.seconds, "the total still sums every row")
        assertEquals(1, summary.daysListened, "but only a real date can be part of a streak")
    }

    // --- Persistence ------------------------------------------------------------------------------

    @Test
    fun `days round-trip through the file store`(@TempDir dir: File) {
        val file = dir.resolve("listening.json")
        FileListeningStatsStore(file).record(alice, "2026-08-14", seconds = 600.0, chaptersFinished = 2)

        val reloaded = FileListeningStatsStore(file).days.value.single()
        assertEquals(600.0, reloaded.seconds)
        assertEquals(2, reloaded.chaptersFinished)
        assertEquals(alice, reloaded.ownerKey)
    }

    @Test
    fun `the file names no server, no account and nothing that was read`(@TempDir dir: File) {
        // The type has nowhere to put a title, a chapter id or a URL, and this pins that the file
        // it produces carries none of them either. `chaptersFinished` is a count, not an id.
        val file = dir.resolve("listening.json")
        FileListeningStatsStore(file).record(alice, "2026-08-14", seconds = 600.0, chaptersFinished = 2)

        val text = file.readText()
        listOf("http", "alice", "ttsroad.example", "token", "title", "fiction").forEach {
            assertFalse(text.contains(it, ignoreCase = true), "listening.json must not carry '$it': $text")
        }
    }

    @Test
    fun `an unreadable or half-written file is no statistics, not a crash`(@TempDir dir: File) {
        val corrupt = dir.resolve("corrupt.json")
        corrupt.writeText("[{ this is not json")
        assertTrue(FileListeningStatsStore(corrupt).days.value.isEmpty())

        // A row with no owner cannot be attributed to anyone, so it is dropped on read.
        val ownerless = dir.resolve("ownerless.json")
        ownerless.writeText("""[{"ownerKey":"","date":"2026-08-14","seconds":60.0,"chaptersFinished":0}]""")
        assertTrue(FileListeningStatsStore(ownerless).days.value.isEmpty())
    }

    @Test
    fun `a day is the listener's own local day`() {
        // 00:30 UTC on the 15th is still the evening of the 14th in New York.
        val millis = java.time.Instant.parse("2026-08-15T00:30:00Z").toEpochMilli()

        assertEquals("2026-08-15", ListeningStats.dateOf(millis, ZoneId.of("UTC")))
        assertEquals("2026-08-14", ListeningStats.dateOf(millis, ZoneId.of("America/New_York")))
    }
}
