package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.SearchResponse
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

/**
 * What the search screen shows.
 *
 * [query] is the text in the field and [resultQuery] is the text [result] actually answers. They
 * differ while the user is typing a second search over the first one's results, which is why the
 * screen can say "showing results for …" instead of implying the list matches what is on screen.
 */
data class SearchUiState(
    val query: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val result: SearchResponse? = null,
    val resultQuery: String = "",
    /**
     * The server answered 404 for the endpoint it advertised.
     *
     * Distinct from [error] on purpose, and for the same reason `SettingsStateHolder` separates
     * "the server cannot answer at all" from "the answer is empty": one is worth a retry and the
     * other never will be.
     */
    val unsupported: Boolean = false,
) {
    /** True once a search has completed, so "no matches" is only said about a query that ran. */
    val hasSearched: Boolean get() = result != null || error != null || unsupported
}

/**
 * Server-side search, held above the screen.
 *
 * Hoisted like the settings and update holders so results survive opening a hit and coming back —
 * a search whose results are destroyed by following one of them is a search you have to run twice.
 * It is also why [Destination.Search][dk.perspektiva.ttsroad.desktop.nav.Destination.Search] can be
 * a plain object with no payload: the query lives here, not in the back stack.
 */
class SearchStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Every keystroke in the field. Deliberately does not search: see [submit]. */
    fun queryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * Runs [query], replacing whatever was in the field.
     *
     * The entry point from the library, where the user has already typed the words once. Blank is
     * accepted and clears rather than asking the server for everything.
     */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        submit()
    }

    /**
     * Runs whatever is in the field.
     *
     * Explicit rather than debounced-as-you-type: the local library filter is the instant path and
     * still works offline, and this one is a network round trip over every chapter's narration text.
     * Making it an action is what keeps those two honestly different.
     */
    fun submit() {
        val query = _state.value.query.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            _state.update { it.copy(busy = false, error = null, result = null, resultQuery = "", unsupported = false) }
            return
        }
        _state.update { it.copy(busy = true, error = null, unsupported = false) }
        searchJob = scope.launch {
            val outcome = runCatching { repository.search(query) }
            _state.update { current ->
                outcome.fold(
                    onSuccess = { response ->
                        if (response == null) {
                            current.copy(busy = false, unsupported = true, result = null, resultQuery = query)
                        } else {
                            current.copy(busy = false, error = null, result = response, resultQuery = query)
                        }
                    },
                    // Content is kept: a failed search over a list already on screen is the same
                    // situation as a failed library refresh, and blanking the screen loses more
                    // than the error explains.
                    onFailure = { failure ->
                        current.copy(busy = false, error = userFacingMessage(failure, "Could not search"))
                    },
                )
            }
        }
    }

    /** Re-runs the query the results belong to — what Refresh means on this screen. */
    fun refresh() {
        if (_state.value.resultQuery.isNotBlank()) search(_state.value.resultQuery)
    }

    /** The account's search results are the account's. Dropped with the session, like the library. */
    fun sessionEnded() {
        searchJob?.cancel()
        _state.value = SearchUiState()
    }
}
