package dk.perspektiva.ttsroad.desktop.data

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local-first playback history with account-wide `kind=auto` bookmark synchronization.
 *
 * The local store remains authoritative while offline and keeps dismissals machine-local. Server
 * breadcrumbs add the cross-device half: a pause on this desktop can be found in the browser, and
 * a moment recorded in the browser appears in this desktop's Jump back shelf after a refresh.
 */
class SyncedPlaybackHistoryStore(
    private val local: PlaybackHistoryStore,
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentOwnerKey: () -> String,
) : PlaybackHistoryStore {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val submittedPositions = mutableMapOf<String, Double>()

    override val history = local.history

    override fun record(snapshot: PlaybackSnapshot) {
        local.record(snapshot)

        // A snapshot captured for the session being left must never be filed under whichever
        // account happens to have signed in while the asynchronous request was waiting to run.
        if (snapshot.ownerKey.isBlank() || snapshot.ownerKey != currentOwnerKey()) return
        if (snapshot.positionSeconds < MinServerPositionSeconds) return

        val shouldSubmit = synchronized(submittedPositions) {
            val previous = submittedPositions[snapshot.key]
            if (previous == snapshot.positionSeconds) {
                false
            } else {
                submittedPositions[snapshot.key] = snapshot.positionSeconds
                if (submittedPositions.size > MaxRememberedSubmissions) {
                    submittedPositions.remove(submittedPositions.keys.first())
                }
                true
            }
        }
        if (!shouldSubmit) return

        scope.launch {
            if (snapshot.ownerKey != currentOwnerKey()) return@launch
            // Best effort by design. Progress sync still owns the resumable position, and an
            // offline breadcrumb is preserved locally; surfacing an error every five minutes
            // would turn a background convenience into an alert storm.
            runCatching {
                repository.createBookmark(
                    BookmarkCreateRequest(
                        chapterId = snapshot.chapterId,
                        positionSeconds = snapshot.positionSeconds,
                        kind = BookmarkKind.Auto,
                    ),
                )
            }
        }
    }

    override fun merge(snapshots: List<PlaybackSnapshot>) = local.merge(snapshots)

    override fun dismiss(key: String) = local.dismiss(key)

    override fun clear() = local.clear()

    override suspend fun refresh(ownerKey: String) {
        if (ownerKey.isBlank() || ownerKey != currentOwnerKey()) return
        val bookmarks = runCatching { repository.bookmarks(kind = BookmarkKind.Auto) }.getOrNull() ?: return

        // A request started for account A can finish after account B signs in. Drop it rather than
        // persisting A's chapter titles under B's local owner key.
        if (ownerKey != currentOwnerKey()) return
        local.merge(bookmarks.mapNotNull { it.toSnapshot(ownerKey) })
    }

    override fun close() {
        // Playback records once more during release. Give that final request a short chance to
        // finish, then leave promptly even when the server disappeared while the window closed.
        runBlocking {
            withTimeoutOrNull(CloseTimeoutMs) { job.children.toList().joinAll() }
        }
        scope.cancel()
        local.close()
    }

    private fun Bookmark.toSnapshot(ownerKey: String): PlaybackSnapshot? {
        val chapter = chapterId?.takeIf { it > 0 } ?: return null
        val fiction = fictionId?.takeIf { it > 0 } ?: return null
        val recordedAt = createdAt?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        } ?: return null
        return PlaybackSnapshot(
            fictionId = fiction,
            chapterId = chapter,
            fictionTitle = fictionTitle.orEmpty(),
            chapterTitle = chapterTitle.orEmpty(),
            positionSeconds = positionSeconds.coerceAtLeast(0.0),
            // The bookmark contract has no duration. PlaybackHistory.merge preserves a local one
            // when this machine has played the same chapter; a remote-only row simply has no bar.
            durationSeconds = 0.0,
            recordedAtMs = recordedAt,
            ownerKey = ownerKey,
        )
    }

    private companion object {
        const val MinServerPositionSeconds: Double = 30.0
        const val MaxRememberedSubmissions: Int = 240
        const val CloseTimeoutMs: Long = 3_000L
    }
}
