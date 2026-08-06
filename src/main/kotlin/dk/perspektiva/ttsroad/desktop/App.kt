package dk.perspektiva.ttsroad.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.ui.AarisColor
import dk.perspektiva.ttsroad.desktop.ui.ContentMaxWidth
import dk.perspektiva.ttsroad.desktop.ui.FictionDetailScreen
import dk.perspektiva.ttsroad.desktop.ui.LibraryScreen
import dk.perspektiva.ttsroad.desktop.ui.LoginStateHolder
import dk.perspektiva.ttsroad.desktop.ui.MetaText
import dk.perspektiva.ttsroad.desktop.ui.NowPlayingBar
import dk.perspektiva.ttsroad.desktop.ui.PageGutter
import dk.perspektiva.ttsroad.desktop.ui.PlayerScreen
import dk.perspektiva.ttsroad.desktop.ui.SettingsScreen
import dk.perspektiva.ttsroad.desktop.ui.SettingsStateHolder
import dk.perspektiva.ttsroad.desktop.ui.hasSession
import dk.perspektiva.ttsroad.desktop.ui.rememberStateHolder

private sealed interface Screen {
    data object Library : Screen
    data class Fiction(val fiction: FictionSummary) : Screen
    data object Player : Screen
    data object Settings : Screen
}

/**
 * Root composable. The object graph is *passed in*, not built here — see [AppContainer]. The
 * default argument keeps `App()` usable from a preview or a smoke test, but `main()` owns the
 * real container so it can be closed when the window closes.
 */
@Composable
fun App(container: AppContainer = remember { AppContainer() }) {
    val sessionStore = container.sessionStore
    val repository = container.repository
    val playback = container.playback
    val session by sessionStore.session.collectAsState()
    val sessionEnd by repository.sessionEnd.collectAsState()
    // Hoisted above navigation on purpose: `when (screen)` disposes the screen it leaves, so a
    // holder created inside SettingsScreen would drop the selected pane and the loaded device list
    // every time the user glanced at the library.
    val settings = rememberStateHolder(repository, sessionStore) {
        SettingsStateHolder(repository, sessionStore)
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    // Where the full player collapses back to, so expanding the bar never loses browse context.
    var playerReturn by remember { mutableStateOf<Screen>(Screen.Library) }
    val playerState by playback.state.collectAsState()

    fun openPlayer() {
        if (screen != Screen.Player) playerReturn = screen
        screen = Screen.Player
    }

    // One reaction to "there is no session any more", whether that came from Sign out or from a
    // 401 on an API or audio request: stop the audio and reset navigation. Without it a revoked
    // token leaves a chapter playing behind the login screen.
    LaunchedEffect(session.isLoggedIn) {
        if (!session.isLoggedIn) {
            playback.stop()
            screen = Screen.Library
            playerReturn = Screen.Library
            // Device rows name other machines on the account that just ended; keeping them on
            // screen for whoever signs in next would be both wrong and a small privacy leak.
            settings.sessionEnded()
        } else {
            // Cheap, and it is what makes optional UI correct after a restart, where login did
            // not run but a keyring-backed session was restored.
            repository.refreshCurrentCapabilities()
        }
    }

    Box(Modifier.fillMaxSize().background(AarisColor.Bg)) {
        if (!session.isLoggedIn) {
            LoginScreen(
                repository = repository,
                initialServerUrl = session.serverUrl,
                initialUsername = session.username,
                sessionEndedMessage = sessionEnd?.message,
                persistsCredentials = sessionStore.persistsCredentials,
            )
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
                        Screen.Settings -> SettingsScreen(sessionStore, repository, settings)
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
private fun LoginScreen(
    repository: TtsRoadRepository,
    initialServerUrl: String,
    initialUsername: String?,
    sessionEndedMessage: String?,
    persistsCredentials: Boolean,
) {
    val holder = rememberStateHolder(repository) { LoginStateHolder(repository) }
    val ui by holder.state.collectAsState()
    // Credentials stay in Compose state, deliberately: see LoginStateHolder's doc comment.
    // The two non-secret fields are prefilled from the retained session hints, so signing back in
    // after an expiry does not mean retyping the server address.
    var serverUrl by remember { mutableStateOf(initialServerUrl.ifBlank { "https://" }) }
    var username by remember { mutableStateOf(initialUsername.orEmpty().ifBlank { "admin" }) }
    var password by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    val twoFactor = ui.twoFactor
    val busy = ui.busy
    val error = ui.error

    LaunchedEffect(serverUrl) { holder.serverUrlChanged(serverUrl) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).verticalScroll(rememberScrollState())) {
            MetaText(text = "// Operator Console", color = AarisColor.Accent)
            Spacer(Modifier.height(6.dp))
            Text("TTSROAD", style = MaterialTheme.typography.displaySmall, color = AarisColor.Ink)
            Spacer(Modifier.height(6.dp))
            MetaText(text = "Connect to your private server")
            // Why the login screen is showing itself, in the server's own words.
            sessionEndedMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = AarisColor.Warning, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Field("SERVER URL", serverUrl) { serverUrl = it }
            // Unauthenticated discovery: proof the address is a real TTSRoad server, shown before
            // a password is typed rather than after it has been sent somewhere.
            ui.discovered?.let {
                Spacer(Modifier.height(6.dp))
                MetaText(text = "${it.serverName} ${it.serverVersion}", color = AarisColor.Ok)
            }
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
            if (!persistsCredentials) {
                Spacer(Modifier.height(12.dp))
                MetaText(
                    text = "No OS keyring here — you will need to sign in again after a restart",
                    color = AarisColor.Warning,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { holder.submit(serverUrl, username, password, totpCode) },
                // A 429 means the server is counting attempts; leaving the button live would let
                // the user extend their own lockout.
                enabled = !busy && ui.retryAfterSeconds == null && serverUrl.isNotBlank() &&
                    username.isNotBlank() && password.isNotBlank() &&
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

