package dk.perspektiva.ttsroad.desktop.di

import dk.perspektiva.ttsroad.desktop.data.FileSessionStore
import dk.perspektiva.ttsroad.desktop.data.FileWindowPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.RetrofitTtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadAuthInterceptor
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.WindowPreferencesStore
import dk.perspektiva.ttsroad.desktop.player.GstPlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.HttpMediaSource
import dk.perspektiva.ttsroad.desktop.player.JavaSoundPlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.MediaSourceFactory
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlaybackEngine
import dk.perspektiva.ttsroad.desktop.player.QueuePlaybackController
import dk.perspektiva.ttsroad.desktop.security.CredentialStore
import dk.perspektiva.ttsroad.desktop.security.CredentialStores
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
        { client, repo -> MediaSourceFactory { url -> HttpMediaSource(client, repo, url) } },
    // GStreamer where it exists, Java Sound where it does not. Resolved once, here, so nothing
    // downstream has to know which backend it got — only what that backend reports it can do.
    // A machine with no GStreamer must still get a working app, just without speed control.
    audioEngineFactory: () -> PlaybackEngine =
        { GstPlaybackEngine.createOrNull() ?: JavaSoundPlaybackEngine() },
    playbackFactory: (TtsRoadRepository, MediaSourceFactory, PlaybackEngine, AppDispatchers) -> PlaybackController =
        { repo, mediaSources, engine, d -> QueuePlaybackController(repo, mediaSources, engine, d.io) },
    libraryCacheFactory: (TtsRoadRepository, AppDispatchers, () -> Long) -> LibraryCache =
        { repo, d, now -> LibraryCache(repo, d.main, now) },
    // A store rather than a file path, so a test never writes into the user's config directory.
    windowPreferencesStore: WindowPreferencesStore = FileWindowPreferencesStore(),
) : AutoCloseable {
    val httpClient: OkHttpClient = httpClientFactory(sessionStore)
    val repository: TtsRoadRepository = repositoryFactory(sessionStore, httpClient, dispatchers)
    val mediaSources: MediaSourceFactory = mediaSourceFactory(httpClient, repository)
    val audioEngine: PlaybackEngine = audioEngineFactory()
    val playback: PlaybackController = playbackFactory(repository, mediaSources, audioEngine, dispatchers)

    /**
     * Library and chapter data, held here rather than inside a screen.
     *
     * That placement is the whole point: its lifetime is the signed-in session, so navigating away
     * from the library and back shows what was already loaded instead of re-fetching it. It is
     * emptied by `App` when the session ends.
     */
    val libraryCache: LibraryCache = libraryCacheFactory(repository, dispatchers, clock)

    /** Remembered window size/position/maximised state. Never holds anything transient or secret. */
    val windowPreferences: WindowPreferencesStore = windowPreferencesStore

    /** Called when the main window closes; without it the playback job and temp file outlive it. */
    override fun close() {
        playback.release()
        libraryCache.close()
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
