package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the chapter list for one fiction, plus the played/unplayed toggle.
 *
 * Behaviour matches the previous inline implementation exactly, including the deliberate
 * "mark, then refetch the whole list" (rather than patching locally) so the server stays the
 * authority on what actually changed.
 */
class FictionDetailStateHolder(
    private val repository: TtsRoadRepository,
    private val fictionId: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow<Load<ChaptersResponse>>(Load.Loading)
    val state: StateFlow<Load<ChaptersResponse>> = _state.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun setPlayed(chapterId: Int, played: Boolean) {
        scope.launch {
            _actionError.value = null
            runCatching {
                repository.markPlayed(listOf(chapterId), played)
                load()
            }.onFailure { _actionError.value = it.message ?: "Could not update chapter" }
        }
    }

    private suspend fun load() {
        _state.value = runCatching { repository.chapters(fictionId) }
            .fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Could not load chapters") })
    }
}
