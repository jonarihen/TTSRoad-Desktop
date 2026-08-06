package dk.perspektiva.ttsroad.desktop

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.PlaybackMarkResponse
import dk.perspektiva.ttsroad.desktop.data.PlaybackProgressResponse
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Repository fake for tests that care about *what the UI does with a result*, not about HTTP.
 * Tests that care about the wire use [RetrofitTtsRoadRepository][
 * dk.perspektiva.ttsroad.desktop.data.RetrofitTtsRoadRepository] against a MockWebServer instead.
 */
open class FakeRepository(
    var loginResult: LoginResult = LoginResult.Success,
    var libraryResult: Result<LibraryResponse> = Result.success(LibraryResponse()),
    var chaptersResult: Result<ChaptersResponse> = Result.success(ChaptersResponse(fiction = FictionSummary())),
    var serverUrl: String = "https://ttsroad.example.com/",
    var capabilitiesResult: ServerCapabilities = ServerCapabilities.Baseline,
) : TtsRoadRepository {
    var loginCalls: Int = 0
        private set
    var lastLoginTotp: String? = null
        private set
    var logoutCalls: Int = 0
        private set
    var libraryCalls: Int = 0
        private set
    var chaptersCalls: Int = 0
        private set

    /** Base URLs discovery was asked about, in order — capability probing is observable. */
    val capabilityProbes: MutableList<String> = mutableListOf()
    val markedPlayed: MutableList<Pair<List<Int>, Boolean>> = mutableListOf()
    val savedProgress: MutableList<Triple<Int, Double, Boolean>> = mutableListOf()

    private val _currentCapabilities = MutableStateFlow(ServerCapabilities.Baseline)
    override val currentCapabilities: StateFlow<ServerCapabilities> = _currentCapabilities.asStateFlow()

    private val _sessionEnd = MutableStateFlow<SessionEnd?>(null)
    override val sessionEnd: StateFlow<SessionEnd?> = _sessionEnd.asStateFlow()

    override suspend fun capabilities(baseUrl: String, forceRefresh: Boolean): ServerCapabilities {
        capabilityProbes += baseUrl
        return capabilitiesResult
    }

    override suspend fun refreshCurrentCapabilities(forceRefresh: Boolean): ServerCapabilities =
        capabilitiesResult.also { _currentCapabilities.value = it }

    override fun forgetCapabilities(baseUrl: String) {
        _currentCapabilities.value = ServerCapabilities.Baseline
    }

    override suspend fun endSession(end: SessionEnd) {
        _sessionEnd.value = end
    }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String?,
    ): LoginResult {
        loginCalls++
        lastLoginTotp = totpCode
        return loginResult
    }

    override suspend fun logout() {
        logoutCalls++
    }

    override suspend fun library(): LibraryResponse {
        libraryCalls++
        return libraryResult.getOrThrow()
    }

    override suspend fun chapters(fictionId: Int, playableOnly: Boolean): ChaptersResponse {
        chaptersCalls++
        return chaptersResult.getOrThrow()
    }

    override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse {
        markedPlayed += chapterIds to played
        return PlaybackMarkResponse(status = "ok", played = played, chapterIds = chapterIds, count = chapterIds.size)
    }

    override suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse {
        savedProgress += Triple(chapterId, positionSeconds, isPlayed)
        return PlaybackProgressResponse(status = "saved", chapterId = chapterId)
    }

    override fun authHeaderValue(): String? = "Bearer test-token"

    override fun resolveUrl(url: String): String =
        if (url.startsWith("http", ignoreCase = true)) url else serverUrl.trimEnd('/') + url
}

/**
 * Playback fake for UI tests: records the transport calls the UI makes and lets the test push an
 * arbitrary [PlayerUiState] in, without ever opening an audio device or a socket.
 */
class FakePlaybackController(initial: PlayerUiState = PlayerUiState()) : PlaybackController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val calls: MutableList<String> = mutableListOf()

    fun emit(state: PlayerUiState) {
        _state.value = state
    }

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        calls += "play(${chapter.resolvedChapterId})"
    }

    override suspend fun playQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary?,
    ) {
        calls += "playQueue($startChapterId)"
    }

    override fun togglePlayPause() {
        calls += "togglePlayPause"
        _state.update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun skipBy(deltaMs: Long) {
        calls += "skipBy($deltaMs)"
    }

    override fun skipToNextChapter() {
        calls += "next"
    }

    override fun skipToPreviousChapter() {
        calls += "previous"
    }

    override fun skipToQueueIndex(index: Int) {
        calls += "queueIndex($index)"
    }

    override fun setSpeed(speed: Float) {
        calls += "speed($speed)"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun release() {
        calls += "release"
    }
}

/** `RecordedRequest.body` is nullable in mockwebserver3; tests always want the text. */
fun mockwebserver3.RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/**
 * An OkHttp client wired exactly the way the app wires it — one auth interceptor reading [store].
 *
 * Repository tests must not hand-build a bare client: since Phase 1 the `Authorization` header is
 * the interceptor's job, so a bare client would quietly assert against unauthenticated requests.
 */
fun authedClient(store: dk.perspektiva.ttsroad.desktop.data.SessionStore): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder()
        .addInterceptor(
            dk.perspektiva.ttsroad.desktop.data.TtsRoadAuthInterceptor { store.current().bearerCredentials },
        )
        .build()

/** [CommandRunner] that records what it was asked to run and replays canned results. */
class FakeCommandRunner(
    private val results: MutableMap<String, dk.perspektiva.ttsroad.desktop.security.CommandResult> = mutableMapOf(),
    private var fallback: dk.perspektiva.ttsroad.desktop.security.CommandResult =
        dk.perspektiva.ttsroad.desktop.security.CommandResult(0, "", ""),
) : dk.perspektiva.ttsroad.desktop.security.CommandRunner {
    /** Every invocation: the argv it was given and whatever was written to stdin. */
    val invocations: MutableList<Pair<List<String>, String?>> = mutableListOf()

    /** Canned result for the first argv element after the executable, e.g. "store" / "lookup". */
    fun on(verb: String, result: dk.perspektiva.ttsroad.desktop.security.CommandResult) = apply {
        results[verb] = result
    }

    fun default(result: dk.perspektiva.ttsroad.desktop.security.CommandResult) = apply { fallback = result }

    override fun run(
        command: List<String>,
        stdin: String?,
    ): dk.perspektiva.ttsroad.desktop.security.CommandResult {
        invocations += command to stdin
        return results[command.getOrNull(1)] ?: fallback
    }
}

/** [CredentialStore] that can be told to fail, so the migration's failure path is reachable. */
class FailingCredentialStore(
    override val id: String = "failing",
    override val displayName: String = "Failing store",
    override val persistsAcrossRestarts: Boolean = true,
) : dk.perspektiva.ttsroad.desktop.security.CredentialStore {
    override fun store(key: String, secret: String): Unit =
        throw dk.perspektiva.ttsroad.desktop.security.CredentialStoreException("nope")

    override fun retrieve(key: String): String? = null

    override fun delete(key: String) = Unit
}
