package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json

/**
 * Where one reader is in the life of one new chapter.
 *
 * The states are the server's own words, and the *first* one is the point of the
 * feature: a notice is raised the moment a chapter is pulled and stays open while it converts. A
 * new chapter that cannot be played is a promise, and clearing the notice before the audio lands
 * loses the only record that the promise was made.
 *
 * [Stalled] is not stored anywhere — the server derives it from the chapter's own status, so there
 * is one answer to "is this converting" rather than two to keep in sync.
 */
enum class ChapterNotificationState(val wire: String) {
    Pulled("pulled"),
    Stalled("stalled"),
    Ready("ready"),
    Dismissed("dismissed"),
    ;

    companion object {
        /**
         * Unknown states resolve to [Pulled], not to null.
         *
         * A server newer than this build may name a state it has never heard of, and the safe
         * reading of "something is happening to this chapter" is the one that keeps the notice on
         * screen and refuses to dismiss it. Guessing [Ready] would offer a Play button for audio
         * that may not exist.
         */
        fun fromWire(value: String?): ChapterNotificationState =
            entries.firstOrNull { it.wire == value } ?: Pulled
    }
}

data class ChapterNotificationsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val notifications: List<ChapterNotification> = emptyList(),
    /** Everything not dismissed, converting chapters included. What a badge counts. */
    val unread: Int = 0,
    val ready: Int = 0,
)

data class ChapterNotification(
    val id: Int = 0,
    private val state: String = "pulled",
    /**
     * Whether the server will accept a dismissal, which it answers 409 to while a chapter is still
     * converting.
     *
     * Read from the payload rather than inferred from [state], because it is the one question every
     * client asks and all three must answer it identically — a client that worked it out for itself
     * would be a fourth opinion about a rule the server enforces.
     */
    val dismissible: Boolean = false,
    /** Whether the chapter has audio. Never inferred from the notice's own state. */
    val playable: Boolean = false,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "ready_at") val readyAt: String? = null,
    val fiction: NotificationFiction = NotificationFiction(),
    val chapter: NotificationChapter = NotificationChapter(),
) {
    val presentation: ChapterNotificationState get() = ChapterNotificationState.fromWire(state)
}

data class NotificationFiction(
    val id: Int = 0,
    val title: String = "Untitled",
    val slug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
)

data class NotificationChapter(
    val id: Int = 0,
    val title: String = "Untitled",
    @param:Json(name = "chapter_number") val chapterNumber: Int? = null,
    val status: String? = null,
    /**
     * Conversion percentage while a chapter is still being narrated, null once it is done.
     *
     * Null rather than 100 at the end, so a row that stopped updating cannot read as one still
     * running.
     */
    @param:Json(name = "tts_progress") val ttsProgress: Int? = null,
)

/** One line for a row: "Chapter 412 · converting 62%", or what went wrong instead. */
fun ChapterNotification.detailLabel(): String {
    val chapterLabel = chapter.chapterNumber?.let { "Chapter $it" } ?: chapter.title
    val state = when (presentation) {
        ChapterNotificationState.Ready -> "ready to listen"
        ChapterNotificationState.Stalled -> "conversion failed"
        ChapterNotificationState.Dismissed -> "dismissed"
        ChapterNotificationState.Pulled ->
            chapter.ttsProgress?.let { "converting $it%" } ?: "converting"
    }
    return "$chapterLabel  ·  $state"
}

/**
 * Which notices have *become* ready since [alreadySeen], and the set to remember next time.
 *
 * Pure, because the interesting rule has nothing to do with the network: the first look of a
 * session announces **nothing**. A chapter that was already ready when the app started is not news
 * — the app was closed when it happened — and without this every launch would re-announce the whole
 * backlog. `alreadySeen` is null on that first look and a set on every one after.
 */
fun newlyReady(
    notifications: List<ChapterNotification>,
    alreadySeen: Set<Int>?,
): Pair<List<ChapterNotification>, Set<Int>> {
    val readyNow = notifications.filter { it.presentation == ChapterNotificationState.Ready }
    val ids = readyNow.map { it.id }.toSet()
    if (alreadySeen == null) return emptyList<ChapterNotification>() to ids
    return readyNow.filter { it.id !in alreadySeen } to ids
}

/**
 * What the desktop should raise for a batch that just became ready, or null for nothing.
 *
 * Collapsed into one line above a single chapter, because a serial converting a backlog would
 * otherwise stack a dozen system notifications at once — the noise that gets a feature muted.
 */
fun readyNotificationText(fresh: List<ChapterNotification>): Pair<String, String>? = when {
    fresh.isEmpty() -> null
    fresh.size == 1 -> {
        val item = fresh.single()
        item.fiction.title to "${item.chapter.title} is ready to listen"
    }
    else -> {
        val serials = fresh.map { it.fiction.title }.distinct()
        val where = if (serials.size == 1) serials.single() else "${serials.size} serials"
        "${fresh.size} chapters ready" to "New audio in $where"
    }
}
