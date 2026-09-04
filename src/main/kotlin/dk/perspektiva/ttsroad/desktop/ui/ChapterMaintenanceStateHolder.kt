package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.chapterExcludeMessage
import dk.perspektiva.ttsroad.desktop.data.chapterRetryMessage
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChapterMaintenanceUiState(
    /** The chapter a request is in flight for, so one row can be disabled rather than the list. */
    val busyChapterId: Int? = null,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * Repairing one chapter (#113).
 *
 * Every action here re-reads the fiction's chapters afterwards rather than patching a row: a retry
 * changes `status`, an exclusion changes `excluded` *and* what the filters count, and a delete
 * removes the row entirely. Predicting all three locally would be three chances to disagree with the
 * server about a shared object, and the list is already cached — the re-read is one request.
 */
class ChapterMaintenanceStateHolder(
    private val repository: TtsRoadRepository,
    private val cache: LibraryCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(ChapterMaintenanceUiState())
    val state: StateFlow<ChapterMaintenanceUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Queue a chapter for conversion again.
     *
     * Open to any signed-in account. A `409` comes back as a notice rather than an error, because
     * the chapter is either already converting or deliberately excluded — in both cases what the
     * user wanted is not a thing that failed.
     */
    fun retry(chapter: ChapterSummary) = run(chapter) {
        val outcome = repository.retryChapter(chapter.resolvedChapterId)
        chapterRetryMessage(outcome, chapter.resolvedTitle)
    }

    fun setExcluded(chapter: ChapterSummary, excluded: Boolean) = run(chapter) {
        when (val confirmed = repository.setChapterExcluded(chapter.resolvedChapterId, excluded)) {
            null -> "This server cannot exclude a chapter."
            else -> chapterExcludeMessage(chapter.resolvedTitle, confirmed)
        }
    }

    fun delete(chapter: ChapterSummary) = run(chapter) {
        when (repository.deleteChapter(chapter.resolvedChapterId)) {
            // Null is the server's 404, which is ambiguous by design between "no such endpoint" and
            // "already gone". Neither is worth claiming a deletion for.
            null -> "This server cannot delete a chapter."
            else -> "${chapter.resolvedTitle} is deleted."
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    /**
     * One request at a time, then re-read.
     *
     * The refresh runs whatever the outcome, including a failure: a retry that reported `409`
     * because the chapter was already converting has still left the row out of date on screen.
     */
    private fun run(chapter: ChapterSummary, block: suspend () -> String?) {
        if (_state.value.busyChapterId != null) return
        // Marked busy *here*, not inside the coroutine. A launched body does not run until the
        // dispatcher reaches it, so a guard that reads state the body sets lets a second press
        // through in the gap — and these are writes against one shared row, where the second one
        // would race the first rather than merely repeat it.
        _state.update {
            it.copy(busyChapterId = chapter.resolvedChapterId, notice = null, error = null)
        }
        job = scope.launch {
            val result = runCatching { block() }
            _state.update {
                it.copy(
                    busyChapterId = null,
                    notice = result.getOrNull(),
                    error = result.exceptionOrNull()
                        ?.let { failure -> userFacingMessage(failure, "Could not change that chapter") },
                )
            }
            cache.refreshChapters(chapter.fictionId)
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
