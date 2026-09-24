package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.di.AppDispatchers
import dk.perspektiva.ttsroad.desktop.update.DownloadOutcome
import dk.perspektiva.ttsroad.desktop.update.LatestRelease
import dk.perspektiva.ttsroad.desktop.update.ReleaseAsset
import dk.perspektiva.ttsroad.desktop.update.UpdateChecker
import dk.perspektiva.ttsroad.desktop.update.UpdateDownloader
import dk.perspektiva.ttsroad.desktop.update.UpdateSettingsStore
import dk.perspektiva.ttsroad.desktop.update.UpdateStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the About pane draws. Downloading is separate from checking; both can be in flight once. */
data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Unknown,
    val automatic: Boolean = true,
    val isDownloading: Boolean = false,
    /** Set once a verified installer is on disk and has been handed to the desktop. */
    val downloadedName: String? = null,
    val downloadError: String? = null,
) {
    val available: LatestRelease?
        get() = (status as? UpdateStatus.Available)?.release

    val downloadable: ReleaseAsset?
        get() = (status as? UpdateStatus.Available)?.asset
}

/**
 * Drives the update check for the About pane.
 *
 * The screen never calls the checker or the downloader directly, so the pane stays a rendering of
 * one state value and every decision — throttled, dismissed, verified, rejected — is exercised in
 * the holder's own tests rather than only through the Compose runtime.
 */
class UpdateStateHolder(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    settingsStore: UpdateSettingsStore,
    dispatcher: CoroutineDispatcher = AppDispatchers.Default.main,
) : StateHolder(dispatcher) {

    private val _state = MutableStateFlow(UpdateUiState(automatic = settingsStore.settings.value.automatic))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var generation = 0L

    /**
     * The once-per-launch check. Safe to call on every entry to the pane: the checker itself owns
     * the throttle, and a throttled call answers [UpdateStatus.Unknown] without a request.
     */
    fun checkAutomatically() {
        check(manual = false)
    }

    fun checkNow() {
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (!scope.isActive || (!manual && checkJob?.isActive == true)) return
        if (manual) {
            invalidateRequests()
            _state.value = _state.value.copy(
                status = UpdateStatus.Checking,
                isDownloading = false,
                downloadedName = null,
                downloadError = null,
            )
        }
        val requestGeneration = generation
        checkJob = scope.launch(start = CoroutineStart.LAZY) {
            val status = checker.check(manual)
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@launch
            if (status !is UpdateStatus.Unknown || manual) {
                _state.value = _state.value.copy(status = status, downloadError = null)
            }
        }.also { it.start() }
    }

    /** Downloads and verifies. Installing the result stays an explicit action by the user. */
    fun download() {
        if (!scope.isActive) return
        val state = _state.value
        val release = state.available ?: return
        val asset = state.downloadable ?: return
        if (state.isDownloading) return
        val requestGeneration = generation
        _state.value = state.copy(isDownloading = true, downloadError = null, downloadedName = null)
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = downloader.download(release, asset)
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@launch
            when (outcome) {
                is DownloadOutcome.Verified -> _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadedName = outcome.file.name,
                )

                is DownloadOutcome.Failed -> _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadError = outcome.reason,
                )
            }
        }.also { it.start() }
    }

    /** Stops this version being announced again, and clears it from the pane. */
    fun dismiss() {
        val version = _state.value.available?.version ?: return
        invalidateRequests()
        checker.dismiss(version)
        _state.value = _state.value.copy(
            status = UpdateStatus.UpToDate(0L),
            isDownloading = false,
            downloadedName = null,
            downloadError = null,
        )
    }

    fun setAutomatic(enabled: Boolean) {
        checker.setAutomatic(enabled)
        _state.value = _state.value.copy(automatic = enabled)
    }

    private fun invalidateRequests() {
        generation++
        checkJob?.cancel()
        downloadJob?.cancel()
    }

    override fun onCleared() {
        invalidateRequests()
    }
}
