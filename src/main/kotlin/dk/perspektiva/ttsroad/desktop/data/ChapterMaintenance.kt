package dk.perspektiva.ttsroad.desktop.data

/**
 * Repairing one chapter (#113).
 *
 * The desktop has always *seen* a failed chapter — `availability()` maps the server's
 * status/sub-status/error triple onto [ChapterAvailability.Failed] and the row draws a chip for it —
 * and offered nothing to press. So a chapter that failed conversion was a permanent hole in a
 * serial from this client: the scheduler does not retry on its own, and the one-request fix lived
 * only in the web console.
 *
 * The three routes deliberately do **not** share a gate, and that asymmetry is the server's, not an
 * accident to be tidied away:
 *
 * - **Retry** is open to any signed-in account. It repairs one chapter, harms nobody, and the
 *   account looking at a failed row is usually the one that wants it fixed.
 * - **Exclude** and **delete** are admin-only, because a chapter is a shared object: excluding one
 *   changes what *every* account's podcast feed contains, and deleting destroys the audio for
 *   everybody.
 *
 * The capability flag says only that the routes exist; `me.is_admin` decides which of them this
 * account may use. Both gates, always — a drawn control is never authorization.
 */

/**
 * What a retry actually did.
 *
 * The interesting member is [AlreadyRunning], the server's `409`. It is not a failure: it means the
 * chapter is excluded or is being processed right now, so the thing the user wanted is either
 * already happening or deliberately switched off. Reporting it as an error would invite a second
 * press, and a second press would say the same thing.
 */
sealed interface ChapterRetryOutcome {
    /** Queued for conversion. */
    data class Queued(val chapterId: Int) : ChapterRetryOutcome

    /** `409` — excluded, or already converting. */
    data object AlreadyRunning : ChapterRetryOutcome

    /** `404` — this server has no maintenance routes. */
    data object Unsupported : ChapterRetryOutcome
}

/** The line to show for a [ChapterRetryOutcome], or null when there is nothing worth saying. */
fun chapterRetryMessage(outcome: ChapterRetryOutcome, title: String): String? = when (outcome) {
    is ChapterRetryOutcome.Queued -> "$title is queued for conversion again."
    // Says which of the two it is, because the actions differ: one is waiting, the other is undoing
    // an exclusion, and "could not retry" would point at neither.
    ChapterRetryOutcome.AlreadyRunning ->
        "$title is already being converted, or is excluded. Nothing to retry."
    ChapterRetryOutcome.Unsupported -> "This server cannot retry a chapter."
}

/** The line after an exclude or un-exclude. */
fun chapterExcludeMessage(title: String, excluded: Boolean): String = when {
    excluded -> "$title is excluded. It is off every account's feed and player until it is put back."
    else -> "$title is back on every account's feed and player."
}

/**
 * What excluding or deleting costs, for the confirmation.
 *
 * Both sentences name *everybody*, because that is the fact a single-account mental model hides: a
 * chapter is one shared row, not a copy per listener.
 */
fun chapterDeleteConfirmation(title: String): String =
    "Delete $title and its audio from the server. Every account loses it, from the shelf and from " +
        "the podcast feed, and the listening progress recorded against it goes with it. " +
        "Re-converting it later means the source still has the text. This cannot be undone."

fun chapterExcludeConfirmation(title: String, excluding: Boolean): String = when {
    excluding ->
        "Take $title off every account's podcast feed and player. The audio is kept and it can be " +
            "put back, but while it is excluded it cannot be retried."
    else -> "Put $title back on every account's podcast feed and player."
}

/**
 * Whether a chapter is worth offering a retry for.
 *
 * Only a failed one. A converting chapter is already doing the thing retry asks for and the server
 * answers `409`; a ready one has nothing to repair. Offering it everywhere would make the row's
 * busiest state indistinguishable from its broken one.
 */
fun ChapterSummary.canRetry(): Boolean = availability() == ChapterAvailability.Failed

/** Both halves of the gate for the destructive pair. */
fun canMaintainChapters(capabilities: ServerCapabilities, isAdmin: Boolean): Boolean =
    capabilities.chapterMaintenance && isAdmin
