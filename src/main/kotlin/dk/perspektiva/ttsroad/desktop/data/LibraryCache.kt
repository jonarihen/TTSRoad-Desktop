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

    @Volatile private var diskCache: () -> LibraryDiskCache? = { null }

    /**
     * Attaches the account-scoped rebuildable store after the composition root has built downloads.
     * Kept as an attachment rather than a constructor argument so existing isolated caches remain
     * memory-only by default and never touch a developer's real cache directory in tests.
     */
    fun attachDiskCache(supplier: () -> LibraryDiskCache?): LibraryCache = apply {
        diskCache = supplier
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _library = MutableStateFlow(Cached<LibraryResponse>())
    val library: StateFlow<Cached<LibraryResponse>> = _library.asStateFlow()
    private var libraryJob: Job? = null

    /**
     * The whole server, for finding something to follow.
     *
     * A second state rather than a scope on [library], and deliberately **never written to disk**:
     * the offline namespace holds what this account chose to keep, and a catalogue of everything
     * the server happens to host is not that. It is also why switching between the two costs no
     * request the second time — each keeps its own answer.
     */
    private val _browseAll = MutableStateFlow(Cached<LibraryResponse>())
    val browseAll: StateFlow<Cached<LibraryResponse>> = _browseAll.asStateFlow()
    private var browseAllJob: Job? = null

    private val chapterStates = LinkedHashMap<Int, MutableStateFlow<Cached<ChaptersResponse>>>()
    private val chapterJobs = HashMap<Int, Job>()
    private val chapterOptions = LinkedHashMap<Int, MutableStateFlow<ChapterListOptions>>()

    // --- Library ---------------------------------------------------------------------------

    /** Opening the library screen. Reuses what is there; starts one load when there is nothing. */
    fun ensureLibrary() {
        if (_library.value.hasContent || libraryJob?.isActive == true) return
        primeLibraryFromDisk()
        // Cached disk content is shown immediately but still refreshed; otherwise an offline
        // snapshot would become permanent merely because it existed.
        refreshLibrary()
    }

    /** The explicit Refresh action. Always re-asks, cancelling any load already running. */
    fun refreshLibrary(forceFull: Boolean = false) {
        libraryJob?.cancel()
        if (!_library.value.hasContent) primeLibraryFromDisk()
        _library.update { it.copy(isRefreshing = true, error = null) }
        val persistent = diskCache()
        libraryJob = scope.launch {
            val current = _library.value.value
            load(
                state = _library,
                fallback = "Could not load library",
                onLoaded = { value, savedAt -> persistent?.storeLibrary(value, savedAt) },
            ) { refreshLibraryValue(current, forceFull) }
        }
    }

    // --- Browse all --------------------------------------------------------------------------

    /** Opening browse-all. Same coalescing rule as [ensureLibrary]; no disk priming. */
    fun ensureBrowseAll() {
        if (_browseAll.value.hasContent || browseAllJob?.isActive == true) return
        refreshBrowseAll()
    }

    fun refreshBrowseAll() {
        browseAllJob?.cancel()
        _browseAll.update { it.copy(isRefreshing = true, error = null) }
        browseAllJob = scope.launch {
            load(state = _browseAll, fallback = "Could not load the server's fictions") {
                repository.library(LibraryScope.All)
            }
        }
    }

    /**
     * Follows or unfollows [fictionId], answering the state the **server** now holds.
     *
     * Not optimistic, and that is the point: the shelf is a list whose membership changes, so a
     * guessed answer would have to add or remove a row — and undo it on failure — rather than flip
     * a flag in place. One request, then the flag is patched from what came back and the shelf is
     * re-asked, because following something also changes what "Continue listening" contains.
     *
     * Null means the server answered 404: no such fiction, or no such endpoint. Neither did
     * anything, so neither may render as success.
     */
    suspend fun setFollowing(fictionId: Int, following: Boolean): Boolean? {
        val confirmed = repository.setFollowing(fictionId, following) ?: return null
        patchFollowing(fictionId, confirmed)
        // Membership changed, so the shelf is a different list now — a flag patch cannot express
        // a row appearing or disappearing.
        refreshLibrary(forceFull = true)
        return confirmed
    }

    /** Patches the flag on both lists without a request. Public so a test can pin the rule. */
    fun patchFollowing(fictionId: Int, following: Boolean) {
        listOf(_library, _browseAll).forEach { state ->
            state.update { cached ->
                val library = cached.value ?: return@update cached
                cached.copy(
                    value = library.copy(
                        fictions = library.fictions.map { fiction ->
                            if (fiction.id == fictionId) fiction.copy(following = following) else fiction
                        },
                    ),
                )
            }
        }
    }

    /** Drops every in-memory/disk metadata reference after a confirmed server-side delete. */
    fun forgetFiction(fictionId: Int) {
        chapterJobs.remove(fictionId)?.cancel()
        chapterStates.remove(fictionId)
        chapterOptions.remove(fictionId)
        listOf(_library, _browseAll).forEach { state ->
            state.update { cached ->
                val library = cached.value ?: return@update cached
                cached.copy(
                    value = library.copy(
                        fictions = library.fictions.filterNot { it.id == fictionId },
                        continueListening = library.continueListening.filterNot {
                            it.resolvedFictionId == fictionId
                        },
                        recentChapters = library.recentChapters.filterNot {
                            it.resolvedFictionId == fictionId
                        },
                    ),
                )
            }
        }
        diskCache()?.removeChapters(fictionId)
    }

    /** Publishes the server-returned metadata immediately while the full refresh runs underneath. */
    fun patchFiction(fiction: FictionSummary) {
        fun ChapterSummary.patch(): ChapterSummary =
            if (resolvedFictionId == fiction.id) {
                copy(
                    fiction = this.fiction?.let { fiction },
                    fictionTitle = fiction.title,
                    fictionAuthor = fiction.author,
                    coverImageUrl = fiction.coverImageUrl,
                )
            } else {
                this
            }

        listOf(_library, _browseAll).forEach { state ->
            state.update { cached ->
                val library = cached.value ?: return@update cached
                cached.copy(
                    value = library.copy(
                        fictions = library.fictions.map { current ->
                            if (current.id == fiction.id) {
                                // Mutation payloads are global fiction shapes, so they do not
                                // carry this account's shelf membership. Preserve what the
                                // library knew until its refresh returns the scoped row.
                                fiction.copy(following = fiction.following ?: current.following)
                            } else {
                                current
                            }
                        },
                        continueListening = library.continueListening.map(ChapterSummary::patch),
                        recentChapters = library.recentChapters.map(ChapterSummary::patch),
                    ),
                )
            }
        }
        chapterStates[fiction.id]?.update { cached ->
            val chapters = cached.value ?: return@update cached
            cached.copy(value = chapters.copy(fiction = fiction))
        }
    }

    /**
     * What this client believes about [fictionId]'s follow state, or null when nothing says.
     *
     * Reads the two library payloads and never the chapters one, which does not carry the key —
     * see [FictionSummary.following].
     */
    fun followingOf(fictionId: Int): Boolean? =
        (_library.value.value?.fictions.orEmpty() + _browseAll.value.value?.fictions.orEmpty())
            .firstOrNull { it.id == fictionId }
            ?.following

    /**
     * The fiction [fictionId] names, from either shelf, or null when neither holds it.
     *
     * Both payloads, not just the followed one: a jump-back moment or a bookmark can point at a
     * serial this account has since unfollowed, or at one it has only ever browsed.
     */
    fun cachedFiction(fictionId: Int): FictionSummary? =
        (_library.value.value?.fictions.orEmpty() + _browseAll.value.value?.fictions.orEmpty())
            .firstOrNull { it.id == fictionId }

    /**
     * The fiction [fictionId] names, fetching it when neither shelf has it.
     *
     * The fallback is the chapters endpoint, which carries a `fiction` built by the server's own
     * `_fiction_payload`. That is deliberately the same request the caller is about to need anyway
     * — nothing here opens a fiction without also wanting its chapters — so resolving costs no
     * extra round trip in the case that made it necessary.
     *
     * Throws rather than answering null on a failed request, because "the server said there is no
     * such fiction" and "the request never arrived" want different sentences on screen, and only
     * the caller knows which surface is asking.
     */
    suspend fun resolveFiction(fictionId: Int): FictionSummary =
        cachedFiction(fictionId) ?: repository.chapters(fictionId).fiction

    // --- Chapters --------------------------------------------------------------------------

    /** The chapter list for one fiction. Stable across navigation, so scroll and data survive. */
    fun chapters(fictionId: Int): StateFlow<Cached<ChaptersResponse>> = chapterState(fictionId).asStateFlow()

    fun ensureChapters(fictionId: Int) {
        val state = chapterState(fictionId)
        if (state.value.hasContent || chapterJobs[fictionId]?.isActive == true) return
        primeChaptersFromDisk(fictionId, state)
        refreshChapters(fictionId)
    }

    fun refreshChapters(fictionId: Int) {
        chapterJobs[fictionId]?.cancel()
        val state = chapterState(fictionId)
        if (!state.value.hasContent) primeChaptersFromDisk(fictionId, state)
        state.update { it.copy(isRefreshing = true, error = null) }
        val persistent = diskCache()
        chapterJobs[fictionId] = scope.launch {
            val current = state.value.value
            load(
                state = state,
                fallback = "Could not load chapters",
                onLoaded = { value, savedAt -> persistent?.storeChapters(fictionId, value, savedAt) },
            ) { refreshChaptersValue(fictionId, current) }
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
        browseAllJob?.cancel()
        browseAllJob = null
        chapterJobs.values.forEach(Job::cancel)
        chapterJobs.clear()
        chapterStates.clear()
        chapterOptions.clear()
        _library.value = Cached()
        _browseAll.value = Cached()
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

    private fun primeLibraryFromDisk() {
        val stored = diskCache()?.loadLibrary() ?: return
        if (_library.value.hasContent) return
        _library.value = Cached(value = stored.value, lastSuccessMillis = stored.savedAtMillis)
    }

    private fun primeChaptersFromDisk(
        fictionId: Int,
        state: MutableStateFlow<Cached<ChaptersResponse>>,
    ) {
        val stored = diskCache()?.loadChapters(fictionId) ?: return
        if (state.value.hasContent) return
        state.value = Cached(value = stored.value, lastSuccessMillis = stored.savedAtMillis)
    }

    /** One index call, then only the shelf bytes that actually changed. */
    private suspend fun refreshLibraryValue(
        current: LibraryResponse?,
        forceFull: Boolean,
    ): LibraryResponse {
        if (forceFull) return repository.library()
        val cursor = current?.serverTime?.takeIf { it.isNotBlank() } ?: return repository.library()
        val index = repository.deltaSync(cursor) ?: return repository.library()
        val nextCursor = index.serverTime?.takeIf { it.isNotBlank() } ?: return repository.library()
        if (!index.delta) return repository.library()
        // The current backend's sync index does not count follow-table changes. A follows-aware
        // response therefore gets one cheap library delta even when the index is otherwise quiet;
        // its complete `following_ids` detects removals and the rare new-row case below.
        val checksMembership = current.scope == LibraryScope.Followed.wireValue && current.followingIds != null
        if (!index.changesLibrary && !checksMembership) return current.withSyncCursor(nextCursor)
        val delta = repository.libraryDelta(cursor)
        if (current.deltaNeedsFullFollowPull(delta)) return repository.library()
        return current.mergedWith(delta)
    }

    /** One index call, then one small per-fiction delta only when that fiction moved. */
    private suspend fun refreshChaptersValue(
        fictionId: Int,
        current: ChaptersResponse?,
    ): ChaptersResponse {
        val cursor = current?.serverTime?.takeIf { it.isNotBlank() } ?: return repository.chapters(fictionId)
        val index = repository.deltaSync(cursor) ?: return repository.chapters(fictionId)
        val nextCursor = index.serverTime?.takeIf { it.isNotBlank() } ?: return repository.chapters(fictionId)
        if (!index.delta) return repository.chapters(fictionId)
        check(fictionId !in index.deleted.fictions) { "This fiction was deleted on the server" }
        if (!index.changesFiction(fictionId)) return current.withSyncCursor(nextCursor)
        return current.mergedWith(repository.chaptersDelta(fictionId, cursor))
    }

    private suspend fun <T> load(
        state: MutableStateFlow<Cached<T>>,
        fallback: String,
        onLoaded: (T, Long) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ) {
        val result = runCatching { block() }
        // `runCatching` swallows cancellation, and a superseded load must not publish anything —
        // otherwise the answer to a request the user has already replaced lands on screen.
        coroutineContext.ensureActive()
        result
            .onSuccess { value ->
                val savedAt = clock()
                state.value = Cached(value = value, lastSuccessMillis = savedAt)
                onLoaded(value, savedAt)
            }
            .onFailure { failure ->
                state.update {
                    it.copy(isRefreshing = false, error = userFacingMessage(failure, fallback))
                }
            }
    }
}
