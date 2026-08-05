package dk.perspektiva.ttsroad.desktop.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
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

    @GET("api/mobile/library")
    suspend fun library(): LibraryResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
    ): ChaptersResponse

    @POST("api/mobile/playback/progress")
    suspend fun saveProgress(@Body request: PlaybackProgressRequest): PlaybackProgressResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse
}
