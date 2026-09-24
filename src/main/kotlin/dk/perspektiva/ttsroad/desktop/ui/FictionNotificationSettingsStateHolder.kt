package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.FictionNotificationModes
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.desktop.data.FictionNotificationSettingsRequest
import dk.perspektiva.ttsroad.desktop.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.notificationNumber
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.data.validBacklogHours
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

data class FictionNotificationDraft(val mode: String, val hours: String) {
    fun request(saved: FictionNotificationSettings): FictionNotificationSettingsRequest? {
        if (mode !in FictionNotificationModes) return null
        val threshold = if (mode == NotificationModeBacklog) {
            validBacklogHours(hours) ?: return null
        } else {
            validBacklogHours(saved.backlogHours.toString()) ?: 2.0
        }
        return FictionNotificationSettingsRequest(mode, threshold)
    }

    companion object {
        fun from(settings: FictionNotificationSettings): FictionNotificationDraft = FictionNotificationDraft(
            settings.mode,
            if (settings.backlogHours.isFinite()) notificationNumber(settings.backlogHours) else "",
        )
    }
}

data class FictionNotificationSettingsUiState(
    val visible: Boolean = false,
    val settings: FictionNotificationSettings? = null,
    val draft: FictionNotificationDraft? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val stale: Boolean = false,
    val loadError: String? = null,
    val saveError: String? = null,
) {
    val busy: Boolean get() = loading || saving
    val error: String? get() = saveError ?: loadError
    val canEdit: Boolean get() = visible && settings != null && draft != null && !busy
    val canSave: Boolean get() = canEdit && dirty && settings?.let { draft?.request(it) } != null
    val dirty: Boolean get() = settings?.let { saved ->
        draft?.let { it.mode != saved.mode || validBacklogHours(it.hours) != saved.backlogHours }
    } == true
}

class FictionNotificationSettingsStateHolder(
    private val repository: TtsRoadRepository,
    private val fictionId: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val enabled: Boolean = repository.currentCapabilities.value.backlogNotifications,
    private val onSaved: () -> Unit = {},
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(FictionNotificationSettingsUiState())
    val state: StateFlow<FictionNotificationSettingsUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var pollJob: Job? = null
    private var generation = 0L
    private var disposed = false

    fun open() {
        if (!enabled || disposed || _state.value.visible) return
        _state.update {
            it.copy(visible = true, draft = it.settings?.let(FictionNotificationDraft::from), saveError = null)
        }
        refresh()
        pollJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                refresh()
            }
        }
    }

    fun close() {
        generation++
        pollJob?.cancel()
        loadJob?.cancel()
        saveJob?.cancel()
        _state.update {
            it.copy(visible = false, loading = false, saving = false, stale = it.stale || it.saving)
        }
    }

    fun setMode(mode: String) {
        if (!_state.value.canEdit || mode !in FictionNotificationModes) return
        _state.update { it.copy(draft = it.draft?.copy(mode = mode)) }
    }

    fun setHours(hours: String) {
        if (!_state.value.canEdit) return
        _state.update { it.copy(draft = it.draft?.copy(hours = hours)) }
    }

    fun refresh() {
        val before = _state.value
        if (!enabled || disposed || !before.visible || before.saving || before.loading) return
        val version = ++generation
        val keepDraft = before.dirty
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = scope.launch {
            try {
                val settings = repository.fictionNotificationSettings(fictionId)
                currentCoroutineContext().ensureActive()
                if (version != generation) return@launch
                if (settings == null) {
                    loadFailed("Notification settings unavailable. Check that you still follow this book, then retry.")
                } else {
                    _state.update {
                        it.copy(
                            settings = settings,
                            draft = if (keepDraft) it.draft else FictionNotificationDraft.from(settings),
                            loading = false,
                            loadError = null,
                            stale = it.saveError != null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (version == generation) {
                    loadFailed(userFacingMessage(failure, "Could not load chapter notification settings"))
                }
            }
        }
    }

    fun save() {
        val before = _state.value
        if (!enabled || disposed || !before.canSave) return
        val request = before.settings?.let { before.draft?.request(it) } ?: return
        val version = ++generation
        loadJob?.cancel()
        _state.update { it.copy(saving = true, loading = false, saveError = null) }
        saveJob = scope.launch {
            try {
                val settings = repository.updateFictionNotificationSettings(fictionId, request)
                currentCoroutineContext().ensureActive()
                if (version != generation) return@launch
                if (settings == null) {
                    saveFailed("The server did not save these settings. Check that you still follow this book, then retry.")
                } else {
                    _state.update {
                        it.copy(
                            settings = settings,
                            draft = FictionNotificationDraft.from(settings),
                            saving = false,
                            stale = false,
                            loadError = null,
                            saveError = null,
                        )
                    }
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (version == generation) {
                    saveFailed(userFacingMessage(failure, "Could not save chapter notification settings"))
                }
            }
        }
    }

    private fun loadFailed(message: String) {
        _state.update { it.copy(loading = false, loadError = message, stale = it.settings != null) }
    }

    private fun saveFailed(message: String) {
        _state.update { it.copy(saving = false, saveError = message, stale = it.settings != null) }
    }

    override fun onCleared() {
        disposed = true
        close()
    }
}
