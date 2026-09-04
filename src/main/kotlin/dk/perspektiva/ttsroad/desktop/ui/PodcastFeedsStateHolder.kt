package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.ClipboardWriter
import dk.perspektiva.ttsroad.desktop.data.FeedLink
import dk.perspektiva.ttsroad.desktop.data.FeedsResponse
import dk.perspektiva.ttsroad.desktop.data.RotatedFeedNotice
import dk.perspektiva.ttsroad.desktop.data.SystemClipboardWriter
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.accountFeedLinks
import dk.perspektiva.ttsroad.desktop.data.fictionFeedLinks
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodcastFeedsUiState(
    val loading: Boolean = false,
    /** True once the server has answered 404 — it has no feed routes. */
    val unsupported: Boolean = false,
    val account: List<FeedLink> = emptyList(),
    val fictions: List<FeedLink> = emptyList(),
    val confirmingRotate: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = account.isEmpty() && fictions.isEmpty()
}

/**
 * The podcast URLs (#117).
 *
 * Nothing here logs a URL. The token in one is the whole authorization, and `AppLog` is persistent —
 * a feed URL in a log file is a credential in a log file. Errors are reported through
 * [userFacingMessage] on the *exception*, which never carries the request body.
 */
class PodcastFeedsStateHolder(
    private val repository: TtsRoadRepository,
    private val clipboard: ClipboardWriter = SystemClipboardWriter,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(PodcastFeedsUiState())
    val state: StateFlow<PodcastFeedsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var loaded = false

    /** Loads once per session. [force] is the Refresh action. */
    fun ensureLoaded(force: Boolean = false) {
        if (job?.isActive == true) return
        if (loaded && !force) return
        job = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.feeds() }
                .onSuccess { response ->
                    loaded = true
                    _state.update { it.withResponse(response) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = userFacingMessage(failure, "Could not load your feed URLs"),
                        )
                    }
                }
        }
    }

    /**
     * Copy one URL.
     *
     * The notice names the row rather than echoing the URL: repeating a credential into a status
     * line puts it somewhere a screenshot or a screen share picks it up, which is the one place it
     * was not already.
     */
    fun copy(link: FeedLink) {
        runCatching { clipboard.write(link.url) }
            .onSuccess { _state.update { it.copy(notice = "Copied the ${link.label} URL.", error = null) } }
            .onFailure {
                _state.update { it.copy(error = "Could not reach the clipboard.", notice = null) }
            }
    }

    fun askToRotate() {
        if (_state.value.loading) return
        _state.update { it.copy(confirmingRotate = true, notice = null, error = null) }
    }

    fun dismissRotate() = _state.update { it.copy(confirmingRotate = false) }

    fun confirmRotate() {
        if (!_state.value.confirmingRotate) return
        job?.cancel()
        _state.update { it.copy(confirmingRotate = false, loading = true, notice = null, error = null) }
        job = scope.launch {
            runCatching { repository.rotateLibraryFeed() }
                .onSuccess { response ->
                    // The rotate answers with the same shape, so the new URLs are adopted from it
                    // rather than re-fetched — one request, and no window where the screen shows the
                    // revoked pair as if it still worked.
                    _state.update { it.withResponse(response).copy(notice = RotatedFeedNotice) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = userFacingMessage(failure, "Could not issue new URLs"),
                        )
                    }
                }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    private fun PodcastFeedsUiState.withResponse(response: FeedsResponse?): PodcastFeedsUiState =
        when (response) {
            null -> copy(loading = false, unsupported = true, account = emptyList(), fictions = emptyList())
            else -> copy(
                loading = false,
                unsupported = false,
                account = accountFeedLinks(response.library),
                fictions = fictionFeedLinks(response.fictions),
            )
        }

    override fun onCleared() {
        job?.cancel()
    }
}
