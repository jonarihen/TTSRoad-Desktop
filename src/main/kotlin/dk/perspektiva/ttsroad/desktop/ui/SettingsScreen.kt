package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.BuildInfo
import dk.perspektiva.ttsroad.desktop.data.DeviceSession
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.formatExpiresIn
import dk.perspektiva.ttsroad.desktop.data.formatServerTimestamp
import dk.perspektiva.ttsroad.desktop.data.redactSecrets

/** Below this the two panes stack, so the settings list stays usable in a narrow window. */
private val TwoPaneMinWidth = 780.dp
private val NavPaneWidth = 220.dp

/**
 * The desktop control centre: a two-pane settings screen.
 *
 * State lives in [SettingsStateHolder] rather than in this composable, and the production caller
 * hoists that holder above navigation — so leaving settings and coming back keeps the selected pane
 * and the loaded device list. Everything irreversible here goes through one confirmation dialog.
 */
@Composable
fun SettingsScreen(
    sessionStore: SessionStore,
    repository: TtsRoadRepository,
    holder: SettingsStateHolder = rememberStateHolder(repository, sessionStore) {
        SettingsStateHolder(repository, sessionStore)
    },
    // Injected so "expires in 42 days" can be asserted without the test depending on wall time.
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val ui by holder.state.collectAsState()
    val session by sessionStore.session.collectAsState()
    val capabilities by repository.currentCapabilities.collectAsState()

    LaunchedEffect(ui.section) {
        when (ui.section) {
            SettingsSection.Account -> holder.verifyAccount()
            SettingsSection.Devices -> holder.ensureDevicesLoaded()
            else -> Unit
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = maxWidth < TwoPaneMinWidth
        val pane: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PageGutter),
            ) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (ui.section) {
                        SettingsSection.Account -> AccountPane(ui, session, capabilities, sessionStore, holder, nowMs)
                        SettingsSection.Devices -> DevicesPane(ui, session, holder, nowMs)
                        SettingsSection.Playback -> PlaybackPane()
                        SettingsSection.Offline -> OfflinePane()
                        SettingsSection.About -> AboutPane(session, capabilities, sessionStore)
                    }
                }
            }
        }

        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                SettingsNav(current = ui.section, stacked = true, onSelect = holder::openSection)
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Box(Modifier.weight(1f).fillMaxWidth()) { pane() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                SettingsNav(current = ui.section, stacked = false, onSelect = holder::openSection)
                VerticalDivider(thickness = 1.dp, color = AarisColor.Line)
                Box(Modifier.weight(1f).fillMaxHeight()) { pane() }
            }
        }
    }

    ui.confirmation?.let { pending ->
        val copy = confirmationCopy(pending)
        ConfirmDialog(
            title = copy.title,
            body = copy.body,
            confirmLabel = copy.confirmLabel,
            onConfirm = holder::confirm,
            onDismiss = holder::dismissConfirmation,
        )
    }
}

private data class ConfirmationCopy(val title: String, val body: String, val confirmLabel: String)

private fun confirmationCopy(confirmation: SettingsConfirmation): ConfirmationCopy = when (confirmation) {
    is SettingsConfirmation.RevokeDevice -> ConfirmationCopy(
        title = "SIGN OUT DEVICE",
        body = "${confirmation.device.resolvedName} will need to sign in again. " +
            "Anything playing on it stops.",
        confirmLabel = "SIGN IT OUT",
    )

    SettingsConfirmation.RevokeOtherDevices -> ConfirmationCopy(
        title = "SIGN OUT OTHER DEVICES",
        body = "Every other signed-in device will need to sign in again. This one stays signed in.",
        confirmLabel = "SIGN THEM OUT",
    )

    SettingsConfirmation.SignOut -> ConfirmationCopy(
        title = "SIGN OUT",
        body = "This device will need to sign in again. Nothing you asked to keep on this " +
            "computer is deleted.",
        // Not just "SIGN OUT": the button that opened this dialog says that, and a confirmation
        // whose answer is worded identically to the thing you just pressed is not a confirmation.
        confirmLabel = "SIGN OUT THIS DEVICE",
    )
}

// --- Navigation ----------------------------------------------------------------------------

@Composable
private fun SettingsNav(current: SettingsSection, stacked: Boolean, onSelect: (SettingsSection) -> Unit) {
    if (stacked) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PageGutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsSection.entries.forEach { NavEntry(it, it == current, onSelect) }
        }
    } else {
        Column(
            Modifier
                .width(NavPaneWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = PageGutter, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetaText(text = "// Settings", color = AarisColor.Accent)
            Spacer(Modifier.height(8.dp))
            SettingsSection.entries.forEach { NavEntry(it, it == current, onSelect) }
        }
    }
}

/**
 * One entry in the settings list.
 *
 * `selectable` rather than `clickable`: it makes the entry reachable with Tab, activatable with
 * Enter or Space, and — through [Role.Tab] and the selected flag — announced as "tab, selected" by
 * a screen reader instead of as an anonymous piece of text.
 */
@Composable
private fun NavEntry(section: SettingsSection, selected: Boolean, onSelect: (SettingsSection) -> Unit) {
    Row(
        Modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = { onSelect(section) },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) AarisColor.BgHover else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp)
                .background(if (selected) AarisColor.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        MetaText(section.label, color = if (selected) AarisColor.Ink else AarisColor.Muted)
    }
}

// --- Account -------------------------------------------------------------------------------

@Composable
private fun AccountPane(
    ui: SettingsUiState,
    session: SessionState,
    capabilities: ServerCapabilities,
    sessionStore: SessionStore,
    holder: SettingsStateHolder,
    nowMs: () -> Long,
) {
    PaneTitle("Account", "Server, sign-in and this device")

    SettingsCard {
        SettingRow("Server", listOfNotNull(serverDisplayName(session, capabilities), capabilities.serverVersion ?: session.serverVersion).joinToString(" "))
        RowDivider()
        SettingRow("Address", session.serverUrl)
        RowDivider()
        // An older server answers 404 to discovery and lands on the baseline, where every optional
        // feature is off — which is exactly what this row then says.
        SettingRow("Optional features", describeCapabilities(capabilities))
    }

    MetaText(text = "// Signed in", color = AarisColor.Accent)
    SettingsCard {
        SettingRow("User", ui.verifiedUser?.username ?: session.username.orEmpty())
        RowDivider()
        SettingRow("Role", if (ui.verifiedUser?.isAdmin ?: session.isAdmin) "Admin" else "User")
        RowDivider()
        // Where the bearer token actually lives. Worth surfacing: it is the difference between a
        // session that survives a restart and one that deliberately does not.
        SettingRow(
            "Credential storage",
            sessionStore.credentialStoreName +
                if (sessionStore.persistsCredentials) "" else " (this session only)",
        )
    }

    MetaText(text = "// This device", color = AarisColor.Accent)
    SettingsCard {
        SettingRow("Session id", session.deviceId?.toString() ?: "Not reported by this server")
        RowDivider()
        SettingRow(
            "Session expires",
            listOfNotNull(
                formatServerTimestamp(session.expiresAt),
                formatExpiresIn(session.expiresAt, nowMs()),
            ).joinToString(" · ").ifBlank { "Unknown" },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { holder.openSection(SettingsSection.Devices) },
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("MANAGE DEVICE SESSIONS") }
        Button(
            onClick = holder::askSignOut,
            enabled = !ui.signingOut,
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text(if (ui.signingOut) "SIGNING OUT" else "SIGN OUT") }
    }
}

// --- Device sessions -----------------------------------------------------------------------

@Composable
private fun DevicesPane(
    ui: SettingsUiState,
    session: SessionState,
    holder: SettingsStateHolder,
    nowMs: () -> Long,
) {
    val devices = ui.devices
    PaneTitle("Device sessions", "Everything signed in to this account")

    when {
        // Gated: either the server said it has no device management, or the endpoint answered 404.
        // Either way this is a missing feature, not a fault, and every other setting still works.
        devices.unsupported -> InfoCard(
            "This server has no device-session API. Update the backend to manage sign-ins from " +
                "here. Everything else in Settings works as usual.",
        )

        devices.isInitialLoad -> Box(Modifier.fillMaxWidth().height(120.dp)) { CenterProgress() }

        devices.loaded == null -> {
            Text(
                devices.error ?: "Could not load device sessions",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = holder::refreshDevices,
                enabled = !devices.isLoading,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("RETRY") }
        }

        else -> {
            // A failed refresh reports itself above the list it failed to replace, rather than
            // replacing it with an error screen.
            if (devices.isLoading) ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            devices.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            devices.notice?.let { MetaText(text = it, color = AarisColor.Ok) }

            MetaText(text = "// This device", color = AarisColor.Accent)
            val current = holder.currentDevice()
            if (current == null) {
                InfoCard("This session is not in the list yet.")
            } else {
                // No revoke control on this row at all: ending *this* session is what Sign out is
                // for, and a delete button next to "This device" is an accident waiting to happen.
                DeviceCard(current, isCurrent = true, nowMs = nowMs, onRevoke = null)
            }

            MetaText(text = "// Other sessions", color = AarisColor.Accent)
            val others = holder.otherDevices()
            if (others.isEmpty()) {
                InfoCard("Nothing else is signed in.")
            } else {
                others.forEach { device ->
                    DeviceCard(
                        device = device,
                        isCurrent = false,
                        nowMs = nowMs,
                        onRevoke = { holder.askRevoke(device) }.takeIf { !devices.isBusy },
                    )
                }
                Button(
                    onClick = holder::askRevokeOtherDevices,
                    enabled = !devices.isBusy,
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) { Text(if (devices.isBusy) "WORKING" else "SIGN OUT ALL OTHER DEVICES") }
            }

            OutlinedButton(
                onClick = holder::refreshDevices,
                enabled = !devices.isLoading && !devices.isBusy,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("REFRESH") }
        }
    }

    if (devices.loaded != null) {
        MetaText(
            text = "Signing a device out cannot be undone — it has to sign in again.",
            color = AarisColor.Dim,
        )
    }
    // Shown when the list cannot be: the current session's identity comes from the login response,
    // so it never depends on an endpoint the server may not have.
    if (session.deviceId != null && (devices.unsupported || devices.error != null)) {
        MetaText(text = "This session's id is ${session.deviceId}", color = AarisColor.Dim)
    }
}

@Composable
private fun DeviceCard(
    device: DeviceSession,
    isCurrent: Boolean,
    nowMs: () -> Long,
    onRevoke: (() -> Unit)?,
) {
    val lastUsed = formatServerTimestamp(device.lastUsedAt) ?: "Never"
    val signedIn = formatServerTimestamp(device.createdAt) ?: "-"
    val expires = listOfNotNull(
        formatServerTimestamp(device.expiresAt),
        formatExpiresIn(device.expiresAt, nowMs()),
    ).joinToString(" · ").ifBlank { "-" }
    val lastIp = device.lastIp ?: "-"
    // One sentence for a screen reader, instead of eight unlabelled fragments read in layout order.
    val spoken = buildString {
        append(device.resolvedName)
        if (isCurrent) append(", this device")
        device.status?.takeIf { it.isNotBlank() }?.let { append(", $it") }
        append(", last used $lastUsed, signed in $signedIn, $expires, last IP $lastIp")
    }

    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.resolvedName,
                        style = MaterialTheme.typography.titleMedium,
                        color = AarisColor.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) AarisTag(text = "This device")
                    device.status?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(6.dp))
                        AarisTag(text = it)
                    }
                }
                DeviceDetail("Last used", lastUsed)
                DeviceDetail("Signed in", signedIn)
                DeviceDetail("Expires", expires)
                // Null until the session is actually used, so a fresh sign-in shows a dash.
                DeviceDetail("Last IP", lastIp)
            }
            onRevoke?.let {
                OutlinedButton(
                    onClick = it,
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Danger),
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { contentDescription = "Sign out ${device.resolvedName}" },
                ) { Text("SIGN OUT") }
            }
        }
    }
}

@Composable
private fun DeviceDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetaText(label)
        Spacer(Modifier.width(12.dp))
        MetaText(value, color = AarisColor.Ink)
    }
}

// --- Not-yet-built phases ------------------------------------------------------------------

/**
 * Playback preferences do not exist yet.
 *
 * Deliberately a description and not a set of dead switches: a control that looks live and changes
 * nothing is worse than an honest empty pane, because the user cannot tell it did not work.
 */
@Composable
private fun PlaybackPane() {
    PaneTitle("Playback", "Not available yet")
    InfoCard(
        "Playback preferences are not part of this build. Skip interval, playback speed, " +
            "auto-marking a finished chapter and a sleep timer arrive with the playback-preferences " +
            "phase; until then the player uses fixed 30-second skips at normal speed.",
    )
}

@Composable
private fun OfflinePane() {
    PaneTitle("Offline", "Not available yet")
    InfoCard(
        "Offline downloads are not part of this build. Nothing is stored for offline use, so " +
            "there is no download cache to size or clear here — and signing out cannot delete " +
            "anything you asked to keep. Chapters are streamed to a temporary file that is removed " +
            "when playback moves on.",
    )
}

// --- Updates & About -----------------------------------------------------------------------

@Composable
private fun AboutPane(
    session: SessionState,
    capabilities: ServerCapabilities,
    sessionStore: SessionStore,
) {
    PaneTitle("Updates & About", "Build, licences and diagnostics")

    SettingsCard {
        // BuildInfo is generated from the single `ttsroad.version` Gradle property, so this always
        // matches the installer the user actually ran.
        SettingRow("Application", "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}")
        RowDivider()
        SettingRow("Release channel", "Installed build")
        RowDivider()
        // Honest rather than aspirational: there is no updater in this build, so claiming to be
        // "up to date" would be a claim nothing checked.
        SettingRow("Update check", "Not available — install a newer build to update")
        RowDivider()
        SettingRow("Java runtime", "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    }

    MetaText(text = "// Open source", color = AarisColor.Accent)
    SettingsCard {
        SettingRow("Apache License 2.0", "Kotlin, Compose Multiplatform, OkHttp, Retrofit, Moshi, Coil")
        RowDivider()
        SettingRow("LGPL 2.1", "JLayer, MP3SPI, Tritonus (JavaZOOM) — MP3 decoding")
    }

    MetaText(text = "// Diagnostics", color = AarisColor.Accent)
    val diagnostics = buildDiagnostics(
        session = session,
        capabilities = capabilities,
        credentialStoreName = sessionStore.credentialStoreName,
        persistsCredentials = sessionStore.persistsCredentials,
    )
    SettingsCard {
        Text(
            diagnostics,
            style = MaterialTheme.typography.bodyMedium,
            color = AarisColor.Muted,
        )
    }
    OutlinedButton(
        onClick = { copyToClipboard(diagnostics) },
        shape = RectangleShape,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) { Text("COPY DIAGNOSTICS") }
}

/**
 * The block a user can paste into a bug report.
 *
 * Everything here is either a machine fact or a server hint; the bearer token has no line of its
 * own *and* the whole block goes through [redactSecrets], so a future field that accidentally
 * carries a credential cannot make it into the clipboard verbatim.
 */
fun buildDiagnostics(
    session: SessionState,
    capabilities: ServerCapabilities,
    credentialStoreName: String,
    persistsCredentials: Boolean,
): String = redactSecrets(
    buildString {
        appendLine("${BuildInfo.APP_NAME} ${BuildInfo.VERSION}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        appendLine("Credential storage: $credentialStoreName${if (persistsCredentials) "" else " (session only)"}")
        appendLine("Server: ${session.serverUrl.ifBlank { "-" }}")
        appendLine("Server build: ${serverDisplayName(session, capabilities)} ${capabilities.serverVersion ?: session.serverVersion ?: "unknown"}")
        appendLine("Optional features: ${describeCapabilities(capabilities)}")
        appendLine("Session id: ${session.deviceId?.toString() ?: "unknown"}")
    }.trim(),
)

private fun copyToClipboard(text: String) {
    // Headless CI has no clipboard; a failure to copy is not worth crashing settings over.
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

// --- Shared bits ---------------------------------------------------------------------------

private fun serverDisplayName(session: SessionState, capabilities: ServerCapabilities): String =
    capabilities.serverName.takeIf { capabilities.isDiscovered } ?: session.serverName

/** One line naming everything the signed-in server advertised, or saying that it advertised none. */
fun describeCapabilities(capabilities: ServerCapabilities): String {
    val enabled = buildList {
        if (capabilities.readAlong) add("Read-along")
        if (capabilities.search) add("Server search")
        if (capabilities.bookmarks) add("Bookmarks")
        if (capabilities.deltaSync) add("Delta sync")
        if (capabilities.batchProgress) add("Batch progress")
        if (capabilities.audioContentHash) add("Audio content hash")
        if (capabilities.deviceManagement) add("Device management")
    }
    return when {
        enabled.isNotEmpty() -> enabled.joinToString(", ")
        capabilities.isDiscovered -> "None advertised"
        else -> "Not advertised by this server"
    }
}

@Composable
private fun PaneTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink)
        Spacer(Modifier.height(4.dp))
        MetaText(subtitle)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun InfoCard(text: String) {
    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = AarisColor.Muted,
        )
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

@Composable
private fun SettingRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetaText(label)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, color = AarisColor.Ink)
    }
}

/**
 * The one dialog every irreversible action goes through.
 *
 * Keyboard behaviour is the point of the extra wiring: Escape dismisses (explicitly, rather than
 * relying on the platform mapping), and focus lands on CANCEL — so the key a user hits reflexively
 * is the safe one, never the destructive one.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .semantics { paneTitle = title },
        containerColor = AarisColor.BgRaise,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = AarisColor.Ink) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = AarisColor.Muted) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RectangleShape,
                modifier = Modifier.focusRequester(cancelFocus).pointerHoverIcon(PointerIcon.Hand),
            ) { Text("CANCEL") }
        },
    )
}
