package dk.perspektiva.ttsroad.desktop

import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.BookmarkCreateRequest
import dk.perspektiva.ttsroad.desktop.data.BookmarkPatchRequest
import dk.perspektiva.ttsroad.desktop.data.AudiobookExportsResponse
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationsResponse
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.CoverUploadResult
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.DeviceSession
import dk.perspektiva.ttsroad.desktop.data.DeltaSyncResponse
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.FictionCreateRequest
import dk.perspektiva.ttsroad.desktop.data.FictionUpdateRequest
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import dk.perspektiva.ttsroad.desktop.data.ChapterRetryOutcome
import dk.perspektiva.ttsroad.desktop.data.FictionMaintenanceAction
import dk.perspektiva.ttsroad.desktop.data.MaintenanceResponse
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.PlaybackMarkResponse
import dk.perspektiva.ttsroad.desktop.data.PlaybackStateRow
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.ServerQueueRequest
import dk.perspektiva.ttsroad.desktop.data.ServerQueueResponse
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
    /** Null means use [libraryResult], which models an older endpoint returning a full response. */
    var libraryDeltaResult: Result<LibraryResponse>? = null,
    /** Null means use [chaptersResult], which models an older endpoint returning a full response. */
    var chaptersDeltaResult: Result<ChaptersResponse>? = null,
    /** `success(null)` is the server saying it has no delta index. */
    var deltaSyncResult: Result<DeltaSyncResponse?> = Result.success(null),
    var serverUrl: String = "https://ttsroad.example.com/",
    var capabilitiesResult: ServerCapabilities = ServerCapabilities.Baseline,
    /** `success(null)` is the server saying it has no device API — not "no devices". */
    var devicesResult: Result<List<DeviceSession>?> = Result.success(emptyList()),
    var revokeResult: Result<Boolean> = Result.success(true),
    var currentUserResult: Result<MobileUser?> = Result.success(null),
    var readAlongResult: Result<dk.perspektiva.ttsroad.desktop.data.ReadAlongFetchResult> = Result.success(
        dk.perspektiva.ttsroad.desktop.data.ReadAlongFetchResult.NotFound,
    ),
    var readerPreferencesResult: Result<dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesResponse?> =
        Result.success(null),
    /** `success(null)` is the server saying it cannot search — not "nothing matched". */
    var searchResult: Result<dk.perspektiva.ttsroad.desktop.data.SearchResponse?> = Result.success(null),
    /** What `scope=all` answers. Defaults to the same payload the shelf gives. */
    var browseAllResult: Result<LibraryResponse> = Result.success(LibraryResponse()),
    /** Null means "echo what was asked". `success(null)` is the server's 404. */
    var followResult: Result<Boolean?>? = null,
    /**
     * Per-fiction override, consulted before [followResult].
     *
     * A bulk unfollow's interesting case is the *partial* one — some ids succeed and one does not —
     * which a single shared answer cannot express.
     */
    var followResultFor: ((Int) -> Result<Boolean?>?)? = null,
    /** `success(null)` is the server saying it has no bookmark API — not "no bookmarks". */
    var bookmarksResult: Result<List<Bookmark>?> = Result.success(emptyList()),
    var createBookmarkResult: Result<Bookmark?> = Result.success(Bookmark(id = 1)),
    var updateBookmarkResult: Result<Bookmark?> = Result.success(Bookmark(id = 1)),
    var deleteBookmarkResult: Result<Boolean> = Result.success(true),
    /** `success(null)` is the server saying it has no queue API — not "the queue is empty". */
    var queueResult: Result<ServerQueueResponse?> = Result.success(ServerQueueResponse()),
    var createFictionResult: Result<FictionSummary> = Result.success(FictionSummary(id = 101, title = "Added")),
    var updateFictionResult: Result<FictionSummary> = Result.success(FictionSummary(id = 1, title = "Updated")),
    var deleteFictionResult: Result<Boolean> = Result.success(true),
    var uploadEpubResult: Result<FictionSummary> = Result.success(FictionSummary(id = 202, title = "Uploaded")),
    /** Defaults to the server accepting the image and answering the fiction it now holds. */
    var uploadCoverResult: Result<CoverUploadResult> =
        Result.success(CoverUploadResult.Saved(FictionSummary(id = 1, title = "Updated"))),
    var audiobookExportsResult: Result<AudiobookExportsResponse?> = Result.success(null),
    var voicesResult: Result<List<MobileVoice>?> = Result.success(null),
    var retryChapterResult: Result<ChapterRetryOutcome> = Result.success(ChapterRetryOutcome.Unsupported),
    var setChapterExcludedResult: Result<Boolean?>? = null,
    var deleteChapterResult: Result<Boolean?> = Result.success(null),
    var fictionMaintenanceResult: Result<MaintenanceResponse?> = Result.success(null),
    /** Null models a server whose notifications route answers 404. */
    var chapterNotificationsResult: Result<ChapterNotificationsResponse?> = Result.success(null),
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
    val libraryDeltaCursors: MutableList<String> = mutableListOf()
    val chapterDeltaCalls: MutableList<Pair<Int, String>> = mutableListOf()
    val deltaSyncCursors: MutableList<String> = mutableListOf()
    var devicesCalls: Int = 0
        private set
    var currentUserCalls: Int = 0
        private set
    var audiobookExportsCalls: Int = 0
        private set
    var voicesCalls: Int = 0
        private set
    val retriedChapters: MutableList<Int> = mutableListOf()
    val excludedChapters: MutableList<Pair<Int, Boolean>> = mutableListOf()
    val deletedChapters: MutableList<Int> = mutableListOf()
    val fictionMaintenanceCalls: MutableList<Pair<Int, FictionMaintenanceAction>> = mutableListOf()
    var revokeOtherDevicesCalls: Int = 0
        private set
    var readAlongCalls: Int = 0
        private set
    val readAlongEtags: MutableList<String?> = mutableListOf()
    val readerPreferencePatches: MutableList<dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesPatch> =
        mutableListOf()

    /** Token ids passed to [revokeDevice], in order — "the current session was never revoked". */
    val revokedDevices: MutableList<Int> = mutableListOf()

    /** Base URLs discovery was asked about, in order — capability probing is observable. */
    val capabilityProbes: MutableList<String> = mutableListOf()
    /** Queries the server was actually asked, in order — trimming and debouncing are observable. */
    val searchQueries: MutableList<String> = mutableListOf()

    /** Scopes the library was asked for, in order — browse-all is a different request. */
    val libraryScopes: MutableList<dk.perspektiva.ttsroad.desktop.data.LibraryScope> = mutableListOf()

    /** `(fictionId, following)` pairs passed to [setFollowing], in order. */
    val followCalls: MutableList<Pair<Int, Boolean>> = mutableListOf()
    var queueCalls: Int = 0
        private set

    /** Every queue mutation body, in order — the action and what it addressed are both observable. */
    val queueRequests: MutableList<ServerQueueRequest> = mutableListOf()
    val markedPlayed: MutableList<Pair<List<Int>, Boolean>> = mutableListOf()
    val savedProgress: MutableList<Triple<Int, Double, Boolean>> = mutableListOf()

    /** Bookmark traffic, in order — the `kind` filter is part of what the tests assert. */
    val bookmarkListCalls: MutableList<Pair<String?, Int?>> = mutableListOf()
    val createdBookmarks: MutableList<BookmarkCreateRequest> = mutableListOf()
    val patchedBookmarks: MutableList<Pair<Int, BookmarkPatchRequest>> = mutableListOf()
    val deletedBookmarks: MutableList<Int> = mutableListOf()
    val createdFictions: MutableList<FictionCreateRequest> = mutableListOf()
    val updatedFictions: MutableList<Pair<Int, FictionUpdateRequest>> = mutableListOf()
    val deletedFictions: MutableList<Int> = mutableListOf()
    val uploadedEpubs: MutableList<Pair<java.io.File, String?>> = mutableListOf()

    /** Notification ids passed to [dismissChapterNotification], in order. */
    val dismissedNotifications: MutableList<Int> = mutableListOf()
    var dismissReadCalls: Int = 0
    var chapterNotificationCalls: Int = 0

    /** `(fictionId, file)` pairs passed to [uploadFictionCover], in order. */
    val uploadedCovers: MutableList<Pair<Int, java.io.File>> = mutableListOf()

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

    override suspend fun library(
        scope: dk.perspektiva.ttsroad.desktop.data.LibraryScope,
    ): LibraryResponse {
        libraryCalls++
        libraryScopes += scope
        return (if (scope == dk.perspektiva.ttsroad.desktop.data.LibraryScope.All) browseAllResult else libraryResult)
            .getOrThrow()
    }

    override suspend fun libraryDelta(updatedSince: String): LibraryResponse {
        libraryDeltaCursors += updatedSince
        return (libraryDeltaResult ?: libraryResult).getOrThrow()
    }

    override suspend fun deltaSync(updatedSince: String): DeltaSyncResponse? {
        deltaSyncCursors += updatedSince
        return deltaSyncResult.getOrThrow()
    }

    override suspend fun setFollowing(fictionId: Int, following: Boolean): Boolean? {
        followCalls += fictionId to following
        // An *unset* `followResult` echoes what was asked, which is what a healthy server does.
        // A set one is used as-is, null included — `success(null)` is the server's 404 and must not
        // fall through to the echo.
        val configured = followResultFor?.invoke(fictionId) ?: followResult ?: return following
        return configured.getOrThrow()
    }

    override suspend fun createFiction(request: FictionCreateRequest): FictionSummary {
        createdFictions += request
        return createFictionResult.getOrThrow()
    }

    override suspend fun updateFiction(fictionId: Int, request: FictionUpdateRequest): FictionSummary {
        updatedFictions += fictionId to request
        return updateFictionResult.getOrThrow()
    }

    override suspend fun deleteFiction(fictionId: Int): Boolean {
        deletedFictions += fictionId
        return deleteFictionResult.getOrThrow()
    }

    override suspend fun uploadEpub(file: java.io.File, voice: String?): FictionSummary {
        uploadedEpubs += file to voice
        return uploadEpubResult.getOrThrow()
    }

    override suspend fun chapterNotifications(): ChapterNotificationsResponse? {
        chapterNotificationCalls++
        return chapterNotificationsResult.getOrThrow()
    }

    override suspend fun dismissChapterNotification(notificationId: Int): Boolean {
        dismissedNotifications += notificationId
        return true
    }

    override suspend fun dismissReadChapterNotifications(): Boolean {
        dismissReadCalls++
        return true
    }

    override suspend fun uploadFictionCover(fictionId: Int, file: java.io.File): CoverUploadResult {
        uploadedCovers += fictionId to file
        return uploadCoverResult.getOrThrow()
    }

    override suspend fun currentUser(): MobileUser? {
        currentUserCalls++
        return currentUserResult.getOrThrow()
    }

    override suspend fun devices(): List<DeviceSession>? {
        devicesCalls++
        return devicesResult.getOrThrow()
    }

    override suspend fun audiobookExports(): AudiobookExportsResponse? {
        audiobookExportsCalls++
        return audiobookExportsResult.getOrThrow()
    }

    override suspend fun voices(): List<MobileVoice>? {
        voicesCalls++
        return voicesResult.getOrThrow()
    }

    override suspend fun retryChapter(chapterId: Int): ChapterRetryOutcome {
        retriedChapters += chapterId
        return retryChapterResult.getOrThrow()
    }

    override suspend fun setChapterExcluded(chapterId: Int, excluded: Boolean): Boolean? {
        excludedChapters += chapterId to excluded
        // Unset echoes what was asked, like `setFollowing`. A set one is used as-is, null included.
        return (setChapterExcludedResult ?: return excluded).getOrThrow()
    }

    override suspend fun deleteChapter(chapterId: Int): Boolean? {
        deletedChapters += chapterId
        return deleteChapterResult.getOrThrow()
    }

    override suspend fun runFictionMaintenance(
        fictionId: Int,
        action: FictionMaintenanceAction,
    ): MaintenanceResponse? {
        fictionMaintenanceCalls += fictionId to action
        return fictionMaintenanceResult.getOrThrow()
    }

    override suspend fun revokeDevice(tokenId: Int): Boolean {
        revokedDevices += tokenId
        return revokeResult.getOrThrow()
    }

    override suspend fun revokeOtherDevices(): Boolean {
        revokeOtherDevicesCalls++
        return revokeResult.getOrThrow()
    }

    override suspend fun chapters(fictionId: Int, playableOnly: Boolean): ChaptersResponse {
        chaptersCalls++
        return chaptersResult.getOrThrow()
    }

    override suspend fun chaptersDelta(fictionId: Int, updatedSince: String): ChaptersResponse {
        chapterDeltaCalls += fictionId to updatedSince
        return (chaptersDeltaResult ?: chaptersResult).getOrThrow()
    }

    override suspend fun search(
        query: String,
        limit: Int,
        offset: Int,
    ): dk.perspektiva.ttsroad.desktop.data.SearchResponse? {
        searchQueries += query
        return searchResult.getOrThrow()
    }

    override suspend fun readAlong(
        chapterId: Int,
        ifNoneMatch: String?,
    ): dk.perspektiva.ttsroad.desktop.data.ReadAlongFetchResult {
        readAlongCalls++
        readAlongEtags += ifNoneMatch
        return readAlongResult.getOrThrow()
    }

    override suspend fun readerPreferences(): dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesResponse? =
        readerPreferencesResult.getOrThrow()

    override suspend fun updateReaderPreferences(
        request: dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesPatch,
    ): dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesResponse? {
        readerPreferencePatches += request
        return readerPreferencesResult.getOrThrow()
    }

    override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse {
        markedPlayed += chapterIds to played
        return PlaybackMarkResponse(status = "ok", played = played, chapterIds = chapterIds, count = chapterIds.size)
    }

    override suspend fun bookmarks(kind: String?, fictionId: Int?): List<Bookmark>? {
        bookmarkListCalls += kind to fictionId
        return bookmarksResult.getOrThrow()
    }

    override suspend fun createBookmark(request: BookmarkCreateRequest): Bookmark? {
        createdBookmarks += request
        return createBookmarkResult.getOrThrow()?.copy(
            chapterId = request.chapterId,
            positionSeconds = request.positionSeconds,
            label = request.label,
            note = request.note,
        )
    }

    override suspend fun updateBookmark(bookmarkId: Int, request: BookmarkPatchRequest): Bookmark? {
        patchedBookmarks += bookmarkId to request
        return updateBookmarkResult.getOrThrow()?.copy(
            id = bookmarkId,
            label = request.label,
            note = request.note,
        )
    }

    override suspend fun deleteBookmark(bookmarkId: Int): Boolean {
        deletedBookmarks += bookmarkId
        return deleteBookmarkResult.getOrThrow()
    }

    override suspend fun serverQueue(): ServerQueueResponse? {
        queueCalls++
        return queueResult.getOrThrow()
    }

    override suspend fun updateServerQueue(request: ServerQueueRequest): ServerQueueResponse? {
        queueRequests += request
        return queueResult.getOrThrow()
    }

    override suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ) {
        savedProgress += Triple(chapterId, positionSeconds, isPlayed)
    }

    /** Positions the server would report back; set by a test that wants a losing write reconciled. */
    val serverPlaybackStateFlow = MutableStateFlow<Map<Int, PlaybackStateRow>>(emptyMap())
    override val serverPlaybackState: StateFlow<Map<Int, PlaybackStateRow>> = serverPlaybackStateFlow

    var flushCount: Int = 0
        private set

    override suspend fun flushProgress() {
        flushCount++
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
        startPositionMs: Long?,
    ) {
        calls += if (startPositionMs == null) {
            "playQueue($startChapterId)"
        } else {
            "playQueue($startChapterId@$startPositionMs)"
        }
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

    override fun clearFictionSpeed() {
        calls += "clearFictionSpeed"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun release() {
        calls += "release"
    }
}

/**
 * A [dk.perspektiva.ttsroad.desktop.data.LibraryCache] for a Compose UI test.
 *
 * `Dispatchers.Main.immediate` rather than plain `Dispatchers.Main`, deliberately: a plain main
 * dispatch is an `invokeLater` on the Swing queue, which `waitForIdle` does not track — the test
 * would then assert against whatever happened to have run, and pass or fail depending on machine
 * load. Immediate dispatch runs the load inline against the fake repository, so the UI the test
 * inspects is the UI that load produced.
 */
fun testLibraryCache(
    repository: TtsRoadRepository,
    clock: () -> Long = System::currentTimeMillis,
): dk.perspektiva.ttsroad.desktop.data.LibraryCache =
    dk.perspektiva.ttsroad.desktop.data.LibraryCache(
        repository,
        kotlinx.coroutines.Dispatchers.Main.immediate,
        clock,
    )

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
