package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import kotlinx.coroutines.launch

/** Whether the mini-player should be visible: something has been loaded (or tried to load). */
val PlayerUiState.hasSession: Boolean
    get() = hasMedia || durationMs > 0 || error != null

@Composable
fun PlayerScreen(playback: PlaybackController, repository: TtsRoadRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s: PlayerUiState by playback.state.collectAsState()
    val capabilities by repository.capabilities.collectAsState()
    // Track the drag locally and only seek on release — the MP3 backend re-decodes from the
    // start of the file per seek, so seeking on every drag tick would stutter badly.
    var dragMs by remember { mutableStateOf<Float?>(null) }
    var bookmarkNote by remember { mutableStateOf<String?>(null) }
    var bookmarkFailed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(horizontal = PageGutter, vertical = 20.dp)) {
        BackLink("Back", onBack)
        Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = NarrowMaxWidth).fillMaxWidth().fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MetaText(text = "// Now Playing", color = AarisColor.Accent)
                    Box(Modifier.weight(1f).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        CoverImage(s.fictionTitle ?: s.title, s.coverImageUrl, Modifier.height(320.dp).aspectRatio(2f / 3f))
                    }
                    Text(
                        s.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = AarisColor.Ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    s.fictionTitle?.let {
                        Spacer(Modifier.height(8.dp))
                        MetaText(it)
                    }
                    Spacer(Modifier.height(20.dp))
                    Slider(
                        value = dragMs ?: s.positionMs.coerceAtMost(s.durationMs).toFloat(),
                        onValueChange = { dragMs = it },
                        onValueChangeFinished = {
                            dragMs?.let { playback.seekTo(it.toLong()) }
                            dragMs = null
                        },
                        valueRange = 0f..s.durationMs.coerceAtLeast(1L).toFloat(),
                        enabled = s.durationMs > 0L,
                        colors = SliderDefaults.colors(
                            thumbColor = AarisColor.Accent,
                            activeTrackColor = AarisColor.Accent,
                            inactiveTrackColor = AarisColor.Line,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetaText(formatDuration(dragMs?.toLong() ?: s.positionMs))
                        MetaText(formatDuration(s.durationMs))
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TransportButton(Icons.Default.SkipPrevious, "Previous chapter", enabled = s.hasMedia, size = 48.dp) {
                            playback.skipToPreviousChapter()
                        }
                        TransportButton(Icons.Default.Replay30, "Back 30 seconds", enabled = s.hasMedia, size = 48.dp) {
                            playback.skipBy(-30_000)
                        }
                        TransportButton(
                            if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (s.isPlaying) "Pause" else "Play",
                            enabled = s.hasMedia,
                            size = 64.dp,
                            filled = true,
                        ) { playback.togglePlayPause() }
                        TransportButton(Icons.Default.Forward30, "Forward 30 seconds", enabled = s.hasMedia, size = 48.dp) {
                            playback.skipBy(30_000)
                        }
                        TransportButton(Icons.Default.SkipNext, "Next chapter", enabled = s.hasNext, size = 48.dp) {
                            playback.skipToNextChapter()
                        }
                    }
                    // Gated on the server actually having bookmarks — offering a control that
                    // 404s would be worse than not offering it.
                    if (capabilities.bookmarks) {
                        Spacer(Modifier.height(18.dp))
                        val chapterId = s.queue.getOrNull(s.currentIndex)?.chapterId
                        val positionMs = s.positionMs
                        OutlinedButton(
                            onClick = {
                                val target = chapterId ?: return@OutlinedButton
                                scope.launch {
                                    bookmarkFailed = false
                                    bookmarkNote = null
                                    runCatching {
                                        repository.createBookmark(
                                            chapterId = target,
                                            positionSeconds = positionMs / 1000.0,
                                            label = s.title,
                                        )
                                    }.fold(
                                        onSuccess = { bookmarkNote = "Bookmarked at ${formatDuration(positionMs)}" },
                                        onFailure = {
                                            bookmarkFailed = true
                                            bookmarkNote = it.message ?: "Could not save bookmark"
                                        },
                                    )
                                }
                            },
                            enabled = s.hasMedia && chapterId != null,
                            shape = RectangleShape,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("BOOKMARK THIS POSITION")
                        }
                        bookmarkNote?.let {
                            Spacer(Modifier.height(8.dp))
                            MetaText(it, color = if (bookmarkFailed) AarisColor.Danger else AarisColor.Ok)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val error = s.error
                    when {
                        error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                        !s.hasMedia && s.hasSession -> MetaText(text = "Buffering…", color = AarisColor.Dim)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (s.queue.size > 1) {
                Spacer(Modifier.width(24.dp))
                QueuePanel(s, playback, Modifier.width(300.dp).fillMaxHeight())
            }
        }
    }
}

/** Up-next side panel: the loaded fiction's chapters, with the current one highlighted. */
@Composable
private fun QueuePanel(s: PlayerUiState, playback: PlaybackController, modifier: Modifier) {
    Column(modifier.border(1.dp, AarisColor.Line).background(AarisColor.BgRaise)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText("// Up next", color = AarisColor.Accent)
            MetaText("${s.currentIndex + 1}/${s.queue.size}", color = AarisColor.Dim)
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(s.queue, key = { index, item -> "${item.chapterId}-$index" }) { index, item ->
                QueueRow(
                    number = index + 1,
                    title = item.title,
                    isCurrent = index == s.currentIndex,
                    isPlaying = s.isPlaying,
                ) { playback.skipToQueueIndex(index) }
                HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
            }
        }
    }
}

@Composable
private fun QueueRow(number: Int, title: String, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(
                when {
                    isCurrent -> AarisColor.BgHover
                    hovered -> AarisColor.BgHover.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("%02d".format(number), color = if (isCurrent) AarisColor.Accent else AarisColor.Dim)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (isCurrent) AarisColor.Accent else if (hovered) AarisColor.Ink else AarisColor.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Spacer(Modifier.width(8.dp))
            MetaText(if (isPlaying) "Playing" else "Paused", color = AarisColor.Accent)
        }
    }
}

/**
 * Persistent bottom bar (Audible/Spotify-style): playback keeps its place in the UI while the
 * user browses. Clicking the track info expands to the full player.
 */
@Composable
fun NowPlayingBar(playback: PlaybackController, onExpand: () -> Unit) {
    val s: PlayerUiState by playback.state.collectAsState()
    val fraction = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
        ThinProgress(fraction, Modifier.fillMaxWidth(), height = 2.dp)
        Box(Modifier.fillMaxWidth().background(AarisColor.BgRaise)) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = PageGutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarTrackInfo(s, Modifier.weight(1f), onExpand)
                Spacer(Modifier.width(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton(Icons.Default.Replay30, "Back 30 seconds", enabled = s.hasMedia, size = 36.dp) {
                        playback.skipBy(-30_000)
                    }
                    TransportButton(
                        if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (s.isPlaying) "Pause" else "Play",
                        enabled = s.hasMedia,
                        size = 44.dp,
                        filled = true,
                    ) { playback.togglePlayPause() }
                    TransportButton(Icons.Default.Forward30, "Forward 30 seconds", enabled = s.hasMedia, size = 36.dp) {
                        playback.skipBy(30_000)
                    }
                    TransportButton(Icons.Default.SkipNext, "Next chapter", enabled = s.hasNext, size = 36.dp) {
                        playback.skipToNextChapter()
                    }
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MetaText("${formatDuration(s.positionMs)} / ${formatDuration(s.durationMs)}", color = AarisColor.Dim)
                }
            }
        }
    }
}

@Composable
private fun BarTrackInfo(s: PlayerUiState, modifier: Modifier, onExpand: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(s.fictionTitle ?: s.title, s.coverImageUrl, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                s.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (hovered) AarisColor.Accent else AarisColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val error = s.error
            when {
                error != null -> MetaText(error, color = AarisColor.Danger)
                !s.hasMedia -> MetaText("Buffering…", color = AarisColor.Dim)
                else -> s.fictionTitle?.let { MetaText(it, color = AarisColor.Dim) }
            }
        }
    }
}

/** Square AARIS transport control: outlined by default, accent-filled for the primary action. */
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    size: Dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        filled && !enabled -> AarisColor.Line
        filled && hovered -> AarisColor.AccentHover
        filled -> AarisColor.Accent
        hovered && enabled -> AarisColor.BgHover
        else -> Color.Transparent
    }
    val tint = when {
        filled -> AarisColor.Bg
        !enabled -> AarisColor.Dim
        hovered -> AarisColor.Ink
        else -> AarisColor.Muted
    }
    Box(
        Modifier
            .size(size)
            .background(background)
            .let { if (filled) it else it.border(1.dp, if (hovered && enabled) AarisColor.Dim else AarisColor.Line) }
            .hoverable(interaction)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size / 2))
    }
}
