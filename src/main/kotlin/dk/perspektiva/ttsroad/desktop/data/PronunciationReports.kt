package dk.perspektiva.ttsroad.desktop.data

/**
 * "That word is pronounced wrong", filed where it was heard (#121).
 *
 * TTS gets names wrong constantly and in ways only a listener notices. The server has had a store
 * for these and a panel that acts on them; this client had no way to file one, so the only route was
 * to remember the word, leave the app and open a browser. That remembering step is where the report
 * was lost.
 *
 * Which is the whole argument for filing from the player: the chapter and the position are already
 * known to the millisecond, so a report is one field — the word — and everything else is filled in.
 */

/** The server's own ceilings, checked here so a too-long note is not a 400 after the fact. */
const val MaxReportedWordChars: Int = 200
const val MaxReportNoteChars: Int = 2000

/**
 * Why this report cannot be sent, or null when it can.
 *
 * A note with no word is a valid report — "the narrator garbles the surname in the third paragraph"
 * says something. A report with neither is noise, and the server would store an empty row.
 */
fun pronunciationReportProblem(word: String, note: String): String? = when {
    word.isBlank() && note.isBlank() -> "Type the word that sounded wrong, or a note about it."
    word.trim().length > MaxReportedWordChars -> "That word is too long — $MaxReportedWordChars characters at most."
    note.trim().length > MaxReportNoteChars -> "That note is too long — $MaxReportNoteChars characters at most."
    else -> null
}

/**
 * The body to send, or null when [pronunciationReportProblem] would refuse it.
 *
 * Blank fields are sent as absent rather than as empty strings, and `fiction_id` is never sent: the
 * server derives it from the chapter rather than trusting a client, so including one is at best
 * redundant and at worst a mismatched pair.
 */
fun pronunciationReportRequest(
    chapterId: Int,
    positionMs: Long,
    word: String,
    note: String,
): PronunciationReportRequest? {
    if (pronunciationReportProblem(word, note) != null) return null
    return PronunciationReportRequest(
        chapterId = chapterId,
        // Seconds, because that is the server's unit. Taken from the player rather than typed —
        // it is already known exactly and nobody can type a timestamp accurately.
        positionSeconds = (positionMs.coerceAtLeast(0L) / 1000.0),
        word = word.trim().takeIf { it.isNotEmpty() },
        note = note.trim().takeIf { it.isNotEmpty() },
    )
}

/** "Chapter 12 · 41:07", or as much of it as the report carries. */
fun pronunciationReportLocation(report: PronunciationReport): String {
    val chapter = report.chapterTitle?.takeIf { it.isNotBlank() }
        ?: report.chapterNumber?.let { "Chapter $it" }
    val position = formatReportPosition(report.positionSeconds)
    return listOfNotNull(chapter, position).joinToString("  ·  ").ifEmpty { "Unknown position" }
}

/** `m:ss`, or `h:mm:ss` past an hour. Null at zero — an unset position is not "the very start". */
fun formatReportPosition(seconds: Double): String? {
    if (seconds <= 0.0) return null
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/**
 * What filing a report did.
 *
 * [AtCapacity] is the server's `409` and is not a failure to retry: the account has too many open
 * reports and the fix is to resolve or delete some. Its message is the server's own, because the
 * number in it is the only actionable part and it can differ per deployment.
 */
sealed interface ReportOutcome {
    data class Filed(val report: PronunciationReport) : ReportOutcome
    data class AtCapacity(val message: String) : ReportOutcome
    data object Unsupported : ReportOutcome
}
