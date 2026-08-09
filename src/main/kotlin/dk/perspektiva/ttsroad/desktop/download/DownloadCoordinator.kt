package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.LibraryDiskCache
import dk.perspektiva.ttsroad.desktop.data.ReadAlongDiskCache
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.StorageIdentity
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** One fiction's measured user-requested audio on disk. */
data class OfflineFictionUsage(
    val fictionId: Int,
    val title: String,
    val bytes: Long,
    val chapters: Int,
)

/** What Settings can say without exposing any other account's metadata. */
data class OfflineStorageSummary(
    val available: Boolean = false,
    val downloadBytes: Long = 0L,
    val downloadedChapters: Int = 0,
    val streamingCacheBytes: Long = 0L,
    val streamingCacheFiles: Int = 0,
    val fictions: List<OfflineFictionUsage> = emptyList(),
)

/** Narrow Settings seam, with an unavailable implementation for previews and isolated tests. */
interface OfflineStorageController {
    fun summary(): OfflineStorageSummary
    suspend fun deleteAllDownloads(): Long
    suspend fun clearStreamingCache(): Long
}

object UnavailableOfflineStorageController : OfflineStorageController {
    override fun summary(): OfflineStorageSummary = OfflineStorageSummary()
    override suspend fun deleteAllDownloads(): Long = 0L
    override suspend fun clearStreamingCache(): Long = 0L
}

/**
 * One account's downloads: where they live, what the index says, and the queue working on them.
 *
 * Bundled rather than passed around separately because all three are derived from the same identity
 * and it must be impossible to hold the storage of one account with the index of another.
 */
class DownloadSession(
    val identity: StorageIdentity,
    val storage: DownloadStorage,
    val streamingCache: StreamingCache,
    val libraryCache: LibraryDiskCache,
    val readAlongCache: ReadAlongDiskCache,
    val index: DownloadIndexStore,
    val manager: DownloadManager,
) : AutoCloseable {
    override fun close() = manager.close()
}

/**
 * Keeps the download stack pointed at whoever is signed in.
 *
 * Downloads are namespaced per server *and* per account, but the container is built once at startup
 * — often signed out — and a sign-out followed by a different sign-in has to move the whole stack
 * without rebuilding the app. So this owns the lifecycle: it derives an identity from the session,
 * builds the stack the first time that identity is seen, and tears the previous one down.
 *
 * Signing out deliberately **keeps the files**. The issue is explicit that downloads survive a
 * sign-out and become reachable again when that account signs back in; only the *live* session goes
 * away, which is why [current] drops to null rather than the directory being deleted.
 */
class DownloadCoordinator(
    private val sessionStore: SessionStore,
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
    private val dataDir: File = AppDirectories.dataDir(),
    private val cacheDir: File = AppDirectories.cacheDir(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable, OfflineStorageController {

    private val _current = MutableStateFlow<DownloadSession?>(null)

    /** The stack for the signed-in account, or null when nobody is signed in. */
    val current: StateFlow<DownloadSession?> = _current.asStateFlow()

    /**
     * Rebuilds the stack if the signed-in identity has changed, and returns it.
     *
     * Pull-based rather than a collector on the session flow, because the thing that needs it most
     * — the media source factory — is called from the playback thread at chapter-prepare time and
     * must see the *current* answer rather than whatever a collector last published.
     */
    @Synchronized
    fun refresh(): DownloadSession? {
        val session = sessionStore.current()
        val identity = identityOf(session)

        if (identity == null) {
            // Signed out. The files stay; only the live stack goes.
            closeCurrent()
            return null
        }
        _current.value?.let { existing ->
            if (existing.identity == identity) return existing
        }
        closeCurrent()

        val built = runCatching { build(identity) }
            .onFailure { AppLog.warn("could not open the download storage for this account", it) }
            .getOrNull()
        _current.value = built
        return built
    }

    /** The index for the signed-in account, refreshed. Handed to the offline media source. */
    fun indexOrNull(): DownloadIndexStore? = refresh()?.index

    /** The storage for the signed-in account, refreshed. */
    fun storageOrNull(): DownloadStorage? = refresh()?.storage

    /** Rebuildable streamed audio for the signed-in account, refreshed. */
    fun streamingCacheOrNull(): StreamingCache? = refresh()?.streamingCache

    /** Rebuildable library/chapter metadata for the signed-in account only. */
    fun libraryCacheOrNull(): LibraryDiskCache? = refresh()?.libraryCache

    /** Rebuildable, account-scoped narration text and cue documents. */
    fun readAlongCacheOrNull(): ReadAlongDiskCache? = refresh()?.readAlongCache

    /**
     * Measures the current account only. A signed-out username/server hint is deliberately not
     * enough authority to open its index, and another account's fiction titles are never listed.
     */
    override fun summary(): OfflineStorageSummary {
        val session = refresh() ?: return OfflineStorageSummary()
        val files = session.storage.audioFileBytes()
        val entries = session.index.entries.value
        val byFiction = entries.groupBy { it.fictionId }.mapNotNull { (fictionId, rows) ->
            val sizes = rows.associateWith { entry ->
                files[entry.fileName].orZero() + files[entry.fileName + DownloadStorage.PartSuffix].orZero()
            }
            val bytes = sizes.values.sum()
            val chapters = sizes.count { it.value > 0L }
            if (bytes <= 0L && chapters == 0) return@mapNotNull null
            OfflineFictionUsage(
                fictionId = fictionId,
                title = rows.firstNotNullOfOrNull { it.fictionTitle.takeIf(String::isNotBlank) }
                    ?: "Fiction $fictionId",
                bytes = bytes,
                chapters = chapters,
            )
        }.sortedByDescending { it.bytes }
        val stream = session.streamingCache.stats()
        return OfflineStorageSummary(
            available = true,
            downloadBytes = files.values.sum(),
            downloadedChapters = entries.count { entry ->
                entry.isOffline && files[entry.fileName].orZero() > 0L
            },
            streamingCacheBytes = stream.bytes,
            streamingCacheFiles = stream.files,
            fictions = byFiction,
        )
    }

    override suspend fun deleteAllDownloads(): Long = refresh()?.manager?.deleteAll() ?: 0L

    override suspend fun clearStreamingCache(): Long = refresh()?.streamingCache?.clear() ?: 0L

    private fun Long?.orZero(): Long = this ?: 0L

    private fun identityOf(session: SessionState): StorageIdentity? {
        if (!session.isLoggedIn || session.serverUrl.isBlank()) return null
        return StorageIdentity.of(
            connectUrl = session.serverUrl,
            // Runtime discovery wins, while the persisted login/discovery value preserves the
            // exact same namespace when startup capability discovery cannot reach the server.
            advertisedBaseUrl = advertisedBaseUrl ?: session.advertisedBaseUrl,
            username = session.username,
        )
    }

    /**
     * The server's own `base_url`, when discovery has reported one.
     *
     * Set rather than read from a flow so this stays usable from the playback thread. A change
     * takes effect on the next [refresh]; discovery runs long before the first download, so in
     * practice the first identity already has it.
     */
    @Volatile
    var advertisedBaseUrl: String? = null
        set(value) {
            val stableValue = value?.trim()?.takeIf { it.isNotEmpty() }
            val session = sessionStore.current()
            // Capability discovery may be the first endpoint to report base_url. Keep that
            // non-secret identity hint beside the session so an offline restart can find the
            // downloads created under it. A failed discovery reports null and must not erase it.
            if (stableValue != null && session.isLoggedIn && session.advertisedBaseUrl != stableValue) {
                sessionStore.rememberAdvertisedBaseUrl(stableValue)
            }
            val changed = field != stableValue
            field = stableValue
            // A late-arriving base_url renames the namespace, so the stack has to be rebuilt
            // against it rather than left on the address-derived fallback.
            if (changed) runCatching { refresh() }
        }

    private fun build(identity: StorageIdentity): DownloadSession {
        val storage = DownloadStorage.forIdentity(identity, dataDir)
        if (!storage.prepare()) {
            error("the download directory could not be made owner-only")
        }
        val index = FileDownloadIndexStore(storage.indexFile)
        val downloader = ChapterDownloader(client, repository, storage)
        val manager = DownloadManager(downloader, storage, index, dispatcher)
        index.entries.value.filter { it.state == DownloadState.Removing }
            .forEach { manager.remove(it.chapterId) }
        val streamingCache = StreamingCache.forIdentity(identity, cacheDir)
        val libraryCache = LibraryDiskCache.forIdentity(identity, cacheDir)
        val readAlongCache = ReadAlongDiskCache.forIdentity(identity, cacheDir)
        // Interrupted rows are already Queued after index recovery. Cached chapter metadata gives
        // them their live URL again without waiting for the user to open every fiction manually.
        val pendingFictions = DownloadIndex.pending(index.entries.value).map { it.fictionId }.toSet()
        val cachedChapters = pendingFictions.mapNotNull(libraryCache::loadChapters)
        manager.resumePending(
            chaptersById = cachedChapters.flatMap { it.value.chapters }
                .associateBy { it.resolvedChapterId },
            fictionTitles = cachedChapters.associate { it.value.fiction.id to it.value.fiction.title },
        )
        return DownloadSession(identity, storage, streamingCache, libraryCache, readAlongCache, index, manager)
    }

    private fun closeCurrent() {
        _current.value?.let { runCatching { it.close() } }
        _current.value = null
    }

    @Synchronized
    override fun close() = closeCurrent()
}
