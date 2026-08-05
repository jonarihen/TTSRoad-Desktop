package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the library load. Behaviour is identical to the previous inline `LaunchedEffect(Unit)`:
 * one fetch when the screen appears, mapped into [Load].
 */
class LibraryStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow<Load<LibraryResponse>>(Load.Loading)
    val state: StateFlow<Load<LibraryResponse>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _state.value = runCatching { repository.library() }
                .fold({ Load.Ok(it) }, { Load.Err(userFacingMessage(it, "Could not load library")) })
        }
    }
}
