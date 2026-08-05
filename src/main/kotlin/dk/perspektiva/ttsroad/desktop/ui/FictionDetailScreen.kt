package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import kotlinx.coroutines.launch

@Composable
fun FictionDetailScreen(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = rememberStateHolder(repository, fiction.id) {
        FictionDetailStateHolder(repository, fiction.id)
    }
    val state by holder.state.collectAsState()
    val actionError by holder.actionError.collectAsState()

    PageScroll {
        BackLink("Library", onBack)
        Spacer(Modifier.height(20.dp))
        when (val s = state) {
            Load.Loading -> {
                FictionHeader(fiction, repository, chapters = emptyList(), onResume = null)
                Spacer(Modifier.height(80.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CenterProgress() }
            }
            is Load.Err -> {
                FictionHeader(fiction, repository, chapters = emptyList(), onResume = null)
                Spacer(Modifier.height(32.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is Load.Ok -> {
                val chapters = s.value.chapters
                // The chapters endpoint returns fresher counts than the library summary.
                FictionHeader(
                    s.value.fiction,
                    repository,
                    chapters = chapters,
                    onResume = { target ->
                        scope.launch { playback.playQueue(chapters, target.resolvedChapterId, s.value.fiction) }
                    },
                )
                actionError?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(36.dp))
                SectionTitle("01", "Chapters — ${chapters.size}")
                Spacer(Modifier.height(8.dp))
                chapters.forEach { chapter ->
                    ChapterListRow(
                        chapter = chapter,
                        onPlay = {
                            scope.launch { playback.playQueue(chapters, chapter.resolvedChapterId, s.value.fiction) }
                        },
                        onMarkPlayed = { played ->
                            holder.setPlayed(chapter.resolvedChapterId, played)
                        },
                    )
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
                }
            }
        }
    }
}

@Composable
fun BackLink(label: String, onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText("← $label", color = if (hovered) AarisColor.Ink else AarisColor.Muted)
    }
}

/** Best chapter to resume: furthest in-progress one, else the first playable. */
private fun resumeTarget(chapters: List<ChapterSummary>): ChapterSummary? =
    chapters.filter { it.audio != null && it.resolvedPositionSeconds > 0.0 && it.playback?.isPlayed != true }
        .maxByOrNull { it.resolvedPositionSeconds }
        ?: chapters.firstOrNull { it.audio != null && it.playback?.isPlayed != true }
        ?: chapters.firstOrNull { it.audio != null }

@Composable
private fun FictionHeader(
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    chapters: List<ChapterSummary>,
    onResume: ((ChapterSummary) -> Unit)?,
) {
    Row(Modifier.fillMaxWidth()) {
        CoverImage(
            fiction.title,
            fiction.coverImageUrl?.let(repository::resolveUrl),
            Modifier.width(190.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            MetaText(text = "// Fiction", color = AarisColor.Accent)
            Spacer(Modifier.height(8.dp))
            Text(
                fiction.title,
                style = MaterialTheme.typography.displaySmall,
                color = AarisColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            fiction.author?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                MetaText("by $it")
            }
            fiction.rating?.let { rating ->
                Spacer(Modifier.height(6.dp))
                MetaText(
                    buildString {
                        append("★ ")
                        append("%.2f".format(rating))
                        fiction.ratingCount?.takeIf { it > 0 }?.let { append("  ·  $it ratings") }
                    },
                    color = AarisColor.Warning,
                )
            }
            if (fiction.tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fiction.tags.take(5).forEach { AarisTag(it) }
                }
            }
            fiction.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Muted,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            ThinProgress(fiction.readyFraction, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            MetaText(
                buildString {
                    append("${fiction.doneChapters}/${fiction.totalChapters} chapters ready")
                    if (fiction.processingChapters > 0) append("  ·  ${fiction.processingChapters} processing")
                    if (fiction.errorChapters > 0) append("  ·  ${fiction.errorChapters} failed")
                },
                color = AarisColor.Dim,
            )
            val target = remember(chapters) { resumeTarget(chapters) }
            if (onResume != null && target != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onResume(target) },
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (target.resolvedPositionSeconds > 0.0) "RESUME" else "START LISTENING")
                }
            }
        }
    }
}

@Composable
private fun ChapterListRow(
    chapter: ChapterSummary,
    onPlay: () -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
) {
    val playable = chapter.audio != null
    val isPlayed = chapter.playback?.isPlayed == true
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .let { if (playable) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = playable, onClick = onPlay)
            .background(if (hovered && playable) AarisColor.BgRaise else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaText(chapterNumberLabel(chapter), color = AarisColor.Dim, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chapter.resolvedTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (playable) AarisColor.Ink else AarisColor.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                chapter.audioDurationLabel,
                chapter.playback?.remainingLabel?.let { "$it left" }
                    ?: chapter.resumeTimeLabel?.let { "$it in" },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                MetaText(meta, color = AarisColor.Dim)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!playable) {
            AarisTag(chapter.status ?: "pending")
        } else {
            // Played-check toggle: always visible once played; revealed on hover otherwise.
            if (isPlayed || hovered) {
                RowIconAction(
                    icon = Icons.Default.Check,
                    contentDescription = if (isPlayed) "Mark unplayed" else "Mark played",
                    tint = if (isPlayed) AarisColor.Ok else AarisColor.Dim,
                ) { onMarkPlayed(!isPlayed) }
            }
            if (hovered) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(30.dp).background(AarisColor.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = AarisColor.Bg, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Borderless hover-reveal icon action used inside list rows. */
@Composable
private fun RowIconAction(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(30.dp)
            .background(if (hovered) AarisColor.BgHover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (hovered) AarisColor.Ink else tint, modifier = Modifier.size(18.dp))
    }
}

private fun chapterNumberLabel(chapter: ChapterSummary): String {
    val n = chapter.displayNumber ?: return "—"
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}
