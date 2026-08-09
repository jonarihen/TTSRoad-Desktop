package dk.perspektiva.ttsroad.desktop.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Memory/disk/network read-along cache with conditional revalidation and offline fallback.
 *
 * Memory is explicitly session-scoped via [clear]. Disk is supplied by the live download
 * coordinator, so it is unreachable while signed out and cannot cross a server/account boundary.
 */
class ReadAlongCache(
    private val repository: TtsRoadRepository,
) {
    private data class MemoryEntry(val cached: CachedReadAlong, val document: ReadAlongDocument)

    private val memory = ConcurrentHashMap<Int, MemoryEntry>()
    private val locks = ConcurrentHashMap<Int, Mutex>()
    @Volatile private var diskCache: () -> ReadAlongDiskCache? = { null }

    fun attachDiskCache(supplier: () -> ReadAlongDiskCache?): ReadAlongCache = apply {
        diskCache = supplier
    }

    suspend fun load(chapterId: Int): ReadAlongDocument? {
        require(chapterId > 0) { "invalid chapter id" }
        return locks.getOrPut(chapterId) { Mutex() }.withLock { loadLocked(chapterId) }
    }

    /** Best effort and intentionally silent: prefetch must never disturb playback. */
    suspend fun prefetch(chapterId: Int) {
        runCatching { load(chapterId) }
    }

    fun clear() {
        memory.clear()
        locks.clear()
    }

    private suspend fun loadLocked(chapterId: Int): ReadAlongDocument? {
        val disk = diskCache()
        val available = memory[chapterId] ?: disk?.read(chapterId)?.toMemory(chapterId)?.also {
            memory[chapterId] = it
        }
        return try {
            when (val fetched = repository.readAlong(chapterId, available?.cached?.etag)) {
                is ReadAlongFetchResult.Modified -> {
                    if (fetched.response.chapter.id != chapterId) {
                        error("The server returned read-along text for a different chapter")
                    }
                    val cached = CachedReadAlong(etag = fetched.etag, response = fetched.response)
                    val entry = cached.toMemory(chapterId)
                        ?: error("The read-along document could not be parsed safely")
                    memory[chapterId] = entry
                    disk?.write(chapterId, cached)
                    entry.document
                }

                ReadAlongFetchResult.NotModified ->
                    available?.document ?: error("The server returned 304 without a cached document")

                ReadAlongFetchResult.NotFound -> {
                    memory.remove(chapterId)
                    disk?.delete(chapterId)
                    null
                }
            }
        } catch (failure: Throwable) {
            // A refused credential immediately removes the authority to read account content.
            if (failure is HttpException && failure.code() == 401) throw failure
            available?.document ?: throw failure
        }
    }

    private fun CachedReadAlong.toMemory(chapterId: Int): MemoryEntry? {
        if (version != CachedReadAlong.CurrentVersion || response.chapter.id != chapterId) return null
        return runCatching { MemoryEntry(this, ReadAlongDocument.from(response)) }.getOrNull()
    }
}
