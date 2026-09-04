package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * How long a discovered capability set is trusted before it is re-asked. Long enough that
 * discovery is not a per-screen cost, short enough that a server upgraded under a long-running
 * desktop process is noticed the same day.
 */
private val CapabilityTtlMillis = TimeUnit.HOURS.toMillis(6)

/**
 * Batch size used when the server has `/playback/sync` but published no limit. Matches the
 * backend's own `MAX_PLAYBACK_SYNC_ITEMS`, and is a floor rather than a guess: sending fewer items
 * than allowed only costs an extra round trip, while sending more loses the whole batch to a 400.
 */
private const val DefaultMaxPlaybackSyncItems = 500

/** What an EPUB is. The server checks the *filename*, but a correct type costs nothing. */
private val EpubMediaType = "application/epub+zip".toMediaType()
private val TextMediaType = "text/plain".toMediaType()

/** Outcome of a login attempt. */
sealed interface LoginResult {
    data object Success : LoginResult

    /** Password accepted, a valid 2FA code is still required; resubmit with `totpCode`. */
    data object TotpRequired : LoginResult

    /**
     * The server is throttling this username or this client IP (HTTP 429).
     *
     * Modelled separately from [Failure] because it is the one failure where retrying *will* work
     * and the server said when — so the UI can stop the user hammering the button and burning
     * further attempts against the same counter.
     */
    data class RateLimited(val message: String, val retryAfterSeconds: Int?) : LoginResult

    data class Failure(val message: String) : LoginResult
}

/**
 * What a cover upload did.
 *
 * Typed rather than thrown for the same reason [LoginResult] is: "the server read this request and
 * said no" is not a failed request, and the three answers below want three different sentences on
 * screen. Anything else — a 500, a dropped connection, an expired session — still propagates as an
 * exception, because none of those say anything about the image.
 */
sealed interface CoverUploadResult {
    /** The server accepted it and answered the fiction as it now stands. */
    data class Saved(val fiction: FictionSummary) : CoverUploadResult

    /**
     * The route answered 404.
     *
     * Deliberately one case for two causes: a server older than cover upload and a fiction that is
     * no longer there are indistinguishable on the wire, and both mean the cover did not change.
     */
    data object Unsupported : CoverUploadResult

    /** The server refused the image itself — too large, not an image, not this account's to change. */
    data class Rejected(val message: String) : CoverUploadResult
}

/** Said only where the server refused without explaining itself, which older proxies manage to do. */
private fun coverRejectionFor(code: Int): String = when (code) {
    413 -> "That image is larger than this server accepts"
    403 -> "This account may not change cover art"
    else -> "The server did not accept that image"
}

/**
 * Seam for everything the UI needs from the TTSRoad server. [RetrofitTtsRoadRepository] is the
 * production implementation; tests either drive that one against a MockWebServer or substitute a
 * hand-written fake.
 */
interface TtsRoadRepository {
    /** What the signed-in server supports. [ServerCapabilities.Baseline] until discovery lands. */
    val currentCapabilities: StateFlow<ServerCapabilities>

    /** Why the stored credential was dropped, or null while the session is usable. */
    val sessionEnd: StateFlow<SessionEnd?>

    /**
     * What the server says it holds for chapters this client has written to, as of the last flush.
     *
     * This is how a losing write gets noticed. `/playback/sync` returns the server's own state for
     * every chapter in a batch, so when an offline position loses to a newer one from the browser,
     * the newer position is here — and playback can resume from it rather than the stale local one.
     */
    val serverPlaybackState: StateFlow<Map<Int, PlaybackStateRow>>

    /**
     * Send everything queued but unsent. Safe to call when there is nothing to do.
     *
     * Separate from [saveProgress] so a reconnect — app launch, session restored — can drain a
     * queue left behind by an earlier run without first having to play something.
     */
    suspend fun flushProgress()

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String? = null,
    ): LoginResult

    suspend fun logout()

    /**
     * Ask [baseUrl] which optional features it supports. Never throws and never signs anyone out —
     * it is called against a half-typed URL from the login screen.
     */
    suspend fun capabilities(baseUrl: String, forceRefresh: Boolean = false): ServerCapabilities

    /** Re-ask the signed-in server and publish to [currentCapabilities]. */
    suspend fun refreshCurrentCapabilities(forceRefresh: Boolean = false): ServerCapabilities

    /** Drop discovered capabilities for [baseUrl] so the next call re-asks. */
    fun forgetCapabilities(baseUrl: String)

    /**
     * Drop the session because the server refused the credential.
     *
     * Public because the audio path reaches the same conclusion from outside this class: a 401 on
     * `/audio/…` means exactly what a 401 on an API call means, and both must land on the same
     * login screen with the same explanation.
     */
    suspend fun endSession(end: SessionEnd)

    suspend fun library(scope: LibraryScope = LibraryScope.Followed): LibraryResponse

    /** The changed shelf rows and complete listening rails since a server-issued cursor. */
    suspend fun libraryDelta(updatedSince: String): LibraryResponse = library()

    /** The cheap delta index, or null when this server predates it. */
    suspend fun deltaSync(updatedSince: String): DeltaSyncResponse? = null

    suspend fun createFiction(request: FictionCreateRequest): FictionSummary =
        error("fiction management is not implemented")

    suspend fun updateFiction(fictionId: Int, request: FictionUpdateRequest): FictionSummary =
        error("fiction management is not implemented")

    suspend fun deleteFiction(fictionId: Int): Boolean = false

    /** Uploads one EPUB and answers the fiction the server created from it. */
    suspend fun uploadEpub(file: File, voice: String? = null): FictionSummary =
        error("EPUB upload is not implemented")

    /**
     * Replaces a fiction's cover art with a local image file.
     *
     * Defaults to [CoverUploadResult.Unsupported] so a substituted repository that has never heard
     * of the route behaves like a server that has never heard of it either.
     */
    suspend fun uploadFictionCover(fictionId: Int, file: File): CoverUploadResult =
        CoverUploadResult.Unsupported

    /**
     * Follows or unfollows a fiction, answering the state **the server now holds**, or null when it
     * answered 404.
     *
     * The 404 is genuinely ambiguous — no such fiction, or no such endpoint — and both mean the
     * same thing to the caller: the toggle did not happen and must not render as though it did.
     */
    suspend fun setFollowing(fictionId: Int, following: Boolean): Boolean?

    /**
     * The signed-in account as the server sees it now, or null when the server has no `/me`.
     *
     * Worth asking even though login already returned a user: `is_admin` can be changed from the
     * web console while a desktop session is open, and this is the cheapest way to notice.
     */
    suspend fun currentUser(): MobileUser?

    /**
     * Every session on this account, or **null when the server has no device-management API**.
     *
     * Null rather than an empty list because the two mean opposite things to the UI: "nothing else
     * is signed in" is a normal, correct answer, while "this server cannot answer" has to become a
     * concise unsupported state instead of an HTTP error the user cannot act on.
     */
    suspend fun devices(): List<DeviceSession>?

    /** Finished M4B exports, or null when this server predates the read-only mobile surface. */
    suspend fun audiobookExports(): AudiobookExportsResponse? = null

    /**
     * The narrator catalogue, or null when this server has no `/voices` route.
     *
     * Null and empty differ here the way they do for [devices]: an empty catalogue would mean the
     * server has no voices installed, which is a different thing to say than "this server cannot be
     * asked", and only the second one should retire the picker.
     */
    suspend fun voices(): List<MobileVoice>? = null

    /**
     * Revokes one session.
     *
     * False means the server answered 404, which is ambiguous by design: the endpoint may be
     * missing, or that session may already be gone (`app/routers/mobile.py` returns the same 404
     * for an unknown, an already-revoked, and another user's token). Callers disambiguate with what
     * they already know — a caller holding a successfully loaded list is clearly talking to a
     * server that has the endpoint.
     */
    suspend fun revokeDevice(tokenId: Int): Boolean

    /** Revokes every session except this one. False means 404, as in [revokeDevice]. */
    suspend fun revokeOtherDevices(): Boolean

    suspend fun chapters(fictionId: Int, playableOnly: Boolean = false): ChaptersResponse

    /** Changed/deleted chapter rows for one fiction since a server-issued cursor. */
    suspend fun chaptersDelta(fictionId: Int, updatedSince: String): ChaptersResponse = chapters(fictionId)

    /**
     * Server-side search, or **null when this server has no search endpoint**.
     *
     * Null rather than an empty result for the same reason [devices] uses it: "nothing matched" is
     * a normal answer the UI phrases one way, and "this server cannot search" is a different one it
     * has to phrase another. The `search` capability is the gate; this is the fallback for a server
     * that advertised it and then answered 404 anyway.
     */
    suspend fun search(
        query: String,
        limit: Int = SearchLimits.Default,
        offset: Int = 0,
    ): SearchResponse?

    /** Conditional reader document request; 404 is a normal [ReadAlongFetchResult.NotFound]. */
    suspend fun readAlong(chapterId: Int, ifNoneMatch: String? = null): ReadAlongFetchResult

    /** Null means this older server has no account-preferences endpoint. */
    suspend fun readerPreferences(): ReaderPreferencesResponse?

    /** Null means this older server has no account-preferences endpoint. */
    suspend fun updateReaderPreferences(request: ReaderPreferencesPatch): ReaderPreferencesResponse?

    suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse

    /**
     * This account's bookmarks, or **null when the server has no bookmark API**.
     *
     * Defaults to `manual` for the reason the backend documents: the same table holds the web
     * player's jump-back breadcrumbs, and a day of listening buries the marks a reader chose under
     * a few hundred automatic ones.
     */
    suspend fun bookmarks(
        kind: String? = BookmarkKind.Manual,
        fictionId: Int? = null,
    ): List<Bookmark>?

    /** The created bookmark, or null on a server with no bookmark API. */
    suspend fun createBookmark(request: BookmarkCreateRequest): Bookmark?

    /** The updated bookmark, or null when the server has no such bookmark (or no such API). */
    suspend fun updateBookmark(bookmarkId: Int, request: BookmarkPatchRequest): Bookmark?

    /** True once the mark is gone. A second delete is a success, not a 404 — it is idempotent. */
    suspend fun deleteBookmark(bookmarkId: Int): Boolean

    /**
     * The account's cross-library queue, or **null when the server has no queue API**.
     *
     * Null rather than an empty queue for the same reason [devices] does it: "your queue is empty"
     * is a normal answer the user can act on, while "this server has no shared queue" has to hide
     * the surface entirely instead of showing an empty list that no action can ever fill.
     */
    suspend fun serverQueue(): ServerQueueResponse?

    /** The queue after [request], or null on a server with no queue API. */
    suspend fun updateServerQueue(request: ServerQueueRequest): ServerQueueResponse?

    /**
     * New-chapter notices, or **null on a server that has none of this**.
     *
     * Same rule as [serverQueue]: "nothing new" is an answer worth drawing, "this server cannot
     * tell you about new chapters" is a surface to hide.
     */
    suspend fun chapterNotifications(): ChapterNotificationsResponse? = null

    /**
     * Clears one notice. Answers false when the server refused because the chapter cannot be
     * played yet.
     *
     * The 409 is caught here rather than thrown, because it is not a failure — it is the server
     * enforcing the rule this whole feature is built on, and it reaches a caller that already knows
     * not to have offered the control. Everything else still propagates.
     */
    suspend fun dismissChapterNotification(notificationId: Int): Boolean = false

    /** Clears every notice whose chapter plays, leaving the converting ones. */
    suspend fun dismissReadChapterNotifications(): Boolean = false

    /**
     * Record a listening position and try to get it to the server.
     *
     * Queued to disk with a timestamp *before* being sent. That ordering is the fix for #36: if the
     * send fails — offline, server down, laptop closed — the position survives with the time it was
     * actually reached, so when it does land it can be ordered against whatever happened since
     * instead of blindly overwriting it.
     *
     * Failures propagate, as they did before the queue existed — a 401 in particular still has to
     * reach the session-end handling. The caller ([dk.perspektiva.ttsroad.desktop.player
     * .QueuePlaybackController]) already treats a failed save as non-fatal, and the position stays
     * queued either way, so the next save retries it.
     */
    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    )

    /** Whether a bearer credential exists at all, so the audio path can fail fast when signed out. */
    fun authHeaderValue(): String?

    /** Resolves a possibly-relative audio/cover URL against the server we're logged into. */
    fun resolveUrl(url: String): String
}

/**
 * Retrofit/OkHttp implementation.
 *
 * The [client] carries [TtsRoadAuthInterceptor], so no method here handles the bearer header. The
 * [client], [ioDispatcher], [clock] and [deviceNameProvider] are injected so tests can point the
 * whole repository at a MockWebServer, run on a test dispatcher, expire the capability cache
 * without waiting on wall time, and avoid the blocking reverse-DNS lookup the real device name does.
 */
class RetrofitTtsRoadRepository(
    private val sessionStore: SessionStore,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceNameProvider: () -> String = ::defaultDeviceName,
    /**
     * Unsent listening positions. Injected so a test can hold the queue in memory instead of
     * writing to the user's real config directory, the same reason the session store is.
     */
    private val progressOutbox: ProgressOutboxStore =
        FileProgressOutboxStore(AppDirectories.configDir().resolve("progress-outbox.json")),
    private val stamp: () -> String = ::nowStamp,
) : TtsRoadRepository {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // One Retrofit per base URL so connections, the TLS session, and thread pools are reused
    // across calls (a new client per request would re-handshake).
    private val apiCache = HashMap<String, TtsRoadApi>()

    /**
     * Discovered capabilities per normalized base URL. In memory only: it is one cheap
     * unauthenticated call per launch, and a stale flag surviving a reinstall would be worse than
     * refetching.
     */
    private val capabilityCache = HashMap<String, CachedCapabilities>()

    private data class CachedCapabilities(val value: ServerCapabilities, val fetchedAtMillis: Long)

    private val _currentCapabilities = MutableStateFlow(ServerCapabilities.Baseline)
    override val currentCapabilities: StateFlow<ServerCapabilities> = _currentCapabilities.asStateFlow()

    private val _sessionEnd = MutableStateFlow<SessionEnd?>(null)
    override val sessionEnd: StateFlow<SessionEnd?> = _sessionEnd.asStateFlow()

    private val _serverPlaybackState = MutableStateFlow<Map<Int, PlaybackStateRow>>(emptyMap())
    override val serverPlaybackState: StateFlow<Map<Int, PlaybackStateRow>> =
        _serverPlaybackState.asStateFlow()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String?,
    ): LoginResult = withContext(ioDispatcher) {
        try {
            // Inside the try on purpose: a missing scheme becomes a user-correctable message
            // rather than an IllegalArgumentException escaping into the UI.
            val normalized = normalizeBaseUrl(baseUrl)
            val response = api(normalized).login(
                LoginRequest(
                    username = username.trim(),
                    password = password,
                    deviceName = deviceNameProvider(),
                    totpCode = totpCode?.trim()?.ifBlank { null },
                ),
            )
            sessionStore.save(
                SessionState(
                    serverUrl = normalized,
                    token = response.token,
                    username = response.user.username,
                    isAdmin = response.user.isAdmin,
                    serverName = response.server?.name ?: "TTSRoad",
                    serverVersion = response.server?.version,
                    advertisedBaseUrl = response.server?.baseUrl?.trim()?.takeIf { it.isNotEmpty() },
                    deviceId = response.deviceId,
                    expiresAt = response.expiresAt,
                ),
            )
            _sessionEnd.value = null
            // Forced: the previous answer may be from a different account or from before an
            // upgrade, and optional UI has to be gated by the time the library renders.
            refreshCurrentCapabilities(forceRefresh = true)
            LoginResult.Success
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            when {
                e.code() == 429 -> {
                    val throttle = parseLoginThrottle(body, e.response()?.headers()?.get("Retry-After"))
                    LoginResult.RateLimited(throttle.displayMessage, throttle.retryAfterSeconds)
                }
                // The server returns `detail: {message, totp_required: true}` for BOTH "code
                // missing" and "code wrong", so a raw substring match is the only reliable signal.
                e.code() == 401 && body?.contains("totp_required") == true -> LoginResult.TotpRequired
                else -> LoginResult.Failure(
                    redactSecrets(detailMessage(body)).takeIf { it.isNotBlank() }
                        ?: "Invalid username or password",
                )
            }
        } catch (e: Exception) {
            LoginResult.Failure(describeNetworkFailure(e))
        }
    }

    override suspend fun logout() = withContext(ioDispatcher) {
        val session = sessionStore.current()
        runCatching { if (session.isLoggedIn) api(session.serverUrl).logout() }
        // Local sign-out happens even if the server call failed.
        sessionStore.clearToken()
        forgetSessionScopedState(session.serverUrl)
        // An explicit sign-out is not a session *ending badly*; the login screen has nothing to explain.
        _sessionEnd.value = null
    }

    override suspend fun endSession(end: SessionEnd) = withContext(ioDispatcher) {
        val serverUrl = sessionStore.current().serverUrl
        sessionStore.clearToken()
        forgetSessionScopedState(serverUrl)
        _sessionEnd.value = end
    }

    override suspend fun capabilities(
        baseUrl: String,
        forceRefresh: Boolean,
    ): ServerCapabilities = withContext(ioDispatcher) {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull()
            ?: return@withContext ServerCapabilities.Baseline
        val cached = synchronized(capabilityCache) { capabilityCache[normalized] }
        if (!forceRefresh && cached != null && clock() - cached.fetchedAtMillis < CapabilityTtlMillis) {
            return@withContext cached.value
        }
        try {
            val discovered = ServerCapabilities.from(api(normalized).capabilities())
            synchronized(capabilityCache) {
                capabilityCache[normalized] = CachedCapabilities(discovered, clock())
            }
            discovered
        } catch (e: HttpException) {
            if (e.code() == 404) {
                // Definitive: that server will not grow the endpoint under us, so cache the
                // baseline rather than re-asking from every screen.
                synchronized(capabilityCache) {
                    capabilityCache[normalized] = CachedCapabilities(ServerCapabilities.Baseline, clock())
                }
                ServerCapabilities.Baseline
            } else {
                // A 500 or a proxy hiccup is not evidence the server lost a feature. Downgrading
                // a working server to baseline over one bad response would make UI flicker.
                cached?.value ?: ServerCapabilities.Baseline
            }
        } catch (_: Exception) {
            cached?.value ?: ServerCapabilities.Baseline
        }
    }

    override suspend fun refreshCurrentCapabilities(forceRefresh: Boolean): ServerCapabilities {
        val session = sessionStore.current()
        if (!session.isLoggedIn) {
            _currentCapabilities.value = ServerCapabilities.Baseline
            return ServerCapabilities.Baseline
        }
        return capabilities(session.serverUrl, forceRefresh).also { _currentCapabilities.value = it }
    }

    override fun forgetCapabilities(baseUrl: String) {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull() ?: return
        synchronized(capabilityCache) { capabilityCache.remove(normalized) }
    }

    override suspend fun library(scope: LibraryScope): LibraryResponse =
        withAuthorizedApi { it.library(scope.wireValue) }

    override suspend fun libraryDelta(updatedSince: String): LibraryResponse =
        withAuthorizedApi { it.library(LibraryScope.Followed.wireValue, updatedSince) }

    override suspend fun deltaSync(updatedSince: String): DeltaSyncResponse? =
        ifEndpointExists { it.deltaSync(updatedSince) }

    override suspend fun createFiction(request: FictionCreateRequest): FictionSummary =
        withAuthorizedApi { it.createFiction(request).fiction }

    override suspend fun updateFiction(fictionId: Int, request: FictionUpdateRequest): FictionSummary =
        withAuthorizedApi { it.updateFiction(fictionId, request).fiction }

    override suspend fun deleteFiction(fictionId: Int): Boolean =
        withAuthorizedApi { it.deleteFiction(fictionId) }.let { it.deleted && it.fictionId == fictionId }

    override suspend fun uploadEpub(file: File, voice: String?): FictionSummary = withAuthorizedApi { api ->
        // Streamed from the file rather than read into a byte array: an EPUB is allowed to be tens
        // of megabytes, and buffering one in the heap to hand it to OkHttp — which will only write
        // it to a socket — is a copy nobody needs.
        val part = MultipartBody.Part.createFormData(
            "file",
            // The server rejects anything without a `.epub` extension, so the *name* is part of
            // the request rather than decoration. Only the leaf is sent; a path is not the
            // server's business.
            file.name,
            file.asRequestBody(EpubMediaType),
        )
        api.uploadEpub(part, voice?.takeIf(String::isNotBlank)?.toRequestBody(TextMediaType)).fiction
    }

    override suspend fun uploadFictionCover(fictionId: Int, file: File): CoverUploadResult = try {
        // Streamed from the file like an EPUB is, and named `file` because that is the one part
        // name the route accepts. The media type is derived from the extension as a courtesy —
        // the server decides from the decoded bytes, not from what the client claims.
        val part = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody(CoverImageFormats.mediaTypeOf(file.name).toMediaType()),
        )
        CoverUploadResult.Saved(withAuthorizedApi { it.uploadFictionCover(fictionId, part).fiction })
    } catch (e: HttpException) {
        when (e.code()) {
            404 -> CoverUploadResult.Unsupported
            // The server's own words where it has them: "Cover too large; limit is 10 MB" is worth
            // more to the person holding the file than any sentence this client could invent.
            400, 403, 413, 415, 422 -> CoverUploadResult.Rejected(
                redactSecrets(detailMessage(e.response()?.errorBody()?.string()))
                    .takeIf { it.isNotBlank() }
                    ?: coverRejectionFor(e.code()),
            )
            else -> throw e
        }
    }

    override suspend fun setFollowing(fictionId: Int, following: Boolean): Boolean? =
        ifEndpointExists {
            // The answer is read, never assumed: a response is the only proof the shelf changed.
            if (following) it.follow(fictionId).following else it.unfollow(fictionId).following
        }

    override suspend fun currentUser(): MobileUser? = ifEndpointExists { it.me() }?.user

    override suspend fun devices(): List<DeviceSession>? = ifEndpointExists { it.devices() }?.devices

    override suspend fun audiobookExports(): AudiobookExportsResponse? =
        ifEndpointExists { it.audiobookExports() }

    override suspend fun voices(): List<MobileVoice>? = ifEndpointExists { it.voices() }?.voices

    override suspend fun revokeDevice(tokenId: Int): Boolean =
        ifEndpointExists { it.revokeDevice(tokenId) } != null

    override suspend fun revokeOtherDevices(): Boolean =
        ifEndpointExists { it.revokeOtherDevices() } != null

    override suspend fun chapters(fictionId: Int, playableOnly: Boolean): ChaptersResponse =
        withAuthorizedApi { it.chapters(fictionId = fictionId, playableOnly = playableOnly) }

    override suspend fun chaptersDelta(fictionId: Int, updatedSince: String): ChaptersResponse =
        withAuthorizedApi { it.chapters(fictionId = fictionId, updatedSince = updatedSince) }

    override suspend fun search(query: String, limit: Int, offset: Int): SearchResponse? =
        ifEndpointExists {
            it.search(
                // Trimmed and bounded here rather than at the call site: the server rejects an
                // over-long `q` with a 422, and a 422 is not something a listener can act on.
                query = query.trim().take(SearchLimits.MaxQueryLength),
                limit = limit.coerceIn(1, SearchLimits.Max),
                offset = offset.coerceAtLeast(0),
            )
        }

    override suspend fun readAlong(chapterId: Int, ifNoneMatch: String?): ReadAlongFetchResult =
        withAuthorizedApi { api ->
            val response = api.readAlong(chapterId, ifNoneMatch)
            when {
                response.code() == 304 -> ReadAlongFetchResult.NotModified
                response.code() == 404 -> ReadAlongFetchResult.NotFound
                response.isSuccessful -> {
                    val body = response.body() ?: error("The server returned an empty read-along document")
                    ReadAlongFetchResult.Modified(body, response.headers()["ETag"])
                }
                else -> throw HttpException(response)
            }
        }

    override suspend fun readerPreferences(): ReaderPreferencesResponse? =
        ifEndpointExists { it.readerPreferences() }

    override suspend fun updateReaderPreferences(request: ReaderPreferencesPatch): ReaderPreferencesResponse? =
        ifEndpointExists { it.updateReaderPreferences(request) }

    override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
        withAuthorizedApi { it.markPlayback(PlaybackMarkRequest(chapterIds, played)) }

    override suspend fun bookmarks(kind: String?, fictionId: Int?): List<Bookmark>? =
        ifEndpointExists { it.bookmarks(kind = kind, fictionId = fictionId) }?.bookmarks

    override suspend fun createBookmark(request: BookmarkCreateRequest): Bookmark? =
        ifEndpointExists {
            it.createBookmark(
                request.copy(
                    // Bounded here so an over-long label is a shorter bookmark rather than a 400:
                    // the server truncates to exactly these limits anyway.
                    label = request.label?.take(BookmarkLimits.MaxLabelChars),
                    note = request.note?.take(BookmarkLimits.MaxNoteChars),
                    positionSeconds = request.positionSeconds.coerceAtLeast(0.0),
                ),
            )
        }?.bookmark

    override suspend fun updateBookmark(bookmarkId: Int, request: BookmarkPatchRequest): Bookmark? =
        ifEndpointExists {
            it.updateBookmark(
                bookmarkId,
                request.copy(
                    label = request.label?.take(BookmarkLimits.MaxLabelChars),
                    note = request.note?.take(BookmarkLimits.MaxNoteChars),
                ),
            )
        }?.bookmark

    override suspend fun deleteBookmark(bookmarkId: Int): Boolean =
        // A 404 here is "already gone or never existed", which is the outcome the caller wanted
        // either way — the delete is idempotent by design, so it is not worth surfacing.
        ifEndpointExists { it.deleteBookmark(bookmarkId) } != null

    override suspend fun serverQueue(): ServerQueueResponse? = ifEndpointExists { it.queue() }

    override suspend fun chapterNotifications(): ChapterNotificationsResponse? =
        ifEndpointExists { it.chapterNotifications() }

    override suspend fun dismissChapterNotification(notificationId: Int): Boolean = try {
        withAuthorizedApi { it.dismissChapterNotification(notificationId) }
        true
    } catch (e: HttpException) {
        // 409: still converting, which the caller should not have offered but the server refuses
        // regardless. 404: somebody else's, or already gone. Neither is worth an error dialog —
        // both mean "the list you are looking at is out of date", and a refresh says so.
        if (e.code() == 409 || e.code() == 404) false else throw e
    }

    override suspend fun dismissReadChapterNotifications(): Boolean = try {
        withAuthorizedApi { it.dismissReadChapterNotifications() }
        true
    } catch (e: HttpException) {
        if (e.code() == 404) false else throw e
    }

    override suspend fun updateServerQueue(request: ServerQueueRequest): ServerQueueResponse? =
        ifEndpointExists { it.updateQueue(request) }

    override suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ) {
        // Nothing is queued while signed out. A position recorded with no account behind it
        // belongs to nobody, and keeping it would mean the next person to sign in on this machine
        // flushes the last one's listening history to their own account.
        if (!sessionStore.current().isLoggedIn) return
        progressOutbox.record(
            PendingProgress(
                fictionId = fictionId,
                chapterId = chapterId,
                positionSeconds = positionSeconds.coerceAtLeast(0.0),
                isPlayed = isPlayed,
                clientUpdatedAt = stamp(),
            ),
        )
        flushProgress()
    }

    /**
     * Drain the outbox.
     *
     * Batched through `/playback/sync` when the server has it, in chunks of the published
     * `max_playback_sync_items`. Falls back to `/playback/progress` otherwise — that endpoint
     * cannot order writes, so the overwrite this exists to prevent is still possible against an
     * older server, but losing the position entirely would be worse and the backend keeps it
     * working deliberately.
     */
    override suspend fun flushProgress() {
        if (!sessionStore.current().isLoggedIn) return
        val pending = progressOutbox.entries.value
        if (pending.isEmpty()) return

        try {
            if (currentCapabilities.value.batchProgress) {
                flushBatched(pending)
            } else {
                flushOneByOne(pending)
            }
        } catch (e: HttpException) {
            // A dead credential can never flush this queue, so holding it would mean carrying a
            // growing file forever. Anything else is transient as far as this client can tell.
            if (e.code() == 401) progressOutbox.clear()
            throw e
        }
    }

    private suspend fun flushBatched(pending: List<PendingProgress>) {
        val limit = currentCapabilities.value.maxPlaybackSyncItems ?: DefaultMaxPlaybackSyncItems
        for (batch in ProgressOutbox.batches(pending, limit)) {
            val response = withAuthorizedApi { api ->
                api.syncProgress(
                    PlaybackSyncRequest(
                        batch.map {
                            PlaybackSyncItem(
                                chapterId = it.chapterId,
                                positionSeconds = it.positionSeconds,
                                isPlayed = it.isPlayed,
                                clientUpdatedAt = it.clientUpdatedAt,
                            )
                        },
                    ),
                )
            }
            // Rejections are as final as acceptances — every reason the server can give is terminal
            // for that item, so re-sending would only get the same answer.
            progressOutbox.drop(
                response.accepted.map { it.chapterId } + response.rejected.map { it.chapterId },
            )
            if (response.serverState.isNotEmpty()) {
                _serverPlaybackState.value =
                    _serverPlaybackState.value + response.serverState.associateBy { it.chapterId }
            }
        }
    }

    private suspend fun flushOneByOne(pending: List<PendingProgress>) {
        for (entry in pending) {
            withAuthorizedApi {
                it.saveProgress(
                    PlaybackProgressRequest(
                        entry.fictionId,
                        entry.chapterId,
                        entry.positionSeconds,
                        entry.isPlayed,
                    ),
                )
            }
            progressOutbox.drop(listOf(entry.chapterId))
        }
    }

    override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader

    override fun resolveUrl(url: String): String = resolveAgainstServer(sessionStore.current().serverUrl, url)

    /**
     * Everything discovered about a server that must not outlive the session that discovered it.
     *
     * Capabilities are per server *and* per account: the next sign-in may be a different server
     * entirely, and leaving the old flags standing would show features the new one cannot serve.
     */
    private fun forgetSessionScopedState(serverUrl: String) {
        forgetCapabilities(serverUrl)
        _currentCapabilities.value = ServerCapabilities.Baseline
        // Chapter ids are per server; carrying reconciled positions across a sign-out would let one
        // account's resume point leak into another's.
        _serverPlaybackState.value = emptyMap()
    }

    /**
     * Runs an authenticated call, answering null when the server has never heard of the endpoint.
     *
     * The device-management API is *additive* and `api_version` did not change with it, so there is
     * no version to test against and a 404 is the only available signal that the backend predates
     * it. Everything else keeps its normal meaning — in particular a 401 still ends the session,
     * because "this server is old" and "this token is dead" must not be confused.
     */
    private suspend fun <T> ifEndpointExists(block: suspend (TtsRoadApi) -> T): T? = try {
        withAuthorizedApi(block)
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi) -> T): T =
        withContext(ioDispatcher) {
            val session = sessionStore.current()
            require(session.isLoggedIn) { "Not logged in" }
            try {
                block(api(session.serverUrl))
            } catch (e: HttpException) {
                // A 401 on an authenticated endpoint means the stored token can never work again —
                // expired, revoked from another device, or the server's database was reset — so
                // retrying is pointless and holding on to it just produces "HTTP 401" on every
                // screen until the user finds Settings > Sign out. Anything else (500, a socket
                // error, a proxy) says nothing about the credential and must NOT sign anyone out.
                if (e.code() == 401) {
                    endSession(parseSessionEnd(e.response()?.errorBody()?.string()))
                }
                throw e
            }
        }

    private fun api(baseUrl: String): TtsRoadApi {
        val normalized = normalizeBaseUrl(baseUrl)
        return synchronized(apiCache) {
            apiCache.getOrPut(normalized) {
                Retrofit.Builder()
                    .baseUrl(normalized)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(TtsRoadApi::class.java)
            }
        }
    }
}

/** Blocking reverse-DNS lookup + OS name; injected so tests never do it. */
fun defaultDeviceName(): String {
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
    val os = System.getProperty("os.name") ?: "Desktop"
    return listOfNotNull(host, os).joinToString(" · ").ifBlank { "Desktop" }
}
