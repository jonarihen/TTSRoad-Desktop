package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
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

enum class ReaderTheme(val wireName: String, val label: String) {
    Dark("dark", "Dark"),
    Sepia("sepia", "Sepia"),
    Light("light", "Light"),
}

enum class ReaderHighlight(val wireName: String, val label: String) {
    Sentence("sentence", "Sentence + word"),
    Word("word", "Word"),
    Off("off", "Off"),
}

data class ReaderPreferences(
    val fontSize: Double = DefaultFontSize,
    val lineHeight: Double = DefaultLineHeight,
    val theme: ReaderTheme = ReaderTheme.Dark,
    val highlight: ReaderHighlight = ReaderHighlight.Sentence,
) {
    companion object {
        const val MinFontSize: Double = 14.0
        const val MaxFontSize: Double = 30.0
        const val DefaultFontSize: Double = 19.0
        const val MinLineHeight: Double = 1.3
        const val MaxLineHeight: Double = 2.4
        const val DefaultLineHeight: Double = 1.75
    }
}

fun ReaderPreferences.sanitised(): ReaderPreferences = copy(
    fontSize = fontSize.takeIf(Double::isFinite)
        ?.coerceIn(ReaderPreferences.MinFontSize, ReaderPreferences.MaxFontSize)
        ?: ReaderPreferences.DefaultFontSize,
    lineHeight = lineHeight.takeIf(Double::isFinite)
        ?.coerceIn(ReaderPreferences.MinLineHeight, ReaderPreferences.MaxLineHeight)
        ?: ReaderPreferences.DefaultLineHeight,
)

fun ReaderPreferencesWire.mergeInto(fallback: ReaderPreferences): ReaderPreferences = ReaderPreferences(
    fontSize = fontSize ?: fallback.fontSize,
    lineHeight = lineHeight ?: fallback.lineHeight,
    theme = ReaderTheme.entries.firstOrNull { it.wireName == theme } ?: fallback.theme,
    highlight = ReaderHighlight.entries.firstOrNull { it.wireName == highlight } ?: fallback.highlight,
).sanitised()

fun ReaderPreferences.toPatch(): ReaderPreferencesPatch = sanitised().let { value ->
    ReaderPreferencesPatch(value.fontSize, value.lineHeight, value.theme.wireName, value.highlight.wireName)
}

interface ReaderPreferencesStore : AutoCloseable {
    val preferences: StateFlow<ReaderPreferences>
    fun update(transform: (ReaderPreferences) -> ReaderPreferences)
    suspend fun refreshFromServer()
    override fun close() = Unit
}

class InMemoryReaderPreferencesStore(
    initial: ReaderPreferences = ReaderPreferences(),
) : ReaderPreferencesStore {
    private val state = MutableStateFlow(initial.sanitised())
    override val preferences: StateFlow<ReaderPreferences> = state.asStateFlow()

    override fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        state.value = transform(state.value).sanitised()
    }

    override suspend fun refreshFromServer() = Unit
}

/** Local-first reader settings, opportunistically synchronized with the signed-in account. */
class FileReaderPreferencesStore(
    private val repository: TtsRoadRepository,
    private val file: File = defaultFile(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReaderPreferencesStore {
    private data class Stored(
        val version: Int? = null,
        val fontSize: Double? = null,
        val lineHeight: Double? = null,
        val theme: String? = null,
        val highlight: String? = null,
    )

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Stored::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = MutableStateFlow(read())
    override val preferences: StateFlow<ReaderPreferences> = state.asStateFlow()
    private var syncJob: Job? = null

    @Synchronized
    override fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(state.value).sanitised()
        if (next == state.value) return
        state.value = next
        write(next)
        syncJob?.cancel()
        syncJob = scope.launch {
            // A small debounce collapses repeated +/- presses into one account PATCH.
            delay(300)
            runCatching { repository.updateReaderPreferences(state.value.toPatch()) }
                .onSuccess { response ->
                    response?.preferences?.mergeInto(state.value)?.let { accepted ->
                        state.value = accepted
                        write(accepted)
                    }
                }
                .onFailure { AppLog.warn("could not sync reader preferences", it) }
        }
    }

    override suspend fun refreshFromServer() {
        runCatching { repository.readerPreferences() }
            .onSuccess { response ->
                response?.preferences?.mergeInto(state.value)?.let { loaded ->
                    state.value = loaded
                    write(loaded)
                }
            }
            .onFailure { AppLog.warn("could not refresh reader preferences", it) }
    }

    private fun read(): ReaderPreferences = runCatching {
        if (!file.isFile) return ReaderPreferences()
        val stored = adapter.fromJson(file.readText()) ?: return ReaderPreferences()
        ReaderPreferencesWire(stored.fontSize, stored.lineHeight, stored.theme, stored.highlight)
            .mergeInto(ReaderPreferences())
    }.onFailure { AppLog.warn("could not read reader preferences", it) }.getOrDefault(ReaderPreferences())

    private fun write(value: ReaderPreferences) {
        val stored = Stored(1, value.fontSize, value.lineHeight, value.theme.wireName, value.highlight.wireName)
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(stored)) }
            .onFailure { AppLog.warn("could not write reader preferences", it) }
    }

    override fun close() {
        syncJob?.cancel()
        scope.cancel()
    }

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("reader.json")
    }
}
