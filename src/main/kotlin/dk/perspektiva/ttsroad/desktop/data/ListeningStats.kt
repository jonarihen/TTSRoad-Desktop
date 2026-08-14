package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One account's listening on one calendar day.
 *
 * A day rather than a session, because every question worth answering here — hours, chapters, a
 * streak — is a question about days, and storing sessions would mean storing *when* somebody
 * listened at minute resolution for years. A day is the coarsest unit that still answers all three.
 *
 * [ownerKey] is the same hashed server-plus-account key `PlaybackHistory` uses. Two accounts on one
 * machine keep separate totals: "you have listened for 300 hours" is a claim about a person, and
 * pooling it across accounts would be wrong in the direction that flatters.
 */
data class ListeningDay(
    val ownerKey: String = "",
    /** ISO-8601 local date, `2026-08-14`. Local, because a listener's "day" is their own. */
    val date: String = "",
    val seconds: Double = 0.0,
    val chaptersFinished: Int = 0,
) {
    val key: String get() = "$ownerKey@$date"
}

/** What the Listening pane shows, derived rather than stored — see [ListeningStats.summarise]. */
data class ListeningSummary(
    val seconds: Double = 0.0,
    val chaptersFinished: Int = 0,
    val daysListened: Int = 0,
    /** Consecutive days ending today or yesterday; 0 once a day has been missed. */
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val bestDaySeconds: Double = 0.0,
    val last7DaysSeconds: Double = 0.0,
    val last30DaysSeconds: Double = 0.0,
) {
    val hasAnything: Boolean get() = seconds > 0.0 || chaptersFinished > 0
}

object ListeningStats {
    /**
     * How many day rows are kept.
     *
     * Two years of daily listening, and a hard bound on a file that is otherwise appended to for
     * the life of the install. Trimmed oldest-first, which is also the order in which a total stops
     * being interesting.
     */
    const val MaxDays: Int = 730

    /** The local date [millis] falls on, in [zone]. */
    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /**
     * Adds listening to one account's day, creating the row if this is the first of it.
     *
     * Negative or non-finite input is dropped rather than clamped to zero: it can only come from a
     * clock that moved, and a wrong number is worse here than a missing one — these totals are only
     * ever read by the person they are about, who will notice an afternoon that never happened.
     */
    fun record(
        days: List<ListeningDay>,
        ownerKey: String,
        date: String,
        seconds: Double = 0.0,
        chaptersFinished: Int = 0,
    ): List<ListeningDay> {
        if (ownerKey.isBlank() || date.isBlank()) return days
        val added = seconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val finished = chaptersFinished.coerceAtLeast(0)
        if (added == 0.0 && finished == 0) return days

        val key = "$ownerKey@$date"
        val existing = days.firstOrNull { it.key == key }
        val updated = (existing ?: ListeningDay(ownerKey = ownerKey, date = date)).let {
            it.copy(seconds = it.seconds + added, chaptersFinished = it.chaptersFinished + finished)
        }
        // Sorted by date so the bound trims the oldest, and so streaks read a sequence rather than
        // whatever order the file happened to be written in.
        return (days.filterNot { it.key == key } + updated)
            .sortedBy { it.date }
            .let { if (it.size <= MaxDays) it else it.takeLast(MaxDays) }
    }

    /**
     * Totals for one account.
     *
     * `today` is passed in rather than read, because a streak is the one number here that changes
     * without anyone listening: it has to become 0 the day after it lapses, and a test that cannot
     * choose "today" cannot assert that at all.
     */
    fun summarise(days: List<ListeningDay>, ownerKey: String, today: LocalDate): ListeningSummary {
        val mine = days.filter { it.ownerKey == ownerKey }
        if (mine.isEmpty()) return ListeningSummary()

        val dates = mine.mapNotNull { day ->
            runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
        }.sortedBy { it.first }

        val listened = dates.filter { it.second.seconds > 0.0 }.map { it.first }.toSet()
        return ListeningSummary(
            seconds = mine.sumOf { it.seconds },
            chaptersFinished = mine.sumOf { it.chaptersFinished },
            daysListened = listened.size,
            currentStreakDays = currentStreak(listened, today),
            longestStreakDays = longestStreak(listened),
            bestDaySeconds = mine.maxOf { it.seconds },
            last7DaysSeconds = sumSince(dates, today.minusDays(6)),
            last30DaysSeconds = sumSince(dates, today.minusDays(29)),
        )
    }

    private fun sumSince(dates: List<Pair<LocalDate, ListeningDay>>, from: LocalDate): Double =
        dates.filter { !it.first.isBefore(from) }.sumOf { it.second.seconds }

    /**
     * Today counts, and so does yesterday.
     *
     * Counting only from today would report a broken streak every morning until the first chapter
     * of the day, which is both wrong and demoralising in a feature whose entire purpose is
     * encouragement.
     */
    private fun currentStreak(listened: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in listened -> today
            today.minusDays(1) in listened -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (cursor in listened) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun longestStreak(listened: Set<LocalDate>): Int {
        var longest = 0
        listened.forEach { day ->
            // Only count from the *start* of a run, so an n-day run is walked once rather than n
            // times — the difference between linear and quadratic over two years of rows.
            if (day.minusDays(1) in listened) return@forEach
            var streak = 0
            var cursor = day
            while (cursor in listened) {
                streak++
                cursor = cursor.plusDays(1)
            }
            longest = maxOf(longest, streak)
        }
        return longest
    }
}

/** Seam so tests never touch the real user config directory. */
interface ListeningStatsStore {
    val days: StateFlow<List<ListeningDay>>

    fun record(ownerKey: String, date: String, seconds: Double = 0.0, chaptersFinished: Int = 0)

    fun clear()
}

class InMemoryListeningStatsStore(
    initial: List<ListeningDay> = emptyList(),
) : ListeningStatsStore {
    private val _days = MutableStateFlow(initial)
    override val days: StateFlow<List<ListeningDay>> = _days.asStateFlow()

    @Synchronized
    override fun record(ownerKey: String, date: String, seconds: Double, chaptersFinished: Int) {
        _days.value = ListeningStats.record(_days.value, ownerKey, date, seconds, chaptersFinished)
    }

    @Synchronized
    override fun clear() {
        _days.value = emptyList()
    }
}

/**
 * `listening.json` beside the other non-secret local files, written owner-only.
 *
 * Machine-local for the same reason the history is: these are totals about listening *done here*,
 * they must survive a sign-out, and there is no server contract for them. The account key inside
 * each row is what keeps two accounts on one machine apart; it is the same hashed key the history
 * uses, so nothing here names a server or a person either.
 */
class FileListeningStatsStore(
    private val file: File = defaultFile(),
) : ListeningStatsStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<ListeningDay>>(
            Types.newParameterizedType(List::class.java, ListeningDay::class.java),
        )

    private val _days = MutableStateFlow(read())
    override val days: StateFlow<List<ListeningDay>> = _days.asStateFlow()

    @Synchronized
    override fun record(ownerKey: String, date: String, seconds: Double, chaptersFinished: Int) {
        val next = ListeningStats.record(_days.value, ownerKey, date, seconds, chaptersFinished)
        if (next == _days.value) return
        _days.value = next
        write(next)
    }

    @Synchronized
    override fun clear() {
        _days.value = emptyList()
        write(emptyList())
    }

    private fun write(days: List<ListeningDay>) {
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(days)) }
            .onFailure { AppLog.warn("could not write the listening statistics file", it) }
    }

    private fun read(): List<ListeningDay> =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the listening statistics file", it) }
            .getOrNull()
            ?.filter { it.ownerKey.isNotBlank() && it.date.isNotBlank() }
            ?.sortedBy { it.date }
            ?.takeLast(ListeningStats.MaxDays)
            ?: emptyList()

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("listening.json")
    }
}
