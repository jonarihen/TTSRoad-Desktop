package dk.perspektiva.ttsroad.desktop.download

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pure rules for the download index. No file, no clock, no coroutine.
 *
 * Separated from [FileDownloadIndex] so the transitions that matter — a crash leaving a row mid
 * flight, a restart having to resume, a delete racing a write — are asserted in plain unit tests
 * rather than against a temp directory and a scheduler.
 */
object DownloadIndex {

    /**
     * Rows an interrupted run left behind, made honest again at startup.
     *
     * The rule is that **no in-flight state survives a restart as itself**. A [DownloadState
     * .Downloading] row means a worker was writing to a `.part` file when the process died, and
     * there is no worker now; leaving it would show a progress bar that never moves. It becomes
     * [DownloadState.Queued] so the queue picks it up again — the `.part` file is still there and
     * the transfer resumes from where it stopped rather than from zero.
     *
     * [DownloadState.Removing] is the opposite case: a delete was interrupted, so the file may or
     * may not still exist and the row is no longer wanted either way. It is dropped, and the
     * reconciliation against the filesystem below removes whatever is left.
     */
    fun recoverAfterRestart(entries: List<DownloadEntry>): List<DownloadEntry> =
        entries.mapNotNull { entry ->
            when (entry.state) {
                DownloadState.Downloading -> entry.copy(state = DownloadState.Queued)
                DownloadState.Removing -> null
                DownloadState.None -> null
                else -> entry
            }
        }

    /**
     * Drops rows whose bytes are gone, and demotes ones that claim to be complete but are not.
     *
     * The index and the filesystem are two sources of truth and only one of them is real. A user
     * who deletes files by hand, a cache cleaner, a failed disk — any of these leaves the index
     * claiming an offline chapter that will not play. Checking at startup costs one `exists` per
     * row and turns "playback fails mysteriously" into "the row says Download again".
     *
     * [fileExists] and [fileLength] are parameters so this is testable without a disk.
     */
    fun reconcile(
        entries: List<DownloadEntry>,
        fileExists: (String) -> Boolean,
        fileLength: (String) -> Long = { 0L },
    ): List<DownloadEntry> = entries.mapNotNull { entry ->
        when (entry.state) {
            DownloadState.Downloaded -> when {
                !fileExists(entry.fileName) -> null
                // A file shorter than the recorded size is a truncated write, not a download.
                entry.bytesDownloaded > 0 && fileLength(entry.fileName) < entry.bytesDownloaded -> null
                else -> entry
            }
            else -> entry
        }
    }

    /** Adds or replaces one row, newest-wins, keeping one row per chapter. */
    fun put(entries: List<DownloadEntry>, entry: DownloadEntry): List<DownloadEntry> =
        listOf(entry) + entries.filterNot { it.key == entry.key }

    fun remove(entries: List<DownloadEntry>, chapterId: Int): List<DownloadEntry> =
        entries.filterNot { it.key == chapterId }

    fun find(entries: List<DownloadEntry>, chapterId: Int): DownloadEntry? =
        entries.firstOrNull { it.key == chapterId }

    /** Work waiting to be done, oldest request first so a queue drains in the order it was filled. */
    fun pending(entries: List<DownloadEntry>): List<DownloadEntry> =
        entries.filter { it.state.isActive }.sortedBy { it.updatedAtMs }

    /** Total bytes actually occupied by completed downloads. */
    fun bytesOnDisk(entries: List<DownloadEntry>): Long =
        entries.filter { it.isOffline }.sumOf { it.bytesDownloaded }

    /**
     * The next [count] chapters to fetch, starting at [startChapterId].
     *
     * "Download next 10" means the ten *after where the listener is*, in reading order, skipping
     * anything already downloaded or already queued — not the first ten in the list and not ten
     * more copies of what is already there. [order] is the chapter ids in reading order, which the
     * caller takes from the same `playbackOrder` the queue is built from, so this cannot disagree
     * with what will actually play next.
     */
    fun nextToDownload(
        order: List<Int>,
        startChapterId: Int?,
        entries: List<DownloadEntry>,
        count: Int = DefaultBatch,
    ): List<Int> {
        if (order.isEmpty() || count <= 0) return emptyList()
        val startIndex = startChapterId?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val alreadyHandled = entries
            .filter { it.state == DownloadState.Downloaded || it.state.isActive }
            .map { it.chapterId }
            .toSet()
        return order.drop(startIndex)
            .filterNot { it in alreadyHandled }
            .take(count)
    }

    /** What "Download next N" means when the user has not said otherwise. Matches mobile. */
    const val DefaultBatch: Int = 10
}

/** Seam so tests never touch the real data directory. */
interface DownloadIndexStore {
    val entries: StateFlow<List<DownloadEntry>>

    fun put(entry: DownloadEntry)

    fun remove(chapterId: Int)

    fun clear()
}

/** In-memory index for tests and for a session with nowhere safe to write. */
class InMemoryDownloadIndexStore(
    initial: List<DownloadEntry> = emptyList(),
) : DownloadIndexStore {
    private val _entries = MutableStateFlow(initial)
    override val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    override fun put(entry: DownloadEntry) {
        _entries.value = DownloadIndex.put(_entries.value, entry)
    }

    override fun remove(chapterId: Int) {
        _entries.value = DownloadIndex.remove(_entries.value, chapterId)
    }

    override fun clear() {
        _entries.value = emptyList()
    }
}

/**
 * The on-disk shape. Fully nullable and schema-versioned, for the same reason the playback
 * preferences file is: a file from another build must load degraded rather than throw at startup.
 */
internal data class StoredDownloadEntry(
    val chapterId: Int? = null,
    val fictionId: Int? = null,
    val fictionTitle: String? = null,
    val chapterTitle: String? = null,
    val state: String? = null,
    val bytesDownloaded: Long? = null,
    val totalBytes: Long? = null,
    val fileName: String? = null,
    val failureMessage: String? = null,
    val updatedAtMs: Long? = null,
)

internal data class StoredDownloadIndex(
    val version: Int? = null,
    val entries: List<StoredDownloadEntry>? = null,
)

/**
 * `downloads.json` in the download root, written atomically and owner-only.
 *
 * Owner-only despite holding no secret: it is a reading history by another name, and a shared
 * machine should not make one user's library listing world-readable.
 *
 * Every mutation rewrites the whole file. The index is bounded by how many chapters a person has
 * downloaded — hundreds, not millions — so a full atomic rewrite is both simpler and safer than an
 * append log that could be torn, and "transactional" here means exactly that: a reader sees the
 * state before a change or the state after it, never a half-written one.
 */
class FileDownloadIndexStore(
    private val file: File,
    /** Existence check used to reconcile the index against reality at startup. */
    private val fileExists: (String) -> Boolean = { name -> file.parentFile?.resolve(name)?.isFile == true },
    private val fileLength: (String) -> Long = { name -> file.parentFile?.resolve(name)?.length() ?: 0L },
) : DownloadIndexStore {

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StoredDownloadIndex::class.java)

    private val _entries = MutableStateFlow(load())
    override val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    override fun put(entry: DownloadEntry) {
        write(DownloadIndex.put(_entries.value, entry))
    }

    override fun remove(chapterId: Int) {
        write(DownloadIndex.remove(_entries.value, chapterId))
    }

    override fun clear() {
        write(emptyList())
    }

    private fun write(next: List<DownloadEntry>) {
        if (next == _entries.value) return
        _entries.value = next
        val stored = StoredDownloadIndex(
            version = CurrentVersion,
            entries = next.map { entry ->
                StoredDownloadEntry(
                    chapterId = entry.chapterId,
                    fictionId = entry.fictionId,
                    fictionTitle = entry.fictionTitle,
                    chapterTitle = entry.chapterTitle,
                    state = entry.state.name,
                    bytesDownloaded = entry.bytesDownloaded,
                    totalBytes = entry.totalBytes,
                    fileName = entry.fileName,
                    failureMessage = entry.failureMessage,
                    updatedAtMs = entry.updatedAtMs,
                )
            },
        )
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(stored)) }
            .onFailure { AppLog.warn("could not write the download index", it) }
    }

    /**
     * Reads, migrates, recovers in-flight rows, and reconciles against the filesystem.
     *
     * A corrupt or unreadable index is treated as an empty one rather than as a fatal error. That
     * loses the *listing*, not the audio: the files are still there, and re-downloading is a worse
     * outcome than a startup crash only if the crash is survivable, which it is not.
     */
    private fun load(): List<DownloadEntry> {
        val stored = runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the download index; starting empty", it) }
            .getOrNull()
            ?: return emptyList()

        val migrated = migrate(stored)
        val recovered = DownloadIndex.recoverAfterRestart(migrated)
        return DownloadIndex.reconcile(recovered, fileExists, fileLength)
    }

    /**
     * Brings a stored payload up to [CurrentVersion].
     *
     * There is only one version so far, so this is where the shape of a migration lives rather than
     * a migration itself: unknown/absent versions are read with the current reader, and any row
     * that cannot produce the fields an entry needs is dropped rather than defaulted into something
     * that claims to be a downloaded file.
     */
    private fun migrate(stored: StoredDownloadIndex): List<DownloadEntry> {
        val version = stored.version ?: 0
        if (version > CurrentVersion) {
            // A newer build wrote this. Reading it with the current reader is still the best
            // available answer; unknown fields are simply absent from the model.
            AppLog.warn("the download index was written by a newer build (v$version)")
        }
        return stored.entries.orEmpty().mapNotNull { it.toEntry() }
    }

    private fun StoredDownloadEntry.toEntry(): DownloadEntry? {
        val id = chapterId?.takeIf { it > 0 } ?: return null
        val name = fileName?.takeIf { it.isNotBlank() && isSafeName(it) } ?: return null
        val parsedState = DownloadState.entries.firstOrNull { it.name.equals(state, ignoreCase = true) }
            ?: return null
        return DownloadEntry(
            chapterId = id,
            fictionId = fictionId ?: 0,
            fictionTitle = fictionTitle.orEmpty(),
            chapterTitle = chapterTitle.orEmpty(),
            state = parsedState,
            bytesDownloaded = bytesDownloaded?.coerceAtLeast(0L) ?: 0L,
            totalBytes = totalBytes?.coerceAtLeast(0L) ?: 0L,
            fileName = name,
            failureMessage = failureMessage,
            updatedAtMs = updatedAtMs ?: 0L,
        )
    }

    companion object {
        /** Bumped only when a stored field changes meaning. */
        const val CurrentVersion: Int = 1

        /**
         * Whether a name read back from the index is still one this app would have written.
         *
         * The index is a plain file that an attacker with write access — or a corrupted disk — can
         * edit, and every name in it is later resolved against the download root. A traversal here
         * would let `../../../.ssh/id_rsa` be "deleted as a download".
         *
         * A **whitelist**, not a list of forbidden characters. Every name this app generates is
         * `<id>.mp3`, `<id>-<hash>.mp3`, or one of those plus `.part`, so the safe set is known
         * exactly — and blacklisting is how these checks rot, one separator, encoding or reserved
         * name at a time. The dot-dot and leading-dot rules stay on top because `..` is spelled
         * entirely with permitted characters.
         */
        private val SafeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        fun isSafeName(name: String): Boolean =
            SafeName.matches(name) &&
                !name.contains("..") &&
                File(name).name == name
    }
}
