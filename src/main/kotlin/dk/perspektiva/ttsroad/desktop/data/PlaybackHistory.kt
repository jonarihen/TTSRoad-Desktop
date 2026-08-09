package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One "you were here" record.
 *
 * There is deliberately no URL field of any kind — not the server, not the audio object, not the
 * cover. This file sits unencrypted in the user's config directory and outlives the session that
 * wrote it, so the rule is that it holds *identifiers and titles the user typed or the server
 * named*, and nothing that could reconstruct where the content lives or who was signed in. Covers
 * are re-resolved from the live library cache by [fictionId] at display time.
 *
 * [dismissed] is a property of this snapshot, not of a day. Hiding "continue chapter 12" must not
 * also hide chapter 13 tomorrow, and must not un-hide chapter 12 tomorrow either.
 */
data class PlaybackSnapshot(
    val fictionId: Int,
    val chapterId: Int,
    val fictionTitle: String,
    val chapterTitle: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val recordedAtMs: Long,
    val dismissed: Boolean = false,
    /**
     * Which signed-in account this snapshot belongs to — see [PlaybackHistory.ownerKeyFor].
     *
     * The file is machine-local and outlives every session, but its *contents* are not: fiction and
     * chapter titles are the second user's business only if they are the second user's titles.
     * Without this, signing out and signing in as somebody else on the same desktop showed them the
     * previous account's reading history in the "Jump back in" strip. It is a hash, so it scopes
     * without naming the server or the person.
     */
    val ownerKey: String = "",
) {
    /**
     * Identity for thinning and for dismissal.
     *
     * A chapter *of one account's library*, not a playback session: listening to chapter 12 twice
     * is one thing to offer to resume, and a dismissal of it should survive the position moving.
     * The owner is part of it because fiction ids are only unique within a server, so two accounts
     * can hold the same id for different content.
     */
    val key: String get() = "$ownerKey:$fictionId:$chapterId"

    /** How far in, 0..1. Zero when the duration is unknown, so a bar cannot render nonsense. */
    val progress: Float
        get() = if (durationSeconds <= 0.0) 0f
        else (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Local listening history: what to offer as "last heard", and what the jump-back list shows.
 *
 * Bounded by construction — [PlaybackHistory.MaxEntries] entries, one per chapter, newest first —
 * because this is written on every pause and every chapter change for the life of the install. An
 * unbounded list would be a slow leak that only shows up on the machines of the people who use the
 * app most.
 */
object PlaybackHistory {
    /**
     * How many chapters are remembered.
     *
     * Enough to cover several serials in flight; small enough that the file stays a few kilobytes
     * and the whole thing can be read synchronously at startup.
     */
    const val MaxEntries: Int = 60

    /** How many distinct fictions the jump-back surface offers at once. */
    const val JumpBackChoices: Int = 4

    /**
     * A chapter this far in is not worth offering to resume.
     *
     * At 96% the playback controller has already marked it played, so "continue" would mean
     * "replay the last few seconds and then auto-advance", which is not what the listener wants
     * from a card that says *continue*.
     */
    const val ResumableCeiling: Float = 0.96f

    /**
     * Adds or updates [snapshot], then thins.
     *
     * Rules, in order:
     * 1. An existing entry for the same chapter is replaced, not appended — listening for an hour
     *    leaves one record of that chapter, at the furthest point reached.
     * 2. The replacement **inherits the old entry's dismissal**. Otherwise the next progress save
     *    would silently undo a dismissal the user had just made.
     * 3. Newest first, capped at [MaxEntries].
     */
    fun record(existing: List<PlaybackSnapshot>, snapshot: PlaybackSnapshot): List<PlaybackSnapshot> {
        val previous = existing.firstOrNull { it.key == snapshot.key }
        val merged = snapshot.copy(dismissed = previous?.dismissed ?: snapshot.dismissed)
        return (listOf(merged) + existing.filterNot { it.key == merged.key })
            .sortedByDescending { it.recordedAtMs }
            .take(MaxEntries)
    }

    /** Marks one chapter's snapshot dismissed. A later snapshot of a *different* chapter is unaffected. */
    fun dismiss(existing: List<PlaybackSnapshot>, key: String): List<PlaybackSnapshot> =
        existing.map { if (it.key == key) it.copy(dismissed = true) else it }

    /**
     * The owner key for a session: a hash of the server and the account.
     *
     * Hashed rather than stored plainly so the file scopes history without also becoming a record
     * of which servers this machine talks to and under what name — the same reasoning that keeps
     * URLs out of [PlaybackSnapshot] entirely.
     */
    fun ownerKeyFor(serverUrl: String, username: String?): String {
        // The username is *not* case-folded: it is `response.user.username` as the server spelled
        // it, so it is already canonical, and folding it would merge two genuinely distinct
        // accounts on a server that treats case as significant (Django's default). Throughout this
        // function the rule is that merging two identities is far worse than splitting one —
        // splitting costs a lost strip, merging discloses one person's reading to another.
        val identity = "${canonicalServer(serverUrl)}|${username?.trim().orEmpty()}"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    /**
     * Lowercases only the parts of a URL that are genuinely case-insensitive.
     *
     * Scheme and host are; **the path is not**. `normalizeBaseUrl` keeps whatever path the user
     * configured and Retrofit supports path-based base URLs, so `https://host/TTSRoad/` and
     * `https://host/ttsroad/` are two different deployments. Folding the whole URL made them one
     * owner, and two people with the same username on those instances would have seen and dismissed
     * each other's history.
     */
    private fun canonicalServer(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return trimmed

        val scheme = trimmed.substring(0, schemeEnd).lowercase(java.util.Locale.ROOT)
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOf('/').takeIf { it >= 0 } ?: rest.length
        val authority = rest.substring(0, authorityEnd).lowercase(java.util.Locale.ROOT)
        val path = rest.substring(authorityEnd)
        return "$scheme://$authority$path"
    }

    /**
     * The single thing to offer as "last heard" to [ownerKey], or null.
     *
     * Skips dismissed entries and ones that are effectively finished, so the card is always an
     * offer the listener can act on rather than one they have to dismiss again.
     *
     * A snapshot written before this build carries no owner and is therefore shown to nobody. That
     * is deliberate: losing the strip once is a far better outcome than showing one account's
     * reading history to the next person who signs in on the same desktop.
     */
    fun lastHeard(existing: List<PlaybackSnapshot>, ownerKey: String): PlaybackSnapshot? =
        existing.filter { it.isOwnedBy(ownerKey) && it.isResumable }.maxByOrNull { it.recordedAtMs }

    /**
     * Up to [limit] things to jump back to, newest first, at most one per fiction.
     *
     * One per fiction because the alternative — the raw list — fills with consecutive chapters of
     * whatever serial was last playing, which is the one thing the listener does not need help
     * finding.
     */
    fun jumpBackChoices(
        existing: List<PlaybackSnapshot>,
        ownerKey: String,
        limit: Int = JumpBackChoices,
    ): List<PlaybackSnapshot> =
        existing.filter { it.isOwnedBy(ownerKey) && it.isResumable }
            .sortedByDescending { it.recordedAtMs }
            .distinctBy { it.fictionId }
            .take(limit)

    private val PlaybackSnapshot.isResumable: Boolean
        get() = !dismissed && progress < ResumableCeiling

    /** Never true for a blank key on either side, so an unowned snapshot is shown to nobody. */
    private fun PlaybackSnapshot.isOwnedBy(ownerKey: String): Boolean =
        ownerKey.isNotBlank() && this.ownerKey == ownerKey
}

/** Seam so tests never touch the real user config directory. */
interface PlaybackHistoryStore {
    val history: StateFlow<List<PlaybackSnapshot>>

    fun record(snapshot: PlaybackSnapshot)

    fun dismiss(key: String)

    fun clear()
}

/** In-memory store for tests and for a session with nowhere safe to write. */
class InMemoryPlaybackHistoryStore(
    initial: List<PlaybackSnapshot> = emptyList(),
) : PlaybackHistoryStore {
    private val _history = MutableStateFlow(initial)
    override val history: StateFlow<List<PlaybackSnapshot>> = _history.asStateFlow()

    @Synchronized
    override fun record(snapshot: PlaybackSnapshot) {
        _history.value = PlaybackHistory.record(_history.value, snapshot)
    }

    @Synchronized
    override fun dismiss(key: String) {
        _history.value = PlaybackHistory.dismiss(_history.value, key)
    }

    @Synchronized
    override fun clear() {
        _history.value = emptyList()
    }
}

/**
 * `history.json` beside the other settings, written owner-only through [SecureFiles].
 *
 * Owner-only despite holding no secret: fiction and chapter titles are a reading history, which is
 * the sort of thing that should not be world-readable on a shared machine just because it is not a
 * password.
 */
class FilePlaybackHistoryStore(
    private val file: File = defaultFile(),
) : PlaybackHistoryStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<PlaybackSnapshot>>(
            Types.newParameterizedType(List::class.java, PlaybackSnapshot::class.java),
        )

    private val _history = MutableStateFlow(read())
    override val history: StateFlow<List<PlaybackSnapshot>> = _history.asStateFlow()

    /**
     * Every mutation is read-modify-write, and the readers and writers are on different threads:
     * the controller records from its own scope while the UI dismisses from the Compose thread.
     * Unsynchronised, two of them can derive from the same list and the later write wins outright —
     * which loses a dismissal, because a stale `record` carries `dismissed = false` and the whole
     * point of the inheritance rule is that a progress save must never undo one. Synchronising the
     * mutation *and* the file write also keeps the file ordered the same way as the flow.
     */
    @Synchronized
    override fun record(snapshot: PlaybackSnapshot) {
        write(PlaybackHistory.record(_history.value, snapshot))
    }

    @Synchronized
    override fun dismiss(key: String) {
        write(PlaybackHistory.dismiss(_history.value, key))
    }

    @Synchronized
    override fun clear() {
        write(emptyList())
    }

    private fun write(next: List<PlaybackSnapshot>) {
        if (next == _history.value) return
        _history.value = next
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(next)) }
            .onFailure { AppLog.warn("could not write the playback history file", it) }
    }

    private fun read(): List<PlaybackSnapshot> =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the playback history file", it) }
            .getOrNull()
            // Trim on the way in as well: a file from a build with a larger cap must not make this
            // build's list unbounded just because it was written before the cap came down.
            ?.sortedByDescending { it.recordedAtMs }
            ?.distinctBy { it.key }
            ?.take(PlaybackHistory.MaxEntries)
            ?: emptyList()

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("history.json")
    }
}
