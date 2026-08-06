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
 * Seam for session/credential storage. The app runs on [FileSessionStore]; tests substitute
 * [InMemorySessionStore] so nothing touches the real user config directory.
 *
 * Deliberately non-suspending: [current] is called from non-suspend code on the audio download
 * path, so swapping in an OS keychain later means either caching in memory behind this same
 * interface or changing those call sites too.
 */
interface SessionStore {
    val session: StateFlow<SessionState>
    fun current(): SessionState
    fun save(state: SessionState)

    /**
     * Signs the user out locally. `serverUrl` and `serverName` are deliberately retained so the
     * settings screen can still show which server this install talks to.
     */
    fun clearToken()
}

/** In-memory [SessionStore] with no persistence — used by tests and by UI previews. */
class InMemorySessionStore(initial: SessionState = SessionState()) : SessionStore {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    /** Number of [clearToken] calls, so tests can assert "this did / did not sign the user out". */
    var clearTokenCalls: Int = 0
        private set

    override fun current(): SessionState = _session.value

    override fun save(state: SessionState) {
        _session.value = state
    }

    override fun clearToken() {
        clearTokenCalls++
        _session.value = _session.value.copy(token = null, username = null, isAdmin = false)
    }
}

/**
 * File-backed session persistence (no Android DataStore on desktop). Stored as JSON in a
 * per-user config directory: %APPDATA%/TTSRoad on Windows, ~/Library/Application Support/TTSRoad
 * on macOS, $XDG_CONFIG_HOME/TTSRoad (or ~/.config/TTSRoad) elsewhere.
 *
 * NOTE: the bearer token is stored in plaintext with default file permissions. Moving it into an
 * OS keychain is tracked separately; this class is the single seam that change has to go through.
 */
class FileSessionStore(private val file: File = defaultFile()) : SessionStore {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(SessionState::class.java)

    private val _session = MutableStateFlow(load())
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override fun current(): SessionState = _session.value

    override fun save(state: SessionState) {
        _session.value = state
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(adapter.toJson(state))
        }
    }

    override fun clearToken() {
        save(current().copy(token = null, username = null, isAdmin = false))
    }

    private fun load(): SessionState =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .getOrNull() ?: SessionState()

    companion object {
        fun defaultFile(): File = configDir().resolve("session.json")

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
    }
}
