package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

/**
 * Seam for everything the UI needs from the TTSRoad server. [RetrofitTtsRoadRepository] is the
 * production implementation; tests either drive that one against a MockWebServer or substitute a
 * hand-written fake.
 */
interface TtsRoadRepository {
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String? = null,
    ): LoginResult

    suspend fun logout()

    suspend fun library(): LibraryResponse

    suspend fun chapters(fictionId: Int, playableOnly: Boolean = false): ChaptersResponse

    suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse

    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse?

    /** Authorization header that audio requests must carry (bearer-protected MP3s). */
    fun authHeaderValue(): String?

    /** Resolves a possibly-relative audio/cover URL against the server we're logged into. */
    fun resolveUrl(url: String): String
}

/**
 * Retrofit/OkHttp implementation.
 *
 * The [client], [ioDispatcher] and [deviceNameProvider] are injected so tests can point the whole
 * repository at a MockWebServer, run on a test dispatcher, and avoid the blocking reverse-DNS
 * lookup the real device name does.
 */
class RetrofitTtsRoadRepository(
    private val sessionStore: SessionStore,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deviceNameProvider: () -> String = ::defaultDeviceName,
) : TtsRoadRepository {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // One Retrofit per base URL so connections, the TLS session, and thread pools are reused
    // across calls (a new client per request would re-handshake). The Authorization header is
    // passed per-call (see TtsRoadApi) rather than through a shared mutable field, so concurrent
    // calls can't race on / clobber each other's token.
    private val apiCache = HashMap<String, TtsRoadApi>()

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
                ),
            )
            LoginResult.Success
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            // The server returns `detail: {message, totp_required: true}` for BOTH "code missing"
            // and "code wrong", so a raw substring match is the only reliable signal here.
            if (e.code() == 401 && body?.contains("totp_required") == true) {
                LoginResult.TotpRequired
            } else {
                LoginResult.Failure(parseDetailMessage(body) ?: "Invalid username or password")
            }
        } catch (e: Exception) {
            LoginResult.Failure(e.message ?: "Login failed")
        }
    }

    override suspend fun logout() = withContext(ioDispatcher) {
        val session = sessionStore.current()
        runCatching {
            session.authorizationHeader?.let { auth -> api(session.serverUrl).logout(auth) }
        }
        // Local sign-out happens even if the server call failed.
        sessionStore.clearToken()
    }

    override suspend fun library(): LibraryResponse = withAuthorizedApi { api, auth -> api.library(auth) }

    override suspend fun chapters(fictionId: Int, playableOnly: Boolean): ChaptersResponse =
        withAuthorizedApi { api, auth -> api.chapters(auth, fictionId = fictionId, playableOnly = playableOnly) }

    override suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
        withAuthorizedApi { api, auth -> api.markPlayback(auth, PlaybackMarkRequest(chapterIds, played)) }

    override suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse? = withContext(ioDispatcher) {
        val session = sessionStore.current()
        val auth = session.authorizationHeader ?: return@withContext null
        api(session.serverUrl).saveProgress(
            auth,
            PlaybackProgressRequest(fictionId, chapterId, positionSeconds.coerceAtLeast(0.0), isPlayed),
        )
    }

    override fun authHeaderValue(): String? = sessionStore.current().authorizationHeader

    override fun resolveUrl(url: String): String = resolveAgainstServer(sessionStore.current().serverUrl, url)

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi, String) -> T): T =
        withContext(ioDispatcher) {
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

    /** Handles both `{"detail": "..."}` and `{"detail": {"message": "..."}}`. */
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
}

/** Blocking reverse-DNS lookup + OS name; injected so tests never do it. */
fun defaultDeviceName(): String {
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
    val os = System.getProperty("os.name") ?: "Desktop"
    return listOfNotNull(host, os).joinToString(" · ").ifBlank { "Desktop" }
}
