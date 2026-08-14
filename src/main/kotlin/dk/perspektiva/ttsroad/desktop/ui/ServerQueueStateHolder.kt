package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dk.perspektiva.ttsroad.desktop.data.ServerQueueAction
import dk.perspektiva.ttsroad.desktop.data.ServerQueueItem
import dk.perspektiva.ttsroad.desktop.data.ServerQueueMode
import dk.perspektiva.ttsroad.desktop.data.ServerQueueRequest
import dk.perspektiva.ttsroad.desktop.data.ServerQueueResponse
import dk.perspektiva.ttsroad.desktop.data.ServerQueueSource
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.itemIds
import dk.perspektiva.ttsroad.desktop.data.movedTo
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the queue surface needs.
 *
 * [loaded] and [unsupported] answer different questions, the same way the device-sessions pane
 * separates them: `loaded == emptyList()` is "your queue is empty", which is a normal state with
 * actions that can fill it, while [unsupported] is "this server has no shared queue" and must hide
 * the surface rather than offer actions that would 404.
 *
 * [error] never clears [loaded] — a failed refresh leaves the rows the user was reading on screen.
 */
data class ServerQueueUiState(
    val isLoading: Boolean = false,
    /** A mutation is in flight. Actions are disabled rather than queued, so two clicks cannot race. */
    val isBusy: Boolean = false,
    val loaded: List<ServerQueueItem>? = null,
    val unsupported: Boolean = false,
    /**
     * The account's `queue_when_empty`, shown but not honoured — this client does not call
     * `advance`. Displayed precisely so the gap is stated rather than hidden.
     */
    val whenEmpty: String? = null,
    val maxItems: Int = 0,
    val error: String? = null,
    val notice: String? = null,
    val confirmingClear: Boolean = false,
) {
    val items: List<ServerQueueItem> get() = loaded.orEmpty()

    /** True only while there is nothing at all to show yet — the one case that owes a spinner. */
    val isInitialLoad: Boolean get() = isLoading && loaded == null && error == null && !unsupported

    /** At the server's cap, where a further `add` would be silently dropped server-side. */
    val isFull: Boolean get() = maxItems > 0 && items.size >= maxItems
}

/**
 * The account's server-side queue, held above the screen that shows it.
 *
 * Hoisted for the same reason the bookmarks holder is: the *writers* are elsewhere. "Add to queue"
 * is pressed on a fiction's chapter list, and it has to work — and report what happened — whether
 * or not the queue screen has ever been opened in this session.
 *
 * Every mutation replaces the whole list with what the server answered instead of predicting the
 * result. Ordering, de-duplication and the cap are the server's rules, and another client may have
 * changed the queue between two requests from this one.
 */
class ServerQueueStateHolder(
    private val repository: TtsRoadRepository,
    private val playback: PlaybackController,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    companion object {
        /** What "Queue unplayed" asks for. The server's own default for a `fill` is the same five. */
        const val DefaultFillCount: Int = 5
    }

    private val _state = MutableStateFlow(ServerQueueUiState())
    val state: StateFlow<ServerQueueUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var mutateJob: Job? = null

    /** Loads on first entry; a second visit reuses what is already there. */
    fun ensureLoaded() {
        val current = _state.value
        if (current.isLoading || current.loaded != null || current.unsupported) return
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(isLoading = true, error = null, notice = null) }
            runCatching { repository.serverQueue() }
                .onSuccess { response -> publish(response, notice = null) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isLoading = false, error = userFacingMessage(failure, "Could not load the queue"))
                    }
                }
        }
    }

    /**
     * Appends chapters, or puts them next.
     *
     * Reached from the chapter list, which is why it takes ids rather than rows: the caller already
     * has the bulk id set that "mark played" uses, and the queue is addressed by chapter on the way
     * in and by queue row on the way out.
     */
    fun addChapters(chapterIds: List<Int>, mode: String = ServerQueueMode.End) {
        if (chapterIds.isEmpty()) return
        mutate(
            request = ServerQueueRequest(
                action = ServerQueueAction.Add,
                chapterIds = chapterIds,
                mode = mode,
            ),
            fallback = "Could not add to the queue",
        ) { before, after ->
            addedNotice(chapterIds.size, added = after.size - before.size, mode = mode)
        }
    }

    /**
     * Fills from a fiction's unplayed chapters, letting the **server** choose which.
     *
     * Deliberately not "send the ids this client thinks are unplayed": the server already answers
     * that question for the web and Android Auto, and computing it here would give three clients
     * three chances to disagree about what "unplayed" means at the edges.
     */
    fun fillFromFiction(fictionId: Int, count: Int = DefaultFillCount) {
        mutate(
            request = ServerQueueRequest(
                action = ServerQueueAction.Fill,
                source = ServerQueueSource.FictionUnplayed,
                fictionId = fictionId,
                count = count,
            ),
            fallback = "Could not fill the queue",
        ) { before, after -> addedNotice(count, added = after.size - before.size, mode = ServerQueueMode.End) }
    }

    /** Removes one row. Addressed by queue-row id: the same chapter may legitimately appear twice. */
    fun remove(item: ServerQueueItem) {
        mutate(
            request = ServerQueueRequest(action = ServerQueueAction.Remove, itemIds = listOf(item.id)),
            fallback = "Could not remove that chapter",
        ) { _, _ -> "Removed ${item.resolvedTitle}" }
    }

    /**
     * Moves the row at [from] to [to], sending the **complete** resulting order.
     *
     * `reorder` takes the whole list rather than a delta, so the new order is computed locally by
     * [movedTo] and posted in full. The local computation is not an optimistic update: the answer
     * still replaces the list.
     */
    fun move(from: Int, to: Int) {
        val items = _state.value.items
        val reordered = items.movedTo(from, to)
        if (reordered === items) return
        mutate(
            request = ServerQueueRequest(action = ServerQueueAction.Reorder, itemIds = reordered.itemIds()),
            fallback = "Could not reorder the queue",
        ) { _, _ -> null }
    }

    fun askClear() {
        if (_state.value.items.isEmpty()) return
        _state.update { it.copy(confirmingClear = true) }
    }

    fun dismissConfirmation() {
        _state.update { it.copy(confirmingClear = false) }
    }

    fun confirmClear() {
        if (!_state.value.confirmingClear) return
        _state.update { it.copy(confirmingClear = false) }
        mutate(
            request = ServerQueueRequest(action = ServerQueueAction.Clear),
            fallback = "Could not clear the queue",
        ) { before, _ -> "Cleared ${before.size} ${if (before.size == 1) "chapter" else "chapters"}" }
    }

    /**
     * Starts a queue row.
     *
     * Deliberately *not* the server's `advance` action, and deliberately not a removal. `advance`
     * pops the head and decides what plays next, which would put the network in the path of
     * end-of-chapter behaviour — the one thing the local queue exists to keep working offline. So a
     * row is played by loading its own fiction and handing the chapter to the ordinary player: the
     * queue behaves as a browsable surface, and what happens when the chapter ends is unchanged.
     *
     * The chapter list comes from the repository rather than [LibraryCache][
     * dk.perspektiva.ttsroad.desktop.data.LibraryCache] because a cross-library queue routinely
     * names a fiction the user has not opened in this session, so there would be nothing cached to
     * reuse in the case that matters.
     */
    fun play(item: ServerQueueItem) {
        if (_state.value.isBusy) return
        mutateJob?.cancel()
        mutateJob = scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            runCatching {
                val chapters = repository.chapters(item.fictionId)
                playback.playQueue(chapters.chapters, item.chapterId, chapters.fiction)
            }
                .onSuccess { _state.update { it.copy(isBusy = false) } }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = userFacingMessage(failure, "Could not start that chapter"))
                    }
                }
        }
    }

    /** Drops the queue that belonged to the session that just ended. */
    fun sessionEnded() {
        loadJob?.cancel()
        mutateJob?.cancel()
        _state.value = ServerQueueUiState()
    }

    override fun onCleared() {
        loadJob = null
        mutateJob = null
    }

    /**
     * Runs one mutation and republishes whatever the server answered.
     *
     * [describe] receives the rows before and after so a notice can report what actually changed
     * rather than what was asked for — the server caps, de-duplicates and drops unknown chapters,
     * and "Added 3 chapters" next to a queue that grew by one is a lie the user can see.
     */
    private fun mutate(
        request: ServerQueueRequest,
        fallback: String,
        describe: (before: List<ServerQueueItem>, after: List<ServerQueueItem>) -> String?,
    ) {
        if (_state.value.isBusy) return
        mutateJob?.cancel()
        mutateJob = scope.launch {
            val before = _state.value.items
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            runCatching { repository.updateServerQueue(request) }
                .onSuccess { response -> publish(response, notice = describe(before, response?.items.orEmpty())) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = userFacingMessage(failure, fallback))
                    }
                }
        }
    }

    /** A null response is the server saying it has no queue API at all — not an empty queue. */
    private fun publish(response: ServerQueueResponse?, notice: String?) {
        _state.update {
            if (response == null) {
                it.copy(isLoading = false, isBusy = false, unsupported = true, loaded = null, error = null)
            } else {
                it.copy(
                    isLoading = false,
                    isBusy = false,
                    unsupported = false,
                    loaded = response.items,
                    whenEmpty = response.whenEmpty,
                    maxItems = response.maxItems,
                    error = null,
                    notice = notice,
                )
            }
        }
    }
}

/**
 * What to say after an `add`, based on what the queue actually gained.
 *
 * A chapter already queued, an unknown id, or a queue at its cap all make [added] smaller than
 * [requested], and each is a case where the reassuring message would be wrong.
 */
internal fun addedNotice(requested: Int, added: Int, mode: String): String {
    val where = if (mode == ServerQueueMode.Next) "next" else "to the queue"
    return when {
        added <= 0 && requested == 1 -> "That chapter is already in the queue"
        added <= 0 -> "Those chapters are already in the queue"
        added == 1 -> "Added 1 chapter $where"
        else -> "Added $added chapters $where"
    }
}

/**
 * Binds the hoisted queue holder to one fiction's chapter list.
 *
 * The `available` gate is the capability, not "is the queue loaded": the controls have to appear on
 * a capable server before anyone has opened the queue screen, which is exactly the case the
 * hoisting exists for.
 */
@Composable
fun rememberChapterQueue(
    holder: ServerQueueStateHolder,
    available: Boolean,
    fictionId: Int,
): ChapterQueueUi {
    val state by holder.state.collectAsState()
    return ChapterQueueUi(
        available = available,
        busy = state.isBusy,
        notice = state.notice,
        error = state.error,
        onAddToQueue = { ids -> holder.addChapters(ids, ServerQueueMode.End) },
        onPlayNext = { ids -> holder.addChapters(ids, ServerQueueMode.Next) },
        onQueueUnplayed = { holder.fillFromFiction(fictionId) },
    )
}
