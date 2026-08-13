@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.perspektiva.ttsroad.desktop.data.ReadAlongCache
import dk.perspektiva.ttsroad.desktop.data.ReadAlongDocument
import dk.perspektiva.ttsroad.desktop.data.ReadAlongHighlight
import dk.perspektiva.ttsroad.desktop.data.ReadAlongTimingState
import dk.perspektiva.ttsroad.desktop.data.ReaderHighlight
import dk.perspektiva.ttsroad.desktop.data.ReaderPreferences
import dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.ReaderTheme
import dk.perspektiva.ttsroad.desktop.data.TextSpan
import dk.perspektiva.ttsroad.desktop.data.readAlongMatches
import dk.perspektiva.ttsroad.desktop.data.readAlongTimingsMatch
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

const val ReaderListTestTag: String = "readerList"
const val ReaderFindFieldTestTag: String = "readerFindField"
const val ReaderSettingsButtonTestTag: String = "readerSettingsButton"
const val ReaderParagraphTestTag: String = "readerParagraph"
const val ReaderBookmarkButtonTestTag: String = "readerBookmarkButton"

data class ReaderPalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val sentenceBand: Color,
    val findBand: Color,
)

fun readerPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.Dark -> ReaderPalette(
        AarisColor.Bg, AarisColor.Ink, AarisColor.Muted, AarisColor.Line,
        AarisColor.Accent, AarisColor.BgHover, Color(0x665B7CFF),
    )
    ReaderTheme.Sepia -> ReaderPalette(
        Color(0xFFF2E7CF), Color(0xFF382F26), Color(0xFF776752), Color(0xFFCDBB99),
        Color(0xFF9A431C), Color(0x33A86D32), Color(0x55638BA8),
    )
    ReaderTheme.Light -> ReaderPalette(
        Color(0xFFF8F9FA), Color(0xFF16181C), Color(0xFF606873), Color(0xFFD4D9DF),
        Color(0xFFC43D0E), Color(0x22FF5A1F), Color(0x445B7CFF),
    )
}

enum class ReaderFollowEvent { ManualScroll, BackToCurrent, ChapterChanged, PassiveUpdate }

/** Pure follow-state reducer: passive playback updates can never steal control back. */
fun readerFollowAfter(current: Boolean, event: ReaderFollowEvent): Boolean = when (event) {
    ReaderFollowEvent.ManualScroll -> false
    ReaderFollowEvent.BackToCurrent, ReaderFollowEvent.ChapterChanged -> true
    ReaderFollowEvent.PassiveUpdate -> current
}

fun readerAutoScrollOffsetPx(viewportHeightPx: Int): Int =
    if (viewportHeightPx <= 0) 0 else -(viewportHeightPx / 3)

fun readerShouldPrefetch(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs.coerceAtLeast(0L).toDouble() / durationMs >= 0.8

/** Where a bookmark made in the reader points, and what it ends up called. */
data class ReaderBookmarkAnchor(val positionMs: Long, val label: String?)

/**
 * How long a sentence may be before it is elided into a label.
 *
 * A label is a row in a list, not the passage itself — the note field is where a whole paragraph
 * belongs. Well under the server's 512-character limit on purpose: the truncation the user sees
 * should be this one, made for readability, rather than a silent one made for a database column.
 */
const val ReaderBookmarkLabelChars: Int = 160

/**
 * Where a mark made from the reader points, or null when the reader cannot honestly place one.
 *
 * Pure, because the *judgement* is the interesting part and it has three distinct cases. Reading a
 * chapter that is not playing, or one whose timings do not match the audio, gives no position at
 * all — a bookmark at 0:00 on a chapter somebody was reading at leisure is worse than no bookmark,
 * and this is the same rule that already disables highlighting.
 *
 * Given a position, the *sentence start* beats the millisecond the button was pressed: a listener
 * marking a passage means the passage, not the syllable. Falling back to the raw position covers
 * the gaps between cues, where there is no sentence to anchor to.
 */
fun readerBookmarkAnchor(
    isPlayingThisChapter: Boolean,
    timingsMatch: Boolean,
    positionMs: Long,
    sentenceStartSeconds: Double?,
    sentenceText: String?,
): ReaderBookmarkAnchor? {
    if (!isPlayingThisChapter || !timingsMatch) return null
    val anchored = sentenceStartSeconds
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { (it * 1000.0).roundToLong() }
        ?: positionMs
    val label = sentenceText?.trim()?.replace(WhitespaceRun, " ")?.takeIf { it.isNotEmpty() }?.let {
        if (it.length <= ReaderBookmarkLabelChars) it else it.take(ReaderBookmarkLabelChars).trimEnd() + "…"
    }
    return ReaderBookmarkAnchor(anchored.coerceAtLeast(0L), label)
}

/** Narration text is laid out with newlines in it; a label is one line. */
private val WhitespaceRun = Regex("\\s+")

/** Audio-linked reader that remains useful as a plain, selectable document with no playback. */
@Composable
fun ReaderScreen(
    chapterId: Int,
    fallbackTitle: String,
    cache: ReadAlongCache,
    preferences: ReaderPreferencesStore,
    playback: PlaybackController,
    /** Capability-gated like everywhere else: no control at all without the server API. */
    bookmarksAvailable: Boolean = false,
    onAddBookmark: (positionMs: Long, label: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onChapterAdvanced: (chapterId: Int, title: String) -> Unit,
) {
    val player by playback.state.collectAsState()
    val prefs by preferences.preferences.collectAsState()
    val palette = remember(prefs.theme) { readerPalette(prefs.theme) }
    val scope = rememberCoroutineScope()

    var document by remember(chapterId) { mutableStateOf<ReadAlongDocument?>(null) }
    var loading by remember(chapterId) { mutableStateOf(true) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    var reload by remember(chapterId) { mutableIntStateOf(0) }

    LaunchedEffect(chapterId, reload) {
        loading = true
        error = null
        runCatching { cache.load(chapterId) }
            .onSuccess { document = it }
            .onFailure { error = userFacingMessage(it, "Could not load chapter text") }
        loading = false
    }

    val currentItem = player.queue.getOrNull(player.currentIndex)
    val currentChapterId = currentItem?.chapterId ?: 0
    val isPlayingThisChapter = currentChapterId == chapterId

    // Opening a non-playing chapter must never jump to whatever happens to be playing. Once this
    // reader has followed its own playing chapter, however, queue auto-advance carries it forward.
    var followsQueue by remember(chapterId) { mutableStateOf(isPlayingThisChapter) }
    LaunchedEffect(currentChapterId, chapterId) {
        when {
            currentChapterId == chapterId -> followsQueue = true
            followsQueue && currentChapterId > 0 -> {
                followsQueue = false
                onChapterAdvanced(currentChapterId, currentItem?.title ?: "Chapter")
            }
        }
    }

    var prefetchedChapter by remember(chapterId) { mutableIntStateOf(0) }
    LaunchedEffect(chapterId, player.positionMs, player.durationMs, currentChapterId) {
        if (!isPlayingThisChapter || !readerShouldPrefetch(player.positionMs, player.durationMs)) {
            return@LaunchedEffect
        }
        val next = player.queue.getOrNull(player.currentIndex + 1)?.chapterId ?: return@LaunchedEffect
        if (next <= 0 || next == prefetchedChapter) return@LaunchedEffect
        prefetchedChapter = next
        cache.prefetch(next)
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        when {
            loading && document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }

            document == null && error != null -> ReaderFailure(error.orEmpty()) { reload++ }

            document == null -> ReaderEmptyState(fallbackTitle, palette)

            else -> ReaderDocumentPage(
                document = requireNotNull(document),
                player = player,
                isPlayingThisChapter = isPlayingThisChapter,
                playback = playback,
                prefs = prefs,
                palette = palette,
                bookmarksAvailable = bookmarksAvailable,
                onAddBookmark = onAddBookmark,
                onBack = onBack,
                onOpenSettings = { showSettings = true },
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            prefs = prefs,
            onDismiss = { showSettings = false },
            onChange = { transform -> preferences.update(transform) },
        )
    }
}

@Composable
private fun ReaderDocumentPage(
    document: ReadAlongDocument,
    player: PlayerUiState,
    isPlayingThisChapter: Boolean,
    playback: PlaybackController,
    prefs: ReaderPreferences,
    palette: ReaderPalette,
    bookmarksAvailable: Boolean,
    onAddBookmark: (positionMs: Long, label: String?) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var followPlayback by rememberSaveable(document.chapterId) { mutableStateOf(true) }
    val timingsMatch = !isPlayingThisChapter || readAlongTimingsMatch(
        document.audioDurationSeconds,
        player.durationMs,
    )
    val highlight = remember(document, player.positionMs, isPlayingThisChapter, timingsMatch) {
        if (isPlayingThisChapter && timingsMatch) document.highlightAtMillis(player.positionMs)
        else ReadAlongHighlight.None
    }
    val activeParagraph = highlight.word?.let { document.paragraphIndexAt(it.start) } ?: -1

    val bookmarkAnchor = remember(document, highlight, isPlayingThisChapter, timingsMatch, player.positionMs) {
        readerBookmarkAnchor(
            isPlayingThisChapter = isPlayingThisChapter,
            timingsMatch = timingsMatch,
            positionMs = player.positionMs,
            sentenceStartSeconds = highlight.sentence?.let { document.seekSecondsForOffset(it.start) },
            sentenceText = highlight.sentence?.let { document.textIn(it) },
        )
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            }
        }
    }

    suspend fun scrollToActive() {
        if (activeParagraph < 0) return
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        listState.animateScrollToItem(activeParagraph + 1, readerAutoScrollOffsetPx(viewport))
    }

    LaunchedEffect(activeParagraph, followPlayback) {
        if (followPlayback) scrollToActive()
    }

    var findOpen by rememberSaveable(document.chapterId) { mutableStateOf(false) }
    var query by rememberSaveable(document.chapterId) { mutableStateOf("") }
    val matches = remember(document.text, query) { readAlongMatches(document.text, query) }
    var activeMatch by remember(document.chapterId, query) { mutableIntStateOf(0) }
    val shownMatch = activeMatch.coerceIn(0, matches.lastIndex.coerceAtLeast(0))
    val activeMatchSpan = matches.getOrNull(shownMatch)

    LaunchedEffect(activeMatchSpan) {
        val match = activeMatchSpan ?: return@LaunchedEffect
        val paragraph = document.paragraphIndexAt(match.start)
        if (paragraph >= 0) {
            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            listState.animateScrollToItem(paragraph + 1)
        }
    }

    fun seekToOffset(offset: Int) {
        if (!isPlayingThisChapter || !timingsMatch) return
        val seconds = document.seekSecondsForOffset(offset) ?: return
        playback.seekTo((seconds * 1000.0).roundToLong())
        followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.BackToCurrent)
    }

    fun handleKey(key: Key): Boolean = when (key) {
        Key.PageUp -> {
            scope.launch { listState.scrollBy(-listState.layoutInfo.viewportSize.height.toFloat()) }
            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            true
        }
        Key.PageDown -> {
            scope.launch { listState.scrollBy(listState.layoutInfo.viewportSize.height.toFloat()) }
            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            true
        }
        Key.MoveHome -> {
            scope.launch { listState.scrollToItem(0) }
            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            true
        }
        Key.MoveEnd -> {
            scope.launch { listState.scrollToItem(document.paragraphs.size) }
            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
            true
        }
        else -> false
    }

    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.isCtrlPressed && event.key == Key.F) {
                    findOpen = true
                    true
                } else {
                    handleKey(event.key)
                }
            },
    ) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxSize().widthIn(max = 920.dp)) {
            ReaderToolbar(
                title = document.title.ifBlank { "Chapter" },
                palette = palette,
                // Present but inert while reading a chapter that is not playing: hiding it as the
                // narration catches up would move the other toolbar buttons under the cursor.
                bookmarkEnabled = bookmarksAvailable && bookmarkAnchor != null,
                showBookmark = bookmarksAvailable,
                onBookmark = {
                    bookmarkAnchor?.let { onAddBookmark(it.positionMs, it.label) }
                },
                onBack = onBack,
                onFind = { findOpen = !findOpen },
                onSettings = onOpenSettings,
            )
            if (findOpen) {
                ReaderFindBar(
                    query = query,
                    onQuery = { query = it; activeMatch = 0 },
                    result = if (matches.isEmpty()) "No matches" else "${shownMatch + 1} of ${matches.size}",
                    onPrevious = {
                        if (matches.isNotEmpty()) activeMatch = (shownMatch - 1 + matches.size) % matches.size
                    },
                    onNext = {
                        if (matches.isNotEmpty()) activeMatch = (shownMatch + 1) % matches.size
                    },
                    onClose = { findOpen = false; query = "" },
                    palette = palette,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 14.dp)
                        .testTag(ReaderListTestTag)
                        .onPointerEvent(PointerEventType.Scroll) {
                            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
                        },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = PageGutter,
                        end = PageGutter,
                        top = 20.dp,
                        bottom = 60.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(key = "reader-heading") {
                        ReaderHeading(document, isPlayingThisChapter, timingsMatch, palette)
                    }
                    itemsIndexed(
                        document.paragraphs,
                        key = { index, span -> "paragraph-$index-${span.start}" },
                    ) { _, paragraph ->
                        ReaderParagraph(
                            document = document,
                            paragraph = paragraph,
                            highlight = highlight,
                            matches = matches,
                            activeMatch = activeMatchSpan,
                            prefs = prefs,
                            palette = palette,
                            onSeek = ::seekToOffset,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .onPointerEvent(PointerEventType.Press) {
                            followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.ManualScroll)
                        },
                )
            }
        }

        if (!followPlayback && activeParagraph >= 0) {
            OutlinedButton(
                onClick = {
                    followPlayback = readerFollowAfter(followPlayback, ReaderFollowEvent.BackToCurrent)
                    scope.launch { scrollToActive() }
                },
                shape = RectangleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                    .background(palette.background).pointerHoverIcon(PointerIcon.Hand),
            ) { Text("BACK TO CURRENT", color = palette.accent) }
        }
    }
}

@Composable
private fun ReaderToolbar(
    title: String,
    palette: ReaderPalette,
    bookmarkEnabled: Boolean,
    showBookmark: Boolean,
    onBookmark: () -> Unit,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.ink)
        }
        Text(title, color = palette.ink, maxLines = 1, modifier = Modifier.weight(1f))
        if (showBookmark) {
            IconButton(
                onClick = onBookmark,
                enabled = bookmarkEnabled,
                modifier = Modifier.testTag(ReaderBookmarkButtonTestTag),
            ) {
                Icon(
                    Icons.Default.BookmarkAdd,
                    "Bookmark this passage",
                    tint = if (bookmarkEnabled) palette.ink else palette.muted,
                )
            }
        }
        IconButton(onClick = onFind) { Icon(Icons.Default.Search, "Find in chapter", tint = palette.ink) }
        IconButton(onClick = onSettings, modifier = Modifier.testTag(ReaderSettingsButtonTestTag)) {
            Icon(Icons.Default.Settings, "Reading settings", tint = palette.ink)
        }
    }
    HorizontalDivider(color = palette.line)
}

@Composable
private fun ReaderFindBar(
    query: String,
    onQuery: (String) -> Unit,
    result: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    palette: ReaderPalette,
) {
    val requester = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Find in chapter") },
            modifier = Modifier.weight(1f).focusRequester(requester).testTag(ReaderFindFieldTestTag),
        )
        MetaText(result, color = palette.muted)
        TextButton(onClick = onPrevious) { Text("PREV") }
        TextButton(onClick = onNext) { Text("NEXT") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close find", tint = palette.ink) }
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
}

@Composable
private fun ReaderHeading(
    document: ReadAlongDocument,
    isPlayingThisChapter: Boolean,
    timingsMatch: Boolean,
    palette: ReaderPalette,
) {
    Column {
        MetaText(
            if (document.hasReliableTimings) "// Read along" else "// Text only",
            color = palette.accent,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            document.title.ifBlank { "Chapter" },
            color = palette.ink,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val status = when {
            document.timingState == ReadAlongTimingState.Malformed ->
                "The timing data is malformed, so highlighting is disabled."
            !timingsMatch -> "The timing data does not match this audio version, so highlighting is disabled."
            !document.hasReliableTimings -> "This chapter has narration text but no word timings."
            !isPlayingThisChapter -> "Play this chapter to follow the narration."
            else -> null
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = palette.line)
    }
}

@Composable
private fun ReaderParagraph(
    document: ReadAlongDocument,
    paragraph: TextSpan,
    highlight: ReadAlongHighlight,
    matches: List<TextSpan>,
    activeMatch: TextSpan?,
    prefs: ReaderPreferences,
    palette: ReaderPalette,
    onSeek: (Int) -> Unit,
) {
    val sentence = highlight.sentence?.takeIf { prefs.highlight == ReaderHighlight.Sentence && it.overlaps(paragraph) }
    val word = highlight.word?.takeIf { prefs.highlight != ReaderHighlight.Off && it.overlaps(paragraph) }
    val localMatches = matches.filter { it.overlaps(paragraph) }
    val annotated = remember(paragraph, sentence, word, localMatches, activeMatch, palette) {
        buildAnnotatedString {
            append(document.textIn(paragraph))
            sentence?.let { addReaderStyle(SpanStyle(background = palette.sentenceBand), paragraph, it) }
            localMatches.forEach { match ->
                addReaderStyle(
                    SpanStyle(
                        background = palette.findBand,
                        fontWeight = if (match == activeMatch) FontWeight.Bold else FontWeight.Normal,
                    ),
                    paragraph,
                    match,
                )
            }
            word?.let {
                addReaderStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold), paragraph, it)
            }
        }
    }
    var layout by remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            text = annotated,
            color = palette.ink,
            style = TextStyle(
                fontSize = prefs.fontSize.sp,
                lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
            ),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ReaderParagraphTestTag)
                .semantics { contentDescription = document.textIn(paragraph) }
                .pointerInput(paragraph) {
                    detectTapGestures { position ->
                        val local = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                        onSeek(paragraph.start + local)
                    }
                },
        )
    }
}

private fun AnnotatedString.Builder.addReaderStyle(style: SpanStyle, paragraph: TextSpan, span: TextSpan) {
    val start = (span.start - paragraph.start).coerceIn(0, paragraph.length)
    val end = (span.end - paragraph.start).coerceIn(0, paragraph.length)
    if (end > start) addStyle(style, start, end)
}

@Composable
private fun ReaderFailure(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(PageGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, shape = RectangleShape) { Text("RETRY") }
    }
}

@Composable
private fun ReaderEmptyState(title: String, palette: ReaderPalette) {
    Column(
        Modifier.fillMaxSize().padding(PageGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MetaText("// No narration text", color = palette.accent)
        Spacer(Modifier.height(8.dp))
        Text(title, color = palette.ink, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("This chapter has no narration text available.", color = palette.muted)
    }
}

@Composable
private fun ReaderSettingsDialog(
    prefs: ReaderPreferences,
    onDismiss: () -> Unit,
    onChange: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ReaderNumberControl(
                    label = "Font size",
                    value = "${prefs.fontSize.roundToInt()} pt",
                    canDecrease = prefs.fontSize > ReaderPreferences.MinFontSize,
                    canIncrease = prefs.fontSize < ReaderPreferences.MaxFontSize,
                    onDecrease = { onChange { it.copy(fontSize = it.fontSize - 1.0) } },
                    onIncrease = { onChange { it.copy(fontSize = it.fontSize + 1.0) } },
                )
                ReaderNumberControl(
                    label = "Line height",
                    value = String.format(java.util.Locale.ROOT, "%.1f", prefs.lineHeight),
                    canDecrease = prefs.lineHeight > ReaderPreferences.MinLineHeight,
                    canIncrease = prefs.lineHeight < ReaderPreferences.MaxLineHeight,
                    onDecrease = { onChange { it.copy(lineHeight = it.lineHeight - 0.1) } },
                    onIncrease = { onChange { it.copy(lineHeight = it.lineHeight + 0.1) } },
                )
                MetaText("// Theme", color = AarisColor.Accent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTheme.entries.forEach { theme ->
                        ReaderChoice(theme.label, theme == prefs.theme) { onChange { it.copy(theme = theme) } }
                    }
                }
                MetaText("// Highlight", color = AarisColor.Accent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderHighlight.entries.forEach { mode ->
                        ReaderChoice(mode.label, mode == prefs.highlight) { onChange { it.copy(highlight = mode) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } },
        shape = RectangleShape,
    )
}

@Composable
private fun ReaderNumberControl(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            MetaText(value)
        }
        IconButton(onClick = onDecrease, enabled = canDecrease) { Icon(Icons.Default.Remove, "Decrease $label") }
        IconButton(onClick = onIncrease, enabled = canIncrease) { Icon(Icons.Default.Add, "Increase $label") }
    }
}

@Composable
private fun ReaderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label.uppercase(),
        color = if (selected) AarisColor.Accent else AarisColor.Muted,
        modifier = Modifier
            .border(1.dp, if (selected) AarisColor.Accent else AarisColor.Line)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}
