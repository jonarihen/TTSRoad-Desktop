package dk.perspektiva.ttsroad.desktop.di

import dk.perspektiva.ttsroad.desktop.data.FileSessionStore
import dk.perspektiva.ttsroad.desktop.data.RetrofitTtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.AudioDownloadStore
import dk.perspektiva.ttsroad.desktop.player.AudioEngine
import dk.perspektiva.ttsroad.desktop.player.HttpAudioDownloadStore
import dk.perspektiva.ttsroad.desktop.player.JavaSoundAudioEngine
import dk.perspektiva.ttsroad.desktop.player.Mp3PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
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
 * an HTTP client, or a playback controller.
 *
 * Everything is a constructor parameter with a production default, so a test builds a fully
 * substituted container in one expression. `clock` is here rather than being read from
 * `System.currentTimeMillis()` at call sites so time-dependent behaviour stays testable as this
 * grows (capability TTLs, token expiry).
 */
class AppContainer(
    val dispatchers: AppDispatchers = AppDispatchers.Default,
    val clock: () -> Long = System::currentTimeMillis,
    val httpClient: OkHttpClient = defaultHttpClient(),
    val sessionStore: SessionStore = FileSessionStore(),
    repositoryFactory: (SessionStore, OkHttpClient, AppDispatchers) -> TtsRoadRepository =
        { store, client, d -> RetrofitTtsRoadRepository(store, client, d.io) },
    downloadStoreFactory: (OkHttpClient, TtsRoadRepository) -> AudioDownloadStore =
        { client, repo -> HttpAudioDownloadStore(client, repo) },
    audioEngine: AudioEngine = JavaSoundAudioEngine(),
    playbackFactory: (TtsRoadRepository, AudioDownloadStore, AudioEngine, AppDispatchers) -> PlaybackController =
        { repo, downloads, engine, d -> Mp3PlaybackController(repo, downloads, engine, d.io) },
) : AutoCloseable {
    val repository: TtsRoadRepository = repositoryFactory(sessionStore, httpClient, dispatchers)
    val downloadStore: AudioDownloadStore = downloadStoreFactory(httpClient, repository)
    val playback: PlaybackController = playbackFactory(repository, downloadStore, audioEngine, dispatchers)

    /** Called when the main window closes; without it the playback job and temp file outlive it. */
    override fun close() {
        playback.release()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        runCatching { httpClient.cache?.close() }
    }

    companion object {
        /**
         * ONE OkHttp instance for the whole app: JSON API calls, chapter audio downloads, and
         * Coil's cover-image fetches. Previously each of those built its own client with its own
         * connection pool, thread pool and (differing) timeouts.
         *
         * No call timeout is set on purpose — a chapter MP3 can legitimately take minutes on a
         * slow link, and the read timeout already bounds a stalled connection.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
