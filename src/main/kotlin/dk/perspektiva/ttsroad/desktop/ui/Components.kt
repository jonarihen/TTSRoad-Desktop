package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

// Layout tokens mirrored from the web app's aaris.css so the desktop client reads as the same
// product: `.container` centers content at max-width 1280px with 28px gutters; player/forms cap
// at 560px. Content is centered rather than stretched edge-to-edge across a wide desktop window.
val ContentMaxWidth = 1200.dp
val NarrowMaxWidth = 560.dp
val PageGutter = 28.dp

@Composable
fun SectionTitle(kicker: String, title: String) {
    Column(Modifier.fillMaxWidth()) {
        MetaText(text = "§ $kicker", color = AarisColor.Accent)
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

/**
 * Hairline progress bar drawn AARIS-style (square, accent-on-line) — used on cover art and the
 * now-playing bar, where Material's LinearProgressIndicator (rounded caps, stop gap) looks wrong.
 */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.height(height).background(AarisColor.Line)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(AarisColor.Accent),
        )
    }
}

/** Cover art with the bordered-tile fallback letter while loading / when there is no cover. */
@Composable
fun CoverImage(fallback: String, coverUrl: String?, modifier: Modifier, bordered: Boolean = true) {
    Box(
        modifier
            .background(AarisColor.BgInput)
            .let { if (bordered) it.border(1.dp, AarisColor.Line) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            SubcomposeAsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                val painterState by painter.state.collectAsState()
                if (painterState is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    CoverFallbackLetter(fallback)
                }
            }
        } else {
            CoverFallbackLetter(fallback)
        }
    }
}

@Composable
private fun CoverFallbackLetter(fallback: String) {
    Text(
        fallback.trim().take(1).uppercase().ifBlank { "T" },
        style = MaterialTheme.typography.headlineSmall,
        color = AarisColor.Accent,
    )
}

@Composable
fun CenterProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = AarisColor.Accent)
}

/**
 * The one failure that earns the whole screen: there is nothing cached to show behind it.
 *
 * Always paired with a retry, because an error with no way to act on it is a dead end.
 */
@Composable
fun InitialErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(PageGutter), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(text = "// Could not load", color = AarisColor.Accent)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onRetry,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("RETRY") }
        }
    }
}

/** Nothing is wrong, there is simply nothing here. */
@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = AarisColor.Muted)
        MetaText(detail, color = AarisColor.Dim)
    }
}

/**
 * The non-destructive refresh report.
 *
 * A refresh that fails while content is on screen gets this strip, never a full-page error — and
 * it says *when* what you are looking at was actually fetched. Labelling stale content as current
 * is the specific dishonesty this exists to prevent.
 */
@Composable
fun StaleContentBanner(message: String, lastSuccessMillis: Long?, nowMillis: Long, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AarisColor.BgRaise)
            .border(1.dp, AarisColor.Warning)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$message. Showing content from ${formatLastUpdated(lastSuccessMillis, nowMillis)}."
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AarisColor.Warning)
            MetaText(
                "Showing content from ${formatLastUpdated(lastSuccessMillis, nowMillis)}",
                color = AarisColor.Dim,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = onRetry,
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("RETRY") }
    }
}

/** Hairline "a refresh is running" strip, shown above content rather than instead of it. */
@Composable
fun RefreshingStrip(visible: Boolean) {
    if (visible) ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
}

/**
 * How old the content on screen is, in words.
 *
 * Pure and coarse: minute granularity is all a human needs to decide whether to press Refresh, and
 * a pure function over an injected `now` is what makes it assertable without freezing the clock.
 */
fun formatLastUpdated(lastSuccessMillis: Long?, nowMillis: Long): String {
    if (lastSuccessMillis == null) return "an earlier session"
    val elapsed = nowMillis - lastSuccessMillis
    // Clock skew or a value from the future: "just now" is the honest reading, not a negative age.
    if (elapsed < 60_000L) return "just now"
    val minutes = elapsed / 60_000L
    if (minutes < 60L) return "$minutes ${plural(minutes, "minute")} ago"
    val hours = minutes / 60L
    if (hours < 24L) return "$hours ${plural(hours, "hour")} ago"
    val days = hours / 24L
    return "$days ${plural(days, "day")} ago"
}

private fun plural(count: Long, noun: String): String = if (count == 1L) noun else "${noun}s"

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
