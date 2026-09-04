package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.FictionCreateRequest
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.normaliseVoiceRate
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.data.voiceRateProblem
import dk.perspektiva.ttsroad.desktop.data.SyncScope
import java.io.File
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

/**
 * The "add a fiction" form.
 *
 * Adding is the one fiction-shaped action small enough to be a dialog: a source and an optional
 * voice. Editing an existing fiction is a screen — see [FictionMetadataStateHolder] — because the
 * fields there are shared with every account and, on a server that tracks hand edits, saving one
 * takes it away from the source permanently.
 */
data class FictionAddDraft(
    val fictionUrl: String = "",
    val voice: String = "",
    /** Speech rate. Blank leaves the server's default rather than sending a guess. */
    val rate: String = "",
    /**
     * Whether the server keeps polling the source for new chapters. The endpoint's own default.
     *
     * Offered here because it is a property of *tracking* a serial, and a finished work is exactly
     * the case where somebody wants the backlog once and no poller afterwards.
     */
    val autoPoll: Boolean = true,
    /**
     * How much of the backlog to convert.
     *
     * Defaults to what the web form does. The desktop previously sent nothing, which the backend
     * reads as *every chapter* — see [SyncScope].
     */
    val syncScope: SyncScope = SyncScope.Default,
    /**
     * A chosen EPUB, which makes this an *upload* rather than a Royal Road add.
     *
     * One form with two paths rather than two dialogs: "add a fiction" is one intention, and making
     * the user pick which kind of add they wanted before showing them either form is asking them to
     * know the implementation.
     */
    val epubFile: File? = null,
)

data class FictionDeleteConfirmation(val fictionId: Int, val title: String)

data class FictionManagementUiState(
    val access: FictionManagementAccess = FictionManagementAccess.Unsupported,
    /** Whether this server accepts multipart EPUB uploads at all — advertised separately. */
    val epubUploadAvailable: Boolean = false,
    /** The server's byte ceiling, when it published one. */
    val maxEpubBytes: Long? = null,
    val editor: FictionAddDraft? = null,
    val deleteConfirmation: FictionDeleteConfirmation? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Consumed by the root navigator after a successful delete. */
    val deletedFictionId: Int? = null,
    /**
     * The narrator catalogue, or null when it has not been asked for or the server has no route.
     *
     * Null rather than empty because the two say different things: "not available here" retires the
     * picker and leaves the typed field, while an empty catalogue would be a server with no voices
     * installed — which has never been seen and is not worth pretending to handle differently.
     */
    val voices: List<MobileVoice>? = null,
) {
    val canManage: Boolean get() = access == FictionManagementAccess.Admin

    /** Whether a picker can be drawn: the server has the list and this account may apply a choice. */
    val canPickVoice: Boolean get() = voices != null && canManage

    /** Why the typed rate cannot be sent, or null. Nothing else between here and the database checks. */
    val rateProblem: String? get() = voiceRateProblem(editor?.rate)
    val hasOpenOverlay: Boolean get() = editor != null || deleteConfirmation != null
}

/**
 * Admin fiction management, hoisted above navigation so a mutation cannot be lost with a screen.
 *
 * Capability discovery says whether the stable routes exist; `/api/mobile/me` independently says
 * whether this account may use them. Neither the login-time role nor a visible button is treated
 * as authorization — the server remains the final gate for every write.
 *
 * This holder owns adding and deleting: the two actions that are a question and an answer. Editing
 * an existing fiction is [FictionMetadataStateHolder]'s, and it is a screen, because its fields are
 * shared with every account and a metadata edit takes the field away from the source for good.
 * [canManage] is the gate for all three.
 */
class FictionManagementStateHolder(
    private val repository: TtsRoadRepository,
    private val cache: LibraryCache,
    /** The native EPUB chooser. Substituted in a test, which has neither a display nor a person. */
    private val picker: EpubFilePicker = DesktopEpubFilePicker,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(FictionManagementUiState())
    val state: StateFlow<FictionManagementUiState> = _state.asStateFlow()

    private var accessJob: Job? = null
    private var voicesJob: Job? = null
    private var voiceCatalogueAdvertised: Boolean = false
    private var mutationJob: Job? = null
    private var lastSupported: Boolean? = null

    fun ensureAccess(
        supported: Boolean,
        epubUpload: Boolean = false,
        maxEpubBytes: Long? = null,
        voiceCatalogue: Boolean = false,
        forceRefresh: Boolean = false,
    ) {
        if (!supported) {
            lastSupported = false
            accessJob?.cancel()
            _state.update {
                FictionManagementUiState(access = FictionManagementAccess.Unsupported)
            }
            return
        }
        // Refreshed on every call rather than only on the first: capability discovery can land
        // after the first access check, and a server upgraded under a running desktop is noticed
        // the same day.
        _state.update { it.copy(epubUploadAvailable = epubUpload, maxEpubBytes = maxEpubBytes) }
        voiceCatalogueAdvertised = voiceCatalogue
        if (!voiceCatalogue) _state.update { it.copy(voices = null) }
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
                    if (user?.isAdmin == true) loadVoices()
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

    /**
     * Fetch the narrator catalogue, once, in the background.
     *
     * Deliberately silent on failure. The picker is an improvement on a text field that still works;
     * an error banner over the add form for a list nobody asked for would report a problem the user
     * did not have. A null catalogue simply leaves the field typed.
     */
    private fun loadVoices() {
        if (!voiceCatalogueAdvertised || _state.value.voices != null || voicesJob?.isActive == true) return
        voicesJob = scope.launch {
            runCatching { repository.voices() }
                .onSuccess { voices -> _state.update { it.copy(voices = voices) } }
        }
    }

    fun openAdd() {
        if (!_state.value.canManage || _state.value.isBusy) return
        _state.update { it.copy(editor = FictionAddDraft(), error = null, notice = null) }
    }

    fun updateAdd(
        fictionUrl: String? = null,
        voice: String? = null,
        rate: String? = null,
        autoPoll: Boolean? = null,
        syncScope: SyncScope? = null,
    ) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(
                editor = editor.copy(
                    fictionUrl = fictionUrl ?: editor.fictionUrl,
                    voice = voice ?: editor.voice,
                    rate = rate ?: editor.rate,
                    autoPoll = autoPoll ?: editor.autoPoll,
                    syncScope = syncScope ?: editor.syncScope,
                ),
            )
        }
    }

    /**
     * Opens the native picker and validates what comes back.
     *
     * Checked here rather than only on the server, because every one of these produces a 400 that
     * arrives after the whole file has been uploaded — and telling somebody their 40 MB book was
     * the wrong kind of file *after* sending it is the worst possible order to do it in.
     */
    fun chooseEpub() {
        val state = _state.value
        if (state.editor == null || state.isBusy || !state.epubUploadAvailable) return
        val chosen = picker.choose() ?: return
        val problem = epubProblem(chosen, state.maxEpubBytes)
        _state.update { current ->
            val editor = current.editor ?: return@update current
            if (problem != null) {
                current.copy(error = problem)
            } else {
                current.copy(editor = editor.copy(epubFile = chosen), error = null, notice = null)
            }
        }
    }

    /** Puts the dialog back on the Royal Road path without closing it. */
    fun clearEpub() {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(epubFile = null), error = null)
        }
    }

    fun submitAdd() {
        val editor = _state.value.editor ?: return
        if (!_state.value.canManage || _state.value.isBusy) return
        // A chosen EPUB *is* the answer to "which fiction", so the URL is not also required.
        val validation = when {
            editor.epubFile != null -> epubProblem(editor.epubFile, _state.value.maxEpubBytes)
            editor.fictionUrl.isBlank() -> "A Royal Road URL, a fiction id, or an EPUB is required"
            // Checked here and not only on the button: a disabled control is a courtesy, not a
            // guarantee, and nothing downstream — including the server — checks this string.
            else -> voiceRateProblem(editor.rate)
        }
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        mutationJob?.cancel()
        mutationJob = scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            runCatching {
                editor.epubFile?.let { epub ->
                    repository.uploadEpub(epub, editor.voice.trim().takeIf(String::isNotEmpty))
                } ?: repository.createFiction(
                    FictionCreateRequest(
                        fictionUrl = editor.fictionUrl.trim(),
                        voice = editor.voice.trim().takeIf(String::isNotEmpty),
                        // Normalised, not sent as typed: "10" means +10% and the server would
                        // store the bare string and fail on it at conversion time.
                        rate = normaliseVoiceRate(editor.rate),
                        enabled = editor.autoPoll,
                        // Always sent, including the null that means "everything": the field is
                        // chosen in the form, so its absence would be this client guessing again.
                        syncLimit = editor.syncScope.limit,
                        syncDirection = editor.syncScope.direction,
                    ),
                )
            }
                .onSuccess { fiction ->
                    cache.patchFiction(fiction)
                    cache.refreshLibrary()
                    cache.refreshBrowseAll()
                    _state.update {
                        it.copy(
                            editor = null,
                            isBusy = false,
                            error = null,
                            notice = addedNotice(fiction.title, editor.syncScope),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = userFacingMessage(failure, "Could not add fiction"),
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
        /**
         * What is wrong with this file, or null when nothing is.
         *
         * Pure and internal so the three rules can be asserted without a picker, a server or a
         * dialog. The extension check is not belt-and-braces: AWT's filename filter is a *hint*
         * that several Linux window managers ignore outright.
         */
        /** Says what was actually queued, because the three scopes cost wildly different amounts. */
        internal fun addedNotice(title: String, scope: SyncScope): String = when (scope) {
            SyncScope.Everything ->
                "Added $title; the whole backlog is converting on the server"
            SyncScope.OldestTwentyFive ->
                "Added $title; the oldest 25 chapters are converting on the server"
            SyncScope.NewestTwentyFive ->
                "Added $title; the newest 25 chapters are converting on the server"
        }

        internal fun epubProblem(file: File, maxBytes: Long?): String? = when {
            !file.isFile -> "That file could not be read"
            !file.name.endsWith(".epub", ignoreCase = true) -> "Only .epub files can be uploaded"
            file.length() == 0L -> "That file is empty"
            maxBytes != null && file.length() > maxBytes ->
                "That file is ${formatMegabytes(file.length())}; this server accepts up to " +
                    formatMegabytes(maxBytes)
            else -> null
        }

        private fun formatMegabytes(bytes: Long): String =
            String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

        private val VerifiedAccess = setOf(
            FictionManagementAccess.Admin,
            FictionManagementAccess.NotAdmin,
            FictionManagementAccess.Unsupported,
        )
    }
}
