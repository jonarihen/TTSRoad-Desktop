package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One recorded listening position, waiting to reach the server.
 *
 * [clientUpdatedAt] is the whole point. `/playback/progress` writes whatever it is handed, so a
 * position recorded here while offline overwrites a newer one reached in the browser since.
 * `/playback/sync` orders writes by this stamp and only lets a strictly newer one win.
 */
data class PendingProgress(
    val fictionId: Int,
    val chapterId: Int,
    val positionSeconds: Double,
    val isPlayed: Boolean,
    val clientUpdatedAt: String,
)

private data class StoredOutbox(
    val version: Int = ProgressOutbox.CurrentVersion,
    val entries: List<PendingProgress> = emptyList(),
)

/**
 * The pure rules for the unsent-progress queue. No file, no clock, no coroutine.
 *
 * Split from [FileProgressOutboxStore] for the same reason [dk.perspektiva.ttsroad.desktop
 * .download.DownloadIndex] is: the transitions that matter — a second position for a chapter
 * already queued, a partial acknowledgement, a rejection that must not be retried — are asserted
 * in plain unit tests rather than against a temp directory.
 */
object ProgressOutbox {

    const val CurrentVersion: Int = 1

    /**
     * Queue a position, replacing any earlier one for the same chapter.
     *
     * At most one entry per chapter because only the furthest position matters and the server
     * compares by timestamp anyway — an older entry for a chapter could never win once a newer one
     * exists. That also bounds the file by chapters actually listened to rather than by how long
     * the session ran.
     *
     * Insertion order is preserved for chapters already present, so a long-running session does not
     * keep shuffling the file and rewriting bytes that did not change.
     */
    fun record(entries: List<PendingProgress>, entry: PendingProgress): List<PendingProgress> {
        val index = entries.indexOfFirst { it.chapterId == entry.chapterId }
        if (index < 0) return entries + entry
        return entries.toMutableList().apply { this[index] = entry }
    }

    /**
     * Drop everything the server has now spoken about — accepted *and* rejected.
     *
     * A rejection is as final as an acceptance: every reason the endpoint can give is terminal for
     * that item, so keeping it queued would mean re-sending a payload guaranteed to be refused
     * again. Only a transport failure leaves the queue untouched, and that is expressed by not
     * calling this at all.
     */
    fun drop(entries: List<PendingProgress>, chapterIds: Collection<Int>): List<PendingProgress> {
        if (chapterIds.isEmpty()) return entries
        val gone = chapterIds.toSet()
        return entries.filterNot { it.chapterId in gone }
    }

    /**
     * Split into batches the server will accept.
     *
     * [maxItems] is the server's published `max_playback_sync_items`. It is not advisory: an
     * oversized batch is answered with a 400 rather than truncated, so ignoring it loses the whole
     * batch instead of part of it. A non-positive limit would mean infinitely many empty batches,
     * so it is floored at one.
     */
    fun batches(entries: List<PendingProgress>, maxItems: Int): List<List<PendingProgress>> =
        if (entries.isEmpty()) emptyList() else entries.chunked(maxItems.coerceAtLeast(1))
}

/** Storage for positions that have not reached the server yet. */
interface ProgressOutboxStore {
    val entries: StateFlow<List<PendingProgress>>
    fun record(entry: PendingProgress)
    fun drop(chapterIds: Collection<Int>)
    fun clear()
}

/**
 * The outbox on disk.
 *
 * On disk rather than in memory because the case that loses data is precisely the one where the app
 * closes before the network comes back — an in-memory queue would drop exactly the writes that most
 * need keeping.
 *
 * Written through [SecureFiles.writeAtomically] so a crash mid-write cannot leave a truncated file
 * where a listening position used to be, and so the file carries owner-only permissions like the
 * rest of the per-user state.
 */
class FileProgressOutboxStore(private val file: File) : ProgressOutboxStore {

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StoredOutbox::class.java)

    private val _entries = MutableStateFlow(load())
    override val entries: StateFlow<List<PendingProgress>> = _entries.asStateFlow()

    /**
     * Synchronised because every mutation is read-modify-write and the writers genuinely differ:
     * the playback controller's ten-second tick, its save-on-pause/seek/stop paths, and the flush
     * acknowledging a batch. Two of those working from the same base list would lose one edit.
     */
    @Synchronized
    override fun record(entry: PendingProgress) {
        write(ProgressOutbox.record(_entries.value, entry))
    }

    @Synchronized
    override fun drop(chapterIds: Collection<Int>) {
        write(ProgressOutbox.drop(_entries.value, chapterIds))
    }

    /** Used when the credential dies: a queue that cannot be authenticated can never be flushed. */
    @Synchronized
    override fun clear() {
        write(emptyList())
    }

    private fun write(next: List<PendingProgress>) {
        if (next == _entries.value) return
        _entries.value = next
        file.parentFile?.mkdirs()
        SecureFiles.writeAtomically(file, adapter.toJson(StoredOutbox(entries = next)))
    }

    /**
     * A file this build cannot read starts empty rather than throwing.
     *
     * Losing a few queued positions is bad; failing to construct the repository — and so refusing
     * to play anything at all — because of one malformed JSON file is worse.
     */
    private fun load(): List<PendingProgress> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val stored = adapter.fromJson(file.readText()) ?: return@runCatching emptyList()
        if (stored.version != ProgressOutbox.CurrentVersion) emptyList() else stored.entries
    }.getOrElse { emptyList() }
}
