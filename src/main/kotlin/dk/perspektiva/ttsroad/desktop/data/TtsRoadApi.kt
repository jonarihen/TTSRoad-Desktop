package dk.perspektiva.ttsroad.desktop.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TtsRoadApi {
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
}
