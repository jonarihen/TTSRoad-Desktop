package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * Raw `GET /api/mobile/capabilities` body.
 *
 * `capabilities` and `limits` are deliberately loose maps rather than typed fields: the endpoint is
 * additive by contract, so a server newer than this build will send keys it has never heard of, and
 * a strict model would fail the whole payload over one unknown entry.
 */
data class CapabilitiesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val server: CapabilityServerInfo? = null,
    val capabilities: Map<String, Any?> = emptyMap(),
    val limits: Map<String, Any?> = emptyMap(),
)

data class CapabilityServerInfo(
    val name: String = "TTSRoad",
    val version: String? = null,
    @param:Json(name = "base_url") val baseUrl: String? = null,
)

/**
 * The optional server features this client knows how to use.
 *
 * Every flag defaults to false, which is what makes [Baseline] a correct answer for a server that
 * predates discovery entirely. `api_version` is never consulted: it tracks *breaking* changes to
 * the baseline API, so using it to infer an additive feature would light up UI the server cannot
 * serve. (Concretely: a 1.4.0 server reports `batch_progress: false` while happily accepting a
 * multi-id `playback/mark` — the flag tracks a named route, not the ability.)
 */
data class ServerCapabilities(
    val serverName: String = "TTSRoad",
    val serverVersion: String? = null,
    /** Stable identity advertised by the server; never used as the address for a request. */
    val serverBaseUrl: String? = null,
    val apiVersion: Int = 1,
    val readAlong: Boolean = false,
    val search: Boolean = false,
    val bookmarks: Boolean = false,
    val deltaSync: Boolean = false,
    val batchProgress: Boolean = false,
    val audioContentHash: Boolean = false,
    /** Admin add/edit/delete routes. Account permission is verified separately through `/me`. */
    val fictionManagement: Boolean = false,
    /**
     * Multipart EPUB import, advertised separately from JSON fiction CRUD.
     *
     * The backend is explicit that a deployment can support add/edit/delete without accepting
     * files, so this is never inferred from [fictionManagement].
     */
    val epubUpload: Boolean = false,
    /**
     * Per-user libraries.
     *
     * False means `/api/mobile/library` is still the whole shared list on that server, so this
     * client must not offer a follow control it cannot honour — the capability comment in
     * `app/routers/platform.py` states exactly that consequence.
     */
    val follows: Boolean = false,
    val deviceManagement: Boolean = false,
    /**
     * A server-side cross-library queue this client can read and mutate.
     *
     * False means the account has no shared queue at all on that server — not that it is empty — so
     * the surface is hidden rather than shown holding nothing. The local per-fiction queue the
     * player builds is unaffected either way; the two are different things (see [ServerQueueItem]).
     */
    val queue: Boolean = false,
    /** Read-only list/download surface for finished whole-fiction M4B exports. */
    val audiobookExport: Boolean = false,
    /**
     * New-chapter notices for followed serials, held open until the chapter is listenable.
     *
     * Says nothing about push: the backend advertises this on a deployment with no FCM or VAPID
     * credential at all, because the list is polled. The desktop needs no push credential either —
     * its system notification is a rendering of state it already has.
     */
    val notifications: Boolean = false,
    /**
     * `GET /api/mobile/voices` — the narrator catalogue.
     *
     * Listing is open to any signed-in account; *applying* a choice is admin-gated by the fiction
     * `PATCH`. So this flag alone is never the gate for the picker — see `canPickVoice`, which takes
     * both halves. Previewing a voice is a separate capability and deliberately not mirrored here:
     * a cache miss spends an outbound synthesis request to Microsoft.
     */
    val voiceCatalogue: Boolean = false,
    /**
     * Repair one chapter: retry, exclude, delete.
     *
     * Says only that the routes exist. The three do not share a gate — retry is open to any
     * signed-in account, and the destructive pair is admin-only — so `me.is_admin` decides which of
     * them this account may actually use.
     */
    val chapterMaintenance: Boolean = false,
    /**
     * Act on a whole fiction: poll it now, requeue its failures, retag, re-filter, re-narrate.
     *
     * Like [chapterMaintenance], the flag says only that the routes exist, and the gate is not
     * uniform: poll is open to any account and the other four are admin-only.
     */
    val fictionMaintenance: Boolean = false,
    /**
     * The podcast URLs this account can hand to a podcast app, and the lever that revokes them.
     *
     * Worth its own flag because the whole use is a copy button: a server that cannot list the URLs
     * cannot have that control drawn at all.
     */
    val feedUrls: Boolean = false,
    /** Export and re-import where this account is in everything. */
    val listeningStateBackup: Boolean = false,
    val maxChaptersPerPage: Int? = null,
    /**
     * How many items `/playback/sync` accepts in one batch.
     *
     * Null until discovery reaches a server that publishes it. Not advisory: an oversized batch is
     * answered with a 400 rather than truncated, so a client that guesses high loses the whole
     * batch rather than part of it.
     */
    val maxPlaybackSyncItems: Int? = null,
    /** The server's EPUB ceiling. A `Long`, because a byte count is not an item count. */
    val maxEpubBytes: Long? = null,
    /**
     * The server's cover-art ceiling, when it publishes one.
     *
     * Null on every server built so far: cover upload is an additive route with no advertised
     * limit yet, so the editor checks what it can locally (format, emptiness) and lets a 413 speak
     * for the rest. Parsed anyway because the day a server does publish it, the pre-upload check
     * should start working without a client release.
     */
    val maxCoverBytes: Long? = null,
) {
    /** True once discovery has actually reached a TTSRoad server (only it reports a version). */
    val isDiscovered: Boolean get() = serverVersion != null

    companion object {
        /** What an older server — or an unreachable one — is assumed to support. */
        val Baseline: ServerCapabilities = ServerCapabilities()

        fun from(response: CapabilitiesResponse): ServerCapabilities = ServerCapabilities(
            serverName = response.server?.name ?: "TTSRoad",
            serverVersion = response.server?.version,
            serverBaseUrl = response.server?.baseUrl,
            apiVersion = response.apiVersion,
            readAlong = response.capabilities.flag("readalong"),
            search = response.capabilities.flag("search"),
            bookmarks = response.capabilities.flag("bookmarks"),
            deltaSync = response.capabilities.flag("delta_sync"),
            batchProgress = response.capabilities.flag("batch_progress"),
            audioContentHash = response.capabilities.flag("audio_content_hash"),
            fictionManagement = response.capabilities.flag("fiction_management"),
            epubUpload = response.capabilities.flag("epub_upload"),
            follows = response.capabilities.flag("follows"),
            deviceManagement = response.capabilities.flag("device_management"),
            queue = response.capabilities.flag("queue"),
            audiobookExport = response.capabilities.flag("audiobook_export"),
            notifications = response.capabilities.flag("notifications"),
            voiceCatalogue = response.capabilities.flag("voice_catalogue"),
            chapterMaintenance = response.capabilities.flag("chapter_maintenance"),
            fictionMaintenance = response.capabilities.flag("fiction_maintenance"),
            feedUrls = response.capabilities.flag("feed_urls"),
            listeningStateBackup = response.capabilities.flag("listening_state_backup"),
            maxChaptersPerPage = response.limits.intLimit("max_chapters_per_page"),
            maxPlaybackSyncItems = response.limits.intLimit("max_playback_sync_items"),
            maxEpubBytes = response.limits.longLimit("max_epub_bytes"),
            maxCoverBytes = response.limits.longLimit("max_cover_bytes"),
        )

        /**
         * Only a literal JSON `true` enables a feature. A string, a number, or null means the
         * server is saying something this build does not understand, and guessing would light up
         * UI backed by an endpoint that answers 404.
         */
        private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

        /** Moshi parses every JSON number as a Double, so accept any [Number] and drop the rest. */
        private fun Map<String, Any?>.intLimit(key: String): Int? = (this[key] as? Number)?.toInt()

        private fun Map<String, Any?>.longLimit(key: String): Long? = (this[key] as? Number)?.toLong()
    }
}
