package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.UnfollowOutcome
import dk.perspektiva.ttsroad.desktop.data.prunedSelection
import dk.perspektiva.ttsroad.desktop.data.unfollowConfirmation
import dk.perspektiva.ttsroad.desktop.data.unfollowReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ManageShelfUiState(
    /** The followed shelf, as the cache last knew it. */
    val fictions: List<FictionSummary> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val isBusy: Boolean = false,
    /** The confirmation currently on screen, or null. Holds the count it was raised for. */
    val confirming: Int? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val selectedCount: Int get() = selected.size
    val canUnfollow: Boolean get() = selected.isNotEmpty() && !isBusy
    val allSelected: Boolean get() = fictions.isNotEmpty() && selected.size == fictions.size

    /** The sentence under the confirmation, or null when none is up. */
    val confirmationBody: String? get() = confirming?.let(::unfollowConfirmation)
}

/**
 * Editing the shelf itself, rather than one fiction at a time (#110).
 *
 * A shelf is routinely filled without anybody pressing Follow — the per-user library upgrade
 * backfilled every account with every fiction, and adding one auto-follows it for the adder — so the
 * useful operation is subtractive and plural. There is deliberately no bulk *follow*: the complaint
 * is a shelf filled without asking, and a faster way to fill it does not answer that.
 *
 * The unfollows are sent one at a time against the existing idempotent
 * `DELETE /api/mobile/fictions/{id}/follow`, rather than through a new bulk route. That keeps this
 * working against every deployment advertising `follows` today, including ones that will never be
 * upgraded, and it is what makes a **partial** outcome the normal case to design for rather than an
 * edge: the seventh of ten failing leaves six genuinely removed, and neither "done" nor "failed"
 * describes that.
 */
class ManageShelfStateHolder(
    private val cache: LibraryCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(ManageShelfUiState())
    val state: StateFlow<ManageShelfUiState> = _state.asStateFlow()

    private var unfollowJob: Job? = null

    init {
        scope.launch {
            cache.library.collect { cached ->
                val fictions = cached.value?.fictions.orEmpty()
                _state.update {
                    // A refresh can land while rows are ticked. Pruning here rather than at use
                    // keeps the count and the ticked rows from ever disagreeing.
                    it.copy(fictions = fictions, selected = prunedSelection(it.selected, fictions))
                }
            }
        }
    }

    fun toggle(fictionId: Int) {
        if (_state.value.isBusy) return
        _state.update {
            val selected = if (fictionId in it.selected) it.selected - fictionId else it.selected + fictionId
            it.copy(selected = selected, notice = null, error = null)
        }
    }

    /** Select everything, or clear it when everything already is. One control, both directions. */
    fun toggleAll() {
        if (_state.value.isBusy) return
        _state.update {
            val selected = if (it.allSelected) emptySet() else it.fictions.mapTo(LinkedHashSet()) { row -> row.id }
            it.copy(selected = selected, notice = null, error = null)
        }
    }

    fun clearSelection() {
        if (_state.value.isBusy) return
        _state.update { it.copy(selected = emptySet(), notice = null, error = null) }
    }

    fun askToUnfollow() {
        val current = _state.value
        if (!current.canUnfollow) return
        _state.update { it.copy(confirming = current.selected.size, notice = null, error = null) }
    }

    fun dismissConfirmation() = _state.update { it.copy(confirming = null) }

    /**
     * Unfollow everything selected, reporting what actually happened.
     *
     * Rows are attempted in shelf order so a partial result is comprehensible — "the first six went"
     * rather than a scattering. Each removal patches the cached flag as it lands, so the screen
     * empties as it works instead of at the end; the shelf is re-read once at the finish because a
     * membership change is a different list, not a flag flip.
     */
    fun confirmUnfollow() {
        val current = _state.value
        if (current.confirming == null || !current.canUnfollow) return
        val targets = current.fictions.map { it.id }.filter { it in current.selected }
        if (targets.isEmpty()) return

        unfollowJob?.cancel()
        unfollowJob = scope.launch {
            _state.update { it.copy(isBusy = true, confirming = null, notice = null, error = null) }
            val removed = mutableListOf<Int>()
            val failed = mutableListOf<Int>()
            targets.forEach { id ->
                // A 404 is null here and is *not* success: it means no such fiction or no such
                // endpoint, and neither removed anything.
                val confirmed = runCatching { cache.setFollowing(id, following = false) }.getOrNull()
                if (confirmed == false) removed += id else failed += id
                _state.update { it.copy(selected = it.selected - removed.toSet()) }
            }
            val outcome = UnfollowOutcome(removed = removed, failed = failed)
            _state.update {
                it.copy(
                    isBusy = false,
                    // The failures stay ticked: they are what a retry would act on, and clearing
                    // them would present a partial result as a finished one.
                    selected = failed.toSet(),
                    notice = unfollowReport(outcome),
                    error = null,
                )
            }
        }
    }

    fun refresh() = cache.refreshLibrary()

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    override fun onCleared() {
        unfollowJob?.cancel()
    }
}
