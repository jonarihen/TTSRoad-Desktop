package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Non-credential part of the login form's state.
 *
 * The three text fields deliberately stay in Compose state in `LoginScreen`: routing every
 * keystroke through a StateFlow buys nothing and risks changing typing behaviour, and the
 * credentials should live as briefly as possible. What lives here is the part with logic worth
 * testing — the mapping from [LoginResult] to what the user sees, in particular the fact that the
 * server cannot distinguish "2FA code missing" from "2FA code wrong" (both are a 401 carrying
 * `totp_required`), so "wrong code" has to be inferred from local state.
 */
data class LoginUiState(
    val busy: Boolean = false,
    val twoFactor: Boolean = false,
    val error: String? = null,
    /**
     * What the typed URL turned out to be, before any credential was sent. Null while the URL is
     * unrecognised, unreachable, or too old to answer discovery.
     */
    val discovered: ServerCapabilities? = null,
    /**
     * Set by a 429 so the button can stay disabled instead of burning further attempts, and
     * cleared again once the server's wait has elapsed — see [LoginStateHolder.submit]. It must
     * clear itself: the submit button is the only thing that could reset it, and it is the very
     * control this field disables, so without the timer a throttled user is locked out of the
     * login screen until they restart the app.
     */
    val retryAfterSeconds: Int? = null,
)

class LoginStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * How long typing has to pause before the server is probed. Long enough that a URL is not
     * discovered character by character, short enough to land before the password is typed.
     */
    private val probeDebounceMillis: Long = 600,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var lockoutJob: Job? = null

    /**
     * Called on every keystroke in the server field.
     *
     * The point is to identify the server *before* credentials are sent: seeing
     * "Perspektiva TTSRoad 1.4.0" under the URL is how a user knows they are not about to type a
     * password into a typo'd hostname. Discovery is unauthenticated and never throws, so a
     * half-typed address costs nothing and shows nothing.
     */
    fun serverUrlChanged(serverUrl: String) {
        probeJob?.cancel()
        _state.update { it.copy(discovered = null) }
        if (serverUrl.isBlank()) return
        probeJob = scope.launch {
            delay(probeDebounceMillis)
            val found = repository.capabilities(serverUrl).takeIf { it.isDiscovered }
            _state.update { it.copy(discovered = found) }
        }
    }

    /**
     * Attempts a sign-in.
     *
     * Refuses while a 429 lockout is still running, so a caller that does not read
     * [LoginUiState.retryAfterSeconds] cannot extend the user's own lockout by retrying.
     */
    fun submit(serverUrl: String, username: String, password: String, totpCode: String) {
        if (_state.value.busy || _state.value.retryAfterSeconds != null) return
        lockoutJob?.cancel()
        scope.launch {
            val twoFactor = _state.value.twoFactor
            _state.update { it.copy(busy = true, error = null, retryAfterSeconds = null) }
            val result = repository.login(
                baseUrl = serverUrl,
                username = username,
                password = password,
                totpCode = if (twoFactor) totpCode else null,
            )
            _state.value = when (result) {
                // Success needs no state change: the session StateFlow flips the UI to the library.
                LoginResult.Success -> LoginUiState(busy = false, twoFactor = twoFactor, error = null)
                LoginResult.TotpRequired -> LoginUiState(
                    busy = false,
                    twoFactor = true,
                    error = if (twoFactor && totpCode.isNotBlank()) "Invalid authentication code" else null,
                    discovered = _state.value.discovered,
                )

                is LoginResult.RateLimited -> LoginUiState(
                    busy = false,
                    twoFactor = twoFactor,
                    error = result.message,
                    discovered = _state.value.discovered,
                    retryAfterSeconds = result.retryAfterSeconds,
                )

                is LoginResult.Failure -> LoginUiState(
                    busy = false,
                    twoFactor = twoFactor,
                    error = result.message,
                    discovered = _state.value.discovered,
                )
            }
            _state.value.retryAfterSeconds?.let(::startLockoutCountdown)
        }
    }

    /**
     * Re-enables the form once the server's wait has passed.
     *
     * One sleep rather than a per-second tick: the value is only read as "is the form locked", and
     * a countdown that recomposed the login screen every second would buy nothing.
     */
    private fun startLockoutCountdown(seconds: Int) {
        lockoutJob?.cancel()
        lockoutJob = scope.launch {
            delay(seconds.coerceAtLeast(1).toLong() * 1_000L)
            _state.update { it.copy(retryAfterSeconds = null) }
        }
    }
}
