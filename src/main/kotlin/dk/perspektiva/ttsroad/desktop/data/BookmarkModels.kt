package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * `GET /api/mobile/bookmarks`.
 *
 * The same rows and shapes as the web's `/api/bookmarks` — both routers call into
 * `app/services/bookmarks.py`, so a mark made here is the same record the browser sees.
 */
data class BookmarksResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    /** The cursor to echo back as `updated_since` next time. Server time, never this machine's. */
    @param:Json(name = "server_time") val serverTime: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    /** Ids tombstoned since the cursor. Only ever populated on a delta pull. */
    val deleted: List<Int> = emptyList(),
)

data class Bookmark(
    val id: Int = 0,
    /**
     * **Nullable.** `retire_bookmarks_for_chapters` soft-deletes marks and *clears the link* when a
     * chapter is hard-deleted, rather than letting the foreign key cascade take them silently. So a
     * bookmark can outlive its audio, and a non-null assertion here is a crash waiting for the day
     * someone deletes a chapter.
     */
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    /** Derived server-side from the chapter, so it is null for exactly the same reason. */
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "position_label") val positionLabel: String? = null,
    val label: String? = null,
    val note: String? = null,
    val color: String? = null,
    /**
     * `manual` — a mark the reader chose — or `auto`, a jump-back breadcrumb recorded because
     * playback stopped there. Anywhere the user-chosen list is rendered must filter to `manual`, or
     * a day of listening buries the marks somebody actually made.
     */
    val kind: String = BookmarkKind.Manual,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "deleted_at") val deletedAt: String? = null,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Int? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_slug") val fictionSlug: String? = null,
) {
    /** Whether there is still audio to play. False once the chapter has been deleted server-side. */
    val isPlayable: Boolean get() = (chapterId ?: 0) > 0 && (fictionId ?: 0) > 0

    val positionMs: Long get() = (positionSeconds * 1000).toLong().coerceAtLeast(0L)

    /** What the row is called: the reader's own label, else the chapter, else the position. */
    val displayLabel: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: chapterTitle?.takeIf { it.isNotBlank() }
            ?: positionLabel?.takeIf { it.isNotBlank() }
            ?: "Bookmark"
}

object BookmarkKind {
    const val Manual: String = "manual"
    const val Auto: String = "auto"
}

/** What the server will accept, mirrored so a request it would reject is never sent. */
object BookmarkLimits {
    const val MaxLabelChars: Int = 512
    const val MaxNoteChars: Int = 4000
}

data class BookmarkCreateRequest(
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    val label: String? = null,
    val note: String? = null,
    val kind: String = BookmarkKind.Manual,
)

/**
 * A PATCH.
 *
 * Every field is nullable and Moshi omits nulls, which lines up exactly with the server's rule that
 * an **absent** key means "leave it alone". Clearing a label is expressed by sending an empty
 * string, which `_clean_text` turns into null — so "leave alone" and "clear" stay distinguishable
 * without needing to serialise an explicit JSON null.
 */
data class BookmarkPatchRequest(
    val label: String? = null,
    val note: String? = null,
    @param:Json(name = "position_seconds") val positionSeconds: Double? = null,
)

data class BookmarkWriteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val bookmark: Bookmark? = null,
)

data class BookmarkDeleteResponse(
    val status: String = "",
    val id: Int = 0,
    @param:Json(name = "deleted_at") val deletedAt: String? = null,
)

/**
 * Newest first, and tombstones dropped.
 *
 * A soft-deleted row can come back in a full list as well as in a delta — the server's list query
 * is what decides — so filtering here rather than trusting the shape is what keeps a deleted mark
 * from reappearing after a refresh.
 */
fun List<Bookmark>.visibleBookmarks(): List<Bookmark> =
    filter { it.deletedAt == null }.sortedByDescending { it.createdAt.orEmpty() }
