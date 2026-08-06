package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.DeviceSession
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.currentSessionFirst
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The panes of the settings screen, in the order they appear in the left-hand list. */
enum class SettingsSection(val label: String) {
    Account("Account"),
    Devices("Device sessions"),
    Playback("Playback"),
    Offline("Offline"),
    About("Updates & About"),
}

/**
 * A question the user has to answer before something irreversible happens.
 *
 * One slot, one dialog: only one of these can be pending at a time, and modelling it as state
 * rather than as a `var showDialog` inside a composable is what makes both answers — confirm and
 * cancel — testable without a display.
 */
sealed interface SettingsConfirmation {
    data class RevokeDevice(val device: DeviceSession) : SettingsConfirmation
    data object RevokeOtherDevices : SettingsConfirmation
    data object SignOut : SettingsConfirmation
}

/**
 * Everything the device-sessions pane needs.
 *
 * [loaded] and [unsupported] answer two different questions and are deliberately separate:
 * `loaded == emptyList()` means "this account has no other sessions", while [unsupported] means
 * "this server cannot answer at all". [error] never clears [loaded] — a failed refresh must leave
 * the list the user was already reading on screen.
 */
data class DeviceSessionsUiState(
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val loaded: List<DeviceSession>? = null,
    val unsupported: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    /** True only while there is nothing at all to show yet — the one case that owes a spinner. */
    val isInitialLoad: Boolean get() = isLoading && loaded == null && error == null && !unsupported
}

data class SettingsUiState(
    val section: SettingsSection = SettingsSection.Account,
    val confirmation: SettingsConfirmation? = null,
    val devices: DeviceSessionsUiState = DeviceSessionsUiState(),
    /** From `GET /api/mobile/me`; null until it answers, or against a server without the endpoint. */
    val verifiedUser: MobileUser? = null,
    val signingOut: Boolean = false,
)

/**
 * State for the whole settings screen, including device-session management.
 *
 * Held above the settings composable (see `App`) so switching to the library and back keeps the
 * selected pane and the loaded device list — settings that reset themselves every time the user
 * looks at something else are settings nobody trusts.
 */
class SettingsStateHolder(
    private val repository: TtsRoadRepository,
    private val sessionStore: SessionStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var verifyJob: Job? = null

    fun openSection(section: SettingsSection) {
        _state.update { it.copy(section = section) }
    }

    /**
     * Drops everything that belonged to the session that just ended.
     *
     * Called when the app loses its session: device rows name other machines on *that* account, so
     * showing them to whoever signs in next would be both wrong and a small privacy leak.
     */
    fun sessionEnded() {
        loadJob?.cancel()
        verifyJob?.cancel()
        _state.value = SettingsUiState()
    }

    // --- Account ---------------------------------------------------------------------------

    /** Confirms the account with the server. Silent on failure: the stored session still stands. */
    fun verifyAccount() {
        if (verifyJob?.isActive == true || _state.value.verifiedUser != null) return
        verifyJob = scope.launch {
            runCatching { repository.currentUser() }
                .onSuccess { user -> _state.update { it.copy(verifiedUser = user) } }
        }
    }

    fun askSignOut() {
        _state.update { it.copy(confirmation = SettingsConfirmation.SignOut) }
    }

    // --- Device sessions -------------------------------------------------------------------

    /** Loads on first entry to the pane; a second visit reuses what is already there. */
    fun ensureDevicesLoaded() {
        val devices = _state.value.devices
        if (devices.isLoading || devices.loaded != null || devices.unsupported) return
        refreshDevices()
    }

    fun refreshDevices() {
        loadJob?.cancel()
        // The notice belongs to the last action, not to this list; re-reading on demand is exactly
        // when "Signed out Pixel 9" stops being news.
        updateDevices { it.copy(notice = null) }
        loadJob = scope.launch { loadDevices() }
    }

    /**
     * Offers to revoke [device].
     *
     * Refuses for the current session even if a caller asks: the row for this window has no revoke
     * control, and the deliberate way to end *this* session is Sign out. Enforcing it here as well
     * means a future UI change cannot reintroduce the accident.
     */
    fun askRevoke(device: DeviceSession) {
        if (device.isCurrentFor(sessionStore.current())) return
        _state.update { it.copy(confirmation = SettingsConfirmation.RevokeDevice(device)) }
    }

    fun askRevokeOtherDevices() {
        _state.update { it.copy(confirmation = SettingsConfirmation.RevokeOtherDevices) }
    }

    fun dismissConfirmation() {
        _state.update { it.copy(confirmation = null) }
    }

    /** Carries out whatever is pending. A no-op when nothing is, so a stray Enter cannot fire it. */
    fun confirm() {
        val pending = _state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null) }
        when (pending) {
            is SettingsConfirmation.RevokeDevice -> runRevoke(
                success = "Signed out ${pending.device.resolvedName}",
                failure = "Could not sign that device out",
            ) { repository.revokeDevice(pending.device.id) }

            SettingsConfirmation.RevokeOtherDevices -> runRevoke(
                success = "Signed out every other device",
                failure = "Could not sign the other devices out",
            ) { repository.revokeOtherDevices() }

            SettingsConfirmation.SignOut -> signOut()
        }
    }

    /** The devices to render, current session first. Empty when nothing has loaded. */
    fun visibleDevices(): List<DeviceSession> =
        _state.value.devices.loaded.orEmpty().currentSessionFirst(sessionStore.current())

    fun currentDevice(): DeviceSession? {
        val session = sessionStore.current()
        return _state.value.devices.loaded?.firstOrNull { it.isCurrentFor(session) }
    }

    fun otherDevices(): List<DeviceSession> {
        val session = sessionStore.current()
        return visibleDevices().filterNot { it.isCurrentFor(session) }
    }

    private suspend fun loadDevices() {
        // Gate one: what the server said about itself. Only a *discovered* "no" skips the call —
        // an undiscovered baseline means we never got an answer, and refusing to ask on that basis
        // would hide a working feature. Gate two is the 404 below.
        val capabilities = repository.currentCapabilities.value
        if (capabilities.isDiscovered && !capabilities.deviceManagement) {
            updateDevices { it.copy(isLoading = false, unsupported = true, loaded = null, error = null) }
            return
        }
        updateDevices { it.copy(isLoading = true, error = null) }
        runCatching { repository.devices() }
            .onSuccess { loaded ->
                updateDevices {
                    it.copy(
                        isLoading = false,
                        unsupported = loaded == null,
                        loaded = loaded,
                        error = null,
                    )
                }
            }
            .onFailure { failure ->
                // `loaded` is deliberately untouched: a refresh that fails must not blank a list
                // the user is already reading. The message goes inline above it instead.
                updateDevices {
                    it.copy(
                        isLoading = false,
                        error = userFacingMessage(failure, "Could not load device sessions"),
                    )
                }
            }
    }

    private fun runRevoke(success: String, failure: String, action: suspend () -> Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            updateDevices { it.copy(isBusy = true, error = null, notice = null) }
            val outcome = runCatching { action() }
            outcome.onSuccess { endpointExists ->
                updateDevices {
                    when {
                        endpointExists -> it.copy(notice = success)
                        // A 404 from a server that just answered the listing is the server saying
                        // that session is already gone, not that it lacks the feature.
                        it.loaded != null -> it.copy(notice = "That session was already signed out")
                        else -> it.copy(unsupported = true)
                    }
                }
            }
            updateDevices { it.copy(isBusy = false) }
            // Re-read rather than patching locally: the server decides what survived, and after
            // "revoke others" the client cannot know how many rows that was.
            loadDevices()
            // Re-applied *after* the reload: a successful reload legitimately clears a stale load
            // error, and without this it would also clear the report of the action that just
            // failed — leaving a screen that looks like the revoke worked.
            outcome.exceptionOrNull()?.let { error ->
                updateDevices { it.copy(error = userFacingMessage(error, failure)) }
            }
        }
    }

    private fun signOut() {
        scope.launch {
            _state.update { it.copy(signingOut = true) }
            runCatching { repository.logout() }
            // No state reset here — losing the session drives `sessionEnded()` from App, which is
            // the same path a server-side revocation takes.
            _state.update { it.copy(signingOut = false) }
        }
    }

    private fun updateDevices(block: (DeviceSessionsUiState) -> DeviceSessionsUiState) {
        _state.update { it.copy(devices = block(it.devices)) }
    }
}
