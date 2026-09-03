package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed as itemsIndexedInRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistory
import dk.perspektiva.ttsroad.desktop.data.PlaybackHistoryStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackSnapshot
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.chapterKeys
import dk.perspektiva.ttsroad.desktop.data.fictionKeys
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.launch

/** Test handle for "how many fiction cards did the lazy grid actually compose". */
const val FictionCardTestTag: String = "fictionCard"
const val AddFictionButtonTestTag: String = "addFictionButton"

/** Minimum card width; the grid fits as many columns as that allows, like `auto-fill minmax()`. */
private val MinCardWidth = 200.dp
private val GridGap = 18.dp

/**
 * The library.
 *
 * Everything on this screen is one [LazyVerticalGrid]: the hero, the shelves and the section
 * headers are full-width spans, the fiction cards are grid cells. That is deliberate rather than
 * cosmetic — the previous version chunked every fiction into rows inside a `verticalScroll`
 * column, which composes all of them, so a thousand-fiction library built a thousand cards (and
 * decoded a thousand covers) before the first frame.
 *
 * Data comes from [cache], not from a holder created here, so leaving and returning shows what was
 * already loaded instead of a spinner.
 */
@Composable
fun LibraryScreen(
    cache: LibraryCache,
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onOpenFiction: (FictionSummary) -> Unit,
    onOpenPlayer: () -> Unit,
    /**
     * Whether the signed-in server advertises the `search` capability.
     *
     * False hides the escalation entirely rather than disabling it: the field above still filters
     * what is loaded, which is the whole feature on a server that cannot do more.
     */
    serverSearchAvailable: Boolean = false,
    onSearchServer: (String) -> Unit = {},
    /**
     * Whether the signed-in server advertises the `follows` capability.
     *
     * False means `/api/mobile/library` is still the whole shared list there, so there is no shelf
     * to distinguish from a catalogue and the mode switch is absent entirely.
     */
    followsAvailable: Boolean = false,
    fictionManagement: FictionManagementUiState = FictionManagementUiState(),
    onAddFiction: () -> Unit = {},
    /**
     * Local listening history, for the "Jump back in" strip. Defaulted to an in-memory store so a
     * screen test never reads or writes the real config directory.
     */
    history: PlaybackHistoryStore = remember { InMemoryPlaybackHistoryStore() },
    /**
     * Which account's snapshots may be shown. Blank means "nobody's", which is what a signed-out
     * screen and a history file from an older build both get — see [PlaybackHistory.jumpBackChoices].
     */
    historyOwnerKey: String = "",
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    // Retained per destination, so walking into a fiction from browse-all and back lands where the
    // user left rather than snapping to the shelf.
    var browsing by rememberSaveable { mutableStateOf(false) }
    // Browse-all is unreachable on a server without follows, and staying in it after a downgrade
    // would leave the user in a mode the server no longer honours.
    val browseAll = browsing && followsAvailable
    val shelf by cache.library.collectAsState()
    val everything by cache.browseAll.collectAsState()
    val state = if (browseAll) everything else shelf
    val snapshots by history.history.collectAsState()
    val jumpBack = remember(snapshots, historyOwnerKey) {
        PlaybackHistory.jumpBackChoices(snapshots, historyOwnerKey)
    }
    // Keyless on purpose: fires once per screen *appearance*, not per recomposition. Reuses cached
    // content and coalesces with a load already in flight, so Back into the library costs nothing.
    LaunchedEffect(Unit) { cache.ensureLibrary() }
    // Local history paints immediately; the account-wide auto bookmarks merge underneath it when
    // reachable, so opening the library after listening in the browser adds those moments without
    // blanking the offline fallback.
    LaunchedEffect(history, historyOwnerKey) { history.refresh(historyOwnerKey) }
    // Keyed, because switching modes is what makes browse-all worth fetching at all; it coalesces
    // the same way, so flipping back and forth costs one request each.
    LaunchedEffect(browseAll) { if (browseAll) cache.ensureBrowseAll() }

    // Hoisted out of the lazy content on purpose: an item that scrolls off is disposed, and the
    // search text must not be one of the things that disposal takes with it. `rememberSaveable`
    // then hands it to the per-destination state holder, so Back from a fiction restores it.
    var query by rememberSaveable { mutableStateOf("") }
    // Deliberately not `rememberSaveable`: a failure to open is about a click that just happened,
    // and restoring last session's would be a message with nothing behind it.
    var openError by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()

    // The rails — hero, jump-back, recent — always come from the shelf, in both modes. The server
    // derives them from the followed set either way ("browse-all widens the catalogue, not what the
    // app thinks you are in the middle of"), and reading them from the mode being browsed would
    // blank them while browse-all is still loading.
    val rails = shelf.value
    val library = state.value
    val error = state.error
    fun refreshCurrent() = if (browseAll) cache.refreshBrowseAll() else cache.refreshLibrary()
    when {
        rails == null && shelf.error != null -> InitialErrorState(shelf.error.orEmpty()) { cache.refreshLibrary() }
        rails == null -> CenterProgress()
        else -> {
            val filtered = remember(library?.fictions, query) {
                filterFictions(library?.fictions.orEmpty(), query)
            }
            val keys = remember(filtered) { fictionKeys(filtered) }
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(MinCardWidth),
                    state = gridState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxSize()
                        .testTag(LibraryGridTestTag),
                    contentPadding = PaddingValues(PageGutter),
                    horizontalArrangement = Arrangement.spacedBy(GridGap),
                    verticalArrangement = Arrangement.spacedBy(GridGap),
                ) {
                    openError?.let { message ->
                        fullWidthItem("open-error") {
                            InlineNotice(message) { openError = null }
                        }
                    }

                    // A refresh that failed reports itself here, above content it did not replace.
                    if (state.isStale) {
                        fullWidthItem("stale") {
                            StaleContentBanner(
                                message = error.orEmpty(),
                                lastSuccessMillis = state.lastSuccessMillis,
                                nowMillis = nowMillis(),
                                onRetry = { refreshCurrent() },
                            )
                        }
                    }

                    val continueList = rails.continueListening
                    if (continueList.isNotEmpty()) {
                        val hero = continueList.first()
                        fullWidthItem("hero") {
                            ContinueHero(hero, repository) {
                                scope.launch { playback.play(hero, hero.fiction) }
                                onOpenPlayer()
                            }
                        }
                        if (continueList.size > 1) {
                            fullWidthItem("continue") {
                                Column {
                                    SectionTitle("01", "Continue listening")
                                    Spacer(Modifier.height(16.dp))
                                    ContinueShelf(continueList.drop(1), repository) { chapter ->
                                        scope.launch { playback.play(chapter, chapter.fiction) }
                                    }
                                }
                            }
                        }
                    }

                    // Local-first and cross-device: the server's `auto` bookmarks merge into the
                    // same bounded list, while this machine's copy survives an offline start.
                    // Dismissing an entry hides that snapshot locally, not "today".
                    if (jumpBack.isNotEmpty()) {
                        fullWidthItem("jump-back") {
                            Column {
                                Spacer(Modifier.height(20.dp))
                                SectionTitle("02", "Jump back in")
                                Spacer(Modifier.height(16.dp))
                                JumpBackShelf(
                                    snapshots = jumpBack,
                                    // Resolved outside the followed shelf on purpose: a moment
                                    // recorded on another client can name a serial this account has
                                    // since unfollowed, or one it has only browsed, and looking it
                                    // up in `rails` alone turned the card into a clickable no-op.
                                    onOpen = { snapshot ->
                                        scope.launch {
                                            openError = null
                                            runCatching { cache.resolveFiction(snapshot.fictionId) }
                                                .onSuccess(onOpenFiction)
                                                .onFailure { failure ->
                                                    openError = userFacingMessage(
                                                        failure,
                                                        "Could not open ${snapshot.fictionTitle}",
                                                    )
                                                }
                                        }
                                    },
                                    onDismiss = { history.dismiss(it.key) },
                                )
                            }
                        }
                    }

                    fullWidthItem("fictions-header") {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionTitle(
                                    "03",
                                    if (browseAll) "Every fiction" else "Fictions",
                                    Modifier.weight(1f),
                                )
                                if (fictionManagement.canManage) {
                                    Spacer(Modifier.width(16.dp))
                                    OutlinedButton(
                                        onClick = onAddFiction,
                                        enabled = !fictionManagement.isBusy,
                                        shape = RectangleShape,
                                        modifier = Modifier
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .testTag(AddFictionButtonTestTag),
                                    ) { Text("ADD FICTION") }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            fictionManagement.notice?.let {
                                MetaText(it, color = AarisColor.Ok)
                                Spacer(Modifier.height(10.dp))
                            }
                            if (fictionManagement.access == FictionManagementAccess.Unavailable) {
                                Text(
                                    fictionManagement.error ?: "Fiction management is temporarily unavailable",
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            if (followsAvailable) {
                                LibraryScopeTabs(browseAll) { browsing = it }
                                Spacer(Modifier.height(14.dp))
                            }
                            if (library != null && library.fictions.isNotEmpty()) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    label = { Text("SEARCH TITLE, AUTHOR OR TAG") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    // Enter escalates rather than filtering again: the filter has
                                    // already applied itself on every keystroke, so the only thing
                                    // left for the key to mean is "look further than this".
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            if (serverSearchAvailable && query.isNotBlank()) onSearchServer(query)
                                        },
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (serverSearchAvailable) {
                                    Spacer(Modifier.height(10.dp))
                                    SearchServerAction(
                                        query = query,
                                        matches = filtered.size,
                                        onSearch = { onSearchServer(query) },
                                    )
                                }
                            }
                        }
                    }

                    when {
                        // Only the grid waits, never the rails above it: switching to browse-all
                        // must not take the hero off screen while one request runs.
                        library == null && error != null -> fullWidthItem("grid-error") {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                InlineRetry(error, ::refreshCurrent)
                            }
                        }

                        library == null -> fullWidthItem("grid-loading") {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                CenterProgress()
                            }
                        }

                        library.fictions.isEmpty() -> fullWidthItem("no-fictions") {
                            if (browseAll) {
                                EmptyState(
                                    "The server has no fictions",
                                    if (fictionManagement.canManage) {
                                        "Use Add fiction to start tracking one."
                                    } else {
                                        "An administrator can add one from a supported client."
                                    },
                                )
                            } else {
                                EmptyState(
                                    "Your shelf is empty",
                                    "Switch to Everything to find something to follow.",
                                )
                            }
                        }

                        filtered.isEmpty() -> fullWidthItem("no-matches") {
                            EmptyState("No matches for \"$query\"", "Try a different title, author or tag.")
                        }

                        else -> itemsIndexed(
                            filtered,
                            key = { index, _ -> keys[index] },
                            contentType = { _, _ -> "fiction" },
                        ) { _, fiction ->
                            FictionCard(fiction, repository, Modifier.testTag(FictionCardTestTag)) {
                                onOpenFiction(fiction)
                            }
                        }
                    }

                    if (rails.recentChapters.isNotEmpty()) {
                        fullWidthItem("recent") {
                            Column {
                                Spacer(Modifier.height(20.dp))
                                SectionTitle("04", "Recent")
                                Spacer(Modifier.height(16.dp))
                                ContinueShelf(rails.recentChapters, repository) { chapter ->
                                    scope.launch { playback.play(chapter, chapter.fiction) }
                                }
                            }
                        }
                    }
                }
                RefreshingStrip(state.isRefreshing && !state.isStale)
            }
        }
    }
}

/** Test handle for the library's scroll container. */
const val LibraryGridTestTag: String = "libraryGrid"

const val JumpBackCardTestTag: String = "jumpBackCard"
const val JumpBackDismissTestTag: String = "jumpBackDismiss"

/**
 * The local "jump back in" strip.
 *
 * Opens the fiction rather than starting playback: the snapshot holds an id and a title, not a
 * `ChapterSummary`, and the honest action for "here is where you were" is to take the listener to
 * the chapter list that already highlights it — not to guess at a payload and start audio.
 */
@Composable
private fun JumpBackShelf(
    snapshots: List<PlaybackSnapshot>,
    onOpen: (PlaybackSnapshot) -> Unit,
    onDismiss: (PlaybackSnapshot) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        snapshots.forEach { snapshot ->
            AarisCard(modifier = Modifier.width(260.dp).testTag(JumpBackCardTestTag)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            MetaText(snapshot.fictionTitle.ifBlank { "Unknown" })
                            Spacer(Modifier.height(4.dp))
                            Text(
                                snapshot.chapterTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = AarisColor.Ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Dismissal is per snapshot; a later chapter of the same serial comes back
                        // on its own, which is the behaviour the issue asks for over "hide today".
                        Text(
                            "×",
                            style = MaterialTheme.typography.titleMedium,
                            color = AarisColor.Muted,
                            modifier = Modifier
                                .testTag(JumpBackDismissTestTag)
                                .clickable { onDismiss(snapshot) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .semantics { contentDescription = "Dismiss ${snapshot.chapterTitle}" }
                                .padding(horizontal = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(snapshot.progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Open",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AarisColor.Accent,
                        modifier = Modifier
                            .clickable { onOpen(snapshot) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .semantics { contentDescription = "Open ${snapshot.fictionTitle}" }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

const val SearchServerTestTag: String = "librarySearchServer"

/**
 * The escalation from the local filter to the server's own index.
 *
 * Phrased as what it adds — chapter titles and narration text — rather than as "search", because
 * the field directly above it is also search and the difference between the two is the only reason
 * this control exists. Disabled rather than hidden while the field is empty, so it does not appear
 * and disappear under the cursor as the user types.
 */
@Composable
private fun SearchServerAction(query: String, matches: Int, onSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onSearch,
            enabled = query.isNotBlank(),
            shape = RectangleShape,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(SearchServerTestTag),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            MetaText("Search chapters and text on the server", color = AarisColor.Ink)
        }
        // The case this is really for: the words are in a chapter, not in a title, so the shelf
        // below is empty and the server is the only thing that can answer.
        if (query.isNotBlank() && matches == 0) {
            Spacer(Modifier.width(12.dp))
            MetaText("Nothing here matches — the server may still find it", color = AarisColor.Dim)
        }
    }
}

const val LibraryScopeTabTestTag: String = "libraryScopeTab"

/**
 * Shelf or catalogue.
 *
 * `selectable` tabs rather than a toggle, for the same reason the chapter filter uses them: a tab
 * announces its selected state and can be reached in one Tab stop, where a button that relabels
 * itself announces the state you would move *to*.
 */
@Composable
private fun LibraryScopeTabs(browseAll: Boolean, onSelect: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ScopeTab("My shelf", selected = !browseAll) { onSelect(false) }
        ScopeTab("Everything", selected = browseAll) { onSelect(true) }
    }
}

@Composable
private fun ScopeTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onSelect,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(LibraryScopeTabTestTag),
    ) {
        MetaText(label, color = if (selected) AarisColor.Accent else AarisColor.Muted)
    }
}

/** A failure that took out one section rather than the screen. Always carries its own retry. */
@Composable
private fun InlineRetry(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(
            onClick = onRetry,
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("RETRY") }
    }
}

/** Full-width row inside the grid, for heroes, shelves and section headers. */
private fun LazyGridScope.fullWidthItem(
    key: String,
    content: @Composable () -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "span") { content() }

/** Case-insensitive across title, author and tags — the same three fields as the mobile client. */
internal fun filterFictions(fictions: List<FictionSummary>, query: String): List<FictionSummary> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return fictions
    return fictions.filter { fiction ->
        fiction.title.lowercase().contains(q) ||
            fiction.author?.lowercase()?.contains(q) == true ||
            fiction.tags.any { it.lowercase().contains(q) }
    }
}

/** Fraction of the chapter already listened to, for progress-on-artwork. */
private fun listenedFraction(chapter: ChapterSummary): Float {
    val duration = chapter.audioDuration ?: return 0f
    if (duration <= 0.0) return 0f
    return (chapter.resolvedPositionSeconds / duration).toFloat().coerceIn(0f, 1f)
}

/**
 * Netflix-style billboard for the most recent in-progress chapter: large cover, gradient panel,
 * one prominent resume action.
 */
@Composable
private fun ContinueHero(chapter: ChapterSummary, repository: TtsRoadRepository, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .border(1.dp, AarisColor.Line)
            .background(Brush.horizontalGradient(listOf(AarisColor.BgHover, AarisColor.Bg))),
    ) {
        CoverImage(
            chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
            chapter.resolvedCoverUrl?.let(repository::resolveUrl),
            Modifier.fillMaxHeight().aspectRatio(2f / 3f),
            bordered = false,
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 24.dp)) {
            MetaText(text = "// Continue listening", color = AarisColor.Accent)
            Spacer(Modifier.height(10.dp))
            Text(
                chapter.resolvedTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = AarisColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            chapter.resolvedFictionTitle?.let {
                Spacer(Modifier.height(6.dp))
                MetaText(it)
            }
            Spacer(Modifier.weight(1f))
            ThinProgress(listenedFraction(chapter), Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onResume,
                    enabled = chapter.audio != null,
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (chapter.resolvedPositionSeconds > 0) "RESUME" else "PLAY")
                }
                Spacer(Modifier.width(16.dp))
                val meta = listOfNotNull(chapter.playback?.remainingLabel, chapter.audioDurationLabel).firstOrNull()
                meta?.let { MetaText(it, color = AarisColor.Dim) }
            }
        }
    }
}

/**
 * Horizontal shelf of chapters — hover shows a play overlay, progress sits on the art.
 *
 * Keys come from [chapterKeys] rather than `resolvedChapterId`: the two shelves are two different
 * server payloads whose ids can repeat, and a duplicate key crashes a lazy list outright.
 */
@Composable
private fun ContinueShelf(
    chapters: List<ChapterSummary>,
    repository: TtsRoadRepository,
    onPlay: (ChapterSummary) -> Unit,
) {
    val keys = remember(chapters) { chapterKeys(chapters) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexedInRow(chapters, key = { index, _ -> keys[index] }) { _, chapter ->
            ShelfCard(chapter, repository) { onPlay(chapter) }
        }
    }
}

@Composable
private fun ShelfCard(chapter: ChapterSummary, repository: TtsRoadRepository, onPlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered = pointerOver || focused
    val coverScale by animateFloatAsState(if (hovered) 1.05f else 1f)

    Column(
        Modifier
            .width(156.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = chapter.audio != null, onClick = onPlay),
    ) {
        val edge = when {
            focused -> AarisColor.Accent
            hovered -> AarisColor.Dim
            else -> AarisColor.Line
        }
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).border(1.dp, edge)) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                CoverImage(
                    chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
                    chapter.resolvedCoverUrl?.let(repository::resolveUrl),
                    Modifier.fillMaxSize().graphicsLayer { scaleX = coverScale; scaleY = coverScale },
                    bordered = false,
                )
            }
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(44.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = AarisColor.Bg, modifier = Modifier.size(26.dp))
                    }
                }
            }
            val fraction = listenedFraction(chapter)
            if (fraction > 0f) {
                ThinProgress(fraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            chapter.resolvedTitle,
            style = MaterialTheme.typography.titleMedium,
            color = if (hovered) AarisColor.Ink else AarisColor.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        chapter.resolvedFictionTitle?.let {
            Spacer(Modifier.height(2.dp))
            MetaText(it, color = AarisColor.Dim)
        }
    }
}

/** Cover-forward fiction card: art bleeds to the card edge, TTS-ready progress sits on the art. */
@Composable
private fun FictionCard(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    AarisCard(modifier = modifier, onClick = onOpen) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                CoverImage(
                    fiction.title,
                    fiction.coverImageUrl?.let(repository::resolveUrl),
                    Modifier.fillMaxSize(),
                    bordered = false,
                )
                ThinProgress(fiction.readyFraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    fiction.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AarisColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaText(
                    listOfNotNull(
                        fiction.author?.takeIf { it.isNotBlank() },
                        "${fiction.doneChapters}/${fiction.totalChapters} ready",
                    ).joinToString("  ·  "),
                    color = AarisColor.Dim,
                )
            }
        }
    }
}
