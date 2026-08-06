package dk.perspektiva.ttsroad.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.nav.AppShortcut
import dk.perspektiva.ttsroad.desktop.nav.Destination
import dk.perspektiva.ttsroad.desktop.nav.EscapeAction
import dk.perspektiva.ttsroad.desktop.nav.NavigationState
import dk.perspektiva.ttsroad.desktop.nav.escapeAction
import dk.perspektiva.ttsroad.desktop.nav.key
import dk.perspektiva.ttsroad.desktop.nav.shortcutFor
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
import dk.perspektiva.ttsroad.desktop.ui.SettingsSection
import dk.perspektiva.ttsroad.desktop.ui.SettingsStateHolder
import dk.perspektiva.ttsroad.desktop.ui.WindowSizeClass
import dk.perspektiva.ttsroad.desktop.ui.hasSession
import dk.perspektiva.ttsroad.desktop.ui.rememberStateHolder
import dk.perspektiva.ttsroad.desktop.ui.windowSizeClassFor

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
    val cache = container.libraryCache
    val session by sessionStore.session.collectAsState()
    val sessionEnd by repository.sessionEnd.collectAsState()
    // Hoisted above navigation on purpose: `when (destination)` disposes the screen it leaves, so a
    // holder created inside SettingsScreen would drop the selected pane and the loaded device list
    // every time the user glanced at the library.
    val settings = rememberStateHolder(repository, sessionStore) {
        SettingsStateHolder(repository, sessionStore)
    }

    // Retained per-destination UI state: search text, filters and scroll offsets are stored under
    // the destination's stable key and released when that destination leaves the back stack.
    val screenState = rememberSaveableStateHolder()
    val nav = remember { NavigationState(onDestinationDropped = screenState::removeState) }
    val playerState by playback.state.collectAsState()

    fun refreshCurrentScreen() {
        when (val destination = nav.current) {
            Destination.Library -> cache.refreshLibrary()
            is Destination.Fiction -> cache.refreshChapters(destination.fiction.id)
            Destination.Settings, Destination.Devices -> settings.refreshCurrentSection()
            Destination.Player, is Destination.Reader -> Unit
        }
    }

    // Escape closes the top dialog or sheet *before* it navigates; only when nothing was open does
    // it mean "go back". Without that ordering, dismissing a confirmation would also leave the
    // screen the confirmation belonged to.
    //
    // An overlay only counts while the screen that owns it is actually on top. The settings holder
    // is hoisted above navigation, so a confirmation left open on Settings is still "open" once the
    // user has walked off to a fiction — and without this check the first Escape there would
    // silently dismiss an invisible dialog instead of going back.
    //
    // Returns whether the key did anything, so an Escape that means nothing is not swallowed here.
    fun dismissOrGoBack(): Boolean {
        val ownsOverlay = nav.current == Destination.Settings || nav.current == Destination.Devices
        return when (escapeAction(settings.hasOpenOverlay && ownsOverlay, nav.canGoBack)) {
            EscapeAction.CloseOverlay -> settings.dismissTopOverlay()
            EscapeAction.GoBack -> nav.back()
            EscapeAction.None -> false
        }
    }

    // One reaction to "there is no session any more", whether that came from Sign out or from a
    // 401 on an API or audio request: stop the audio, drop the cached library, and reset
    // navigation. Without it a revoked token leaves a chapter playing behind the login screen.
    LaunchedEffect(session.isLoggedIn) {
        if (!session.isLoggedIn) {
            playback.stop()
            nav.resetToRoot()
            // The library belonged to the account that just ended; the next person to sign in on
            // this machine must not be shown it.
            cache.clear()
            settings.sessionEnded()
        } else {
            // Cheap, and it is what makes optional UI correct after a restart, where login did
            // not run but a keyring-backed session was restored.
            repository.refreshCurrentCapabilities()
        }
    }

    val rootFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(AarisColor.Bg)
            .focusRequester(rootFocus)
            .focusable()
            // A *preview* handler, so the window shortcuts work even while a text field has focus:
            // F5 in the library's search box still refreshes.
            .onPreviewKeyEvent { event ->
                // Each branch reports whether it actually did something: a Back or an Escape that
                // has nothing to act on must fall through rather than be swallowed at the root.
                when (shortcutFor(event)) {
                    AppShortcut.Back -> nav.back()

                    AppShortcut.Refresh -> {
                        refreshCurrentScreen()
                        true
                    }

                    AppShortcut.Dismiss -> dismissOrGoBack()

                    null -> false
                }
            },
    ) {
        // Keyboard shortcuts are delivered to the focus owner and previewed on the way down, so
        // something inside this Box has to hold focus. Re-taken on every destination change
        // because navigating disposes whatever had it — without this, Alt+Left works on the first
        // screen and silently stops working the moment the user clicks into a second one. It also
        // puts Tab traversal back at the top of the screen the user just arrived on.
        LaunchedEffect(nav.current, session.isLoggedIn) { runCatching { rootFocus.requestFocus() } }

        if (!session.isLoggedIn) {
            LoginScreen(
                repository = repository,
                initialServerUrl = session.serverUrl,
                initialUsername = session.username,
                sessionEndedMessage = sessionEnd?.message,
                persistsCredentials = sessionStore.persistsCredentials,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sizeClass = windowSizeClassFor(maxWidth)
                Column(Modifier.fillMaxSize()) {
                    HeaderBar(
                        serverName = session.serverName,
                        current = nav.current,
                        canGoBack = nav.canGoBack,
                        canRefresh = nav.current.isRefreshable,
                        compact = sizeClass == WindowSizeClass.Compact,
                        onBack = { nav.back() },
                        onRefresh = ::refreshCurrentScreen,
                        onSelect = { nav.open(it) },
                    )
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val destination = nav.current
                        // The state provider is what makes Back restore a screen rather than
                        // rebuild it: everything `rememberSaveable` inside it is filed under the
                        // destination's key and handed back on the next visit.
                        screenState.SaveableStateProvider(destination.key) {
                            when (destination) {
                                Destination.Library -> LibraryScreen(
                                    cache = cache,
                                    repository = repository,
                                    playback = playback,
                                    onOpenFiction = { nav.open(Destination.Fiction(it)) },
                                    onOpenPlayer = { nav.open(Destination.Player) },
                                )

                                is Destination.Fiction -> FictionDetailScreen(
                                    fiction = destination.fiction,
                                    cache = cache,
                                    repository = repository,
                                    playback = playback,
                                    onBack = { nav.back() },
                                    // Offered only for chapters the *server* holds timings for, so
                                    // the row action cannot appear on a chapter the reader could
                                    // never open.
                                    onOpenReader = { chapter ->
                                        nav.open(
                                            Destination.Reader(chapter.resolvedChapterId, chapter.resolvedTitle),
                                        )
                                    },
                                )

                                Destination.Player -> PlayerScreen(
                                    playback = playback,
                                    sizeClass = sizeClass,
                                    onBack = { nav.back() },
                                )

                                Destination.Settings, Destination.Devices -> SettingsScreen(
                                    sessionStore = sessionStore,
                                    repository = repository,
                                    holder = settings,
                                    // Keeps the destination and the open pane in step, so the
                                    // Devices deep link and the in-screen pane list are the same
                                    // thing rather than two competing notions of "where am I".
                                    onSectionSelected = { section ->
                                        nav.replaceTop(
                                            if (section == SettingsSection.Devices) {
                                                Destination.Devices
                                            } else {
                                                Destination.Settings
                                            },
                                        )
                                    },
                                )

                                is Destination.Reader -> ReaderPlaceholder(destination.title)
                            }
                        }
                    }
                    if (playerState.hasSession && nav.current != Destination.Player) {
                        NowPlayingBar(playback, compact = sizeClass == WindowSizeClass.Compact) {
                            nav.open(Destination.Player)
                        }
                    }
                }
            }
        }
    }

    // The Devices destination is a deep link into the settings screen: entering it selects the
    // pane, so a future "manage sessions" link from anywhere lands on the right place.
    LaunchedEffect(nav.current) {
        if (nav.current == Destination.Devices) settings.openSection(SettingsSection.Devices)
    }
}

/** Whether the Refresh action means anything on this destination. */
private val Destination.isRefreshable: Boolean
    get() = when (this) {
        Destination.Library, is Destination.Fiction, Destination.Settings, Destination.Devices -> true
        Destination.Player, is Destination.Reader -> false
    }

/**
 * Read-along is not part of this build.
 *
 * Nothing navigates here — the destination exists so the back stack's keys and retention rules are
 * settled before the reader phase lands. An honest pane beats a half-wired screen if something
 * ever does reach it.
 */
@Composable
private fun ReaderPlaceholder(title: String) {
    Box(Modifier.fillMaxSize().padding(PageGutter), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MetaText(text = "// Read-along", color = AarisColor.Accent)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink)
            Spacer(Modifier.height(8.dp))
            MetaText("Read-along arrives in a later build")
        }
    }
}

@Composable
private fun HeaderBar(
    serverName: String,
    current: Destination,
    canGoBack: Boolean,
    canRefresh: Boolean,
    compact: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Destination) -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(AarisColor.Bg)) {
        Row(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = PageGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back",
                enabled = canGoBack,
                onClick = onBack,
            )
            Spacer(Modifier.width(8.dp))
            HeaderIconAction(
                icon = Icons.Default.Refresh,
                label = "Refresh",
                enabled = canRefresh,
                onClick = onRefresh,
            )
            Spacer(Modifier.width(16.dp))
            // The server name is the first thing to go in a narrow window: it is a label, and the
            // room it wants is room the navigation entries need to stay on screen unclipped.
            if (!compact) {
                MetaText(
                    text = "// $serverName",
                    color = AarisColor.Accent,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                Spacer(Modifier.width(20.dp))
            }
            Text(
                "TTSROAD",
                style = MaterialTheme.typography.titleLarge,
                color = AarisColor.Ink,
                maxLines = 1,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(Destination.Library) },
            )
            Spacer(Modifier.weight(1f))
            NavItem(
                "Library",
                active = current == Destination.Library || current is Destination.Fiction,
            ) { onSelect(Destination.Library) }
            NavItem(
                "Settings",
                active = current == Destination.Settings || current == Destination.Devices,
            ) { onSelect(Destination.Settings) }
        }
    }
}

/**
 * Header navigation entry — mono label with an accent underline on the active tab.
 *
 * `selectable` rather than `clickable`: reachable with Tab, activatable with Enter or Space, and
 * announced as a selected tab rather than as an anonymous piece of text.
 */
@Composable
private fun NavItem(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(
                selected = active,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetaText(label, color = if (active || hovered || focused) AarisColor.Ink else AarisColor.Muted)
        Spacer(Modifier.height(4.dp))
        // The underline doubles as the focus ring: keyboard focus shows it even when the tab is
        // not the active one, so a keyboard-only user can always see where they are.
        Box(
            Modifier
                .height(2.dp)
                .width(28.dp)
                .background(
                    when {
                        active -> AarisColor.Accent
                        focused -> AarisColor.Ink
                        else -> Color.Transparent
                    },
                ),
        )
    }
}

/** Square icon action in the header. Keyboard reachable, and labelled for a screen reader. */
@Composable
private fun HeaderIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(32.dp)
            .background(if ((hovered || focused) && enabled) AarisColor.BgHover else Color.Transparent)
            .border(1.dp, if (focused && enabled) AarisColor.Accent else Color.Transparent)
            .hoverable(interaction)
            .let { if (enabled) it.pointerHoverIcon(PointerIcon.Hand) else it }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> AarisColor.Dim
                hovered || focused -> AarisColor.Ink
                else -> AarisColor.Muted
            },
            modifier = Modifier.size(18.dp),
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
