package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val outbox = ProgressOutbox(configDir().resolve("progress-outbox.json"))

    private val _capabilities = MutableStateFlow(ServerCapabilities())
    private val _limits = MutableStateFlow(ServerLimits())
    private val _serverPlaybackState = MutableStateFlow<Map<Int, PlaybackStateRow>>(emptyMap())

    /**
     * What the server says it holds for chapters this client has written to, as of the last flush.
     *
     * This is how a losing write gets noticed. `/playback/sync` returns the server's own state for
     * every chapter in the batch, so when an offline position loses to a newer one from the
     * browser, the newer position is right here — and playback resumes from it instead of from the
     * stale local one.
     */
    val serverPlaybackState: StateFlow<Map<Int, PlaybackStateRow>> = _serverPlaybackState.asStateFlow()

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
        // Last chance to save: the token is about to be revoked, and anything still queued after
        // that can never be sent under it. A clean sign-out should not cost the user their place.
        runCatching { flushProgress() }
        outbox.clear()
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

    /**
     * Record a listening position and try to get it to the server.
     *
     * The write is stamped and queued to disk first, then flushed. That ordering is the fix for
     * #36: if the flush fails — offline, server down, laptop closed — the position survives with
     * the time it was actually reached, so when it does reach the server it can be ordered against
     * whatever else has happened since instead of blindly overwriting it.
     *
     * A flush failure is not raised to the caller. Playback must not stall because progress could
     * not be saved; the entry stays queued and the next call retries it.
     */
    suspend fun recordProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ) {
        outbox.record(
            PendingProgress(
                fictionId = fictionId,
                chapterId = chapterId,
                positionSeconds = positionSeconds.coerceAtLeast(0.0),
                isPlayed = isPlayed,
                clientUpdatedAt = nowStamp(),
            ),
        )
        runCatching { flushProgress() }
    }

    /**
     * Send everything queued.
     *
     * Batched through `/playback/sync` when the server has it, in chunks of the server's published
     * `max_playback_sync_items` — it answers an oversized batch with a 400 rather than truncating,
     * so ignoring the limit would lose the whole batch.
     *
     * Falls back to `/playback/progress` when `batch_progress` is false. That endpoint cannot order
     * writes, so the overwrite this all exists to prevent is still possible against an older
     * server — but losing the position entirely would be worse, and the backend keeps the endpoint
     * working deliberately.
     */
    suspend fun flushProgress() = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        val auth = session.authorizationHeader ?: return@withContext
        val pending = outbox.snapshot()
        if (pending.isEmpty()) return@withContext
        val api = api(session.serverUrl)

        try {
            if (capabilities.value.batchProgress) {
                val batchSize = limits.value.maxPlaybackSyncItems.coerceAtLeast(1)
                pending.chunked(batchSize).forEach { batch ->
                    val response = api.syncProgress(
                        auth,
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
                    // Rejections are as final as acceptances — every reason the server can give is
                    // terminal for that item, so re-sending would just get the same answer.
                    outbox.drop(response.accepted.map { it.chapterId } + response.rejected.map { it.chapterId })
                    if (response.serverState.isNotEmpty()) {
                        _serverPlaybackState.update { known ->
                            known + response.serverState.associateBy { it.chapterId }
                        }
                    }
                }
            } else {
                pending.forEach { entry ->
                    api.saveProgress(
                        auth,
                        PlaybackProgressRequest(
                            entry.fictionId,
                            entry.chapterId,
                            entry.positionSeconds,
                            entry.isPlayed,
                        ),
                    )
                    outbox.drop(listOf(entry.chapterId))
                }
            }
        } catch (e: HttpException) {
            // 401 means the credential is gone, so this queue can never be flushed under it.
            // Anything else is transient as far as this client can tell — keep the queue.
            if (e.code() == 401) outbox.clear()
            throw e
        }
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
