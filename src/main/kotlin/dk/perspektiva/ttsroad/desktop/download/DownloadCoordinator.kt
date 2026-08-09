package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.AppLog
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

/**
 * One account's downloads: where they live, what the index says, and the queue working on them.
 *
 * Bundled rather than passed around separately because all three are derived from the same identity
 * and it must be impossible to hold the storage of one account with the index of another.
 */
class DownloadSession(
    val identity: StorageIdentity,
    val storage: DownloadStorage,
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

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

    private fun identityOf(session: SessionState): StorageIdentity? {
        if (session.serverUrl.isBlank()) return null
        return StorageIdentity.of(
            connectUrl = session.serverUrl,
            // The advertised identity would be better still and is what StorageIdentity prefers;
            // it is threaded in from capability discovery by the caller that has it.
            advertisedBaseUrl = advertisedBaseUrl,
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
            val changed = field != value
            field = value
            // A late-arriving base_url renames the namespace, so the stack has to be rebuilt
            // against it rather than left on the address-derived fallback.
            if (changed) runCatching { refresh() }
        }

    private fun build(identity: StorageIdentity): DownloadSession {
        val storage = DownloadStorage.forIdentity(identity, dataDir)
        if (!storage.prepare()) {
            AppLog.warn("the download directory could not be made owner-only")
        }
        val index = FileDownloadIndexStore(storage.indexFile)
        val downloader = ChapterDownloader(client, repository, storage)
        val manager = DownloadManager(downloader, storage, index, dispatcher)
        return DownloadSession(identity, storage, index, manager)
    }

    private fun closeCurrent() {
        _current.value?.let { runCatching { it.close() } }
        _current.value = null
    }

    @Synchronized
    override fun close() = closeCurrent()
}
