package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.BookmarkCreateRequest
import dk.perspektiva.ttsroad.desktop.data.BookmarkLimits
import dk.perspektiva.ttsroad.desktop.data.BookmarkPatchRequest
import dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.data.visibleBookmarks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the bookmarks screen shows, and what the player and reader say after marking a spot.
 *
 * [notice] is the one piece of state the *other* screens read: a bookmark is made from the player
 * or the reader, but the list it joins is somewhere else entirely, so without a word back the
 * action looks like it did nothing. It is deliberately not an error channel — [error] is —
 * because "saved" and "could not save" are shown in different places and different colours.
 */
data class BookmarksUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * The server answered 404 for the endpoint the capability advertised.
     *
     * Kept apart from [error] for the reason `SearchStateHolder` keeps them apart: one is worth a
     * Retry button and the other never will be.
     */
    val unsupported: Boolean = false,
    val notice: String? = null,
    /** True once a load has completed, so "no bookmarks yet" is only said about a list that ran. */
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = bookmarks.isEmpty()
}

/**
 * The account's manual bookmarks, held above the screens.
 *
 * Hoisted like the settings, update and search holders, and for a sharper reason than any of them:
 * the *writer* is the player or the reader and the *reader* is a destination the user may never
 * have opened. A holder owned by the bookmarks screen would mean the Ctrl+B pressed while
 * listening had nowhere to go.
 *
 * Only `manual` marks are ever requested. The same server table holds the web player's jump-back
 * breadcrumbs as `auto`, and a day of listening writes a few hundred of those — a list that mixed
 * them in would bury the handful of marks somebody actually chose.
 */
class BookmarksStateHolder(
    private val repository: TtsRoadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Chapters this session already holds for a fiction, when it holds any.
     *
     * Consulted before the network so a mark on a serial that is already open plays offline, which
     * is the case where a failed request would be most obviously unnecessary. Defaults to knowing
     * nothing, which is correct for a test and for a screen with no library cache behind it.
     */
    private val cachedChapters: (Int) -> ChaptersResponse? = { null },
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Loads what a mark needs in order to play, or says why it could not.
     *
     * The request lives here rather than in the caller because a failure needs somewhere to be
     * *seen*: the previous version discarded the exception in the navigator, so a Play against an
     * unreachable server produced no navigation, no notice and no error — indistinguishable from a
     * click that never registered. The caller keeps the playback and the navigation, which are the
     * two things a holder should not know about.
     *
     * Answers null having already published the reason.
     */
    suspend fun loadForPlayback(bookmark: Bookmark): ChaptersResponse? {
        val fictionId = bookmark.fictionId
        val chapterId = bookmark.chapterId
        if (fictionId == null || chapterId == null) {
            _state.update { it.copy(error = "That bookmark does not name a chapter to play") }
            return null
        }
        cachedChapters(fictionId)?.let { cached ->
            if (cached.chapters.any { it.id == chapterId }) return cached
        }
        _state.update { it.copy(error = null) }
        return runCatching { repository.chapters(fictionId) }
            .onFailure { failure ->
                _state.update {
                    it.copy(error = userFacingMessage(failure, "Could not open that bookmark"))
                }
            }
            .getOrNull()
            ?.also { loaded ->
                if (loaded.chapters.none { it.id == chapterId }) {
                    _state.update { it.copy(error = "That chapter is no longer in ${loaded.fiction.title}") }
                }
            }
            ?.takeIf { loaded -> loaded.chapters.any { it.id == chapterId } }
    }

    /** Loads once. What the bookmarks screen calls on entry; a second visit costs nothing. */
    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            val outcome = runCatching { repository.bookmarks() }
            _state.update { current ->
                outcome.fold(
                    onSuccess = { list ->
                        if (list == null) {
                            current.copy(loading = false, loaded = true, unsupported = true, bookmarks = emptyList())
                        } else {
                            current.copy(
                                loading = false,
                                loaded = true,
                                unsupported = false,
                                error = null,
                                bookmarks = list.visibleBookmarks(),
                            )
                        }
                    },
                    // The list already on screen is kept. A failed refresh is the same situation as
                    // a failed library refresh: a banner over retained content explains more than
                    // an empty screen does.
                    onFailure = { failure ->
                        current.copy(
                            loading = false,
                            loaded = true,
                            error = userFacingMessage(failure, "Could not load bookmarks"),
                        )
                    },
                )
            }
        }
    }

    /**
     * Marks [positionMs] in [chapterId].
     *
     * Fire-and-forget by design: this is pressed mid-chapter, from a screen that is not the list,
     * so it must never open a dialog or block the transport. Labelling and annotating happen later
     * on the bookmarks screen, where there is room for them.
     */
    fun add(chapterId: Int, positionMs: Long, label: String? = null) {
        if (chapterId <= 0) return
        scope.launch {
            val outcome = runCatching {
                repository.createBookmark(
                    BookmarkCreateRequest(
                        chapterId = chapterId,
                        positionSeconds = positionMs.coerceAtLeast(0L) / 1000.0,
                        label = label?.trim()?.take(BookmarkLimits.MaxLabelChars)?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            _state.update { current ->
                outcome.fold(
                    onSuccess = { created ->
                        if (created == null) {
                            current.copy(unsupported = true, notice = "This server has no bookmarks")
                        } else {
                            current.copy(
                                // Prepended rather than re-fetched: the list is already correct and
                                // a round trip would make the confirmation arrive after it.
                                bookmarks = (listOf(created) + current.bookmarks.filterNot { it.id == created.id }),
                                notice = "Bookmarked at ${created.positionLabel ?: formatDuration(created.positionMs)}",
                            )
                        }
                    },
                    onFailure = { failure ->
                        current.copy(notice = userFacingMessage(failure, "Could not save the bookmark"))
                    },
                )
            }
        }
    }

    /**
     * Edits a mark's label and note.
     *
     * A blank string is sent as `""` rather than omitted, which is how the server distinguishes
     * "clear this" from "leave it alone" — an absent key means the latter. See [BookmarkPatchRequest].
     */
    fun edit(bookmarkId: Int, label: String, note: String) {
        scope.launch {
            val outcome = runCatching {
                repository.updateBookmark(bookmarkId, BookmarkPatchRequest(label = label, note = note))
            }
            _state.update { current ->
                outcome.fold(
                    onSuccess = { updated ->
                        if (updated == null) {
                            current.copy(notice = "That bookmark is no longer there")
                        } else {
                            current.copy(
                                bookmarks = current.bookmarks.map { if (it.id == updated.id) updated else it },
                                notice = "Bookmark saved",
                            )
                        }
                    },
                    onFailure = { failure ->
                        current.copy(notice = userFacingMessage(failure, "Could not save the bookmark"))
                    },
                )
            }
        }
    }

    /**
     * Deletes a mark, optimistically.
     *
     * The row leaves immediately and comes back only if the request fails. The server's delete is
     * idempotent — a second one answers the first one's tombstone rather than 404 — so the retry a
     * user makes after a dropped connection cannot produce an error about a row that is already
     * gone.
     */
    fun remove(bookmarkId: Int) {
        val removed = _state.value.bookmarks.firstOrNull { it.id == bookmarkId } ?: return
        val index = _state.value.bookmarks.indexOf(removed)
        _state.update { it.copy(bookmarks = it.bookmarks.filterNot { row -> row.id == bookmarkId }) }
        scope.launch {
            val outcome = runCatching { repository.deleteBookmark(bookmarkId) }
            _state.update { current ->
                val failed = outcome.getOrNull() != true
                if (!failed) {
                    current.copy(notice = "Bookmark removed")
                } else {
                    current.copy(
                        // Put back exactly where it was, so a failed delete does not also reorder
                        // the list under the user's cursor.
                        bookmarks = current.bookmarks.toMutableList().apply {
                            add(index.coerceIn(0, size), removed)
                        },
                        notice = outcome.exceptionOrNull()
                            ?.let { userFacingMessage(it, "Could not remove the bookmark") }
                            ?: "Could not remove the bookmark",
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** The account's bookmarks are the account's. Dropped with the session, like the library. */
    fun sessionEnded() {
        loadJob?.cancel()
        _state.value = BookmarksUiState()
    }
}
