package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AccountPreferenceSyncState {
    data object Idle : AccountPreferenceSyncState
    data object Syncing : AccountPreferenceSyncState
    data object Failed : AccountPreferenceSyncState
}

interface PlaybackSkipPreferenceStore : AutoCloseable {
    val enabled: StateFlow<Boolean>
    val syncState: StateFlow<AccountPreferenceSyncState>
    fun setEnabled(enabled: Boolean)
    suspend fun refreshFromServer()
    override fun close() = Unit
}

class InMemoryPlaybackSkipPreferenceStore(initial: Boolean = true) : PlaybackSkipPreferenceStore {
    private val state = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    private val sync = MutableStateFlow<AccountPreferenceSyncState>(AccountPreferenceSyncState.Idle)
    override val syncState: StateFlow<AccountPreferenceSyncState> = sync.asStateFlow()
    override fun setEnabled(enabled: Boolean) { state.value = enabled }
    override suspend fun refreshFromServer() = Unit
}

class FilePlaybackSkipPreferenceStore(
    private val repository: TtsRoadRepository,
    private val sessionStore: SessionStore,
    private val root: File = FileSessionStore.configDir().resolve("account-playback"),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackSkipPreferenceStore {
    private data class Stored(val version: Int? = null, val skipAdSegments: Boolean? = null)

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Stored::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var identityKey = currentIdentityKey()
    private val state = MutableStateFlow(readCurrent())
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    private val sync = MutableStateFlow<AccountPreferenceSyncState>(AccountPreferenceSyncState.Idle)
    override val syncState: StateFlow<AccountPreferenceSyncState> = sync.asStateFlow()
    private val generation = AtomicLong()
    private var syncJob: Job? = null

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        switchIdentityIfNeeded()
        if (state.value == enabled) return
        state.value = enabled
        writeCurrent(enabled)
        val mine = generation.incrementAndGet()
        syncJob?.cancel()
        sync.value = AccountPreferenceSyncState.Syncing
        syncJob = scope.launch {
            delay(300)
            runCatching { repository.updatePlaybackSkipPreference(enabled) }
                .onSuccess { response ->
                    if (generation.get() == mine && currentIdentityKey() == identityKey) {
                        response?.preferences?.skipAdSegments?.let(::acceptCurrent)
                        sync.value = AccountPreferenceSyncState.Idle
                    }
                }
                .onFailure {
                    if (generation.get() == mine) sync.value = AccountPreferenceSyncState.Failed
                    AppLog.warn("could not sync advert skipping preference", it)
                }
        }
    }

    override suspend fun refreshFromServer() {
        switchIdentityIfNeeded()
        val mine = generation.incrementAndGet()
        syncJob?.cancel()
        sync.value = AccountPreferenceSyncState.Syncing
        runCatching { repository.readerPreferences() }
            .onSuccess { response ->
                if (generation.get() == mine && currentIdentityKey() == identityKey) {
                    response?.preferences?.skipAdSegments?.let(::acceptCurrent)
                    sync.value = AccountPreferenceSyncState.Idle
                }
            }
            .onFailure {
                if (generation.get() == mine) sync.value = AccountPreferenceSyncState.Failed
                AppLog.warn("could not refresh advert skipping preference", it)
            }
    }

    @Synchronized
    private fun switchIdentityIfNeeded() {
        val current = currentIdentityKey()
        if (current == identityKey) return
        generation.incrementAndGet()
        syncJob?.cancel()
        identityKey = current
        state.value = readCurrent()
        sync.value = AccountPreferenceSyncState.Idle
    }

    private fun acceptCurrent(enabled: Boolean) {
        state.value = enabled
        writeCurrent(enabled)
    }

    private fun currentIdentityKey(): String? {
        val session = sessionStore.current()
        if (!session.isLoggedIn) return null
        return StorageIdentity.of(session.serverUrl, session.advertisedBaseUrl, session.username).relativePath
    }

    private fun currentFile(): File? = identityKey?.let { root.resolve(it).resolve("preferences.json") }

    private fun readCurrent(): Boolean = currentFile()?.let { file ->
        runCatching {
            if (!file.isFile) return@runCatching true
            adapter.fromJson(file.readText())?.skipAdSegments ?: true
        }.onFailure { AppLog.warn("could not read advert skipping preference", it) }.getOrDefault(true)
    } ?: true

    private fun writeCurrent(enabled: Boolean) {
        val file = currentFile() ?: return
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(Stored(1, enabled))) }
            .onFailure { AppLog.warn("could not write advert skipping preference", it) }
    }

    override fun close() = scope.cancel()
}
