package dk.perspektiva.ttsroad.desktop.update

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.FileSessionStore
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the machine remembers between launches about update checking.
 *
 * Machine-local like the listening preferences and for the same reason: which build is installed
 * here is a property of this desktop, not of whoever is signed in. Nothing here is secret, and
 * there is nowhere in the type to put something that is.
 */
data class UpdateSettings(
    /** Automatic checks can be turned off; a manual check always ignores this. */
    val automatic: Boolean = true,
    val lastCheckMillis: Long = 0L,
    /** The version the user chose to stop being told about. Cleared when a newer one appears. */
    val dismissedVersion: String? = null,
)

/** Fully nullable on-disk form, so a file from another build loads degraded instead of throwing. */
internal data class StoredUpdateSettings(
    val automatic: Boolean? = null,
    val lastCheckMillis: Long? = null,
    val dismissedVersion: String? = null,
) {
    fun toSettings(): UpdateSettings = UpdateSettings(
        automatic = automatic ?: true,
        // A timestamp from the future would suppress checks indefinitely; treat it as never checked.
        lastCheckMillis = lastCheckMillis?.takeIf { it >= 0L } ?: 0L,
        dismissedVersion = dismissedVersion?.takeIf { it.isNotBlank() },
    )

    companion object {
        fun from(settings: UpdateSettings): StoredUpdateSettings = StoredUpdateSettings(
            automatic = settings.automatic,
            lastCheckMillis = settings.lastCheckMillis,
            dismissedVersion = settings.dismissedVersion,
        )
    }
}

interface UpdateSettingsStore {
    val settings: StateFlow<UpdateSettings>

    fun update(transform: (UpdateSettings) -> UpdateSettings)
}

/** In-memory store for tests and for the smoke test, which must not write to a real home. */
class InMemoryUpdateSettingsStore(
    initial: UpdateSettings = UpdateSettings(),
) : UpdateSettingsStore {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<UpdateSettings> = _settings.asStateFlow()

    @Synchronized
    override fun update(transform: (UpdateSettings) -> UpdateSettings) {
        _settings.value = transform(_settings.value)
    }
}

/** `update.json` beside the session and playback settings, written owner-only. */
class FileUpdateSettingsStore(
    private val file: File = defaultFile(),
) : UpdateSettingsStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StoredUpdateSettings::class.java)

    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<UpdateSettings> = _settings.asStateFlow()

    @Synchronized
    override fun update(transform: (UpdateSettings) -> UpdateSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(StoredUpdateSettings.from(next))) }
            .onFailure { AppLog.warn("could not write the update settings file", it) }
    }

    private fun read(): UpdateSettings =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the update settings file", it) }
            .getOrNull()
            ?.toSettings()
            ?: UpdateSettings()

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("update.json")
    }
}

/** At most one automatic check per day, on top of at most one per launch. */
const val UpdateCheckIntervalMillis: Long = 24L * 60 * 60 * 1000

/**
 * Whether an automatic check may run now.
 *
 * A clock that has moved backwards (a corrected system time, a restored VM) would otherwise park
 * the next check up to a day in the future, so a [lastCheckMillis] later than [nowMillis] is
 * treated as due rather than as recent.
 */
fun shouldCheckAutomatically(
    settings: UpdateSettings,
    nowMillis: Long,
    alreadyCheckedThisLaunch: Boolean,
): Boolean {
    if (!settings.automatic) return false
    if (alreadyCheckedThisLaunch) return false
    if (settings.lastCheckMillis > nowMillis) return true
    return nowMillis - settings.lastCheckMillis >= UpdateCheckIntervalMillis
}
