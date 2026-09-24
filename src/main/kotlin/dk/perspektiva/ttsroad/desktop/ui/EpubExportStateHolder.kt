package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.suggestedEpubFileName
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.download.EpubDownloadResult
import dk.perspektiva.ttsroad.desktop.download.EpubExportDownloader
import dk.perspektiva.ttsroad.desktop.download.EpubExportOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class EpubExportUiState(
    val fictionId: Int? = null,
    val isBusy: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val notice: String? = null,
    val error: String? = null,
)

class EpubExportStateHolder(
    private val repository: TtsRoadRepository,
    private val downloader: EpubExportDownloader,
    sessionStore: SessionStore,
    private val picker: EpubSavePicker = DesktopEpubSavePicker,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(EpubExportUiState())
    val state: StateFlow<EpubExportUiState> = _state.asStateFlow()
    private var operation: EpubExportOperation? = null
    private var job: Job? = null
    private var cleared = false

    init {
        var owner = sessionStore.current().let { Triple(it.serverUrl, it.username, it.token) }
        scope.launch {
            sessionStore.session.map { Triple(it.serverUrl, it.username, it.token) }
                .distinctUntilChanged().collect { current ->
                    if (current != owner) {
                        owner = current
                        cancel()
                    }
                }
        }
        scope.launch {
            repository.currentCapabilities.collect {
                if (!it.ebookExport) cancel()
            }
        }
    }

    fun download(fiction: FictionSummary) {
        if (cleared || _state.value.isBusy || !repository.currentCapabilities.value.ebookExport) return
        val session = repository.epubExportSession(fiction.id) ?: return
        val active = EpubExportOperation(session)
        operation = active
        _state.value = EpubExportUiState(fictionId = fiction.id, isBusy = true)
        job = scope.launch {
            try {
                val destination = picker.choose(suggestedEpubFileName(fiction.title)) ?: return@launch
                ensureActive()
                active.ensureCurrent()
                val result = downloader.download(active, destination) { downloaded, total ->
                    active.publish {
                        _state.update { it.copy(bytesDownloaded = downloaded, totalBytes = total) }
                    }
                }
                ensureActive()
                active.publish {
                    _state.update {
                        when (result) {
                            is EpubDownloadResult.Success -> it.copy(notice = "Saved EPUB to ${result.file}", error = null)
                            is EpubDownloadResult.Failed -> it.copy(error = result.message, notice = null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                active.publish {
                    _state.update { it.copy(error = userFacingMessage(failure, "Could not export EPUB")) }
                }
            } finally {
                if (operation === active) {
                    if (!active.isCurrent()) _state.value = EpubExportUiState()
                    else _state.update { it.copy(isBusy = false) }
                    operation = null
                }
            }
        }
    }

    fun cancel() {
        operation?.cancel()
        operation = null
        job?.cancel()
        _state.value = EpubExportUiState()
    }

    override fun onCleared() {
        cleared = true
        cancel()
    }
}
