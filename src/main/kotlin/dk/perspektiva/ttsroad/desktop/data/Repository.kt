package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
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

class TtsRoadRepository(private val sessionStore: SessionStore) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // One shared client + one Retrofit per base URL so connections, the TLS session, and
    // thread pools are reused across calls (a new client per request would re-handshake).
    @Volatile
    private var authHeader: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            authHeader?.let { builder.header("Authorization", it) }
            chain.proceed(builder.build())
        }
        .build()

    private val apiCache = HashMap<String, TtsRoadApi>()

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totpCode: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        authHeader = null
        try {
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
            if (session.isLoggedIn) {
                authHeader = session.authorizationHeader
                api(session.serverUrl).logout()
            }
        }
        sessionStore.clearToken()
    }

    suspend fun library(): LibraryResponse = withAuthorizedApi { it.library() }

    suspend fun chapters(fictionId: Int, playableOnly: Boolean = false): ChaptersResponse =
        withAuthorizedApi { it.chapters(fictionId = fictionId, playableOnly = playableOnly) }

    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse? = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (!session.isLoggedIn) return@withContext null
        authHeader = session.authorizationHeader
        api(session.serverUrl).saveProgress(
            PlaybackProgressRequest(fictionId, chapterId, positionSeconds.coerceAtLeast(0.0), isPlayed),
        )
    }

    /** Authorization header that audio requests must carry (bearer-protected MP3s). */
    fun authHeaderValue(): String? = sessionStore.current().authorizationHeader

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi) -> T): T =
        withContext(Dispatchers.IO) {
            val session = sessionStore.current()
            require(session.isLoggedIn) { "Not logged in" }
            authHeader = session.authorizationHeader
            block(api(session.serverUrl))
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
