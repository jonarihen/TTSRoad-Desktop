package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.FictionCreateRequest
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.FictionUpdateRequest
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FictionManagementAccess {
    Unsupported,
    Checking,
    Admin,
    NotAdmin,
    Unavailable,
}

sealed interface FictionEditor {
    data class Add(
        val fictionUrl: String = "",
        val voice: String = "",
    ) : FictionEditor

    data class Edit(
        val fictionId: Int,
        val title: String,
        val author: String,
        val voice: String,
    ) : FictionEditor
}

data class FictionDeleteConfirmation(val fictionId: Int, val title: String)

data class FictionManagementUiState(
    val access: FictionManagementAccess = FictionManagementAccess.Unsupported,
    val editor: FictionEditor? = null,
    val deleteConfirmation: FictionDeleteConfirmation? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Consumed by the root navigator after a successful delete. */
    val deletedFictionId: Int? = null,
) {
    val canManage: Boolean get() = access == FictionManagementAccess.Admin
    val hasOpenOverlay: Boolean get() = editor != null || deleteConfirmation != null
}

/**
 * Admin fiction management, hoisted above navigation so a mutation cannot be lost with a screen.
 *
 * Capability discovery says whether the stable routes exist; `/api/mobile/me` independently says
 * whether this account may use them. Neither the login-time role nor a visible button is treated
 * as authorization — the server remains the final gate for every write.
 */
class FictionManagementStateHolder(
    private val repository: TtsRoadRepository,
    private val cache: LibraryCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(FictionManagementUiState())
    val state: StateFlow<FictionManagementUiState> = _state.asStateFlow()

    private var accessJob: Job? = null
    private var mutationJob: Job? = null
    private var lastSupported: Boolean? = null

    fun ensureAccess(supported: Boolean, forceRefresh: Boolean = false) {
        if (!supported) {
            lastSupported = false
            accessJob?.cancel()
            _state.update {
                FictionManagementUiState(access = FictionManagementAccess.Unsupported)
            }
            return
        }
        val newlySupported = lastSupported != true
        lastSupported = true
        if (accessJob?.isActive == true || (!forceRefresh && !newlySupported && _state.value.access in VerifiedAccess)) {
            return
        }
        accessJob = scope.launch {
            _state.update { it.copy(access = FictionManagementAccess.Checking, error = null) }
            runCatching { repository.currentUser() }
                .onSuccess { user ->
                    _state.update {
                        it.copy(
                            access = when {
                                user == null -> FictionManagementAccess.Unsupported
                                user.isAdmin -> FictionManagementAccess.Admin
                                else -> FictionManagementAccess.NotAdmin
                            },
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            access = FictionManagementAccess.Unavailable,
                            error = userFacingMessage(failure, "Could not verify admin access"),
                        )
                    }
                }
        }
    }

    fun openAdd() {
        if (!_state.value.canManage || _state.value.isBusy) return
        _state.update { it.copy(editor = FictionEditor.Add(), error = null, notice = null) }
    }

    fun openEdit(fiction: FictionSummary) {
        if (!_state.value.canManage || _state.value.isBusy || fiction.id <= 0) return
        _state.update {
            it.copy(
                editor = FictionEditor.Edit(
                    fictionId = fiction.id,
                    title = fiction.title,
                    author = fiction.author.orEmpty(),
                    voice = fiction.voice.orEmpty(),
                ),
                error = null,
                notice = null,
            )
        }
    }

    fun updateAdd(fictionUrl: String? = null, voice: String? = null) {
        _state.update { current ->
            val editor = current.editor as? FictionEditor.Add ?: return@update current
            current.copy(editor = editor.copy(fictionUrl = fictionUrl ?: editor.fictionUrl, voice = voice ?: editor.voice))
        }
    }

    fun updateEdit(title: String? = null, author: String? = null, voice: String? = null) {
        _state.update { current ->
            val editor = current.editor as? FictionEditor.Edit ?: return@update current
            current.copy(
                editor = editor.copy(
                    title = title ?: editor.title,
                    author = author ?: editor.author,
                    voice = voice ?: editor.voice,
                ),
            )
        }
    }

    fun submitEditor() {
        val editor = _state.value.editor ?: return
        if (!_state.value.canManage || _state.value.isBusy) return
        val validation = when (editor) {
            is FictionEditor.Add -> "A Royal Road URL or fiction id is required".takeIf {
                editor.fictionUrl.isBlank()
            }

            is FictionEditor.Edit -> when {
                editor.title.isBlank() -> "Title cannot be empty"
                editor.voice.isBlank() -> "Voice cannot be empty"
                else -> null
            }
        }
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        mutationJob?.cancel()
        mutationJob = scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            val outcome = runCatching {
                when (editor) {
                    is FictionEditor.Add -> repository.createFiction(
                        FictionCreateRequest(
                            fictionUrl = editor.fictionUrl.trim(),
                            voice = editor.voice.trim().takeIf(String::isNotEmpty),
                        ),
                    )

                    is FictionEditor.Edit -> repository.updateFiction(
                        editor.fictionId,
                        FictionUpdateRequest(
                            title = editor.title.trim(),
                            author = editor.author.trim(),
                            voice = editor.voice.trim(),
                        ),
                    )
                }
            }
            outcome
                .onSuccess { fiction ->
                    cache.patchFiction(fiction)
                    cache.refreshLibrary()
                    cache.refreshBrowseAll()
                    if (editor is FictionEditor.Edit) cache.refreshChapters(editor.fictionId)
                    _state.update {
                        it.copy(
                            editor = null,
                            isBusy = false,
                            error = null,
                            notice = if (editor is FictionEditor.Add) {
                                "Added ${fiction.title}; chapter discovery is running on the server"
                            } else {
                                "Saved ${fiction.title}"
                            },
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = userFacingMessage(failure, "Could not save fiction"),
                        )
                    }
                }
        }
    }

    fun askDelete(fiction: FictionSummary) {
        if (!_state.value.canManage || _state.value.isBusy || fiction.id <= 0) return
        _state.update {
            it.copy(
                deleteConfirmation = FictionDeleteConfirmation(fiction.id, fiction.title),
                error = null,
                notice = null,
            )
        }
    }

    fun confirmDelete() {
        val pending = _state.value.deleteConfirmation ?: return
        if (!_state.value.canManage || _state.value.isBusy) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            _state.update { it.copy(deleteConfirmation = null, isBusy = true, error = null, notice = null) }
            runCatching {
                check(repository.deleteFiction(pending.fictionId)) {
                    "The server did not confirm the deletion"
                }
                pending
            }
                .onSuccess {
                    cache.forgetFiction(pending.fictionId)
                    cache.refreshLibrary()
                    cache.refreshBrowseAll()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            notice = "Deleted ${pending.title}",
                            deletedFictionId = pending.fictionId,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = userFacingMessage(failure, "Could not delete fiction"),
                        )
                    }
                }
        }
    }

    fun dismissOverlay() {
        if (_state.value.isBusy) return
        _state.update { it.copy(editor = null, deleteConfirmation = null, error = null) }
    }

    fun consumeDeletedFiction() {
        _state.update { it.copy(deletedFictionId = null) }
    }

    fun sessionEnded() {
        accessJob?.cancel()
        mutationJob?.cancel()
        lastSupported = null
        _state.value = FictionManagementUiState()
    }

    companion object {
        private val VerifiedAccess = setOf(
            FictionManagementAccess.Admin,
            FictionManagementAccess.NotAdmin,
            FictionManagementAccess.Unsupported,
        )
    }
}
