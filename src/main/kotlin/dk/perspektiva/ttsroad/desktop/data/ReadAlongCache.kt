package dk.perspektiva.ttsroad.desktop.data

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

class ReadAlongCache(
    private val repository: TtsRoadRepository,
    private val session: StateFlow<SessionState>? = null,
) {
    private data class MemoryEntry(val cached: CachedReadAlong, val document: ReadAlongDocument)
    private data class Authority(val server: String, val username: String?, val token: String?)
    private data class Context(val authority: Authority?, val diskRoot: Path?)
    private data class Load(
        val generation: Long,
        val disk: ReadAlongDiskCache?,
        val mutex: Mutex,
    )

    private val stateLock = Any()
    private val memory = mutableMapOf<Int, MemoryEntry>()
    private val locks = mutableMapOf<Int, Mutex>()
    private var diskCache: () -> ReadAlongDiskCache? = { null }
    private var context: Context? = null
    private var refused = false
    private val generation = MutableStateFlow(0L)
    val changes: Flow<Long> = session?.let { sessions ->
        combine(generation, sessions) { _, _ -> currentGeneration() }
    } ?: generation.asStateFlow()

    fun attachDiskCache(supplier: () -> ReadAlongDiskCache?): ReadAlongCache = apply {
        synchronized(stateLock) {
            diskCache = supplier
            invalidate()
            context = null
        }
    }

    fun currentGeneration(): Long = synchronized(stateLock) {
        refreshContext()
        generation.value
    }

    suspend fun load(chapterId: Int): ReadAlongDocument? {
        require(chapterId > 0) { "invalid chapter id" }
        val load = synchronized(stateLock) {
            val disk = refreshContext()
            if (refused || (session != null && !session.value.isLoggedIn)) return null
            Load(generation.value, disk, locks.getOrPut(chapterId) { Mutex() })
        }
        return load.mutex.withLock { loadLocked(chapterId, load) }
    }

    suspend fun prefetch(chapterId: Int) {
        try {
            load(chapterId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
        }
    }

    fun clear() {
        synchronized(stateLock) {
            refused = false
            invalidate()
        }
    }

    private fun invalidate() {
        memory.clear()
        locks.clear()
        generation.value++
    }

    private fun refreshContext(): ReadAlongDiskCache? {
        val state = session?.value
        val disk = if (state == null || state.isLoggedIn) diskCache() else null
        val next = Context(
            state?.let { Authority(it.serverUrl, it.username, it.token) },
            disk?.root?.toPath()?.toAbsolutePath()?.normalize(),
        )
        if (context != next) {
            if (context != null) invalidate()
            refused = false
            context = next
        }
        return disk
    }

    private fun ensureCurrent(load: Load) {
        refreshContext()
        if (generation.value != load.generation) throw CancellationException("Read-along session changed")
    }

    private suspend fun loadLocked(chapterId: Int, load: Load): ReadAlongDocument? {
        currentCoroutineContext().ensureActive()
        val available = synchronized(stateLock) {
            ensureCurrent(load)
            memory[chapterId] ?: load.disk?.read(chapterId)?.toMemory(chapterId)?.also {
                ensureCurrent(load)
                memory[chapterId] = it
            }
        }
        return try {
            val fetched = repository.readAlong(chapterId, available?.cached?.etag)
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                ensureCurrent(load)
                when (fetched) {
                    is ReadAlongFetchResult.Modified -> {
                        if (fetched.response.chapter.id != chapterId) {
                            error("The server returned read-along text for a different chapter")
                        }
                        val cached = CachedReadAlong(etag = fetched.etag, response = fetched.response)
                        val entry = cached.toMemory(chapterId)
                            ?: error("The read-along document could not be parsed safely")
                        ensureCurrent(load)
                        load.disk?.write(chapterId, cached)
                        ensureCurrent(load)
                        memory[chapterId] = entry
                        entry.document
                    }

                    ReadAlongFetchResult.NotModified ->
                        available?.document ?: error("The server returned 304 without a cached document")

                    ReadAlongFetchResult.NotFound -> {
                        memory.remove(chapterId)
                        load.disk?.delete(chapterId)
                        null
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (failure is HttpException && failure.code() == 401) {
                    refreshContext()
                    if (generation.value == load.generation) {
                        refused = true
                        invalidate()
                    }
                    throw failure
                }
                ensureCurrent(load)
                available?.document ?: throw failure
            }
        }
    }

    private fun CachedReadAlong.toMemory(chapterId: Int): MemoryEntry? {
        if (version != CachedReadAlong.CurrentVersion || response.chapter.id != chapterId) return null
        return runCatching { MemoryEntry(this, ReadAlongDocument.from(response)) }.getOrNull()
    }
}
