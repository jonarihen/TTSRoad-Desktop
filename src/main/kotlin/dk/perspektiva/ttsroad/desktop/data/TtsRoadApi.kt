package dk.perspektiva.ttsroad.desktop.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TtsRoadApi {
    /**
     * Unauthenticated on purpose — the backend lists this path in `_PUBLIC_PREFIXES`, so the client
     * can ask what a server supports before anyone has signed into it.
     */
    @GET("api/mobile/capabilities")
    suspend fun capabilities(): CapabilitiesResponse

    @POST("api/mobile/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/mobile/logout")
    suspend fun logout(@Header("Authorization") auth: String)

    @GET("api/mobile/library")
    suspend fun library(@Header("Authorization") auth: String): LibraryResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Header("Authorization") auth: String,
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
    ): ChaptersResponse

    @POST("api/mobile/playback/progress")
    suspend fun saveProgress(
        @Header("Authorization") auth: String,
        @Body request: PlaybackProgressRequest,
    ): PlaybackProgressResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(
        @Header("Authorization") auth: String,
        @Body request: PlaybackMarkRequest,
    ): PlaybackMarkResponse
}
