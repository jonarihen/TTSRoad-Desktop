package dk.perspektiva.ttsroad.desktop.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The `api/mobile/…` surface this client uses.
 *
 * No method takes an `Authorization` parameter: the bearer token is attached by
 * [TtsRoadAuthInterceptor] on the shared OkHttp client, which is also what covers the audio and
 * cover-image paths that never go through Retrofit. The two public endpoints opt out explicitly
 * with [NoAuthHeader], which the interceptor strips before the request leaves the process.
 */
interface TtsRoadApi {
    /**
     * Unauthenticated feature discovery. 404 on any server older than the endpoint.
     *
     * Marked public because it is called against a URL the user is still typing: without the
     * marker, a stale session for a host that happens to share the typed origin would send that
     * server's token to whatever is listening there.
     */
    @Headers("$NoAuthHeader: 1")
    @GET("api/mobile/capabilities")
    suspend fun capabilities(): CapabilitiesResponse

    /** Public by definition; marked so a stale token is not offered alongside the credentials. */
    @Headers("$NoAuthHeader: 1")
    @POST("api/mobile/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/mobile/logout")
    suspend fun logout()

    /** The signed-in account as the server sees it — the authoritative `is_admin`. */
    @GET("api/mobile/me")
    suspend fun me(): CurrentUserResponse

    /** Every mobile/desktop sign-in on this account. 404 on a server older than the endpoint. */
    @GET("api/mobile/devices")
    suspend fun devices(): DevicesResponse

    // Both revoke calls answer with a small status object (`{"status": "ok", ...}`) whose shape is
    // not worth modelling: the client re-reads the list afterwards rather than trusting an echo,
    // because a 404 on the DELETE is ambiguous between "already gone" and "no such endpoint".
    @DELETE("api/mobile/devices/{token_id}")
    suspend fun revokeDevice(@Path("token_id") tokenId: Int)

    /**
     * Revokes every *other* session. One server-side call rather than a loop of deletes, precisely
     * so the client cannot get the "which one am I" question wrong: the token making the request is
     * the one the server keeps.
     */
    @POST("api/mobile/devices/revoke-others")
    suspend fun revokeOtherDevices()

    @GET("api/mobile/library")
    suspend fun library(): LibraryResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
    ): ChaptersResponse

    /** Raw response exposes both the ETag and the normal 304 revalidation path. */
    @GET("api/mobile/chapters/{chapter_id}/readalong")
    suspend fun readAlong(
        @Path("chapter_id") chapterId: Int,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): retrofit2.Response<ReadAlongResponse>

    @GET("api/me/preferences")
    suspend fun readerPreferences(): ReaderPreferencesResponse

    @PATCH("api/me/preferences")
    suspend fun updateReaderPreferences(@Body request: ReaderPreferencesPatch): ReaderPreferencesResponse

    @POST("api/mobile/playback/progress")
    suspend fun saveProgress(@Body request: PlaybackProgressRequest): PlaybackProgressResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse
}
