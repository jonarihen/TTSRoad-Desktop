package dk.perspektiva.ttsroad.desktop

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.PlaybackMarkResponse
import dk.perspektiva.ttsroad.desktop.data.PlaybackProgressResponse
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
    val markedPlayed: MutableList<Pair<List<Int>, Boolean>> = mutableListOf()
    val savedProgress: MutableList<Triple<Int, Double, Boolean>> = mutableListOf()

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

    override fun authHeaderValue(): String = "Bearer test-token"

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
