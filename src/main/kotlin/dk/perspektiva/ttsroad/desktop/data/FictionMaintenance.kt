package dk.perspektiva.ttsroad.desktop.data

/**
 * Acting on a whole fiction (#115).
 *
 * The counts are the point. `retag`, `reconvert-all` and `apply-chapter-filter` all answer
 * `status: "ok"` whether they touched four hundred files or none, so a number is the only thing
 * separating "done" from "there was nothing to do" — and on the expensive one, the only thing
 * telling somebody what they just started.
 *
 * Two gates, as everywhere else here: the capability says the routes exist, `is_admin` says who may
 * use them. [FictionMaintenanceAction.adminOnly] carries which is which, so the screen cannot draw a
 * control the server will refuse.
 */
enum class FictionMaintenanceAction(
    val title: String,
    val subtitle: String,
    /** False only for [Poll], which the server leaves open on purpose. */
    val adminOnly: Boolean,
    /** Whether pressing it asks a question first. Only the one that spends hours of TTS. */
    val confirms: Boolean = false,
) {
    /**
     * Not admin-gated, deliberately. The server rate-limits it, and a chapter found early benefits
     * every reader rather than only the person who pressed it.
     */
    Poll(
        title = "Check for new chapters",
        subtitle = "Asks the source now instead of waiting for the next scheduled poll.",
        adminOnly = false,
    ),
    RetryFailed(
        title = "Retry failed chapters",
        subtitle = "Queues every chapter of this fiction that errored. Nothing already converted is touched.",
        adminOnly = true,
    ),

    /**
     * The other half of the metadata editor.
     *
     * A title or cover changed here only reaches the database; the MP3s keep the old ID3 tags, and
     * a podcast client reads the files. Without this, renaming a book from this client is applied
     * in one place and not the other, with nothing on screen saying so.
     */
    Retag(
        title = "Rewrite MP3 tags",
        subtitle = "Applies the current title, author and cover to files already converted. No re-narration.",
        adminOnly = true,
    ),

    /** One-way by design: it excludes, and never un-excludes — a hand-excluded chapter had a reason. */
    ApplyFilter(
        title = "Re-run the chapter filter",
        subtitle = "Excludes existing chapters the fiction's title filter would have skipped. Never un-excludes.",
        adminOnly = true,
    ),
    ReconvertAll(
        title = "Re-narrate every chapter",
        subtitle = "Throws away all existing audio and converts the whole serial again. Hours of TTS.",
        adminOnly = true,
        confirms = true,
    ),
}

/** Which actions to draw. Poll needs only the capability; the rest need the verified admin flag. */
fun fictionMaintenanceActions(
    capabilities: ServerCapabilities,
    isAdmin: Boolean,
): List<FictionMaintenanceAction> = when {
    !capabilities.fictionMaintenance -> emptyList()
    isAdmin -> FictionMaintenanceAction.entries
    else -> FictionMaintenanceAction.entries.filterNot { it.adminOnly }
}

/**
 * What to say the question before re-narrating everything.
 *
 * Names the number, because "re-narrate every chapter" reads the same for a four-chapter serial and
 * a four-hundred-chapter one, and only the second is a decision.
 */
fun reconvertConfirmation(doneChapters: Int): String = when (doneChapters) {
    0 -> "Nothing is converted yet, so this only queues whatever the server has pulled."
    1 -> "The one converted chapter is thrown away and narrated again from scratch."
    else ->
        "All $doneChapters converted chapters are thrown away and narrated again from scratch. " +
            "That is $doneChapters conversions of outbound TTS, and the existing audio is gone as " +
            "soon as this starts."
}

/**
 * The line for a finished action.
 *
 * Every branch names a count, and a count of zero is reported rather than hidden: "nothing needed
 * retagging" is a useful answer, and silence is indistinguishable from a control that did nothing.
 */
fun fictionMaintenanceMessage(
    action: FictionMaintenanceAction,
    response: MaintenanceResponse,
): String = when (action) {
    FictionMaintenanceAction.Poll -> when {
        response.fullIngest -> "Re-read the whole chapter list from the source."
        response.partialSync != null -> "Checked the source — re-read the last ${response.partialSync} chapters."
        else -> "Checked the source for new chapters."
    }

    FictionMaintenanceAction.RetryFailed -> when (val n = response.resetCount ?: 0) {
        0 -> "No failed chapters to retry."
        1 -> "Queued one failed chapter again."
        else -> "Queued $n failed chapters again."
    }

    FictionMaintenanceAction.Retag -> when (val n = response.fileCount ?: 0) {
        0 -> "No files needed rewriting."
        1 -> "Rewrote the tags on one file."
        else -> "Rewrote the tags on $n files."
    }

    // `detail` distinguishes "there is no filter configured" from "the filter matched nothing", and
    // only the first means the control had nothing to work with.
    FictionMaintenanceAction.ApplyFilter -> response.detail?.takeIf { it.isNotBlank() }
        ?: when (val n = response.excludedCount ?: 0) {
            0 -> "The filter excluded nothing new."
            1 -> "Excluded one chapter."
            else -> "Excluded $n chapters."
        }

    FictionMaintenanceAction.ReconvertAll -> when (val n = response.resetCount ?: 0) {
        0 -> "Nothing was queued for re-narration."
        1 -> "Queued one chapter for re-narration."
        else -> "Queued $n chapters for re-narration. This will take a while."
    }
}
