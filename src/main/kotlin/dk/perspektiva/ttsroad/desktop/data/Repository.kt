package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * How long a discovered capability set is trusted before it is re-asked. Long enough that
 * discovery is not a per-screen cost, short enough that a server upgraded under a long-running
 * desktop process is noticed the same day.
 */
private val CapabilityTtlMillis = TimeUnit.HOURS.toMillis(6)

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
 * Seam for everything the UI needs from the TTSRoad server. [RetrofitTtsRoadRepository] is the
 * production implementation; tests either drive that one against a MockWebServer or substitute a
 * hand-written fake.
 */
interface TtsRoadRepository {
    /** What the signed-in server supports. [ServerCapabilities.Baseline] until discovery lands. */
    val currentCapabilities: StateFlow<ServerCapabilities>

    /** Why the stored credential was dropped, or null while the session is usable. */
    val sessionEnd: StateFlow<SessionEnd?>

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

    suspend fun library(): LibraryResponse

    suspend fun chapters(fictionId: Int, playableOnly: Boolean = false): ChaptersResponse

    suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse

    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse?

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

    override suspend fun library(): LibraryResponse = withAuthorizedApi { it.library() }

    override suspend fun chapters(fictionId: Int, playableOnly: Boolean): ChaptersResponse =
        withAuthorizedApi { it.chapters(fictionId = fictionId, playableOnly = playableOnly) }

    override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
        withAuthorizedApi { it.markPlayback(PlaybackMarkRequest(chapterIds, played)) }

    override suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse? {
        if (!sessionStore.current().isLoggedIn) return null
        return withAuthorizedApi {
            it.saveProgress(
                PlaybackProgressRequest(fictionId, chapterId, positionSeconds.coerceAtLeast(0.0), isPlayed),
            )
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
