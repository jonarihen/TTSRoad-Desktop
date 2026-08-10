package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.update.DownloadOutcome
import dk.perspektiva.ttsroad.desktop.update.LatestRelease
import dk.perspektiva.ttsroad.desktop.update.ReleaseAsset
import dk.perspektiva.ttsroad.desktop.update.UpdateChecker
import dk.perspektiva.ttsroad.desktop.update.UpdateDownloader
import dk.perspektiva.ttsroad.desktop.update.UpdateSettingsStore
import dk.perspektiva.ttsroad.desktop.update.UpdateStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {

    private val _state = MutableStateFlow(UpdateUiState(automatic = settingsStore.settings.value.automatic))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

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
        if (_state.value.status is UpdateStatus.Checking) return
        scope.launch {
            // A throttled automatic check must not blank a result the pane is already showing.
            if (manual) _state.value = _state.value.copy(status = UpdateStatus.Checking)
            val status = checker.check(manual)
            if (status is UpdateStatus.Unknown && !manual) {
                _state.value = _state.value.copy(status = _state.value.status)
            } else {
                _state.value = _state.value.copy(status = status, downloadError = null)
            }
        }
    }

    /** Downloads and verifies. Installing the result stays an explicit action by the user. */
    fun download() {
        val state = _state.value
        val release = state.available ?: return
        val asset = state.downloadable ?: return
        if (state.isDownloading) return
        _state.value = state.copy(isDownloading = true, downloadError = null, downloadedName = null)
        scope.launch {
            when (val outcome = downloader.download(release, asset)) {
                is DownloadOutcome.Verified -> _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadedName = outcome.file.name,
                )

                is DownloadOutcome.Failed -> _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadError = outcome.reason,
                )
            }
        }
    }

    /** Stops this version being announced again, and clears it from the pane. */
    fun dismiss() {
        val version = _state.value.available?.version ?: return
        checker.dismiss(version)
        _state.value = _state.value.copy(status = UpdateStatus.UpToDate(0L))
    }

    fun setAutomatic(enabled: Boolean) {
        checker.setAutomatic(enabled)
        _state.value = _state.value.copy(automatic = enabled)
    }
}
