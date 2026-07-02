package dk.perspektiva.ttsroad.desktop

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LoginResult
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.player.Mp3PlaybackController
import dk.perspektiva.ttsroad.desktop.ui.AarisCard
import dk.perspektiva.ttsroad.desktop.ui.AarisColor
import dk.perspektiva.ttsroad.desktop.ui.ContentMaxWidth
import dk.perspektiva.ttsroad.desktop.ui.FictionDetailScreen
import dk.perspektiva.ttsroad.desktop.ui.LibraryScreen
import dk.perspektiva.ttsroad.desktop.ui.MetaText
import dk.perspektiva.ttsroad.desktop.ui.NarrowMaxWidth
import dk.perspektiva.ttsroad.desktop.ui.NowPlayingBar
import dk.perspektiva.ttsroad.desktop.ui.PageGutter
import dk.perspektiva.ttsroad.desktop.ui.PageScroll
import dk.perspektiva.ttsroad.desktop.ui.PlayerScreen
import dk.perspektiva.ttsroad.desktop.ui.hasSession
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Library : Screen
    data class Fiction(val fiction: FictionSummary) : Screen
    data object Player : Screen
    data object Settings : Screen
}

@Composable
fun App() {
    val sessionStore = remember { SessionStore() }
    val repository = remember { TtsRoadRepository(sessionStore) }
    val playback = remember { Mp3PlaybackController(repository) }
    val session by sessionStore.session.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    // Where the full player collapses back to, so expanding the bar never loses browse context.
    var playerReturn by remember { mutableStateOf<Screen>(Screen.Library) }
    val playerState by playback.state.collectAsState()

    fun openPlayer() {
        if (screen != Screen.Player) playerReturn = screen
        screen = Screen.Player
    }

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
                    when (val s = screen) {
                        Screen.Library -> LibraryScreen(
                            repository,
                            playback,
                            onOpenFiction = { screen = Screen.Fiction(it) },
                            onOpenPlayer = ::openPlayer,
                        )
                        is Screen.Fiction -> FictionDetailScreen(
                            s.fiction,
                            repository,
                            playback,
                            onBack = { screen = Screen.Library },
                        )
                        Screen.Player -> PlayerScreen(playback, onBack = { screen = playerReturn })
                        Screen.Settings -> SettingsScreen(sessionStore, repository)
                    }
                }
                if (playerState.hasSession && screen != Screen.Player) {
                    NowPlayingBar(playback, onExpand = ::openPlayer)
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
            Text(
                "TTSROAD",
                style = MaterialTheme.typography.titleLarge,
                color = AarisColor.Ink,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(Screen.Library) },
            )
            Spacer(Modifier.weight(1f))
            NavItem(
                "Library",
                active = current is Screen.Library || current is Screen.Fiction,
            ) { onSelect(Screen.Library) }
            NavItem("Settings", active = current is Screen.Settings) { onSelect(Screen.Settings) }
        }
    }
}

/** Header navigation entry — mono label with an accent underline on the active tab. */
@Composable
private fun NavItem(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetaText(label, color = if (active || hovered) AarisColor.Ink else AarisColor.Muted)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(28.dp)
                .background(if (active) AarisColor.Accent else Color.Transparent),
        )
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
                modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
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
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
    )
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
            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
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
