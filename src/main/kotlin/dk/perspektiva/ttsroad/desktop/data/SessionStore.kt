package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.CredentialStore
import dk.perspektiva.ttsroad.desktop.security.CredentialStores
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in session.
 *
 * [token] is the only secret here and it lives **in memory only** — see [PersistedSettings] for
 * what actually reaches disk. Everything else is a hint: which server this install talks to, who
 * was signed in, what the server calls itself. Those survive sign-out on purpose so the login
 * screen can prefill and the settings screen stays meaningful.
 */
data class SessionState(
    val serverUrl: String = "",
    val token: String? = null,
    val username: String? = null,
    val isAdmin: Boolean = false,
    val serverName: String = "TTSRoad",
    /** From the login response's `server.version`; null against a server too old to send it. */
    val serverVersion: String? = null,
    /** `MobileApiToken.id` for this device — the id the device-management API uses. */
    val deviceId: Int? = null,
    /** Server-side token expiry, ISO-8601. Informational: expiry is discovered via 401, not polled. */
    val expiresAt: String? = null,
) {
    val isLoggedIn: Boolean get() = serverUrl.isNotBlank() && !token.isNullOrBlank()
    val authorizationHeader: String? get() = token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /** What the auth interceptor needs: the credential plus the origin it may be sent to. */
    val bearerCredentials: BearerCredentials?
        get() = authorizationHeader?.takeIf { serverUrl.isNotBlank() }?.let { BearerCredentials(serverUrl, it) }
}

/**
 * Seam for session/credential storage. The app runs on [FileSessionStore]; tests substitute
 * [InMemorySessionStore] so nothing touches the real user config directory or the real keyring.
 *
 * Deliberately non-suspending: [current] is called from the auth interceptor, which runs on
 * OkHttp's dispatcher and cannot suspend. The credential store is therefore read once at
 * construction and written on sign-in/sign-out, never on the request path.
 */
interface SessionStore {
    val session: StateFlow<SessionState>
    fun current(): SessionState
    fun save(state: SessionState)

    /**
     * Signs the user out locally and destroys the credential.
     *
     * Non-secret hints (`serverUrl`, `serverName`, `serverVersion`, `username`) are deliberately
     * retained: the login screen prefills from them, and the settings screen still shows which
     * server this install talks to. `isAdmin`, `deviceId` and `expiresAt` are claims about a
     * session that no longer exists, so they go.
     */
    fun clearToken()

    /** False when the credential lives only in this process, so the UI can warn about a restart. */
    val persistsCredentials: Boolean get() = true

    /** Where the credential is kept, e.g. "Windows Credential Manager". */
    val credentialStoreName: String get() = "In memory"
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
        _session.value = _session.value.copy(
            token = null,
            isAdmin = false,
            deviceId = null,
            expiresAt = null,
        )
    }
}

/**
 * Exactly what is written to the settings file — note the absence of a token field.
 *
 * The token is not "omitted when null", it has no representation here at all, so no future edit to
 * [SessionState] can leak it onto disk by accident. [credentialKey] is the identifier of the
 * keyring entry holding the secret; it opens nothing on its own.
 */
private data class PersistedSettings(
    val serverUrl: String = "",
    val serverName: String = "TTSRoad",
    val serverVersion: String? = null,
    val username: String? = null,
    val credentialKey: String? = null,
)

/**
 * File-backed session settings plus an OS keyring for the bearer token.
 *
 * Layout: `%APPDATA%/TTSRoad` on Windows, `~/Library/Application Support/TTSRoad` on macOS,
 * `$XDG_CONFIG_HOME/TTSRoad` (or `~/.config/TTSRoad`) elsewhere. The file holds server/user hints
 * and a credential identifier; the secret itself is in [credentials].
 *
 * A settings file written by an older build contains a plaintext `token`. Construction migrates it
 * once: keyring first, then an atomic owner-only rewrite without it, then a re-read to confirm the
 * plaintext is really gone. If any step fails the plaintext is destroyed anyway and the user signs
 * in again — a working session is not worth leaving a readable token behind.
 */
class FileSessionStore(
    private val file: File = defaultFile(),
    private val credentials: CredentialStore = CredentialStores.forCurrentPlatform(),
) : SessionStore {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val settingsAdapter = moshi.adapter(PersistedSettings::class.java)
    private val rawAdapter = moshi.adapter(Any::class.java)

    /**
     * The keyring entry the settings file currently points at.
     *
     * Tracked so signing in as a different user, or against a different server, deletes the entry
     * it replaces. Without it every account this machine ever used would leave a live token in the
     * keyring that nothing refers to and nothing ever cleans up.
     */
    private var persistedKey: String? = null

    private val _session = MutableStateFlow(loadAndMigrate())
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override val persistsCredentials: Boolean get() = credentials.persistsAcrossRestarts
    override val credentialStoreName: String get() = credentials.displayName

    override fun current(): SessionState = _session.value

    override fun save(state: SessionState) {
        val key = CredentialStores.credentialKey(state.serverUrl, state.username)
        val token = state.token?.takeIf { it.isNotBlank() }
        val storedKey = if (token != null) {
            runCatching { credentials.store(key, token) }
                .onFailure { AppLog.warn("could not store the session credential", it) }
                .map { key }
                .getOrNull()
        } else {
            runCatching { credentials.delete(key) }
            null
        }
        persistedKey?.takeIf { it != storedKey }?.let { stale ->
            runCatching { credentials.delete(stale) }
        }
        persistedKey = storedKey
        _session.value = state
        write(
            PersistedSettings(
                serverUrl = state.serverUrl,
                serverName = state.serverName,
                serverVersion = state.serverVersion,
                username = state.username,
                credentialKey = storedKey,
            ),
        )
    }

    override fun clearToken() {
        save(current().copy(token = null, isAdmin = false, deviceId = null, expiresAt = null))
    }

    private fun write(settings: PersistedSettings) {
        runCatching { SecureFiles.writeAtomically(file, settingsAdapter.toJson(settings)) }
            .onFailure { AppLog.warn("could not write the session settings file", it) }
            .onSuccess { restricted ->
                if (!restricted) AppLog.warn("the session settings file could not be made owner-only")
            }
    }

    private fun loadAndMigrate(): SessionState {
        val raw = runCatching { if (file.isFile) rawAdapter.fromJson(file.readText()) else null }
            .getOrNull() as? Map<*, *> ?: return SessionState()

        val settings = PersistedSettings(
            serverUrl = raw["serverUrl"] as? String ?: "",
            serverName = raw["serverName"] as? String ?: "TTSRoad",
            serverVersion = raw["serverVersion"] as? String,
            username = raw["username"] as? String,
            credentialKey = raw["credentialKey"] as? String,
        )
        val hints = SessionState(
            serverUrl = settings.serverUrl,
            serverName = settings.serverName,
            serverVersion = settings.serverVersion,
            username = settings.username,
        )

        val legacyToken = (raw["token"] as? String)?.takeIf { it.isNotBlank() }
        if (legacyToken != null) return migrateLegacyToken(settings, hints, legacyToken)

        val key = settings.credentialKey ?: return hints
        persistedKey = key
        val token = runCatching { credentials.retrieve(key) }
            .onFailure { AppLog.warn("could not read the stored session credential", it) }
            .getOrNull()
        if (token.isNullOrBlank()) {
            // A keyring-less install, a keyring the user declined to unlock, or an entry removed
            // out of band. Either way there is no credential, so this is a signed-out start.
            return hints
        }
        return hints.copy(token = token)
    }

    /**
     * One-time move of a plaintext token from the settings file into the keyring.
     *
     * The verification step is the point: a successful `store` plus a successful rewrite still
     * has to be checked, because a write that silently landed somewhere else (a read-only mount
     * reported as success by a filesystem, a stale handle) would leave the token on disk while
     * everything above reported OK.
     */
    private fun migrateLegacyToken(
        settings: PersistedSettings,
        hints: SessionState,
        legacyToken: String,
    ): SessionState {
        val key = settings.credentialKey ?: CredentialStores.credentialKey(settings.serverUrl, settings.username)
        val migrated = runCatching {
            credentials.store(key, legacyToken)
            SecureFiles.writeAtomically(file, settingsAdapter.toJson(settings.copy(credentialKey = key)))
            check(!file.readText().contains(legacyToken)) { "the plaintext token survived the rewrite" }
        }
        if (migrated.isSuccess) {
            AppLog.warn("migrated a plaintext session token into ${credentials.displayName}")
            persistedKey = key
            return hints.copy(token = legacyToken)
        }

        AppLog.warn("plaintext token migration failed; removing it and requiring a new sign-in", migrated.exceptionOrNull())
        runCatching { credentials.delete(key) }
        // Exposure is removed even when we cannot keep the session: rewrite without the token,
        // and if even that fails, delete the file outright.
        val scrubbed = runCatching {
            SecureFiles.writeAtomically(file, settingsAdapter.toJson(settings.copy(credentialKey = null)))
            check(!file.readText().contains(legacyToken)) { "the plaintext token survived the scrub" }
        }
        if (scrubbed.isFailure) {
            runCatching { file.delete() }
            AppLog.warn("could not rewrite the settings file, so it was deleted")
        }
        return hints
    }

    companion object {
        fun defaultFile(): File = configDir().resolve("session.json")

        /**
         * Per-user config directory, per platform convention.
         *
         * [osName], [userHome] and [env] are parameters so the path rules — in particular the XDG
         * Base Directory spec on Linux, where honouring `$XDG_CONFIG_HOME` is the difference
         * between respecting a user's layout and ignoring it — can be tested without a Linux box.
         */
        fun configDir(
            osName: String = System.getProperty("os.name").orEmpty(),
            userHome: String = System.getProperty("user.home").orEmpty(),
            env: (String) -> String? = System::getenv,
        ): File {
            val os = osName.lowercase()
            val base = when {
                os.contains("win") ->
                    env("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
                        ?: File(userHome, "AppData/Roaming")

                os.contains("mac") || os.contains("darwin") -> File(userHome, "Library/Application Support")

                // XDG Base Directory spec: config goes in $XDG_CONFIG_HOME, and the spec says a
                // relative value must be ignored rather than resolved against the cwd.
                else -> env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() && it.startsWith("/") }?.let { File(it) }
                    ?: File(userHome, ".config")
            }
            return File(base, "TTSRoad")
        }
    }
}
