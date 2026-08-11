package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionState(
    val serverUrl: String = "",
    val token: String? = null,
    val username: String? = null,
    val isAdmin: Boolean = false,
    val serverName: String = "TTSRoad",
) {
    val isLoggedIn: Boolean get() = serverUrl.isNotBlank() && !token.isNullOrBlank()
    val authorizationHeader: String? get() = token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}

/**
 * File-backed session persistence (no Android DataStore on desktop). Stored as JSON in a
 * per-user config directory: %APPDATA%/TTSRoad on Windows, ~/.config/ttsroad elsewhere.
 */
class SessionStore {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(SessionState::class.java)
    private val file: File = configDir().resolve("session.json")

    private val _session = MutableStateFlow(load())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    fun current(): SessionState = _session.value

    fun save(state: SessionState) {
        _session.value = state
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(adapter.toJson(state))
        }
    }

    fun clearToken() {
        save(current().copy(token = null, username = null, isAdmin = false))
    }

    private fun load(): SessionState =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .getOrNull() ?: SessionState()
}

/**
 * Per-user config directory: %APPDATA%/TTSRoad on Windows, ~/Library/Application Support/TTSRoad on
 * macOS, $XDG_CONFIG_HOME/TTSRoad (or ~/.config/TTSRoad) elsewhere. Shared by everything that
 * persists across runs, so the session and the unsent-progress outbox live side by side.
 */
fun configDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA")?.let { File(it) } ?: File(home, "AppData/Roaming")
        os.contains("mac") -> File(home, "Library/Application Support")
        else -> System.getenv("XDG_CONFIG_HOME")?.let { File(it) } ?: File(home, ".config")
    }
    return File(base, "TTSRoad")
}

fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "Server URL must start with http:// or https://"
    }
    return "$trimmed/"
}
