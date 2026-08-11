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
    val deviceManagement: Boolean = false,
    val maxChaptersPerPage: Int? = null,
    /**
     * How many items `/playback/sync` accepts in one batch.
     *
     * Null until discovery reaches a server that publishes it. Not advisory: an oversized batch is
     * answered with a 400 rather than truncated, so a client that guesses high loses the whole
     * batch rather than part of it.
     */
    val maxPlaybackSyncItems: Int? = null,
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
            deviceManagement = response.capabilities.flag("device_management"),
            maxChaptersPerPage = response.limits.intLimit("max_chapters_per_page"),
            maxPlaybackSyncItems = response.limits.intLimit("max_playback_sync_items"),
        )

        /**
         * Only a literal JSON `true` enables a feature. A string, a number, or null means the
         * server is saying something this build does not understand, and guessing would light up
         * UI backed by an endpoint that answers 404.
         */
        private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

        /** Moshi parses every JSON number as a Double, so accept any [Number] and drop the rest. */
        private fun Map<String, Any?>.intLimit(key: String): Int? = (this[key] as? Number)?.toInt()
    }
}
