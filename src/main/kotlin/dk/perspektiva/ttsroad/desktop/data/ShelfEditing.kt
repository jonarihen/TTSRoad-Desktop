package dk.perspektiva.ttsroad.desktop.data

/**
 * Removing several fictions from a shelf at once (#110).
 *
 * The pure half: what a selection means, and what to say afterwards. The requests live in
 * `ui/ManageShelfStateHolder.kt`.
 *
 * This exists because a shelf is routinely filled without anybody pressing Follow — the per-user
 * library upgrade backfilled every account with every fiction so that upgrading did not blank
 * everyone's dashboard, and adding a fiction auto-follows it for the adder, which on a single-admin
 * install is the whole server. Emptying it one screen at a time was the only exit.
 *
 * There is deliberately **no bulk follow** here. The problem being solved is a shelf filled without
 * asking; a faster way to fill it is not the answer to that.
 */

/**
 * What actually happened, which is not always what was asked for.
 *
 * Ten unfollows where the seventh fails is not "the operation failed" — six of them happened, and
 * the server is now in a state neither the request nor a rollback describes. Both lists are kept so
 * the screen can say so and leave the selection holding only what is still there.
 */
data class UnfollowOutcome(
    val removed: List<Int> = emptyList(),
    val failed: List<Int> = emptyList(),
) {
    val attempted: Int get() = removed.size + failed.size
    val isCompleteSuccess: Boolean get() = failed.isEmpty() && removed.isNotEmpty()
    val isCompleteFailure: Boolean get() = removed.isEmpty() && failed.isNotEmpty()
}

/**
 * The sentence for an [UnfollowOutcome].
 *
 * Never "done" when something failed and never "failed" when something worked. The partial case
 * names both numbers, because the useful next action — try the rest again — depends on knowing there
 * *is* a rest.
 *
 * Null when nothing was attempted: an outcome with no rows is not news.
 */
fun unfollowReport(outcome: UnfollowOutcome): String? = when {
    outcome.attempted == 0 -> null
    outcome.isCompleteFailure -> when (outcome.failed.size) {
        1 -> "That fiction could not be unfollowed. It is still on your shelf."
        else -> "None of the ${outcome.failed.size} could be unfollowed. They are still on your shelf."
    }
    outcome.failed.isEmpty() -> when (outcome.removed.size) {
        1 -> "Unfollowed one fiction."
        else -> "Unfollowed ${outcome.removed.size} fictions."
    }
    else ->
        "Unfollowed ${outcome.removed.size} of ${outcome.attempted}. " +
            "${outcome.failed.size} could not be removed and are still on your shelf."
}

/**
 * The confirmation shown before anything is sent.
 *
 * It states what unfollowing does *not* do, because the word invites the opposite guess. Following
 * decides whose dashboard and default library view a fiction appears on and nothing else: it is not
 * an access boundary, nothing is deleted, progress is untouched, and the fiction stays openable and
 * re-followable. A destructive-sounding confirmation over a reversible action teaches people to
 * dismiss confirmations.
 */
fun unfollowConfirmation(count: Int): String {
    val subject = if (count == 1) "this fiction" else "these $count fictions"
    return "Remove $subject from your shelf. Nothing is deleted — your listening progress is kept, " +
        "and you can find them again under all fictions and follow them back."
}

/**
 * The selection, minus anything the shelf no longer holds.
 *
 * A refresh can land while rows are ticked, and a selection naming a fiction that is gone would
 * report a count the screen cannot show. Order follows [available] rather than insertion, so the
 * count and the ticked rows always agree.
 */
fun prunedSelection(selected: Set<Int>, available: List<FictionSummary>): Set<Int> {
    if (selected.isEmpty()) return emptySet()
    val present = available.mapTo(LinkedHashSet()) { it.id }
    return present.filterTo(LinkedHashSet()) { it in selected }
}
