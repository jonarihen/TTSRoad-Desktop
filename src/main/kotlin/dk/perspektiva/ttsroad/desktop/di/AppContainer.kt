package dk.perspektiva.ttsroad.desktop.di

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.FilePlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.BrowsePreferencesStore
import dk.perspektiva.ttsroad.desktop.data.FileBrowsePreferencesStore
import dk.perspektiva.ttsroad.desktop.data.FilePlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.FileSessionStore
import dk.perspektiva.ttsroad.desktop.data.FileWindowPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.FileListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.ListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.FileReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistory
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.ReadAlongCache
import dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.RetrofitTtsRoadRepository
import dk.perspektiva.ttsroad.desktop.download.DownloadCoordinator
import dk.perspektiva.ttsroad.desktop.download.AudiobookExportDownloader
import dk.perspektiva.ttsroad.desktop.download.HttpAudiobookExportDownloader
import dk.perspektiva.ttsroad.desktop.download.OfflineFirstMediaSourceFactory
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.SyncedPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadAuthInterceptor
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.WindowPreferencesStore
import dk.perspektiva.ttsroad.desktop.player.GstPlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.HttpMediaSource
import dk.perspektiva.ttsroad.desktop.player.JavaSoundPlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.MediaSourceFactory
import dk.perspektiva.ttsroad.desktop.player.MprisService
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.QueuePlaybackController
import dk.perspektiva.ttsroad.desktop.security.CredentialStore
import dk.perspektiva.ttsroad.desktop.security.CredentialStores
import dk.perspektiva.ttsroad.desktop.update.FileUpdateSettingsStore
import dk.perspektiva.ttsroad.desktop.update.GitHubReleaseSource
import dk.perspektiva.ttsroad.desktop.update.ReleaseSource
import dk.perspektiva.ttsroad.desktop.update.UpdateChecker
import dk.perspektiva.ttsroad.desktop.update.UpdateDownloader
import dk.perspektiva.ttsroad.desktop.update.UpdateSettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Dispatchers as data, so tests can swap the whole set for a `StandardTestDispatcher` instead of
 * mutating the global `Dispatchers` object.
 */
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
) {
    companion object {
        /**
         * `Dispatchers.Main` resolves to the Swing/AWT event queue on Compose Desktop, which
         * blows up if touched off a UI-capable JVM. This factory defers that lookup to first use.
         */
        val Default: AppDispatchers by lazy { AppDispatchers() }
    }
}

/**
 * The single composition root. Nothing else in the app constructs a repository, a session store,
 * a credential store, an HTTP client, or a playback controller.
 *
 * Everything is a constructor parameter with a production default, so a test builds a fully
 * substituted container in one expression. Note the ordering: the credential store feeds the
 * session store, and the session store feeds the HTTP client's auth interceptor — that is the
 * dependency chain that makes "one place attaches the bearer token" true.
 */
class AppContainer(
    val dispatchers: AppDispatchers = AppDispatchers.Default,
    val clock: () -> Long = System::currentTimeMillis,
    // A factory rather than a value so a test that supplies its own session store never probes the
    // machine's real keyring (and never writes to the user's real config directory).
    credentialStore: () -> CredentialStore = { CredentialStores.forCurrentPlatform() },
    val sessionStore: SessionStore = FileSessionStore(credentials = credentialStore()),
    httpClientFactory: (SessionStore) -> OkHttpClient = ::defaultHttpClient,
    repositoryFactory: (SessionStore, OkHttpClient, AppDispatchers) -> TtsRoadRepository =
        { store, client, d -> RetrofitTtsRoadRepository(store, client, d.io, clock) },
    mediaSourceFactory: (OkHttpClient, TtsRoadRepository) -> MediaSourceFactory =
        { client, repo -> MediaSourceFactory { _, url -> HttpMediaSource(client, repo, url) } },
    // GStreamer where it exists, Java Sound where it does not. Resolved once, here, so nothing
    // downstream has to know which backend it got — only what that backend reports it can do.
    // A machine with no GStreamer must still get a working app, just without speed control.
    audioEngineFactory: () -> PlaybackEngine =
        { GstPlaybackEngine.createOrNull() ?: JavaSoundPlaybackEngine() },
    /**
     * Listening settings, persisted per machine rather than per account — see
     * [dk.perspektiva.ttsroad.desktop.data.PlaybackPreferences]. A store rather than a path, so a
     * test never writes into the user's config directory.
     */
    val playbackPreferences: PlaybackPreferencesStore = FilePlaybackPreferencesStore(),
    /**
     * How the shelf is arranged — order, ticked tags, browsed scope — kept across restarts.
     *
     * Machine-local like [playbackPreferences] and for the same reason: it is about how somebody
     * likes to look at a shelf, not about which account they signed into.
     */
    val browsePreferences: BrowsePreferencesStore = FileBrowsePreferencesStore(),
    // Null selects the production local-plus-server store. Supplying a store keeps UI tests wholly
    // in memory and avoids making a fake repository call just because the library was composed.
    playbackHistory: PlaybackHistoryStore? = null,
    /** Day totals for the Listening pane. In-memory in a test, so no screen writes to a real home. */
    val listeningStats: ListeningStatsStore = FileListeningStatsStore(),
    playbackFactory: (
        TtsRoadRepository,
        MediaSourceFactory,
        PlaybackEngine,
        AppDispatchers,
        PlaybackPreferencesStore,
        PlaybackHistoryStore,
        ListeningStatsStore,
        () -> String,
    ) -> PlaybackController =
        { repo, mediaSources, engine, d, prefs, history, stats, owner ->
            QueuePlaybackController(
                repo,
                mediaSources,
                engine,
                d.io,
                prefs,
                history,
                stats,
                ownerKey = owner,
            )
        },
    libraryCacheFactory: (TtsRoadRepository, AppDispatchers, () -> Long) -> LibraryCache =
        { repo, d, now -> LibraryCache(repo, d.main, now) },
    readerPreferencesFactory: (TtsRoadRepository, AppDispatchers) -> ReaderPreferencesStore =
        { repo, d -> FileReaderPreferencesStore(repo, dispatcher = d.io) },
    /**
     * A factory rather than a value so a test can point downloads at a temp directory — the real
     * one writes into the user's data directory the moment somebody signs in.
     */
    downloadCoordinatorFactory: (SessionStore, OkHttpClient, TtsRoadRepository, AppDispatchers) -> DownloadCoordinator =
        { session, client, repo, d -> DownloadCoordinator(session, client, repo, dispatcher = d.io) },
    audiobookExportDownloaderFactory: (
        OkHttpClient,
        TtsRoadRepository,
        AppDispatchers,
    ) -> AudiobookExportDownloader = { client, repo, d ->
        HttpAudiobookExportDownloader(client, repo, d.io)
    },
    // A store rather than a file path, so a test never writes into the user's config directory.
    windowPreferencesStore: WindowPreferencesStore = FileWindowPreferencesStore(),
    /**
     * When this build last looked for a newer one, and which version was dismissed. Machine-local
     * like the listening preferences: which build is installed is a property of this desktop.
     */
    val updateSettings: UpdateSettingsStore = FileUpdateSettingsStore(),
    // A factory so a test substitutes the release feed instead of reaching api.github.com.
    releaseSourceFactory: (OkHttpClient) -> ReleaseSource = { GitHubReleaseSource(it) },
) : AutoCloseable {
    val httpClient: OkHttpClient = httpClientFactory(sessionStore)
    val repository: TtsRoadRepository = repositoryFactory(sessionStore, httpClient, dispatchers)
    val audiobookExportDownloader: AudiobookExportDownloader =
        audiobookExportDownloaderFactory(httpClient, repository, dispatchers)

    /**
     * Offline downloads for whoever is signed in.
     *
     * Above the media sources on purpose: the factory below consults it, so the ordering here is
     * what makes "a downloaded chapter plays without the server" true for every playback path
     * rather than only the ones a screen happens to route through.
     */
    val downloads: DownloadCoordinator =
        downloadCoordinatorFactory(sessionStore, httpClient, repository, dispatchers)

    /**
     * Disk first, network second. The network factory is the injected one, so a test can still
     * substitute it and a machine with no download directory simply always streams.
     */
    val mediaSources: MediaSourceFactory = OfflineFirstMediaSourceFactory(
        index = downloads::indexOrNull,
        storage = downloads::storageOrNull,
        network = mediaSourceFactory(httpClient, repository),
        streamingCache = downloads::streamingCacheOrNull,
    )
    val audioEngine: PlaybackEngine = audioEngineFactory()
    /**
     * Which account's history is being written or shown.
     *
     * Derived from the live session rather than captured once: signing out and signing in as
     * somebody else on the same desktop must change the answer without rebuilding the container.
     */
    val historyOwnerKey: () -> String = {
        val session = sessionStore.current()
        if (session.serverUrl.isBlank()) "" else PlaybackHistory.ownerKeyFor(session.serverUrl, session.username)
    }

    /** Local fallback plus the account-wide `kind=auto` bookmark store shared with the web. */
    val playbackHistory: PlaybackHistoryStore = playbackHistory ?: SyncedPlaybackHistoryStore(
        local = FilePlaybackHistoryStore(),
        repository = repository,
        dispatcher = dispatchers.io,
        currentOwnerKey = historyOwnerKey,
    )

    val playback: PlaybackController = playbackFactory(
        repository,
        mediaSources,
        audioEngine,
        dispatchers,
        playbackPreferences,
        this.playbackHistory,
        listeningStats,
        historyOwnerKey,
    )

    /**
     * The scope MPRIS publishes from. Its own, not the controller's: the bus mirror has to keep
     * running while a chapter is between jobs, and it is torn down here rather than by whatever
     * happens to cancel a playback job.
     */
    private val mprisScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    @Volatile private var mpris: MprisService? = null

    /**
     * Puts the player on the session bus, if there is one.
     *
     * Called from `main` rather than from the constructor because Raise and Quit need the window,
     * which does not exist yet when the container is built. A machine with no D-Bus — every
     * Windows and macOS machine, and a good many Linux ones — silently gets no MPRIS and a fully
     * working player; see [MprisService.createOrNull].
     */
    fun startMpris(onRaise: () -> Unit, onQuit: () -> Unit) {
        if (mpris != null) return
        mpris = MprisService.createOrNull(
            controller = playback,
            preferences = playbackPreferences,
            scope = mprisScope,
            onRaise = onRaise,
            onQuit = onQuit,
        )
    }

    /**
     * Library and chapter data, held here rather than inside a screen.
     *
     * That placement is the whole point: its lifetime is the signed-in session, so navigating away
     * from the library and back shows what was already loaded instead of re-fetching it. It is
     * emptied by `App` when the session ends.
     */
    val libraryCache: LibraryCache = libraryCacheFactory(repository, dispatchers, clock)
        .attachDiskCache(downloads::libraryCacheOrNull)

    /** Reader documents share the download identity but have their own bounded cache. */
    val readAlongCache: ReadAlongCache = ReadAlongCache(repository)
        .attachDiskCache(downloads::readAlongCacheOrNull)

    /** Local fallback first, account GET/PATCH synchronization whenever the server supports it. */
    val readerPreferences: ReaderPreferencesStore = readerPreferencesFactory(repository, dispatchers)

    /** Remembered window size/position/maximised state. Never holds anything transient or secret. */
    val windowPreferences: WindowPreferencesStore = windowPreferencesStore

    /**
     * The one window-behaviour flag a screen can change while the window is open.
     *
     * A flow rather than a re-read of `window.json`, because two places need the same answer at the
     * same moment and neither owns it: Settings writes it, and `Main`'s close handler and tray read
     * it. Written through to the file immediately so the choice survives a crash, and republished so
     * the toggle reflects what was actually stored rather than what was clicked.
     */
    private val _closeToTray = MutableStateFlow(windowPreferencesStore.load().closeToTray)
    val closeToTray: StateFlow<Boolean> = _closeToTray.asStateFlow()

    fun setCloseToTray(enabled: Boolean) {
        if (_closeToTray.value == enabled) return
        _closeToTray.value = enabled
        windowPreferences.save(windowPreferences.load().copy(closeToTray = enabled))
    }

    /** Records that the "still playing in the tray" notice has been shown, and whether to show it. */
    fun consumeTrayNotice(): Boolean {
        val stored = windowPreferences.load()
        if (stored.trayNoticeShown) return false
        windowPreferences.save(stored.copy(trayNoticeShown = true))
        return true
    }

    /**
     * Looks for a newer published build. On the shared HTTP client on purpose: the auth
     * interceptor's same-origin rule is what keeps the TTSRoad bearer token off api.github.com,
     * and a second client would put this call outside that rule.
     */
    val updateChecker: UpdateChecker = UpdateChecker(
        source = releaseSourceFactory(httpClient),
        settingsStore = updateSettings,
        clock = clock,
    )

    /**
     * Downloads go to the cache root: a rebuildable installer is not user data, and one left behind
     * by an update the user never ran should be evictable.
     */
    val updateDownloader: UpdateDownloader = UpdateDownloader(
        client = httpClient,
        targetDirectory = File(AppDirectories.cacheDir(), "updates"),
    )

    /** Called when the main window closes; without it the playback job and temp file outlive it. */
    override fun close() {
        // Off the bus before the transport it mirrors goes away, so a panel applet never holds a
        // name that answers with a half-released player.
        runCatching { mpris?.close() }
        mpris = null
        mprisScope.cancel()
        runCatching { downloads.close() }
        playback.release()
        this.playbackHistory.close()
        libraryCache.close()
        readAlongCache.clear()
        readerPreferences.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        runCatching { httpClient.cache?.close() }
    }

    companion object {
        /**
         * ONE OkHttp instance for the whole app: JSON API calls, chapter audio downloads, and
         * Coil's cover-image fetches. Previously each of those built its own client with its own
         * connection pool, thread pool and (differing) timeouts — and only two of the three knew
         * how to authenticate, which is exactly the seam this interceptor closes.
         *
         * No call timeout is set on purpose — a chapter MP3 can legitimately take minutes on a
         * slow link, and the read timeout already bounds a stalled connection.
         */
        fun defaultHttpClient(sessionStore: SessionStore): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(TtsRoadAuthInterceptor { sessionStore.current().bearerCredentials })
            .build()
    }
}
