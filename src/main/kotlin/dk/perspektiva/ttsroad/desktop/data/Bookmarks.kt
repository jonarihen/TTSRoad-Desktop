package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * One bookmark, in the same shape the web reads from `/api/bookmarks` — same rows, different door,
 * so a mark made here shows up in the browser.
 *
 * [chapterId] is nullable because it really can be null: when a chapter is hard-deleted the server
 * soft-deletes its marks and clears the link rather than letting the foreign key cascade take the
 * rows silently. Such a bookmark has no position left to seek to.
 *
 * Chapter and fiction titles ride along so an account-wide list can render without a second request
 * per row.
 */
data class Bookmark(
    val id: Int = 0,
    @param:Json(name = "user_id") val userId: Int = 0,
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "position_label") val positionLabel: String? = null,
    val label: String? = null,
    val note: String? = null,
    val color: String? = null,
    val kind: String = BookmarkKind.MANUAL,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "deleted_at") val deletedAt: String? = null,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
) {
    /** A retired mark points at a chapter that no longer exists, so it cannot be played. */
    val isPlayable: Boolean get() = chapterId != null
}

object BookmarkKind {
    /** A mark the user chose to make. */
    const val MANUAL = "manual"

    /**
     * A breadcrumb recorded because playback stopped somewhere, not because anyone chose it. Shares
     * the table so both can sync on one endpoint, but filterable so a list of chosen marks is not
     * drowned in them. This client only writes MANUAL.
     */
    const val AUTO = "auto"
}

data class BookmarkCreateRequest(
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    val label: String? = null,
    val note: String? = null,
    val color: String? = null,
    val kind: String = BookmarkKind.MANUAL,
)

/**
 * A partial update. Moshi omits null fields, and the server reads an absent key as "leave it
 * alone" — so a request that sets only [label] does not blank the note.
 */
data class BookmarkUpdateRequest(
    @param:Json(name = "position_seconds") val positionSeconds: Double? = null,
    val label: String? = null,
    val note: String? = null,
    val color: String? = null,
    val kind: String? = null,
)

data class BookmarkListResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "server_time") val serverTime: String? = null,
    @param:Json(name = "updated_since") val updatedSince: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    /** Tombstoned ids — only populated on a delta pull, empty on a full one. */
    val deleted: List<Int> = emptyList(),
)

data class BookmarkWriteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val bookmark: Bookmark,
)

data class BookmarkDeleteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val id: Int = 0,
    @param:Json(name = "deleted_at") val deletedAt: String? = null,
)
