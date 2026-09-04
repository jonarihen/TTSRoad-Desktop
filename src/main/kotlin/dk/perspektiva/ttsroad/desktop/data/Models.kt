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
    /** Narration voice; present on management/detail payloads and optional on older library rows. */
    val voice: String? = null,
    /**
     * Speech rate for the next conversion, as `+0%` / `-10%`.
     *
     * `FictionResponse` has always carried it beside `voice`; this client simply never decoded it,
     * which is why the rate of an existing fiction could not be shown or changed. Nullable for the
     * same reason [voice] is: `/chapters` builds its `fiction` without either.
     */
    val rate: String? = null,
    val slug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    @param:Json(name = "rating_count") val ratingCount: Int? = null,
    /**
     * Which metadata fields a person edited, and which the server therefore stops refreshing from
     * the source. The names are the server's own — see [FictionMetadataFields].
     *
     * Defaults to empty rather than to null, because "this server has never heard of hand edits"
     * and "nothing here is hand-edited" want exactly the same screen: no ownership markers, every
     * field still the source's. A server that supports them says so by listing names.
     */
    @param:Json(name = "metadata_overrides") val metadataOverrides: List<String> = emptyList(),
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
    /**
     * When the fiction row was created and last touched, as the server serialised them.
     *
     * ISO-8601 strings rather than parsed instants: they are only ever compared with each other and
     * sorted, which the lexicographic order of a UTC ISO stamp already gives, and parsing would
     * turn a server sending a shape this build has not seen into a failed library load.
     *
     * Null on a server that does not send them. Null is **not** "a long time ago" — it means *we
     * were not told* — so every order built on these sorts nulls last. See [FictionSort].
     */
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    /**
     * This caller's progress through the fiction, computed by the server in one grouped query.
     *
     * Nested rather than flattened alongside [totalChapters] and [doneChapters], exactly as the
     * backend nests it, and for the backend's own stated reason: those are properties of the
     * fiction and the same for everybody, while every field in here is scoped to the account
     * asking. A shared-looking object holding per-user numbers is how a cache serves one listener
     * another's progress.
     *
     * Null on `/api/mobile/fictions/{id}/chapters`, which builds its `fiction` with
     * `_fiction_payload()` and does not add the key, and on any server predating the aggregate.
     */
    val progress: FictionProgress? = null,
) {
    val readyFraction: Float
        get() = if (totalChapters > 0) (doneChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
}

/**
 * What one account has left of one fiction, as the server counts it.
 *
 * The labels are the server's own rendering and are preferred over anything computed here, so the
 * desktop and the web shelf cannot disagree about the same book by rounding differently.
 */
data class FictionProgress(
    @param:Json(name = "chapters_total") val chaptersTotal: Int = 0,
    /** Chapters with audio — the ones that can actually be listened to. */
    @param:Json(name = "chapters_ready") val chaptersReady: Int = 0,
    @param:Json(name = "chapters_played") val chaptersPlayed: Int = 0,
    @param:Json(name = "chapters_unplayed") val chaptersUnplayed: Int = 0,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    @param:Json(name = "duration_label") val durationLabel: String? = null,
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double = 0.0,
    @param:Json(name = "remaining_label") val remainingLabel: String? = null,
) {
    /**
     * How much of what can be heard has been heard, or null when nothing can be yet.
     *
     * Null rather than zero for an unconverted book: "0% listened" and "there is nothing to listen
     * to" are different sentences, and only one of them is about the reader.
     */
    val listenedFraction: Float?
        get() = if (chaptersReady > 0) {
            (chaptersPlayed.toFloat() / chaptersReady).coerceIn(0f, 1f)
        } else {
            null
        }

    /** True where the server actually had something to say, as opposed to filling in the shape. */
    val isMeaningful: Boolean get() = chaptersTotal > 0 || chaptersReady > 0
}

/**
 * How much of a serial's backlog to convert when it is first tracked.
 *
 * The whole point of naming this is that **the server's default is everything**: `add_fiction`
 * branches on `if body.sync_limit:` and otherwise calls `poll_and_process_fiction(id, True)`. A
 * client that omits the field is not accepting a sensible default, it is queueing four hundred
 * chapters of TTS. The web form has posted 25 since it existed.
 */
enum class SyncScope(val label: String, val detail: String) {
    NewestTwentyFive("Newest 25", "What the web console does. Older chapters can be filled in later."),
    OldestTwentyFive("Oldest 25", "Start at the beginning of the serial."),
    Everything("Everything", "Converts the entire backlog now. On a long serial this is hours of audio."),
    ;

    /** Null for [Everything], which is the server's own "no limit" sentinel. */
    val limit: Int? get() = if (this == Everything) null else DefaultBatch

    /** The server's vocabulary: `last` counts back from the newest chapter, `first` forward. */
    val direction: String get() = if (this == OldestTwentyFive) "first" else "last"

    companion object {
        /** Matches the web form, which is the behaviour everyone adding a fiction already expects. */
        val Default: SyncScope = NewestTwentyFive
        private const val DefaultBatch: Int = 25
    }
}

/**
 * Admin-only `POST /api/mobile/fictions`. A bare Royal Road id is accepted by the server.
 *
 * [syncLimit] is deliberately **not** nullable-by-omission in the caller's mind: see [SyncScope].
 * `enabled` is the auto-poll switch and defaults to the server's own `True`.
 */
data class FictionCreateRequest(
    @param:Json(name = "fiction_url") val fictionUrl: String,
    val voice: String? = null,
    /** Speech rate, in the same vocabulary `FictionUpdate.rate` uses. Null leaves the default. */
    val rate: String? = null,
    val enabled: Boolean = true,
    @param:Json(name = "sync_limit") val syncLimit: Int? = null,
    @param:Json(name = "sync_direction") val syncDirection: String = "last",
)

/**
 * Admin-only fields intentionally exposed by the desktop editor.
 *
 * Every field is nullable and Moshi omits a null rather than writing one, which is what makes the
 * server's "absent means leave this alone" reachable from here. That distinction is load-bearing
 * now that editing a metadata field also *claims* it: sending a title the user never touched would
 * quietly freeze it against every future refresh of the source. The editor therefore sends the
 * fields somebody actually changed, and nothing else.
 */
data class FictionUpdateRequest(
    val title: String? = null,
    /** Empty string clears the author. */
    val author: String? = null,
    /** Empty string clears the description. Ignored by a server that predates hand-edited metadata. */
    val description: String? = null,
    /** Empty list clears the tags. The server trims, de-duplicates and caps whatever it is sent. */
    val tags: List<String>? = null,
    val voice: String? = null,
    /**
     * Speech rate. Like [voice] and unlike the metadata fields, writing it claims nothing: no poll
     * has ever set it, so there is no source ownership to take away.
     */
    val rate: String? = null,
    /**
     * Metadata field names to hand back to the source, so the next poll may overwrite them again.
     *
     * It does **not** restore the value the source last had — nothing keeps a copy of it — and the
     * server applies this after the field assignments above, so releasing a field wins over having
     * just set it in the same request. Unknown names are ignored rather than rejected.
     */
    @param:Json(name = "clear_overrides") val clearOverrides: List<String>? = null,
)

/**
 * The metadata fields a person can take ownership of, named exactly as the server names them.
 *
 * String constants rather than an enum because these names travel on the wire in both directions:
 * a server newer than this build may protect a field this client has never heard of, and an
 * unknown name has to survive being read and echoed rather than failing to parse.
 */
object FictionMetadataFields {
    const val Title: String = "title"
    const val Author: String = "author"
    const val Description: String = "description"
    const val Tags: String = "tags"
    const val CoverImage: String = "cover_image_url"

    /** Ordered the way the editor lays the fields out, which is also how they are listed to a user. */
    val All: List<String> = listOf(Title, Author, Description, Tags, CoverImage)

    /** Sentence-case label for one field name, including one this build does not know. */
    fun labelOf(field: String): String = when (field) {
        Title -> "Title"
        Author -> "Author"
        Description -> "Description"
        Tags -> "Tags"
        CoverImage -> "Cover art"
        else -> field.replace('_', ' ')
    }
}

/** What the server will store for a hand-typed tag list (`_clean_tags` in `app/routers/fictions.py`). */
object FictionTagLimits {
    const val MaxTags: Int = 50
    const val MaxTagChars: Int = 100
}

/**
 * Normalise a tag list the way the server will.
 *
 * Mirrored rather than left to the backend so the editor can show what is actually going to be
 * stored: a round trip that silently drops the third spelling of "LitRPG" reads as the save having
 * failed. De-duplication is case-insensitive and keeps the first spelling, so typing "litrpg" after
 * "LitRPG" does not restyle the tag that is already there.
 */
fun cleanFictionTags(values: List<String>): List<String> {
    val cleaned = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (raw in values) {
        val tag = raw.split(WhitespaceRun).filter(String::isNotEmpty).joinToString(" ")
            .take(FictionTagLimits.MaxTagChars)
        if (tag.isEmpty() || !seen.add(tag.lowercase())) continue
        cleaned += tag
        if (cleaned.size >= FictionTagLimits.MaxTags) break
    }
    return cleaned
}

private val WhitespaceRun = Regex("\\s+")

/**
 * Cover art formats the server stores, and what to call an upload on the wire.
 *
 * The server decides from the decoded bytes rather than from the filename or the declared type, so
 * this is a courtesy — but it is also what the native picker filters on, and telling somebody their
 * TIFF is unsupported before it uploads is better than a 400 afterwards.
 */
object CoverImageFormats {
    val Extensions: List<String> = listOf("jpg", "jpeg", "png", "webp", "gif")

    /** "JPEG, PNG, WEBP or GIF", for a supporting line under the cover control. */
    val Description: String = "JPEG, PNG, WEBP or GIF"

    fun isSupported(fileName: String): Boolean = extensionOf(fileName) in Extensions

    fun mediaTypeOf(fileName: String): String = when (extensionOf(fileName)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
}

data class FictionMutationResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val fiction: FictionSummary,
)

data class FictionDeleteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val deleted: Boolean = false,
)

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

/**
 * `GET /api/mobile/voices` — the edge-tts catalogue, in an envelope (#109).
 *
 * Several hundred entries across a hundred-odd locales, which is why nothing is drawn as a flat
 * list; see `voiceGroups`.
 */
data class VoicesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val voices: List<MobileVoice> = emptyList(),
)

/**
 * One narrator.
 *
 * [name] is the short form — `en-US-BrianNeural` — which is both what a fiction stores and what
 * `FictionUpdateRequest.voice` expects; nothing here ever sends a display name. It is the one field
 * with no meaningful default, so a row arriving without one is dropped rather than offered as a
 * choice that cannot be applied.
 *
 * [locale] and [gender] are nullable on purpose. The server sends both today, and they drive
 * grouping and one line of description — neither is worth failing a parse over, and a catalogue
 * that would not load at all is worse than a voice filed under "Other".
 */
data class MobileVoice(
    val name: String = "",
    val locale: String? = null,
    val gender: String? = null,
)

/**
 * The answer every maintenance route gives (#113).
 *
 * One shape across all of them, and the counts are the reason it is worth modelling rather than
 * ignoring: "requeued" and "did nothing" both come back as `status: "ok"`, and only a number
 * separates them.
 *
 * Every field is nullable because each route fills a different subset. A chapter retry sets
 * `chapterId` and nothing else; the fiction-wide routes this client does not call yet fill the
 * counters, and they are decoded rather than dropped so adding those routes needs no model change.
 */
data class MaintenanceResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    /** Whether the chapter is now excluded, echoed by the exclude route. */
    val excluded: Boolean? = null,
    val deleted: Boolean? = null,
    /** Chapters requeued by `retry-failed` or `reconvert-all`. */
    @param:Json(name = "reset_count") val resetCount: Int? = null,
    /** MP3s whose ID3 tags `retag` rewrote. */
    @param:Json(name = "file_count") val fileCount: Int? = null,
    /** Chapters the filter took out. Never un-excludes: a hand-excluded chapter had a reason. */
    @param:Json(name = "excluded_count") val excludedCount: Int? = null,
    /** True when `poll` re-ingested the whole chapter list rather than the recent tail. */
    @param:Json(name = "full_ingest") val fullIngest: Boolean = false,
    /** How many chapters a partial poll re-read, when it took that branch. */
    @param:Json(name = "partial_sync") val partialSync: Int? = null,
    /**
     * Why nothing happened, when that is worth saying.
     *
     * `apply-chapter-filter` sets it when the fiction has no filter configured — which is not a
     * failure, and is the difference between "excluded nothing" and "there was no rule to run".
     */
    val detail: String? = null,
)

/** `POST /api/mobile/chapters/{id}/exclude` — take a chapter off every feed, or put it back. */
data class ChapterExcludeRequest(val excluded: Boolean = true)

/**
 * `GET /api/mobile/feeds` — every podcast URL this account can hand to a podcast app (#117).
 *
 * A private podcast feed is what this project is for, and until now the only way to get a tokenised
 * URL into a podcast app was to open the web console and copy it from there.
 */
data class FeedsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    /** `followed` or `all`, echoed back — the same scoping `/library` uses. */
    val scope: String = "followed",
    val library: LibraryFeedUrls? = null,
    val fictions: List<FictionFeedUrl> = emptyList(),
)

/**
 * This account's own feeds, which are the ones its rotate lever revokes.
 *
 * Both URLs carry a token derived from the user and a version number, so rotating bumps the version
 * and every previously issued URL stops working.
 */
data class LibraryFeedUrls(
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int = 0,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
    @param:Json(name = "opml_url") val opmlUrl: String? = null,
)

/**
 * One fiction's feed.
 *
 * Deliberately *not* user-scoped: the token comes from the fiction, so this is the same string for
 * every account on the server and rotating it is a separate admin action on that fiction. The
 * account's own rotate does not touch it — which is worth saying on screen, because listing these
 * beside the account's own URLs implies otherwise.
 */
data class FictionFeedUrl(
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "",
    val slug: String? = null,
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int = 0,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
)

/**
 * `GET /api/mobile/account/listening-state` — where this account is in everything (#119).
 *
 * The document is deliberately opaque here: it is the server's own shape, written to a file and
 * posted back unchanged. Modelling its interior would mean a client that had to be updated whenever
 * the server added a field, for no gain — nothing here reads it.
 */
data class ListeningStateExport(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val document: Map<String, Any?>? = null,
)

/** `POST /api/mobile/account/listening-state` — what the merge did. */
data class ListeningStateImport(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val report: ListeningStateReport? = null,
)

/**
 * The merge, counted.
 *
 * Every field matters to somebody, and reducing this to "imported" throws away the only answer to
 * *did it actually do anything*. [playbackSkippedOlder] especially: it is what explains a restore
 * that looks like it did nothing, because the server only ever moves a position forward.
 */
data class ListeningStateReport(
    @param:Json(name = "fictions_matched") val fictionsMatched: Int = 0,
    /** Titles the document named that this server does not have. Expected across servers. */
    @param:Json(name = "fictions_missing") val fictionsMissing: List<String> = emptyList(),
    @param:Json(name = "chapters_missing") val chaptersMissing: Int = 0,
    @param:Json(name = "playback_restored") val playbackRestored: Int = 0,
    @param:Json(name = "playback_skipped_older") val playbackSkippedOlder: Int = 0,
    @param:Json(name = "bookmarks_restored") val bookmarksRestored: Int = 0,
    @param:Json(name = "bookmarks_already_present") val bookmarksAlreadyPresent: Int = 0,
    @param:Json(name = "bookmarks_skipped_full") val bookmarksSkippedFull: Int = 0,
)
