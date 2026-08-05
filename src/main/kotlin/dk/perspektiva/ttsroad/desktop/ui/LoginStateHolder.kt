package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
)

class LoginStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun submit(serverUrl: String, username: String, password: String, totpCode: String) {
        if (_state.value.busy) return
        scope.launch {
            val twoFactor = _state.value.twoFactor
            _state.update { it.copy(busy = true, error = null) }
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
                )
                is LoginResult.Failure -> LoginUiState(
                    busy = false,
                    twoFactor = twoFactor,
                    error = result.message,
                )
            }
        }
    }
}
