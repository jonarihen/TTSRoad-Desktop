package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * What the server on the other end can actually do.
 *
 * Every flag defaults to false on purpose. An older server omits the key entirely, and a client
 * that read a missing capability as "present" would call an endpoint that isn't routed and get a
 * 404 it has no good answer for. False means "don't offer the control", which is always safe.
 *
 * The names mirror the backend's `_CAPABILITY_ROUTE_REQUIREMENTS` (app/routers/platform.py), which
 * derives each flag from whether the routes backing it are registered — so this is a description of
 * the API surface, not of the runtime environment behind it. `audiobook_export` being true, for
 * instance, says the export endpoints exist, not that ffmpeg is installed to produce one.
 */
data class ServerCapabilities(
    val readalong: Boolean = false,
    val search: Boolean = false,
    val bookmarks: Boolean = false,
    val queue: Boolean = false,
    val follows: Boolean = false,
    @param:Json(name = "delta_sync") val deltaSync: Boolean = false,
    @param:Json(name = "batch_progress") val batchProgress: Boolean = false,
    @param:Json(name = "audio_content_hash") val audioContentHash: Boolean = false,
    @param:Json(name = "audiobook_export") val audiobookExport: Boolean = false,
    @param:Json(name = "offline_downloads") val offlineDownloads: Boolean = false,
    @param:Json(name = "live_events") val liveEvents: Boolean = false,
    @param:Json(name = "voice_preview") val voicePreview: Boolean = false,
    @param:Json(name = "player_preferences") val playerPreferences: Boolean = false,
    @param:Json(name = "device_management") val deviceManagement: Boolean = false,
)

/**
 * Server-published bounds. These are not advisory: `/playback/sync` rejects a batch larger than
 * [maxPlaybackSyncItems] with a 400 rather than truncating it, so a client that ignores the limit
 * loses the whole batch instead of part of it.
 */
data class ServerLimits(
    @param:Json(name = "max_chapters_per_page") val maxChaptersPerPage: Int = 200,
    @param:Json(name = "max_playback_sync_items") val maxPlaybackSyncItems: Int = 500,
)

data class CapabilitiesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val server: ServerInfo? = null,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val limits: ServerLimits = ServerLimits(),
)
