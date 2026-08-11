package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Now, as a stamp the backend will actually accept.
 *
 * Truncated to milliseconds deliberately. `Instant.now().toString()` emits as many fractional
 * digits as the platform clock has — up to nine — and the backend hands the string to
 * `datetime.fromisoformat`, which before Python 3.11 accepts only three or six. The backend's
 * stated floor is 3.10, so a nine-digit stamp comes back as `invalid_client_updated_at` and the
 * write is dropped: the exact data loss this endpoint exists to prevent, visible only on some
 * deployments. Filed upstream as jonarihen/TTSRoad#88; this holds regardless of that.
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
 * An item the server refused.
 *
 * Every [reason] it can give — `not_found`, `stale`, `empty`, `missing_client_updated_at`,
 * `invalid_client_updated_at` — is terminal for that item: re-sending the same payload gets the
 * same answer. A rejection means drop it, not retry it.
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
