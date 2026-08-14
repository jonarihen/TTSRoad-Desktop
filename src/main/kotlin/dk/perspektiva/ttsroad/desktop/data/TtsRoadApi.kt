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

    /** Finished admin-created M4B volumes; read-only and admin-only. */
    @GET("api/mobile/exports")
    suspend fun audiobookExports(): AudiobookExportsResponse

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

    /**
     * The caller's shelf, or the whole server with `scope=all`.
     *
     * The parameter is always sent — an older server simply ignores an unknown query parameter and
     * answers the shared list it always did, which is what makes browse-all safe to ask for.
     */
    @GET("api/mobile/library")
    suspend fun library(
        @Query("scope") scope: String = LibraryScope.Followed.wireValue,
        @Query("updated_since") updatedSince: String? = null,
    ): LibraryResponse

    /** Cheap index before resource-specific delta pulls. 404 on a pre-delta server. */
    @GET("api/mobile/sync")
    suspend fun deltaSync(@Query("updated_since") updatedSince: String): DeltaSyncResponse

    /** Adds one shared fiction. Advertised by `fiction_management` and admin-gated by the server. */
    @POST("api/mobile/fictions")
    suspend fun createFiction(@Body request: FictionCreateRequest): FictionMutationResponse

    /** Edits shared fiction metadata. The slug is deliberately not part of the request model. */
    @PATCH("api/mobile/fictions/{fiction_id}")
    suspend fun updateFiction(
        @Path("fiction_id") fictionId: Int,
        @Body request: FictionUpdateRequest,
    ): FictionMutationResponse

    /** Deletes the shared fiction and every account's dependent progress. */
    @DELETE("api/mobile/fictions/{fiction_id}")
    suspend fun deleteFiction(@Path("fiction_id") fictionId: Int): FictionDeleteResponse

    /** Puts a fiction on this account's shelf. 404 for a fiction that does not exist. */
    @POST("api/mobile/fictions/{fiction_id}/follow")
    suspend fun follow(@Path("fiction_id") fictionId: Int): FollowResponse

    /** Takes it off. The fiction stays on the server and stays reachable through `scope=all`. */
    @DELETE("api/mobile/fictions/{fiction_id}/follow")
    suspend fun unfollow(@Path("fiction_id") fictionId: Int): FollowResponse

    /**
     * Library-wide search across fiction metadata, chapter titles and narration text.
     *
     * Advertised as the `search` capability; 404 on a server older than the endpoint. An empty
     * query is an empty result rather than a 422, which is what lets the field clear itself.
     */
    @GET("api/mobile/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = SearchLimits.Default,
        @Query("offset") offset: Int = 0,
    ): SearchResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
        @Query("updated_since") updatedSince: String? = null,
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

    @POST("api/mobile/playback/sync")
    suspend fun syncProgress(@Body request: PlaybackSyncRequest): PlaybackSyncResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse

    /**
     * This account's bookmarks. Advertised as the `bookmarks` capability; 404 on an older server.
     *
     * [kind] is not optional in practice: without `manual` the list is drowned in the jump-back
     * breadcrumbs the web player writes as `auto`.
     */
    @GET("api/mobile/bookmarks")
    suspend fun bookmarks(
        @Query("kind") kind: String? = BookmarkKind.Manual,
        @Query("fiction_id") fictionId: Int? = null,
        @Query("chapter_id") chapterId: Int? = null,
    ): BookmarksResponse

    /** 201 on success; 404 for an unknown chapter, 409 once the account is at its bookmark cap. */
    @POST("api/mobile/bookmarks")
    suspend fun createBookmark(@Body request: BookmarkCreateRequest): BookmarkWriteResponse

    @PATCH("api/mobile/bookmarks/{bookmark_id}")
    suspend fun updateBookmark(
        @Path("bookmark_id") bookmarkId: Int,
        @Body request: BookmarkPatchRequest,
    ): BookmarkWriteResponse

    /** Soft delete, idempotent: a second call answers the first one's tombstone, not a 404. */
    @DELETE("api/mobile/bookmarks/{bookmark_id}")
    suspend fun deleteBookmark(@Path("bookmark_id") bookmarkId: Int): BookmarkDeleteResponse

    /**
     * The account's cross-library queue. Advertised as the `queue` capability; 404 on an older
     * server, which is the only signal available because the endpoint is additive.
     */
    @GET("api/mobile/queue")
    suspend fun queue(): ServerQueueResponse

    /**
     * Every queue mutation. The response is the whole queue as the server now holds it, so the
     * caller replaces its copy rather than predicting what the action did.
     */
    @POST("api/mobile/queue")
    suspend fun updateQueue(@Body request: ServerQueueRequest): ServerQueueResponse
}
