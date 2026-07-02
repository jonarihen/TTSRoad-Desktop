package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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

/** Async load state for screen-level fetches. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Vertically scrolling page whose content is capped at [maxWidth] and centered, with page gutters. */
@Composable
fun PageScroll(
    maxWidth: Dp = ContentMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(horizontal = PageGutter, vertical = PageGutter),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

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

@Composable
fun CenterError(message: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(message, color = MaterialTheme.colorScheme.error)
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
