package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Listening positions that have not reached the server yet, held on disk.
 *
 * On disk rather than in memory because the case that loses data is the one where the app is closed
 * before the network comes back. An in-memory queue would drop exactly the writes that most need
 * keeping.
 *
 * At most one entry per chapter: only the furthest position matters, and the server compares by
 * timestamp anyway, so an older entry for the same chapter could never win once a newer one exists.
 * That also bounds the file by the number of chapters actually listened to offline rather than by
 * how long the session ran.
 */
class ProgressOutbox(private val file: File) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<PendingProgress>>(
        Types.newParameterizedType(List::class.java, PendingProgress::class.java),
    )

    private val lock = Any()
    private val pending: LinkedHashMap<Int, PendingProgress> = load()

    fun record(entry: PendingProgress) = synchronized(lock) {
        pending[entry.chapterId] = entry
        persist()
    }

    fun snapshot(): List<PendingProgress> = synchronized(lock) { pending.values.toList() }

    fun drop(chapterIds: Collection<Int>) = synchronized(lock) {
        if (chapterIds.isEmpty()) return@synchronized
        chapterIds.forEach { pending.remove(it) }
        persist()
    }

    /** Used when the credential dies: an unauthenticated queue can never be flushed. */
    fun clear() = synchronized(lock) {
        if (pending.isEmpty()) return@synchronized
        pending.clear()
        persist()
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(adapter.toJson(pending.values.toList()))
        }
    }

    private fun load(): LinkedHashMap<Int, PendingProgress> {
        val stored = runCatching {
            if (file.isFile) adapter.fromJson(file.readText()) else null
        }.getOrNull().orEmpty()
        return LinkedHashMap<Int, PendingProgress>().apply {
            stored.forEach { put(it.chapterId, it) }
        }
    }
}
