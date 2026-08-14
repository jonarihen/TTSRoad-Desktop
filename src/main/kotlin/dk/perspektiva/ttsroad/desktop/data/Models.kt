package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

// Mirrors the TTSRoad mobile API (/api/mobile/*), shared with the Android client.

data class LoginRequest(
    val username: String,
    val password: String,
    @param:Json(name = "device_name") val deviceName: String,
    @param:Json(name = "totp_code") val totpCode: String? = null,
)

data class LoginResponse(
    val token: String,
    @param:Json(name = "token_type") val tokenType: String = "bearer",
    val user: MobileUser,
    val server: ServerInfo? = null,
    /**
     * The `MobileApiToken` row id for this sign-in. The device-management API identifies "this
     * device" by it, and the server does not always set `is_current`, so it is worth keeping.
     */
    @param:Json(name = "device_id") val deviceId: Int? = null,
    /**
     * ISO-8601 UTC expiry, 90 days out. Informational only: every authenticated request renews it,
     * so a client that scheduled a refresh off this value would be refreshing a moving target. The
     * authoritative signal that a token died is a 401.
     */
    @param:Json(name = "expires_at") val expiresAt: String? = null,
)

data class MobileUser(
    val id: Int,
    val username: String,
    @param:Json(name = "is_admin") val isAdmin: Boolean = false,
)

data class ServerInfo(
    val name: String = "TTSRoad",
    /** Server build, e.g. "1.4.0". Absent from the `/library` server object on older builds. */
    val version: String? = null,
    @param:Json(name = "base_url") val baseUrl: String? = null,
    @param:Json(name = "api_version") val apiVersion: Int = 1,
)

data class LibraryResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    /**
     * Which list this is: `followed` (the caller's shelf, and the default) or `all` (the whole
     * server). Echoed by the server so a client can tell that the scope it asked for is the scope
     * it got — an older server ignores the parameter and answers `followed` shaped content with no
     * `scope` key at all.
     */
    val scope: String? = null,
    /** Server-issued cursor, echoed as `updated_since` on the next delta pull. */
    @param:Json(name = "server_time") val serverTime: String? = null,
    @param:Json(name = "updated_since") val updatedSince: String? = null,
    val delta: Boolean = false,
    /** Fiction ids removed since [updatedSince]. Empty on a full response. */
    val deleted: List<Int> = emptyList(),
    val fictions: List<FictionSummary> = emptyList(),
    /** Complete account membership even on a delta; absent on pre-follows servers. */
    @param:Json(name = "following_ids") val followingIds: List<Int>? = null,
    @param:Json(name = "continue_listening") val continueListening: List<ChapterSummary> = emptyList(),
    @param:Json(name = "recent_chapters") val recentChapters: List<ChapterSummary> = emptyList(),
)

/**
 * The two answers to "which fictions".
 *
 * `followed` is what a client that has never heard of follows gets, and it is safe because the
 * server backfills a follow of every fiction for every existing account on upgrade.
 */
enum class LibraryScope(val wireValue: String) {
    Followed("followed"),
    All("all"),
}

/** `POST`/`DELETE /api/mobile/fictions/{id}/follow`. */
data class FollowResponse(
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    /**
     * The state the server now holds — **not** the state that was asked for.
     *
     * Read rather than assumed, so a request that did not do what it looked like cannot render as
     * success. `created`/`removed` say whether anything actually changed and are deliberately not
     * modelled: following something already followed is a no-op, not a failure.
     */
    val following: Boolean = false,
)

data class ChaptersResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val fiction: FictionSummary,
    /** Server-issued cursor, echoed as `updated_since` on the next delta pull. */
    @param:Json(name = "server_time") val serverTime: String? = null,
    @param:Json(name = "updated_since") val updatedSince: String? = null,
    val delta: Boolean = false,
    /** Deleted or newly excluded chapter ids since [updatedSince]. */
    val deleted: List<Int> = emptyList(),
    val total: Int = 0,
    val chapters: List<ChapterSummary> = emptyList(),
)

data class FictionSummary(
    val id: Int = 0,
    val title: String = "Untitled",
    val author: String? = null,
    val slug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    @param:Json(name = "rating_count") val ratingCount: Int? = null,
    @param:Json(name = "total_chapters") val totalChapters: Int = 0,
    @param:Json(name = "done_chapters") val doneChapters: Int = 0,
    @param:Json(name = "pending_chapters") val pendingChapters: Int = 0,
    @param:Json(name = "error_chapters") val errorChapters: Int = 0,
    @param:Json(name = "processing_chapters") val processingChapters: Int = 0,
    /**
     * Whether this account has the fiction on its shelf, or **null when the payload does not say**.
     *
     * Nullable rather than defaulted-false, because the difference is a real trap: only
     * `/api/mobile/library` adds this key. `/api/mobile/fictions/{id}/chapters` builds its `fiction`
     * with `_fiction_payload()`, which does not — so a detail screen that re-read follow state from
     * its own chapters response would flip every followed book to "unfollowed" the moment the
     * chapters arrived. Null means *ask someone else*, and false would have meant "not followed".
     */
    val following: Boolean? = null,
) {
    val readyFraction: Float
        get() = if (totalChapters > 0) (doneChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
}

data class ChapterSummary(
    val id: Int = 0,
    @param:Json(name = "chapter_id") val apiChapterId: Int? = null,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "Untitled chapter",
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "display_number") val displayNumber: Double? = null,
    /**
     * The raw source chapter number. Nullable and gap-prone by design (`app/routers/mobile.py`) —
     * `display_number` is the 1-based ordinal among non-excluded chapters and is what a reader
     * expects to see, so this is only ever a fallback for ordering.
     */
    @param:Json(name = "chapter_number") val chapterNumber: Int? = null,
    /**
     * 0-based index into the server's *playable* queue, or null when this chapter is not playable.
     *
     * It is the server's own answer to "where would this sit in a playlist", so it is the ordering
     * the player queue follows; the desktop client cross-checks its own ordering against it rather
     * than inventing a second notion of chapter order.
     */
    @param:Json(name = "player_index") val playerIndex: Int? = null,
    val status: String? = null,
    /** Finer-grained conversion phase ("fetching_html"/"preprocessing"/"converting"). */
    @param:Json(name = "sub_status") val subStatus: String? = null,
    /** 0-100 while a chapter is converting. */
    @param:Json(name = "tts_progress") val ttsProgress: Int? = null,
    /**
     * The server's own failure text. Modelled so the client can tell "failed" from "queued", and
     * deliberately never rendered: it is backend detail (paths, stack fragments) that a listener
     * cannot act on. The UI shows a short status instead.
     */
    @param:Json(name = "error_message") val errorMessage: String? = null,
    /** Excluded from the fiction by an admin — visible only with `include_excluded=true`. */
    val excluded: Boolean = false,
    val playable: Boolean = false,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    /** Size of the MP3 in bytes, 0 when unknown. Used by the download-state slot. */
    @param:Json(name = "audio_filesize") val audioFilesize: Long = 0L,
    /** Whether the server holds read-along timings for this chapter. */
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    val audio: AudioInfo? = null,
    val playback: PlaybackInfo? = null,
    val fiction: FictionSummary? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_author") val fictionAuthor: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @param:Json(name = "resume_seconds") val resumeSeconds: Double? = null,
    @param:Json(name = "resume_time_label") val resumeTimeLabel: String? = null,
    /** Library-shelf aggregates for the *fiction* this row belongs to, not for the chapter. */
    @param:Json(name = "played_count") val playedCount: Int? = null,
    @param:Json(name = "remaining_count") val remainingCount: Int? = null,
) {
    val resolvedChapterId: Int get() = id.takeIf { it > 0 } ?: apiChapterId ?: 0
    val resolvedFictionId: Int get() = fictionId.takeIf { it > 0 } ?: fiction?.id ?: 0
    val resolvedTitle: String get() = chapterTitle ?: title
    val resolvedFictionTitle: String? get() = fiction?.title ?: fictionTitle
    val resolvedAuthor: String? get() = fiction?.author ?: fictionAuthor
    val resolvedCoverUrl: String? get() = fiction?.coverImageUrl ?: coverImageUrl
    val resolvedPositionSeconds: Double get() = playback?.positionSeconds ?: resumeSeconds ?: 0.0

    /**
     * The number to sort and label by.
     *
     * `display_number` first because that is what the reader sees; `chapter_number` is the fallback
     * for the library's flat shelf shape, which carries only the raw number.
     */
    val resolvedDisplayNumber: Double? get() = displayNumber ?: chapterNumber?.toDouble()

    /**
     * Whether the player can actually open something for this chapter.
     *
     * Deliberately not `playable` and not `status == "done"`: only the presence of an `audio`
     * object proves there is a URL to fetch, and a row without one must never be queued.
     */
    val hasAudio: Boolean get() = audio != null

    val isPlayed: Boolean get() = playback?.isPlayed == true
}

data class AudioInfo(
    val filename: String? = null,
    val path: String? = null,
    val url: String,
    @param:Json(name = "requires_bearer_auth") val requiresBearerAuth: Boolean = true,
)

data class PlaybackInfo(
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "remaining_label") val remainingLabel: String? = null,
    /** Seconds left, server-computed as `max(0, duration - position)`. */
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double? = null,
    /** ISO-8601 UTC; null until the chapter has actually been listened to. */
    @param:Json(name = "last_listened_at") val lastListenedAt: String? = null,
    /** Server write clock used by `updated_since` filtering. */
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    /** Device clock that resolves conflicts between offline writes. */
    @param:Json(name = "client_updated_at") val clientUpdatedAt: String? = null,
)

data class PlaybackProgressRequest(
    @param:Json(name = "fiction_id") val fictionId: Int,
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    @param:Json(name = "is_played") val isPlayed: Boolean,
)

data class PlaybackProgressResponse(
    val status: String = "",
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
)

data class PlaybackMarkRequest(
    @param:Json(name = "chapter_ids") val chapterIds: List<Int>,
    val played: Boolean,
)

data class PlaybackMarkResponse(
    val status: String = "",
    val played: Boolean = false,
    @param:Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    val count: Int = 0,
)
