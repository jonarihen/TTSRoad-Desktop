package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the shelf is currently arranged: the order, the ticked tags, and which half is being browsed.
 *
 * All three were composition state, which covers navigating into a fiction and back and nothing
 * else — so an order picked on Monday was gone by Tuesday. The web console has kept the same three
 * in `localStorage` all along.
 *
 * **The search text is deliberately not here.** A search is a question being asked now; restoring
 * last week's would open the shelf onto a near-empty grid with no visible cause. That asymmetry is
 * the whole design of this type, not an omission.
 *
 * OS-profile-local like [PlaybackPreferences], and for the same reason: this is about how somebody
 * likes to look at a shelf, not about which TTSRoad account they were signed into. Nothing here is
 * secret, and there is nowhere in the type to put something that is.
 */
data class BrowsePreferences(
    val sort: FictionSort = FictionSort.Default,
    val tags: Set<String> = emptySet(),
    /**
     * Whether the shelf or the whole catalogue was last being browsed.
     *
     * Honoured only where the server advertises `follows`; without per-user libraries the two are
     * the same list and the mode switch is not drawn at all.
     */
    val browsingAll: Boolean = false,
) {
    companion object {
        /**
         * A ceiling on remembered tags.
         *
         * Not a UI limit — tick as many as you like in a session. It bounds what a file written
         * once and never cleaned can grow to, the same reason `fictionSpeeds` is bounded.
         */
        const val MaxRememberedTags: Int = 32
    }
}

interface BrowsePreferencesStore {
    val preferences: StateFlow<BrowsePreferences>

    fun update(transform: (BrowsePreferences) -> BrowsePreferences)
}

/** In-memory store for tests and for the smoke test, which must not write to a real home. */
class InMemoryBrowsePreferencesStore(
    initial: BrowsePreferences = BrowsePreferences(),
) : BrowsePreferencesStore {
    private val _preferences = MutableStateFlow(initial.sanitised())
    override val preferences: StateFlow<BrowsePreferences> = _preferences.asStateFlow()

    @Synchronized
    override fun update(transform: (BrowsePreferences) -> BrowsePreferences) {
        _preferences.value = transform(_preferences.value).sanitised()
    }
}

/**
 * `browse.json`, beside `playback.json` and `reader.json`, written owner-only through [SecureFiles].
 *
 * Read eagerly for the same reason the playback settings are: the library composes on the first
 * frame after sign-in, and a lazy read would draw the shelf once in the default order and then
 * reshuffle it under the reader.
 *
 * Write failure is logged, never surfaced. Losing a remembered sort order is not worth a dialog.
 */
class FileBrowsePreferencesStore(
    private val file: File = defaultFile(),
) : BrowsePreferencesStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StoredBrowsePreferences::class.java)

    private val _preferences = MutableStateFlow(read())
    override val preferences: StateFlow<BrowsePreferences> = _preferences.asStateFlow()

    @Synchronized
    override fun update(transform: (BrowsePreferences) -> BrowsePreferences) {
        val next = transform(_preferences.value).sanitised()
        if (next == _preferences.value) return
        _preferences.value = next
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(StoredBrowsePreferences.from(next))) }
            .onFailure { AppLog.warn("could not write the browse settings file", it) }
    }

    private fun read(): BrowsePreferences =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the browse settings file", it) }
            .getOrNull()
            ?.toPreferences()
            ?: BrowsePreferences()

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("browse.json")
    }
}

/**
 * The on-disk shape: separate from [BrowsePreferences] and **fully nullable**.
 *
 * Same rule as `StoredPlaybackPreferences`. A file written by another build — older, newer, or
 * half-truncated by a crash — must degrade to defaults field by field rather than failing to parse
 * and taking the whole shelf's arrangement with it. The sort is a string because an enum that has
 * gained a case since the file was written is a `null`, not an exception.
 */
internal data class StoredBrowsePreferences(
    val sort: String? = null,
    val tags: List<String>? = null,
    val browsingAll: Boolean? = null,
) {
    fun toPreferences(): BrowsePreferences = BrowsePreferences(
        sort = FictionSort.fromStorage(sort),
        tags = tags?.toSet().orEmpty(),
        browsingAll = browsingAll ?: false,
    ).sanitised()

    companion object {
        fun from(preferences: BrowsePreferences) = StoredBrowsePreferences(
            sort = preferences.sort.name,
            // Sorted so the file does not churn on every write purely because a set reordered.
            tags = preferences.tags.sorted(),
            browsingAll = preferences.browsingAll,
        )
    }
}

/**
 * Brings a value back into range on the way *in*, so a bad one never sits in memory either.
 *
 * Blank tags are dropped rather than kept as an un-tickable box, and the set is trimmed to
 * [BrowsePreferences.MaxRememberedTags] to bound a file that is written for the life of the
 * install.
 */
internal fun BrowsePreferences.sanitised(): BrowsePreferences = copy(
    tags = tags.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .distinctBy(String::lowercase)
        .take(BrowsePreferences.MaxRememberedTags)
        .toSet(),
)
