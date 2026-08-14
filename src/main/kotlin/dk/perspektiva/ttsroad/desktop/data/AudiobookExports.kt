package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/** Read-only `/api/mobile/exports`: finished M4B volumes an admin can save elsewhere. */
data class AudiobookExportsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "ffmpeg_available") val ffmpegAvailable: Boolean = false,
    val exports: List<AudiobookExport> = emptyList(),
)

data class AudiobookExport(
    val id: Int = 0,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
    @param:Json(name = "batch_id") val batchId: String? = null,
    @param:Json(name = "part_index") val partIndex: Int = 1,
    @param:Json(name = "part_count") val partCount: Int = 1,
    val title: String = "Audiobook",
    val filename: String = "audiobook.m4b",
    val status: String = "done",
    val progress: Int = 100,
    @param:Json(name = "encode_mode") val encodeMode: String? = null,
    @param:Json(name = "chapter_count") val chapterCount: Int = 0,
    @param:Json(name = "first_chapter_number") val firstChapterNumber: Int? = null,
    @param:Json(name = "last_chapter_number") val lastChapterNumber: Int? = null,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    @param:Json(name = "duration_label") val durationLabel: String? = null,
    @param:Json(name = "size_bytes") val sizeBytes: Long = 0L,
    @param:Json(name = "size_label") val sizeLabel: String? = null,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "completed_at") val completedAt: String? = null,
    @param:Json(name = "download_url") val downloadUrl: String? = null,
    val downloadable: Boolean = false,
    @param:Json(name = "requires_bearer_auth") val requiresBearerAuth: Boolean = true,
    /** Always false by contract: per-chapter playback has the useful resume semantics. */
    @param:Json(name = "playable_in_app") val playableInApp: Boolean = false,
)
