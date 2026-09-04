package dk.perspektiva.ttsroad.desktop.data

import java.time.LocalDate

/**
 * Keeping a copy of where you are in everything (#119).
 *
 * The state lived only on the server: no way to keep a copy, and no way to move it to a rebuilt
 * instance. This is the client with a real filesystem, so the document goes to a folder the user
 * picks rather than into an app-managed directory.
 *
 * The one thing worth stating loudly is that **import merges and cannot lose progress**. The server
 * only moves a position forward and adds bookmarks rather than reconciling them, so restoring a
 * six-month-old backup over a live account cannot undo six months of listening. Without saying so,
 * "import" reads as "overwrite" and nobody presses it.
 */

/** A sensible filename for a fresh export. Dated, because the point is keeping more than one. */
fun listeningBackupFileName(today: LocalDate): String = "ttsroad-listening-$today.json"

/** The sentence shown beside Import, before anything is picked. */
const val ImportMergeExplanation: String =
    "Importing merges: a position only moves forward and marks are added, never removed. " +
        "Restoring an old backup cannot undo listening you have done since."

/**
 * What the merge did, as lines.
 *
 * Only non-zero counts are listed — a report of eight zeroes is a wall nobody reads — but an
 * entirely empty result still says something, because "nothing happened" is itself the answer and
 * silence looks like a failure.
 *
 * [ListeningStateReport.playbackSkippedOlder] is the line that earns this function: a restore where
 * every position was already further ahead looks broken, and this is the only thing that explains it.
 */
fun listeningImportLines(report: ListeningStateReport): List<String> {
    val lines = buildList {
        if (report.playbackRestored > 0) add(count(report.playbackRestored, "position", "restored"))
        if (report.playbackSkippedOlder > 0) {
            add(
                count(report.playbackSkippedOlder, "position", "left alone") +
                    " — this account was already further ahead",
            )
        }
        if (report.bookmarksRestored > 0) add(count(report.bookmarksRestored, "bookmark", "restored"))
        if (report.bookmarksAlreadyPresent > 0) {
            add(count(report.bookmarksAlreadyPresent, "bookmark", "already here"))
        }
        if (report.bookmarksSkippedFull > 0) {
            add(count(report.bookmarksSkippedFull, "bookmark", "dropped") + " — this account is at its limit")
        }
        if (report.chaptersMissing > 0) {
            add(count(report.chaptersMissing, "chapter", "not found on this server"))
        }
        if (report.fictionsMissing.isNotEmpty()) {
            // Named rather than counted: on a different server this is the normal case, and knowing
            // *which* books did not come across is what tells you whether it mattered.
            add("Not on this server: ${report.fictionsMissing.joinToString(", ")}")
        }
    }
    return lines.ifEmpty {
        listOf(
            if (report.fictionsMatched > 0) {
                "Nothing to change — this account was already up to date with that backup."
            } else {
                "Nothing in that backup matched anything on this server."
            },
        )
    }
}

/** "4 positions restored", "one position restored". */
private fun count(n: Int, noun: String, verb: String): String =
    if (n == 1) "One $noun $verb" else "$n ${noun}s $verb"
