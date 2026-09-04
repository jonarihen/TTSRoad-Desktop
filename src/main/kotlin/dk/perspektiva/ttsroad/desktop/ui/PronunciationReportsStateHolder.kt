package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.PronunciationReport
import dk.perspektiva.ttsroad.desktop.data.ReportOutcome
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.pronunciationReportProblem
import dk.perspektiva.ttsroad.desktop.data.pronunciationReportRequest
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The form, while it is open. Null [chapterId] means nothing is loaded to report against. */
data class PronunciationDraft(
    val chapterId: Int,
    val chapterTitle: String,
    val positionMs: Long,
    val word: String = "",
    val note: String = "",
) {
    val problem: String? get() = pronunciationReportProblem(word, note)
    val canSend: Boolean get() = problem == null
}

data class PronunciationUiState(
    val draft: PronunciationDraft? = null,
    val reports: List<PronunciationReport> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * Filing "that word is pronounced wrong" from where it was heard (#121).
 *
 * The draft carries the chapter and the position taken from the player at the moment the form
 * opened, not at the moment it is sent. Those differ by however long somebody spends typing, and the
 * useful timestamp is the one where the word actually was.
 */
class PronunciationReportsStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(PronunciationUiState())
    val state: StateFlow<PronunciationUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var loaded = false

    /** Opens the form against a frozen chapter and position. */
    fun open(chapterId: Int, chapterTitle: String, positionMs: Long) {
        if (chapterId <= 0) return
        _state.update {
            it.copy(
                draft = PronunciationDraft(chapterId, chapterTitle, positionMs),
                notice = null,
                error = null,
            )
        }
    }

    fun setWord(value: String) = editDraft { it.copy(word = value) }

    fun setNote(value: String) = editDraft { it.copy(note = value) }

    fun dismiss() = _state.update { it.copy(draft = null, error = null) }

    fun send() {
        val draft = _state.value.draft ?: return
        if (_state.value.busy) return
        val request = pronunciationReportRequest(
            chapterId = draft.chapterId,
            positionMs = draft.positionMs,
            word = draft.word,
            note = draft.note,
        ) ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        job = scope.launch {
            runCatching { repository.createPronunciationReport(request) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is ReportOutcome.Filed -> _state.update {
                            it.copy(
                                busy = false,
                                draft = null,
                                // Prepended rather than re-fetched: the server returns the stored
                                // row, so a second request would only confirm what it just said.
                                reports = listOf(outcome.report) + it.reports,
                                notice = "Reported. It shows up in the server's text tools.",
                            )
                        }
                        // Not a failure to retry — the fix is to clear some, and the server's
                        // sentence names how many.
                        is ReportOutcome.AtCapacity ->
                            _state.update { it.copy(busy = false, error = outcome.message) }
                        ReportOutcome.Unsupported -> _state.update {
                            it.copy(busy = false, error = "This server does not take pronunciation reports.")
                        }
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = false, error = userFacingMessage(failure, "Could not file that report"))
                    }
                }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        if (job?.isActive == true) return
        if (loaded && !force) return
        job = scope.launch {
            runCatching { repository.pronunciationReports() }
                .onSuccess { reports ->
                    loaded = true
                    _state.update { it.copy(reports = reports.orEmpty()) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(error = userFacingMessage(failure, "Could not load your reports"))
                    }
                }
        }
    }

    fun delete(report: PronunciationReport) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        job = scope.launch {
            runCatching { repository.deletePronunciationReport(report.id) }
                .onSuccess { removed ->
                    _state.update {
                        if (removed) {
                            it.copy(busy = false, reports = it.reports.filterNot { r -> r.id == report.id })
                        } else {
                            // A 404 is ambiguous between "no such endpoint" and "already gone", so
                            // the row stays rather than being removed on a guess.
                            it.copy(busy = false, error = "That report could not be deleted.")
                        }
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = false, error = userFacingMessage(failure, "Could not delete that report"))
                    }
                }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    private fun editDraft(block: (PronunciationDraft) -> PronunciationDraft) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = block(draft), error = null)
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
