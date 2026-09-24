package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationState
import dk.perspektiva.ttsroad.desktop.data.ReadyNotificationKey
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.newlyReady
import dk.perspektiva.ttsroad.desktop.data.readyNotificationText
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChapterNotificationsUiState(
    val notifications: List<ChapterNotification> = emptyList(),
    val unread: Int = 0,
    val ready: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
) {
    val isEmpty: Boolean get() = notifications.isEmpty()
    val hasClearable: Boolean get() = ready > 0
}

class ChapterNotificationsStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val notify: (String, String) -> Unit = { _, _ -> },
    private val pollIntervalMs: Long = DefaultPollIntervalMs,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(ChapterNotificationsUiState())
    val state: StateFlow<ChapterNotificationsUiState> = _state.asStateFlow()
    private var pollJob: Job? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var readySeen: Set<ReadyNotificationKey>? = null
    private var generation = 0L
    private var refreshPending = false
    private var disposed = false

    fun start() {
        if (disposed || pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(pollIntervalMs)
            }
        }
    }

    fun refresh() {
        if (disposed) return
        if (_state.value.busy || _state.value.loading) {
            refreshPending = true
            return
        }
        val version = ++generation
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch { load(version) }
    }

    private suspend fun load(version: Long, statusMessage: String? = null) {
        try {
            val response = repository.chapterNotifications()
            currentCoroutineContext().ensureActive()
            if (version != generation) return
            if (response == null) {
                _state.update {
                    it.copy(
                        loading = false, unsupported = true, loaded = true,
                        notifications = emptyList(), unread = 0, ready = 0,
                    )
                }
                return
            }
            val (fresh, seen) = newlyReady(response.notifications, readySeen)
            readySeen = seen
            _state.update {
                it.copy(
                    notifications = response.notifications,
                    unread = response.unread,
                    ready = response.ready,
                    loading = false,
                    error = statusMessage,
                    unsupported = false,
                    loaded = true,
                )
            }
            readyNotificationText(fresh)?.let { (title, body) -> notify(title, body) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (version == generation) {
                _state.update {
                    it.copy(loading = false, error = userFacingMessage(failure, "Could not check for new chapters"))
                }
            }
        } finally {
            drainPendingRefresh()
        }
    }

    private fun drainPendingRefresh() {
        if (refreshPending && !disposed && !_state.value.busy && !_state.value.loading) {
            refreshPending = false
            refresh()
        }
    }

    fun dismiss(notification: ChapterNotification) {
        if (!notification.dismissible) return
        mutate("The server refused to clear that notice. Refresh and try again.") {
            repository.dismissChapterNotification(notification.id)
        }
    }

    fun dismissRead() {
        if (!_state.value.hasClearable) return
        mutate("The server refused to clear those notices. Refresh and try again.") {
            repository.dismissReadChapterNotifications()
        }
    }

    private fun mutate(refused: String, action: suspend () -> Boolean) {
        if (disposed || _state.value.busy) return
        val version = ++generation
        loadJob?.cancel()
        _state.update { it.copy(busy = true, loading = false, error = null) }
        actionJob = scope.launch {
            try {
                val accepted = action()
                currentCoroutineContext().ensureActive()
                if (version != generation) return@launch
                if (accepted) {
                    _state.update { it.copy(loading = true) }
                    load(version)
                } else {
                    _state.update { it.copy(loading = true, error = refused) }
                    load(version, statusMessage = refused)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (version == generation) {
                    _state.update { it.copy(error = userFacingMessage(failure, "Could not clear notices")) }
                }
            } finally {
                if (version == generation) _state.update { it.copy(busy = false, loading = false) }
                drainPendingRefresh()
            }
        }
    }

    fun sessionEnded() {
        generation++
        pollJob?.cancel()
        loadJob?.cancel()
        actionJob?.cancel()
        readySeen = null
        refreshPending = false
        _state.value = ChapterNotificationsUiState()
    }

    override fun onCleared() {
        disposed = true
        sessionEnded()
    }

    companion object {
        const val DefaultPollIntervalMs: Long = 60_000L

        fun visible(notifications: List<ChapterNotification>): List<ChapterNotification> =
            notifications.filter { it.presentation != ChapterNotificationState.Dismissed }
    }
}
