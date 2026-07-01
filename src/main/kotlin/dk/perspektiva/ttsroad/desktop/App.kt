package dk.perspektiva.ttsroad.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.Mp3PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlaybackController
import dk.perspektiva.ttsroad.desktop.player.PlayerUiState
import dk.perspektiva.ttsroad.desktop.ui.AarisCard
import dk.perspektiva.ttsroad.desktop.ui.AarisColor
import dk.perspektiva.ttsroad.desktop.ui.MetaText
import kotlinx.coroutines.launch

private enum class Screen { Library, Player, Settings }

// Layout tokens mirrored from the web app's aaris.css so the desktop client reads as the same
// product: `.container` centers content at max-width 1280px with 28px gutters; player/forms cap
// at 560px. Content is centered rather than stretched edge-to-edge across a wide desktop window.
private val ContentMaxWidth = 1200.dp
private val NarrowMaxWidth = 560.dp
private val PageGutter = 28.dp

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Vertically scrolling page whose content is capped at [maxWidth] and centered, with page gutters. */
@Composable
private fun PageScroll(
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
fun App() {
    val sessionStore = remember { SessionStore() }
    val repository = remember { TtsRoadRepository(sessionStore) }
    val playback = remember { Mp3PlaybackController(repository) }
    val session by sessionStore.session.collectAsState()
    var screen by remember { mutableStateOf(Screen.Library) }

    Box(Modifier.fillMaxSize().background(AarisColor.Bg)) {
        if (!session.isLoggedIn) {
            LoginScreen(repository)
        } else {
            Column(Modifier.fillMaxSize()) {
                HeaderBar(
                    serverName = session.serverName,
                    current = screen,
                    onSelect = { screen = it },
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.Library -> LibraryScreen(repository, playback) { screen = Screen.Player }
                        Screen.Player -> PlayerScreen(playback)
                        Screen.Settings -> SettingsScreen(sessionStore, repository)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(serverName: String, current: Screen, onSelect: (Screen) -> Unit) {
    Box(Modifier.fillMaxWidth().background(AarisColor.Bg)) {
        Row(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = PageGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(text = "// $serverName", color = AarisColor.Accent)
            Spacer(Modifier.width(20.dp))
            Text("TTSROAD", style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink)
            Spacer(Modifier.weight(1f))
            Screen.entries.forEach { s ->
                TextButton(onClick = { onSelect(s) }) {
                    Text(
                        text = s.name.uppercase(),
                        color = if (s == current) AarisColor.Accent else AarisColor.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(repository: TtsRoadRepository) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var twoFactor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).verticalScroll(rememberScrollState())) {
            MetaText(text = "// Operator Console", color = AarisColor.Accent)
            Spacer(Modifier.height(6.dp))
            Text("TTSROAD", style = MaterialTheme.typography.displaySmall, color = AarisColor.Ink)
            Spacer(Modifier.height(6.dp))
            MetaText(text = "Connect to your private server")
            Spacer(Modifier.height(24.dp))
            Field("SERVER URL", serverUrl) { serverUrl = it }
            Spacer(Modifier.height(12.dp))
            Field("USERNAME", username) { username = it }
            Spacer(Modifier.height(12.dp))
            Field("PASSWORD", password, password = true) { password = it }
            if (twoFactor) {
                Spacer(Modifier.height(12.dp))
                Field("2FA CODE", totpCode) { totpCode = it }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        when (val r = repository.login(serverUrl, username, password, if (twoFactor) totpCode else null)) {
                            LoginResult.Success -> Unit
                            LoginResult.TotpRequired -> {
                                error = if (twoFactor && totpCode.isNotBlank()) "Invalid authentication code" else null
                                twoFactor = true
                            }
                            is LoginResult.Failure -> error = r.message
                        }
                        busy = false
                    }
                },
                enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
                    (!twoFactor || totpCode.isNotBlank()),
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (twoFactor) "VERIFY" else if (busy) "SIGNING IN" else "SIGN IN")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
    )
}

@Composable
private fun LibraryScreen(
    repository: TtsRoadRepository,
    playback: PlaybackController,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<LibraryResponse>>(Load.Loading) }

    LaunchedEffect(Unit) {
        state = runCatching { repository.library() }
            .fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Could not load library") })
    }

    when (val s = state) {
        Load.Loading -> CenterProgress()
        is Load.Err -> CenterError(s.message)
        is Load.Ok -> {
            val library = s.value
            PageScroll {
                if (library.continueListening.isNotEmpty()) {
                    SectionTitle("01", "Continue listening")
                    Spacer(Modifier.height(16.dp))
                    library.continueListening.take(8).forEach { chapter ->
                        ChapterRow(chapter, repository) {
                            scope.launch { playback.play(chapter, chapter.fiction); onOpenPlayer() }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(32.dp))
                }
                SectionTitle("02", "Fictions")
                Spacer(Modifier.height(16.dp))
                FictionGrid(library.fictions, repository)
            }
        }
    }
}

/**
 * Even-column grid that fills the available width, matching the web app's
 * `repeat(auto-fill, minmax(200px, 1fr))`: cards are at least ~200dp wide and stretch to fill,
 * so there is never a ragged right edge regardless of window width.
 */
@Composable
private fun FictionGrid(fictions: List<FictionSummary>, repository: TtsRoadRepository) {
    val gap = 18.dp
    val minCard = 200.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + gap).value / (minCard + gap).value).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            fictions.chunked(columns).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { fiction ->
                        FictionCard(fiction, repository, Modifier.weight(1f))
                    }
                    // Keep cards in a short final row at their natural width instead of stretching.
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FictionCard(fiction: FictionSummary, repository: TtsRoadRepository, modifier: Modifier = Modifier) {
    AarisCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverTile(
                fiction.title,
                fiction.coverImageUrl?.let(repository::resolveUrl),
                Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            )
            Text(fiction.title, style = MaterialTheme.typography.titleMedium, color = AarisColor.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            fiction.author?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
            LinearProgressIndicator(progress = { fiction.readyFraction }, color = AarisColor.Accent, trackColor = AarisColor.Line, modifier = Modifier.fillMaxWidth())
            MetaText("${fiction.doneChapters}/${fiction.totalChapters} ready")
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterSummary, repository: TtsRoadRepository, onPlay: () -> Unit) {
    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverTile(
                chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
                chapter.resolvedCoverUrl?.let(repository::resolveUrl),
                Modifier.size(56.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(chapter.resolvedTitle, style = MaterialTheme.typography.titleMedium, color = AarisColor.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(listOfNotNull(chapter.resolvedFictionTitle, chapter.audioDurationLabel).joinToString("  ·  "))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onPlay, enabled = chapter.audio != null, shape = RectangleShape) {
                Text(if (chapter.resolvedPositionSeconds > 0) "RESUME" else "PLAY")
            }
        }
    }
}

@Composable
private fun PlayerScreen(playback: PlaybackController) {
    val s: PlayerUiState by playback.state.collectAsState()
    Box(Modifier.fillMaxSize().padding(horizontal = PageGutter, vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = NarrowMaxWidth).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        MetaText(text = "// Now Playing", color = AarisColor.Accent)
        Box(Modifier.weight(1f).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            CoverTile(s.fictionTitle ?: s.title, s.coverImageUrl, Modifier.height(280.dp).aspectRatio(0.7f))
        }
        Text(s.title, style = MaterialTheme.typography.headlineSmall, color = AarisColor.Ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        s.fictionTitle?.let { Spacer(Modifier.height(8.dp)); MetaText(it) }
        Spacer(Modifier.height(20.dp))
        Slider(
            value = s.positionMs.coerceAtMost(s.durationMs).toFloat(),
            onValueChange = { playback.seekTo(it.toLong()) },
            valueRange = 0f..s.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = s.durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetaText(formatDuration(s.positionMs))
            MetaText(formatDuration(s.durationMs))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { playback.skipBy(-30_000) }, enabled = s.hasMedia, shape = RectangleShape) { Text("−30") }
            Button(onClick = { playback.togglePlayPause() }, enabled = s.hasMedia, shape = RectangleShape) { Text(if (s.isPlaying) "PAUSE" else "PLAY") }
            OutlinedButton(onClick = { playback.skipBy(30_000) }, enabled = s.hasMedia, shape = RectangleShape) { Text("+30") }
        }
        Spacer(Modifier.height(8.dp))
        val error = s.error
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
            !s.hasMedia -> MetaText(text = "Buffering…", color = AarisColor.Dim)
        }
        }
    }
}

@Composable
private fun SettingsScreen(sessionStore: SessionStore, repository: TtsRoadRepository) {
    val scope = rememberCoroutineScope()
    val session by sessionStore.session.collectAsState()
    var busy by remember { mutableStateOf(false) }
    PageScroll(maxWidth = NarrowMaxWidth, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MetaText(text = "// Session", color = AarisColor.Accent)
        AarisCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingRow("Server", session.serverUrl)
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingRow("User", session.username.orEmpty())
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingRow("Role", if (session.isAdmin) "Admin" else "User")
            }
        }
        Button(
            onClick = { scope.launch { busy = true; repository.logout(); busy = false } },
            enabled = !busy,
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "SIGNING OUT" else "SIGN OUT") }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetaText(label)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, color = AarisColor.Ink)
    }
}

@Composable
private fun SectionTitle(kicker: String, title: String) {
    Column(Modifier.fillMaxWidth()) {
        MetaText(text = "§ $kicker", color = AarisColor.Accent)
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

@Composable
private fun CoverTile(fallback: String, coverUrl: String?, modifier: Modifier) {
    Box(
        modifier.background(AarisColor.BgInput).border(1.dp, AarisColor.Line),
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
    Text(fallback.trim().take(1).uppercase().ifBlank { "T" }, style = MaterialTheme.typography.headlineSmall, color = AarisColor.Accent)
}

@Composable
private fun CenterProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = AarisColor.Accent)
}

@Composable
private fun CenterError(message: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(message, color = MaterialTheme.colorScheme.error)
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
