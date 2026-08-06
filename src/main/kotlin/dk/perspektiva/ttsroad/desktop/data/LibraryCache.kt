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
    private val chapterOptions = LinkedHashMap<Int, MutableStateFlow<ChapterListOptions>>()

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
     * How this fiction's chapter list is currently being browsed.
     *
     * Held here rather than in the screen because "per fiction, across navigation" is a stronger
     * promise than the destination's retained state can make: popping a fiction off the back stack
     * releases its saved scroll offset, and a user who set the list to newest-first should not have
     * to set it again the next time they open the same serial in the same session.
     */
    fun chapterOptions(fictionId: Int): StateFlow<ChapterListOptions> = optionState(fictionId).asStateFlow()

    fun setChapterOptions(fictionId: Int, options: ChapterListOptions) {
        optionState(fictionId).value = options
    }

    /**
     * Marks chapters played **optimistically**, rolling back if the server refuses.
     *
     * The checkmark moves in the frame the user clicked, one request goes out for the whole id set,
     * and a failure restores exactly the `playback` each row had — not "the inverse mark", which
     * would zero out real progress on a chapter that was already finished. Throws the original
     * failure so the caller can put an inline message next to a list that never blanked.
     */
    suspend fun setPlayed(fictionId: Int, chapterIds: List<Int>, played: Boolean) {
        if (chapterIds.isEmpty()) return
        val undo = snapshotPlayback(fictionId, chapterIds)
        applyPlayed(fictionId, chapterIds, played)
        val response = try {
            repository.markPlayed(chapterIds, played)
        } catch (failure: Throwable) {
            restorePlayback(fictionId, undo)
            throw failure
        }
        // The server returns only the ids it actually touched — unknown, excluded, or (when
        // un-marking) never-started chapters are dropped — so anything it did not confirm goes
        // back to what it was.
        val confirmed = response.chapterIds.ifEmpty { chapterIds }.toSet()
        val rejected = undo.filterKeys { it !in confirmed }
        restorePlayback(fictionId, rejected)
    }

    /** Patches the cached rows without a request. Public so a test can pin the identity rule. */
    fun applyPlayed(fictionId: Int, chapterIds: List<Int>, played: Boolean) {
        val state = chapterStates[fictionId] ?: return
        state.update { cached ->
            val response = cached.value ?: return@update cached
            cached.copy(value = response.copy(chapters = response.chapters.withPlayed(chapterIds, played)))
        }
        patchLibraryShelves(fictionId, chapterIds, played)
    }

    private fun snapshotPlayback(fictionId: Int, chapterIds: List<Int>): Map<Int, PlaybackInfo?> =
        chapterStates[fictionId]?.value?.value?.chapters?.playbackSnapshot(chapterIds).orEmpty()

    private fun restorePlayback(fictionId: Int, snapshot: Map<Int, PlaybackInfo?>) {
        if (snapshot.isEmpty()) return
        val state = chapterStates[fictionId] ?: return
        state.update { cached ->
            val response = cached.value ?: return@update cached
            cached.copy(value = response.copy(chapters = response.chapters.withRestoredPlayback(snapshot)))
        }
        patchLibraryShelvesFromChapters(fictionId, snapshot.keys)
    }

    /**
     * Keeps the library's shelves honest about a chapter that was just marked from the detail
     * screen — the same row appears in "Continue listening"/"Recent", and leaving it showing a
     * resume position it no longer has is the visible half of the same lie.
     *
     * The per-fiction counters are **recomputed from this client's own chapter list** rather than
     * adjusted by a guessed delta: if the chapters are not cached there is nothing to count from,
     * and a wrong number would sit on screen until the next library refresh.
     */
    private fun patchLibraryShelves(fictionId: Int, chapterIds: List<Int>, played: Boolean) {
        val ids = chapterIds.toSet()
        _library.update { cached ->
            val library = cached.value ?: return@update cached
            cached.copy(
                value = library.copy(
                    continueListening = library.continueListening.withPlayed(ids, played),
                    recentChapters = library.recentChapters.withPlayed(ids, played),
                ),
            )
        }
        recountLibraryFor(fictionId)
    }

    /** Rollback counterpart: the shelves follow whatever the chapter list now says. */
    private fun patchLibraryShelvesFromChapters(fictionId: Int, chapterIds: Set<Int>) {
        val chapters = chapterStates[fictionId]?.value?.value?.chapters ?: return
        val byId = chapters.filter { it.resolvedChapterId in chapterIds }
            .associate { it.resolvedChapterId to it.playback }
        _library.update { cached ->
            val library = cached.value ?: return@update cached
            cached.copy(
                value = library.copy(
                    continueListening = library.continueListening.withRestoredPlayback(byId),
                    recentChapters = library.recentChapters.withRestoredPlayback(byId),
                ),
            )
        }
        recountLibraryFor(fictionId)
    }

    private fun recountLibraryFor(fictionId: Int) {
        val chapters = chapterStates[fictionId]?.value?.value?.chapters ?: return
        val played = chapters.count { it.isPlayed }
        val remaining = chapters.count { it.hasAudio && !it.isPlayed }
        _library.update { cached ->
            val library = cached.value ?: return@update cached
            cached.copy(
                value = library.copy(
                    continueListening = library.continueListening.map { row ->
                        if (row.resolvedFictionId != fictionId) {
                            row
                        } else {
                            row.copy(playedCount = played, remainingCount = remaining)
                        }
                    },
                ),
            )
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
        chapterOptions.clear()
        _library.value = Cached()
    }

    override fun close() {
        clear()
        scope.cancel()
    }

    // --- Internals -------------------------------------------------------------------------

    private fun chapterState(fictionId: Int): MutableStateFlow<Cached<ChaptersResponse>> =
        chapterStates.getOrPut(fictionId) { MutableStateFlow(Cached()) }

    private fun optionState(fictionId: Int): MutableStateFlow<ChapterListOptions> =
        chapterOptions.getOrPut(fictionId) { MutableStateFlow(ChapterListOptions()) }

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
