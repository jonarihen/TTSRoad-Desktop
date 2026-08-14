package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/** The cheap `/api/mobile/sync` index that says which resource deltas are worth pulling. */
data class DeltaSyncResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "server_time") val serverTime: String? = null,
    @param:Json(name = "updated_since") val updatedSince: String? = null,
    val delta: Boolean = false,
    val limits: DeltaSyncLimits = DeltaSyncLimits(),
    val endpoints: DeltaSyncEndpoints = DeltaSyncEndpoints(),
    val changed: DeltaSyncChanged = DeltaSyncChanged(),
    val deleted: DeltaSyncDeleted = DeltaSyncDeleted(),
) {
    /** The library payload's rows or always-complete listening rails may have changed. */
    val changesLibrary: Boolean
        get() = changed.fictions.isNotEmpty() || changed.playback > 0 || deleted.fictions.isNotEmpty()

    fun changesFiction(fictionId: Int): Boolean =
        fictionId > 0 && changed.fictions.any { it.fictionId == fictionId }
}

data class DeltaSyncLimits(
    @param:Json(name = "max_chapters_per_page") val maxChaptersPerPage: Int? = null,
    @param:Json(name = "max_playback_sync_items") val maxPlaybackSyncItems: Int? = null,
)

data class DeltaSyncEndpoints(
    val library: String? = null,
    val chapters: String? = null,
    val bookmarks: String? = null,
    @param:Json(name = "playback_sync") val playbackSync: String? = null,
)

data class DeltaSyncChanged(
    val fictions: List<DeltaSyncFiction> = emptyList(),
    val playback: Int = 0,
    val bookmarks: Int = 0,
)

data class DeltaSyncFiction(
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val slug: String? = null,
    val title: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "changed_chapters") val changedChapters: Int = 0,
    @param:Json(name = "deleted_chapters") val deletedChapters: Int = 0,
    @param:Json(name = "changed_playback") val changedPlayback: Int = 0,
)

data class DeltaSyncDeleted(
    val fictions: List<Int> = emptyList(),
    val chapters: List<Int> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
)

/**
 * Applies a library delta to the full local view.
 *
 * Listening rails are complete even on a delta, so they come from [delta] outright. Fiction rows
 * are keyed merges and tombstones are consumed. A server that ignored `updated_since` answers a
 * full response (`delta=false`), which is already a safe replacement.
 */
fun LibraryResponse.mergedWith(delta: LibraryResponse): LibraryResponse {
    if (!delta.delta) return delta
    require(scope == null || delta.scope == null || scope == delta.scope) {
        "library delta scope did not match the cached library"
    }
    val deletedIds = delta.deleted.toSet()
    val followedIds = delta.followingIds?.toSet().takeIf { delta.scope == LibraryScope.Followed.wireValue }
    val changedById = delta.fictions.filter { it.id > 0 }.associateBy { it.id }
    val retained = fictions.mapNotNull { fiction ->
        when {
            fiction.id in deletedIds -> null
            followedIds != null && fiction.id !in followedIds -> null
            changedById.containsKey(fiction.id) -> changedById.getValue(fiction.id)
            else -> fiction
        }
    }
    val knownIds = retained.mapTo(HashSet()) { it.id }
    val added = delta.fictions.filter {
        it.id > 0 && it.id !in knownIds && it.id !in deletedIds && (followedIds == null || it.id in followedIds)
    }
    return delta.copy(
        scope = delta.scope ?: scope,
        delta = false,
        updatedSince = null,
        deleted = emptyList(),
        fictions = retained + added,
    )
}

/**
 * A newly followed fiction can be named by `following_ids` without appearing in the changed rows:
 * the follow moved, not the fiction. The delta cannot materialize a row it did not send, so that
 * one case requires a full shelf pull.
 */
fun LibraryResponse.deltaNeedsFullFollowPull(delta: LibraryResponse): Boolean {
    if (delta.scope != LibraryScope.Followed.wireValue) return false
    val followed = delta.followingIds ?: return false
    val available = (fictions + delta.fictions).mapTo(HashSet()) { it.id }
    return followed.any { it !in available }
}

/** Applies one fiction's changed rows and tombstones to its full cached chapter list. */
fun ChaptersResponse.mergedWith(delta: ChaptersResponse): ChaptersResponse {
    if (!delta.delta) return delta
    require(fiction.id == delta.fiction.id) { "chapter delta belonged to another fiction" }
    val deletedIds = delta.deleted.toSet()
    val changedById = delta.chapters.filter { it.resolvedChapterId > 0 }
        .associateBy { it.resolvedChapterId }
    val retained = chapters.mapNotNull { chapter ->
        val id = chapter.resolvedChapterId
        when {
            id in deletedIds -> null
            changedById.containsKey(id) -> changedById.getValue(id)
            else -> chapter
        }
    }
    val knownIds = retained.mapTo(HashSet()) { it.resolvedChapterId }
    val added = delta.chapters.filter {
        val id = it.resolvedChapterId
        id > 0 && id !in knownIds && id !in deletedIds
    }
    // `display_number` and `player_index` are derived from the whole fiction. Excluding or
    // inserting one row shifts every later value even though those chapter rows did not change,
    // so accepting the sparse values verbatim would leave duplicate numbers in the merged cache.
    var nextPlayerIndex = 0
    val merged = (retained + added)
        .sortedWith(compareBy<ChapterSummary> { it.chapterNumber ?: Int.MAX_VALUE }.thenBy { it.resolvedChapterId })
        .mapIndexed { index, chapter ->
            chapter.copy(
                displayNumber = (index + 1).toDouble(),
                playerIndex = if (chapter.hasAudio) nextPlayerIndex++ else null,
            )
        }
    return delta.copy(
        delta = false,
        updatedSince = null,
        deleted = emptyList(),
        total = merged.size,
        chapters = merged,
    )
}

/** Advances a fully materialized resource after the index proves nothing in it changed. */
fun LibraryResponse.withSyncCursor(serverTime: String): LibraryResponse =
    copy(serverTime = serverTime, updatedSince = null, delta = false, deleted = emptyList())

fun ChaptersResponse.withSyncCursor(serverTime: String): ChaptersResponse =
    copy(serverTime = serverTime, updatedSince = null, delta = false, deleted = emptyList())
