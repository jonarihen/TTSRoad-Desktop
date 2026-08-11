package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Outcome of a login attempt. */
sealed interface LoginResult {
    data object Success : LoginResult
    data object TotpRequired : LoginResult
    data class Failure(val message: String) : LoginResult
}

class TtsRoadRepository(private val sessionStore: SessionStore) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // One shared client + one Retrofit per base URL so connections, the TLS session, and
    // thread pools are reused across calls (a new client per request would re-handshake).
    // The Authorization header is passed per-call (see TtsRoadApi) rather than through a
    // shared mutable field, so concurrent calls can't race on / clobber each other's token.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiCache = HashMap<String, TtsRoadApi>()

    private val _capabilities = MutableStateFlow(ServerCapabilities())
    private val _limits = MutableStateFlow(ServerLimits())

    /**
     * What the currently-connected server supports. Starts all-false and stays that way until
     * [refreshCapabilities] succeeds, so a screen that gates on a flag hides the control rather
     * than flashing it and then withdrawing it.
     */
    val capabilities: StateFlow<ServerCapabilities> = _capabilities.asStateFlow()
    val limits: StateFlow<ServerLimits> = _limits.asStateFlow()

    /**
     * Ask the server what it can do. Safe to call before login — the endpoint is public.
     *
     * Failures are the caller's to swallow: not knowing the capability set is not a reason to fail
     * whatever the user was actually doing, and all-false degrades to the feature set this client
     * had before it learned to ask.
     */
    suspend fun refreshCapabilities(): ServerCapabilities = withContext(Dispatchers.IO) {
        val baseUrl = sessionStore.current().serverUrl
        if (baseUrl.isBlank()) return@withContext ServerCapabilities()
        val response = api(baseUrl).capabilities()
        _capabilities.value = response.capabilities
        _limits.value = response.limits
        response.capabilities
    }

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeBaseUrl(baseUrl)
            val response = api(normalized).login(
                LoginRequest(
                    username = username.trim(),
                    password = password,
                    deviceName = deviceName(),
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
                ),
            )
            // Best-effort: a server that won't answer this is still a server we just logged into.
            runCatching { refreshCapabilities() }
            LoginResult.Success
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            if (e.code() == 401 && body?.contains("totp_required") == true) {
                LoginResult.TotpRequired
            } else {
                LoginResult.Failure(parseDetailMessage(body) ?: "Invalid username or password")
            }
        } catch (e: Exception) {
            LoginResult.Failure(e.message ?: "Login failed")
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        runCatching {
            session.authorizationHeader?.let { auth -> api(session.serverUrl).logout(auth) }
        }
        sessionStore.clearToken()
        // The next sign-in may be against a different server with a different feature set.
        _capabilities.value = ServerCapabilities()
        _limits.value = ServerLimits()
    }

    suspend fun library(): LibraryResponse = withAuthorizedApi { api, auth -> api.library(auth) }

    suspend fun chapters(fictionId: Int, playableOnly: Boolean = false): ChaptersResponse =
        withAuthorizedApi { api, auth -> api.chapters(auth, fictionId = fictionId, playableOnly = playableOnly) }

    suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
        withAuthorizedApi { api, auth -> api.markPlayback(auth, PlaybackMarkRequest(chapterIds, played)) }

    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse? = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        val auth = session.authorizationHeader ?: return@withContext null
        api(session.serverUrl).saveProgress(
            auth,
            PlaybackProgressRequest(fictionId, chapterId, positionSeconds.coerceAtLeast(0.0), isPlayed),
        )
    }

    /** Authorization header that audio requests must carry (bearer-protected MP3s). */
    fun authHeaderValue(): String? = sessionStore.current().authorizationHeader

    /**
     * Audio/cover URLs from the API are normally absolute (the server prefixes them with its
     * configured BASE_URL), but fall back to a bare path like `/audio/slug/file.mp3` if the
     * admin hasn't set BASE_URL. Resolve that case against the server we're actually logged into.
     */
    fun resolveUrl(url: String): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        return sessionStore.current().serverUrl.trimEnd('/') + url
    }

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi, String) -> T): T =
        withContext(Dispatchers.IO) {
            val session = sessionStore.current()
            val auth = requireNotNull(session.authorizationHeader) { "Not logged in" }
            block(api(session.serverUrl), auth)
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

    private fun parseDetailMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val parsed = moshi.adapter(Any::class.java).fromJson(body) as? Map<*, *>
            when (val detail = parsed?.get("detail")) {
                is String -> detail
                is Map<*, *> -> detail["message"] as? String
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun deviceName(): String {
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        val os = System.getProperty("os.name") ?: "Desktop"
        return listOfNotNull(host, os).joinToString(" · ").ifBlank { "Desktop" }
    }
}
