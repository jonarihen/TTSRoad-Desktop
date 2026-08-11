package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One recorded listening position, waiting to reach the server.
 *
 * [clientUpdatedAt] is the whole point. `/playback/progress` writes whatever it is handed, so a
 * position recorded on this machine while offline overwrites a newer one reached in the browser
 * since. `/playback/sync` orders writes by this stamp and only lets a strictly newer one win.
 */
data class PendingProgress(
    val fictionId: Int,
    val chapterId: Int,
    val positionSeconds: Double,
    val isPlayed: Boolean,
    val clientUpdatedAt: String,
)

/**
 * Now, as a stamp the backend will actually accept.
 *
 * Truncated to milliseconds deliberately. `Instant.now().toString()` emits as many fractional
 * digits as the platform clock has — up to nine — and the backend's `parse_iso8601` hands the
 * string to `datetime.fromisoformat`, which before Python 3.11 accepts only three or six. A
 * nine-digit stamp would come back as `invalid_client_updated_at` and the write would be dropped,
 * which is the exact failure this endpoint exists to prevent.
 */
fun nowStamp(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

data class PlaybackSyncItem(
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    @param:Json(name = "is_played") val isPlayed: Boolean,
    @param:Json(name = "client_updated_at") val clientUpdatedAt: String,
)

data class PlaybackSyncRequest(val items: List<PlaybackSyncItem>)

data class PlaybackSyncAccepted(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
)

/**
 * An item the server refused. Every [reason] it can give — `not_found`, `stale`, `empty`,
 * `missing_client_updated_at`, `invalid_client_updated_at` — is terminal for that item: re-sending
 * the same payload gets the same answer, so a rejection means drop it, not retry it.
 */
data class PlaybackSyncRejected(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    val reason: String = "",
    @param:Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
)

/** What the server holds for a chapter after the batch was applied. */
data class PlaybackStateRow(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "last_listened_at") val lastListenedAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "client_updated_at") val clientUpdatedAt: String? = null,
)

data class PlaybackSyncResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "server_time") val serverTime: String? = null,
    val accepted: List<PlaybackSyncAccepted> = emptyList(),
    val rejected: List<PlaybackSyncRejected> = emptyList(),
    @param:Json(name = "server_state") val serverState: List<PlaybackStateRow> = emptyList(),
)
