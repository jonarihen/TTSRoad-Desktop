package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.player.SleepTimerMode
import dk.perspektiva.ttsroad.desktop.player.SleepTimerState
import dk.perspektiva.ttsroad.desktop.player.formatRemaining

/** Whether the mini-player should be visible: something has been loaded (or tried to load). */
val PlayerUiState.hasSession: Boolean
    get() = hasMedia || durationMs > 0 || error != null

@Composable
fun PlayerScreen(
    playback: PlaybackController,
    sizeClass: WindowSizeClass = WindowSizeClass.Expanded,
    /**
     * Listening settings, for the one control the player owns that is a *preference* rather than
     * transport state: skip silence. Defaulted to an in-memory store so a screen test never writes
     * to the real config directory.
     */
    preferences: PlaybackPreferencesStore = remember { InMemoryPlaybackPreferencesStore() },
    readAlongAvailable: Boolean = false,
    onOpenReader: (chapterId: Int, title: String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val s: PlayerUiState by playback.state.collectAsState()
    val compact = sizeClass.isCompact
    val hasQueue = s.queue.size > 1

    Box(Modifier.fillMaxSize().padding(horizontal = PageGutter, vertical = 20.dp)) {
        BackLink("Back", onBack)
        if (compact) {
            // Narrow: the up-next panel stops being a side panel and becomes a section under the
            // transport, inside one scroll container. The transport itself never shrinks — clipped
            // play/skip buttons are the one thing a player must not do.
            Column(
                Modifier.fillMaxSize().padding(top = 28.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerMain(
                    s,
                    playback,
                    preferences,
                    compact = true,
                    readAlongAvailable = readAlongAvailable,
                    onOpenReader = onOpenReader,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasQueue) {
                    Spacer(Modifier.height(20.dp))
                    QueuePanel(s, playback, Modifier.fillMaxWidth().height(260.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PlayerMain(
                        s,
                        playback,
                        preferences,
                        compact = false,
                        readAlongAvailable = readAlongAvailable,
                        onOpenReader = onOpenReader,
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = NarrowMaxWidth)
                            .fillMaxWidth().fillMaxHeight(),
                    )
                }
                if (hasQueue) {
                    Spacer(Modifier.width(24.dp))
                    QueuePanel(s, playback, Modifier.width(300.dp).fillMaxHeight())
                }
            }
        }
    }
}

/** Cover, title, scrubber and transport. Identical controls at every width — only the sizing moves. */
@Composable
private fun PlayerMain(
    s: PlayerUiState,
    playback: PlaybackController,
    preferences: PlaybackPreferencesStore,
    compact: Boolean,
    readAlongAvailable: Boolean,
    onOpenReader: (chapterId: Int, title: String) -> Unit,
    modifier: Modifier,
) {
    val prefs by preferences.preferences.collectAsState()
    // Track the drag locally and only seek on release — the MP3 backend re-decodes from the
    // start of the file per seek, so seeking on every drag tick would stutter badly.
    var dragMs by remember { mutableStateOf<Float?>(null) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MetaText(text = "// Now Playing", color = AarisColor.Accent)
        val cover: @Composable () -> Unit = {
            CoverImage(
                s.fictionTitle ?: s.title,
                s.coverImageUrl,
                Modifier.height(if (compact) 200.dp else 320.dp).aspectRatio(2f / 3f),
            )
        }
        if (compact) {
            Box(Modifier.padding(vertical = 20.dp)) { cover() }
        } else {
            Box(Modifier.weight(1f).padding(vertical = 20.dp), contentAlignment = Alignment.Center) { cover() }
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
        val chapterId = s.queue.getOrNull(s.currentIndex)?.chapterId ?: 0
        if (readAlongAvailable && chapterId > 0) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onOpenReader(chapterId, s.title) },
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("READ ALONG")
            }
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(formatDuration(dragMs?.toLong() ?: s.positionMs))
            // Follows the drag rather than the clock, so scrubbing answers "how much is left if I
            // drop it here" while the thumb is still down.
            remainingLabel(dragMs?.toLong() ?: s.positionMs, s.durationMs, s.speed)?.let {
                MetaText(it, color = AarisColor.Dim, modifier = Modifier.testTag(RemainingLabelTestTag))
            }
            MetaText(formatDuration(s.durationMs))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Icons.Default.SkipPrevious, "Previous chapter", enabled = s.hasMedia, size = 48.dp) {
                playback.skipToPreviousChapter()
            }
            val skipSeconds = (s.skipIntervalMs / 1000).toInt()
            TransportButton(
                rewindIconFor(skipSeconds),
                "Back $skipSeconds seconds",
                enabled = s.hasMedia,
                size = 48.dp,
            ) { playback.skipBackward() }
            TransportButton(
                if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (s.isPlaying) "Pause" else "Play",
                enabled = s.hasMedia,
                size = 64.dp,
                filled = true,
            ) { playback.togglePlayPause() }
            TransportButton(
                forwardIconFor(skipSeconds),
                "Forward $skipSeconds seconds",
                enabled = s.hasMedia,
                size = 48.dp,
            ) { playback.skipForward() }
            TransportButton(Icons.Default.SkipNext, "Next chapter", enabled = s.hasNext, size = 48.dp) {
                playback.skipToNextChapter()
            }
        }
        // Drawn only when the backend can honour it. The Java Sound fallback reports it cannot, and
        // then there is no control at all rather than one that silently does nothing.
        if (s.canChangeSpeed) {
            Spacer(Modifier.height(18.dp))
            SpeedControl(current = s.speed, enabled = s.hasMedia) { playback.setSpeed(it) }
        }
        // Skip-silence belongs next to speed rather than only in Settings: both change how long
        // the chapter takes, and a listener reaching for one is usually reaching for the other.
        // Same capability rule — absent entirely where the backend cannot do it.
        if (s.canSkipSilence) {
            Spacer(Modifier.height(10.dp))
            SkipSilenceToggle(
                checked = prefs.skipSilence,
                enabled = s.hasMedia,
                onToggle = { value -> preferences.update { it.copy(skipSilence = value) } },
            )
        }
        Spacer(Modifier.height(18.dp))
        SleepTimerControl(
            state = s.sleepTimer,
            enabled = s.hasMedia,
            onArm = playback::setSleepTimer,
            onExtend = playback::extendSleepTimer,
        )
        Spacer(Modifier.height(12.dp))
        val error = s.error
        when {
            error != null -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
                // Only after the automatic 2s/5s/15s attempts are spent — before that the
                // controller is already retrying and a button would just race it.
                if (s.canRetry) {
                    Text(
                        "Retry",
                        color = AarisColor.Accent,
                        modifier = Modifier
                            .testTag(RetryButtonTestTag)
                            .clickable { playback.retry() }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            !s.hasMedia && s.hasSession -> MetaText(text = "Buffering…", color = AarisColor.Dim)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The presets issue #4 names, over the 0.5x-3.0x range the GStreamer backend reports. */
private val SpeedPresets = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

const val SpeedChipTestTag = "player-speed-chip"
const val RetryButtonTestTag = "player-retry"

@Composable
private fun SpeedControl(current: Float, enabled: Boolean, onSelect: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MetaText("Speed", color = AarisColor.Dim)
        SpeedPresets.forEach { preset ->
            // Compared with a tolerance, not ==: the value shown is the one the engine accepted,
            // which can be a clamped float rather than the exact preset that was asked for.
            val isCurrent = kotlin.math.abs(current - preset) < 0.01f
            Text(
                text = formatSpeed(preset),
                color = when {
                    !enabled -> AarisColor.Dim
                    isCurrent -> AarisColor.Accent
                    else -> AarisColor.Muted
                },
                modifier = Modifier
                    .testTag(SpeedChipTestTag)
                    .clickable(enabled = enabled) { onSelect(preset) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

const val RemainingLabelTestTag = "player-remaining"

/**
 * "-12:34", and above or below 1x what that costs in wall-clock time: "-12:34  ·  8:13 at 1.5x".
 *
 * The second half is the number that is actually useful to anyone not listening at 1x, and it is
 * omitted at 1x because there it would only restate the first. Null when there is no duration to
 * subtract from — a chapter still opening has no honest answer, and "-0:00" would look like one.
 */
internal fun remainingLabel(positionMs: Long, durationMs: Long, speed: Float): String? {
    if (durationMs <= 0L) return null
    val remaining = (durationMs - positionMs).coerceIn(0L, durationMs)
    val label = "-${formatDuration(remaining)}"
    // A speed the engine has not reported yet arrives as 0; dividing by it would render infinity.
    if (speed <= 0f || kotlin.math.abs(speed - 1f) < 0.01f) return label
    return "$label  ·  ${formatDuration((remaining / speed).toLong())} at ${formatSpeed(speed)}"
}

/** "1x", "1.25x" — no trailing zeros, because "1.00x" reads like a measurement. */
private fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')
    return "${text}x"
}

/**
 * The transport icon matching the configured interval, where Material has one.
 *
 * 15, 45 and 60 have no numbered icon in the set, and a `Replay30` glyph on a button that skips 45
 * seconds is a worse answer than an unnumbered one — the content description carries the number
 * either way, so nothing is lost for a screen reader.
 */
private fun rewindIconFor(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Default.Replay10
    30 -> Icons.Default.Replay30
    else -> Icons.Default.FastRewind
}

private fun forwardIconFor(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Default.Forward10
    30 -> Icons.Default.Forward30
    else -> Icons.Default.FastForward
}

const val SleepTimerChipTestTag = "player-sleep-chip"
const val SleepExtendTestTag = "player-sleep-extend"
const val SkipSilenceTestTag = "player-skip-silence"

/**
 * The sleep timer row: the offered modes, the countdown, and "+5 min" while fading.
 *
 * The extension only appears during the fade, which is when it is actually needed — a permanently
 * visible "+5 min" next to an unarmed timer is a button with no meaning.
 */
@Composable
private fun SleepTimerControl(
    state: SleepTimerState,
    enabled: Boolean,
    onArm: (SleepTimerMode) -> Unit,
    onExtend: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("Sleep", color = AarisColor.Dim)
        SleepChip("Off", selected = !state.isArmed, enabled = enabled) { onArm(SleepTimerMode.Off) }
        SleepTimerMode.OfferedMinutes.forEach { minutes ->
            SleepChip(
                label = "${minutes}m",
                // Compared against the *initial* duration, which extending does not change — so a
                // timer pushed out by "+5 min" still shows which choice it started from.
                selected = state.mode == SleepTimerMode.Duration(minutes),
                enabled = enabled,
            ) { onArm(SleepTimerMode.Duration(minutes)) }
        }
        SleepChip(
            label = "Chapter",
            selected = state.mode == SleepTimerMode.EndOfChapter,
            enabled = enabled,
        ) { onArm(SleepTimerMode.EndOfChapter) }

        if (state.isArmed && state.remainingMs > 0) {
            MetaText(
                text = formatRemaining(state.remainingMs),
                color = if (state.isFading) AarisColor.Warning else AarisColor.Accent,
            )
        }
        if (state.isFading) {
            Text(
                "+5 min",
                style = MaterialTheme.typography.bodyMedium,
                color = AarisColor.Accent,
                modifier = Modifier
                    .testTag(SleepExtendTestTag)
                    .clickable { onExtend() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { contentDescription = "Add five minutes to the sleep timer" }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SleepChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            !enabled -> AarisColor.Dim
            selected -> AarisColor.Accent
            else -> AarisColor.Muted
        },
        modifier = Modifier
            .testTag(SleepTimerChipTestTag)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SkipSilenceToggle(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("Silence", color = AarisColor.Dim)
        Text(
            text = if (checked) "Skipping" else "Keeping",
            style = MaterialTheme.typography.bodyMedium,
            color = if (!enabled) AarisColor.Dim else if (checked) AarisColor.Accent else AarisColor.Muted,
            modifier = Modifier
                .testTag(SkipSilenceTestTag)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = "Skip silence" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Queue length at which the up-next panel grows a search box.
 *
 * A serial with a few chapters is faster to scan than to type into; one with four hundred is not,
 * and scrolling a lazy list looking for "Chapter 217" is exactly the case this phase exists to fix.
 */
const val QueueSearchThreshold: Int = 8

/** Test handle for an up-next row, so a search box holding the same text is not mistaken for one. */
const val QueueRowTestTag: String = "queueRow"

/**
 * Up-next side panel: the loaded queue, with the current chapter highlighted.
 *
 * Rows are labelled with the chapter's own number where the server supplied one, not with the queue
 * position — the queue holds only playable chapters, so the two diverge the moment one chapter in
 * the middle is still converting. Filtering narrows what is listed; it never renumbers or reorders
 * anything, and clicking a filtered row still jumps to that row's real position in the queue.
 */
@Composable
private fun QueuePanel(s: PlayerUiState, playback: PlaybackController, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = s.queue.size >= QueueSearchThreshold
    val rows = remember(s.queue, query, searchable) {
        if (!searchable) {
            s.queue.withIndex().toList()
        } else {
            val q = query.trim()
            if (q.isBlank()) {
                s.queue.withIndex().toList()
            } else {
                s.queue.withIndex().filter { (_, item) -> item.title.contains(q, ignoreCase = true) }
            }
        }
    }

    Column(modifier.border(1.dp, AarisColor.Line).background(AarisColor.BgRaise)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText("// Up next", color = AarisColor.Accent)
            MetaText("${s.currentIndex + 1}/${s.queue.size}", color = AarisColor.Dim)
        }
        if (searchable) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("FIND A CHAPTER") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                MetaText("No matches for \"$query\"", color = AarisColor.Dim)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(rows, key = { _, (index, item) -> "${item.chapterId}-$index" }) { _, (index, item) ->
                QueueRow(
                    label = item.displayNumber?.let(::queueNumberLabel) ?: "%02d".format(index + 1),
                    title = item.title,
                    isCurrent = index == s.currentIndex,
                    isPlaying = s.isPlaying,
                ) { playback.skipToQueueIndex(index) }
                HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
            }
        }
    }
}

private fun queueNumberLabel(number: Double): String =
    if (number % 1.0 == 0.0) "%02d".format(number.toLong()) else number.toString()

@Composable
private fun QueueRow(label: String, title: String, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pointerOver by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered = pointerOver || focused
    Row(
        Modifier
            .testTag(QueueRowTestTag)
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
            .border(1.dp, if (focused) AarisColor.Accent else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(label, color = if (isCurrent) AarisColor.Accent else AarisColor.Dim)
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
fun NowPlayingBar(
    playback: PlaybackController,
    compact: Boolean = false,
    onExpand: () -> Unit,
) {
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
                // The elapsed/total readout is the first thing to go in a narrow window: it is
                // already on the player screen, and keeping it here would squeeze the transport.
                if (!compact) {
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        MetaText("${formatDuration(s.positionMs)} / ${formatDuration(s.durationMs)}", color = AarisColor.Dim)
                    }
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
    // Transport controls are the most likely thing to be driven from the keyboard, so focus has
    // to be as visible here as hover is with a mouse.
    val focused by interaction.collectIsFocusedAsState()
    val active = hovered || focused
    val background = when {
        filled && !enabled -> AarisColor.Line
        filled && active -> AarisColor.AccentHover
        filled -> AarisColor.Accent
        active && enabled -> AarisColor.BgHover
        else -> Color.Transparent
    }
    val tint = when {
        filled -> AarisColor.Bg
        !enabled -> AarisColor.Dim
        active -> AarisColor.Ink
        else -> AarisColor.Muted
    }
    Box(
        Modifier
            .size(size)
            .background(background)
            .border(
                1.dp,
                when {
                    focused && enabled -> AarisColor.Accent
                    filled -> Color.Transparent
                    hovered && enabled -> AarisColor.Dim
                    else -> AarisColor.Line
                },
            )
            .hoverable(interaction)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size / 2))
    }
}
