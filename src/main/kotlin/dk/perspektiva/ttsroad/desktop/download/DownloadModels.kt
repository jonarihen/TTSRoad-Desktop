package dk.perspektiva.ttsroad.desktop.download

/**
 * What has happened to one chapter's audio on this disk.
 *
 * The states are the ones the issue names, and the distinction that matters most is between
 * [Downloading] and [Downloaded]: only the latter may be reported as available offline, and a
 * chapter reaches it only after the bytes have been validated. Everything else is a promise.
 */
enum class DownloadState {
    /** Not on disk and not wanted. The absence of an entry means this too. */
    None,

    /** The user asked for it; a worker has not picked it up yet. */
    Queued,

    /** Bytes are arriving into a `.part` file. */
    Downloading,

    /** Complete, validated, and renamed into place. This is the only state that plays offline. */
    Downloaded,

    /** Stopped with a reason worth showing. Retryable. */
    Failed,

    /** Deletion in progress. Distinct from [None] so the row does not flicker back to "Download". */
    Removing,
    ;

    /** Whether a worker should be given this row. */
    val isActive: Boolean get() = this == Queued || this == Downloading
}

/**
 * Why a download stopped, in terms the UI can render and the retry logic can branch on.
 *
 * Typed for the same reason `PlaybackFailure` is: "out of disk" and "the wifi dropped" want
 * different words and different retry behaviour, and a bare string forces the UI to guess.
 */
sealed interface DownloadFailure {
    val message: String

    /** Retrying is likely to work: a timeout, a dropped connection, a 5xx. */
    data class Transient(override val message: String) : DownloadFailure

    /** The session ended. Retrying without signing in cannot work. */
    data class SessionExpired(override val message: String) : DownloadFailure

    /** The chapter is gone, or the server refuses it. Retrying the same request cannot work. */
    data class Gone(override val message: String) : DownloadFailure

    /** No room. Distinct because the fix is the user's, not the app's. */
    data class OutOfSpace(override val message: String) : DownloadFailure

    /**
     * The bytes arrived but are not what was promised — wrong length, or not decodable.
     *
     * Kept apart from [Transient] because it means the *file* is bad rather than the transfer, so
     * a retry must start from zero rather than resuming a `.part` that is already wrong.
     */
    data class Corrupt(override val message: String) : DownloadFailure

    companion object {
        /** Whether an automatic retry (as opposed to the user pressing Retry) is worth attempting. */
        fun isWorthAutoRetry(failure: DownloadFailure): Boolean = failure is Transient
    }
}

/**
 * One row of the download index.
 *
 * Deliberately holds **no URL and no token**. The audio URL is re-resolved from the live chapter
 * metadata at download time; storing it would put a bearer-protected address in a plain file that
 * outlives the session, and a stale one would be followed later without the user asking. The
 * identity here is the chapter id — the same thing the file name is generated from.
 *
 * Titles are stored because the Settings screen has to name what it is offering to delete while
 * the library is not loaded, and because a download list that says "chapter 512" is not usable.
 */
data class DownloadEntry(
    val chapterId: Int,
    val fictionId: Int,
    val fictionTitle: String,
    val chapterTitle: String,
    val state: DownloadState,
    /** Bytes on disk. For [DownloadState.Downloaded] this is the final size. */
    val bytesDownloaded: Long = 0L,
    /** Expected total, or 0 when the server never said. Never trusted as proof of completeness. */
    val totalBytes: Long = 0L,
    /** The file name actually used, so a content-hash change does not orphan the old one silently. */
    val fileName: String,
    /** Present only in [DownloadState.Failed]. */
    val failureMessage: String? = null,
    val updatedAtMs: Long = 0L,
) {
    /** Index identity: one row per chapter. */
    val key: Int get() = chapterId

    /** 0..1, or null when the total is unknown and a determinate bar would be a lie. */
    val progress: Float?
        get() = if (totalBytes <= 0L) null
        else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

    val isOffline: Boolean get() = state == DownloadState.Downloaded
}
