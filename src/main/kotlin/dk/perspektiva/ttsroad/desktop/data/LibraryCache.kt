package dk.perspektiva.ttsroad.desktop.data

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library and the per-fiction chapter lists, held **above** the screens that show them.
 *
 * This is the seam that makes browsing feel like a desktop application rather than a series of
 * page loads. Previously each screen created its own state holder inside its own composable, so
 * navigating away disposed the holder and coming back re-fetched from zero, replacing the whole
 * screen with a spinner. The cache lives in [dk.perspektiva.ttsroad.desktop.di.AppContainer], so
 * its lifetime is the signed-in session and not the composition.
 *
 * Three rules the states below encode:
 *
 * 1. **Cached content is returned immediately** and refreshed underneath. [ensureLibrary] is the
 *    "I opened this screen" call and is a no-op when there is already content or a load in flight
 *    — that is the request coalescing, and it is why Library → Fiction → Back costs no requests.
 * 2. **Superseded work is cancelled.** [refreshLibrary] cancels the previous load before starting
 *    a new one, and a cancelled load never publishes: it checks its own job before touching state.
 * 3. **A failed refresh never destroys content.** The error goes into [Cached.error] beside the
 *    value; [Cached.lastSuccessMillis] is how the UI says how old what it is showing really is.
 */
class LibraryCache(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _library = MutableStateFlow(Cached<LibraryResponse>())
    val library: StateFlow<Cached<LibraryResponse>> = _library.asStateFlow()
    private var libraryJob: Job? = null

    private val chapterStates = LinkedHashMap<Int, MutableStateFlow<Cached<ChaptersResponse>>>()
    private val chapterJobs = HashMap<Int, Job>()

    // --- Library ---------------------------------------------------------------------------

    /** Opening the library screen. Reuses what is there; starts one load when there is nothing. */
    fun ensureLibrary() {
        if (_library.value.hasContent || libraryJob?.isActive == true) return
        refreshLibrary()
    }

    /** The explicit Refresh action. Always re-asks, cancelling any load already running. */
    fun refreshLibrary() {
        libraryJob?.cancel()
        _library.update { it.copy(isRefreshing = true, error = null) }
        libraryJob = scope.launch {
            load(_library, "Could not load library") { repository.library() }
        }
    }

    // --- Chapters --------------------------------------------------------------------------

    /** The chapter list for one fiction. Stable across navigation, so scroll and data survive. */
    fun chapters(fictionId: Int): StateFlow<Cached<ChaptersResponse>> = chapterState(fictionId).asStateFlow()

    fun ensureChapters(fictionId: Int) {
        if (chapterState(fictionId).value.hasContent || chapterJobs[fictionId]?.isActive == true) return
        refreshChapters(fictionId)
    }

    fun refreshChapters(fictionId: Int) {
        chapterJobs[fictionId]?.cancel()
        val state = chapterState(fictionId)
        state.update { it.copy(isRefreshing = true, error = null) }
        chapterJobs[fictionId] = scope.launch {
            load(state, "Could not load chapters") { repository.chapters(fictionId) }
        }
    }

    /**
     * Marks chapters played, then patches the cached list in place.
     *
     * Throws on failure so the caller can report it without the list ever being cleared. The local
     * patch replaces the old "mark, then refetch everything" round trip: the server's answer to
     * this request already *is* the authority on what changed, and a refetch of a 500-chapter
     * fiction to move one checkmark is what made the list flicker.
     */
    suspend fun setPlayed(fictionId: Int, chapterIds: List<Int>, played: Boolean) {
        val response = repository.markPlayed(chapterIds, played)
        // The server returns only the ids it actually touched — unknown or excluded ones are
        // dropped — so patch exactly those rather than what was asked for.
        val affected = response.chapterIds.ifEmpty { chapterIds }
        applyPlayed(fictionId, affected, played)
    }

    /** Patches the cached rows without a request. Public so a test can pin the identity rule. */
    fun applyPlayed(fictionId: Int, chapterIds: List<Int>, played: Boolean) {
        val state = chapterStates[fictionId] ?: return
        state.update { cached ->
            val response = cached.value ?: return@update cached
            cached.copy(value = response.copy(chapters = response.chapters.withPlayed(chapterIds, played)))
        }
    }

    // --- Lifetime --------------------------------------------------------------------------

    /**
     * Drops everything and stops every load.
     *
     * Called when the session ends: the cache outlives the composition, so without this the next
     * account to sign in on this machine would be shown the previous account's library.
     */
    fun clear() {
        libraryJob?.cancel()
        libraryJob = null
        chapterJobs.values.forEach(Job::cancel)
        chapterJobs.clear()
        chapterStates.clear()
        _library.value = Cached()
    }

    override fun close() {
        clear()
        scope.cancel()
    }

    // --- Internals -------------------------------------------------------------------------

    private fun chapterState(fictionId: Int): MutableStateFlow<Cached<ChaptersResponse>> =
        chapterStates.getOrPut(fictionId) { MutableStateFlow(Cached()) }

    private suspend fun <T> load(
        state: MutableStateFlow<Cached<T>>,
        fallback: String,
        block: suspend () -> T,
    ) {
        val result = runCatching { block() }
        // `runCatching` swallows cancellation, and a superseded load must not publish anything —
        // otherwise the answer to a request the user has already replaced lands on screen.
        coroutineContext.ensureActive()
        result
            .onSuccess { value -> state.value = Cached(value = value, lastSuccessMillis = clock()) }
            .onFailure { failure ->
                state.update {
                    it.copy(isRefreshing = false, error = userFacingMessage(failure, fallback))
                }
            }
    }
}
