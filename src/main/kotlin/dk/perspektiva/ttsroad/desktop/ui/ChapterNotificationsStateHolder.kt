package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationState
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.newlyReady
import dk.perspektiva.ttsroad.desktop.data.readyNotificationText
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChapterNotificationsUiState(
    val notifications: List<ChapterNotification> = emptyList(),
    /** Everything not dismissed, converting chapters included. What the badge counts. */
    val unread: Int = 0,
    val ready: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * The server answered 404 for a route its capability advertised.
     *
     * Kept apart from [error] for the reason the bookmarks holder keeps them apart: one is worth a
     * Retry and the other never will be.
     */
    val unsupported: Boolean = false,
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = notifications.isEmpty()

    /** Whether "clear the ready ones" would do anything. */
    val hasClearable: Boolean get() = ready > 0
}

/**
 * New-chapter notices, and the one transition worth interrupting somebody for.
 *
 * A notice is raised when a chapter is pulled and stays open until it can be played. That means
 * this holder draws two very different things from one list, and the rule it must not get wrong is
 * **which of them may be dismissed**: [ChapterNotification.dismissible] comes from the server,
 * which answers 409 to the rest. Nothing here re-derives it — a client that worked the rule out for
 * itself would be a fourth opinion about something the server enforces.
 *
 * The system notification fires **only on the pulled → ready transition**, never on arrival. Being
 * told twice about one chapter — once when it appears and once when it plays — is the noise that
 * makes people mute a feature like this, and the pulled state is already in the badge and the list,
 * which are surfaces somebody looks at rather than surfaces that interrupt.
 *
 * Polled rather than pushed. The desktop therefore needs no push credential at all: its OS
 * notification is a rendering of state it already has, unlike the phone's.
 */
class ChapterNotificationsStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Raises a system notification. Substituted in a test, which has no tray and no desktop.
     *
     * A `(title, body) -> Unit` rather than the tray state itself, so nothing about this holder
     * depends on a Compose window existing.
     */
    private val notify: (String, String) -> Unit = { _, _ -> },
    private val pollIntervalMs: Long = DefaultPollIntervalMs,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(ChapterNotificationsUiState())
    val state: StateFlow<ChapterNotificationsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var actionJob: Job? = null

    /**
     * Which notices were already `ready` last time we looked.
     *
     * Null until the first successful load, and that is load-bearing: a chapter that was already
     * ready when the app started is not news — the app was closed when it happened — so the first
     * pass seeds this and announces nothing. Without it every launch would re-announce the backlog.
     */
    private var readySeen: Set<Int>? = null

    /** Starts the poll loop. Idempotent, so a screen entering twice does not double the requests. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                load()
                delay(pollIntervalMs)
            }
        }
    }

    fun refresh() {
        actionJob?.cancel()
        actionJob = scope.launch { load() }
    }

    private suspend fun load() {
        _state.update { it.copy(loading = !it.loaded, error = null) }
        runCatching { repository.chapterNotifications() }
            .onSuccess { response ->
                if (response == null) {
                    // The capability said yes and the route said 404. Hide the surface rather than
                    // show an empty list nothing can ever fill.
                    _state.update {
                        it.copy(loading = false, unsupported = true, loaded = true, notifications = emptyList())
                    }
                    return@onSuccess
                }
                val (fresh, seen) = newlyReady(response.notifications, readySeen)
                readySeen = seen
                _state.update {
                    it.copy(
                        notifications = response.notifications,
                        unread = response.unread,
                        ready = response.ready,
                        loading = false,
                        error = null,
                        unsupported = false,
                        loaded = true,
                    )
                }
                readyNotificationText(fresh)?.let { (title, body) -> notify(title, body) }
            }
            .onFailure { failure ->
                _state.update {
                    // Content is kept: a poll that failed says nothing about the notices already
                    // on screen, and blanking them would lose the very thing being waited for.
                    it.copy(loading = false, error = userFacingMessage(failure, "Could not check for new chapters"))
                }
            }
    }

    /**
     * Clears one notice.
     *
     * Refuses locally for a chapter that cannot be played, which the server would refuse anyway —
     * the check is here so a stale list cannot turn into a request that looks like it worked.
     */
    fun dismiss(notification: ChapterNotification) {
        if (!notification.dismissible) return
        actionJob?.cancel()
        actionJob = scope.launch {
            runCatching { repository.dismissChapterNotification(notification.id) }
                .onSuccess { load() }
                .onFailure { failure ->
                    _state.update {
                        it.copy(error = userFacingMessage(failure, "Could not clear that notice"))
                    }
                }
        }
    }

    fun dismissRead() {
        if (!_state.value.hasClearable) return
        actionJob?.cancel()
        actionJob = scope.launch {
            runCatching { repository.dismissReadChapterNotifications() }
                .onSuccess { load() }
                .onFailure { failure ->
                    _state.update {
                        it.copy(error = userFacingMessage(failure, "Could not clear those notices"))
                    }
                }
        }
    }

    /**
     * Empties everything on sign-out, including what has been seen.
     *
     * The seen set has to go with it: notices belong to an account, and keeping it would let the
     * next account's already-ready chapters arrive silently — or, worse, announce the previous
     * account's.
     */
    fun sessionEnded() {
        pollJob?.cancel()
        actionJob?.cancel()
        readySeen = null
        _state.value = ChapterNotificationsUiState()
    }

    override fun onCleared() {
        pollJob = null
        actionJob = null
    }

    companion object {
        /**
         * A minute.
         *
         * Well inside how long a chapter takes to convert, so nothing is lost by asking rather than
         * being pushed — and the request is one small JSON list, not a library refresh.
         */
        const val DefaultPollIntervalMs: Long = 60_000L

        /** Rows worth drawing: dismissed ones are not requested, but a stale list can hold one. */
        fun visible(notifications: List<ChapterNotification>): List<ChapterNotification> =
            notifications.filter { it.presentation != ChapterNotificationState.Dismissed }
    }
}
