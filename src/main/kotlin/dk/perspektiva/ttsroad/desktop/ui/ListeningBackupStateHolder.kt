package dk.perspektiva.ttsroad.desktop.ui

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.listeningBackupFileName
import dk.perspektiva.ttsroad.desktop.data.listeningImportLines
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListeningBackupUiState(
    val busy: Boolean = false,
    /** Where the last export went, so "it worked" names a path rather than being a word. */
    val savedTo: String? = null,
    /** What the last import did, already formatted. */
    val importLines: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Export and re-import where this account is in everything (#119).
 *
 * The document is passed through untouched — read from the server, written to the file, read back,
 * posted. Nothing here inspects its interior, so a server that adds a field needs no change here.
 */
class ListeningBackupStateHolder(
    private val repository: TtsRoadRepository,
    private val savePicker: ListeningBackupSavePicker = DesktopListeningBackupSavePicker,
    private val openPicker: ListeningBackupOpenPicker = DesktopListeningBackupOpenPicker,
    private val today: () -> LocalDate = LocalDate::now,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(ListeningBackupUiState())
    val state: StateFlow<ListeningBackupUiState> = _state.asStateFlow()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val documentAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    private var job: Job? = null

    /**
     * Fetch the document, then ask where to put it.
     *
     * In that order deliberately: a save dialog raised before the request would leave a file behind
     * on a server that answers 404, and an empty file called `ttsroad-listening-….json` is worse
     * than no file.
     */
    fun export() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, savedTo = null, importLines = emptyList(), error = null) }
        job = scope.launch {
            val outcome = runCatching { repository.exportListeningState() }
            val document = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()
            when {
                failure != null -> fail(userFacingMessage(failure, "Could not read your listening state"))
                document == null -> fail("This server cannot export listening state.")
                else -> {
                    val target = savePicker.choose(listeningBackupFileName(today()))
                    if (target == null) {
                        // Cancelling is not a failure and should leave no trace.
                        _state.update { it.copy(busy = false) }
                        return@launch
                    }
                    runCatching { target.writeText(documentAdapter.toJson(document)) }
                        .onSuccess {
                            _state.update { s -> s.copy(busy = false, savedTo = target.absolutePath) }
                        }
                        .onFailure { fail(userFacingMessage(it, "Could not write that file")) }
                }
            }
        }
    }

    fun import() {
        if (_state.value.busy) return
        val source = openPicker.choose() ?: return
        _state.update { it.copy(busy = true, savedTo = null, importLines = emptyList(), error = null) }
        job = scope.launch {
            val document = readDocument(source)
            if (document == null) {
                fail("That file is not a listening backup.")
                return@launch
            }
            runCatching { repository.importListeningState(document) }
                .onSuccess { report ->
                    when (report) {
                        null -> fail("This server cannot import listening state.")
                        else -> _state.update {
                            it.copy(busy = false, importLines = listeningImportLines(report))
                        }
                    }
                }
                .onFailure { fail(userFacingMessage(it, "That backup was refused")) }
        }
    }

    fun dismiss() = _state.update { it.copy(savedTo = null, importLines = emptyList(), error = null) }

    /**
     * Read the file, unwrapping `{"document": …}` if that is what was saved.
     *
     * Both shapes are accepted because both exist in the wild: the server's export is wrapped, and
     * anything hand-edited or produced by the web route may not be. Returns null for anything that
     * is not an object at all, which is the check the filename filter cannot do.
     */
    private fun readDocument(source: File): Map<String, Any?>? = runCatching {
        val parsed = documentAdapter.fromJson(source.readText()) ?: return null
        @Suppress("UNCHECKED_CAST")
        (parsed["document"] as? Map<String, Any?>) ?: parsed
    }.getOrNull()

    private fun fail(message: String) {
        _state.update { it.copy(busy = false, error = message) }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
