package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.BuildInfo
import dk.perspektiva.ttsroad.desktop.data.DeviceSession
import dk.perspektiva.ttsroad.desktop.data.AudiobookExport
import dk.perspektiva.ttsroad.desktop.data.InMemoryPlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.InMemoryListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.ListeningStats
import dk.perspektiva.ttsroad.desktop.data.ListeningStatsStore
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferences
import dk.perspektiva.ttsroad.desktop.data.formatListeningSpan
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.update.UpdateStatus
import java.time.LocalDate
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.data.SessionStore
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.VolumeBoost
import dk.perspektiva.ttsroad.desktop.data.formatExpiresIn
import dk.perspektiva.ttsroad.desktop.data.formatServerTimestamp
import dk.perspektiva.ttsroad.desktop.data.redactSecrets
import dk.perspektiva.ttsroad.desktop.download.OfflineStorageSummary
import dk.perspektiva.ttsroad.desktop.download.OfflineStorageController
import dk.perspektiva.ttsroad.desktop.download.UnavailableOfflineStorageController

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
    offlineStorage: OfflineStorageController = UnavailableOfflineStorageController,
    holder: SettingsStateHolder = rememberStateHolder(repository, sessionStore, offlineStorage) {
        SettingsStateHolder(repository, sessionStore, offlineStorage = offlineStorage)
    },
    /**
     * Reported whenever the open pane changes, so the caller can keep its own idea of "where am I"
     * in step — the app uses it to swap the top back-stack entry between Settings and Devices.
     */
    onSectionSelected: (SettingsSection) -> Unit = {},
    /**
     * Listening settings. Defaulted to an in-memory store so a screen test — and a preview — never
     * writes to the real config directory just by rendering the Playback pane.
     */
    preferences: PlaybackPreferencesStore = remember { InMemoryPlaybackPreferencesStore() },
    /**
     * What the *engine* can do, passed down rather than read here.
     *
     * The pane draws a speed or skip-silence control only where the backend can honour it, which
     * is the same rule the player screen follows — and taking the two flags as parameters keeps
     * Settings independent of the playback controller.
     */
    canChangeSpeed: Boolean = false,
    canSkipSilence: Boolean = false,
    /**
     * Whether closing the window keeps playing in the tray, and how to change it.
     *
     * Passed in for the same reason the two engine flags are: the answer is owned by the window,
     * which is `Main`'s business rather than a screen's, and a pane that reached for the store
     * itself could not be rendered in a test without one.
     */
    closeToTray: Boolean = false,
    onCloseToTrayChange: (Boolean) -> Unit = {},
    /** False on a desktop session with no system tray, where the control would promise nothing. */
    traySupported: Boolean = true,
    /**
     * Day totals for the Listening pane, and whose they are.
     *
     * Defaulted to an in-memory store for the same reason the preferences are: rendering a pane in
     * a test must not read or write the user's real `listening.json`.
     */
    listeningStats: ListeningStatsStore = remember { InMemoryListeningStatsStore() },
    historyOwnerKey: String = "",
    // Injected so "expires in 42 days" can be asserted without the test depending on wall time.
    nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The update check, or null where there is none — a preview or a screen test. Null keeps
     * the About pane from claiming an update state nothing checked.
     */
    updates: UpdateStateHolder? = null,
) {
    val ui by holder.state.collectAsState()
    val session by sessionStore.session.collectAsState()
    val capabilities by repository.currentCapabilities.collectAsState()
    val visibleSections = remember(capabilities.audiobookExport) {
        SettingsSection.entries.filter { section ->
            section != SettingsSection.Audiobooks || capabilities.audiobookExport
        }
    }

    LaunchedEffect(ui.section) {
        when (ui.section) {
            SettingsSection.Account -> holder.verifyAccount()
            SettingsSection.Devices -> holder.ensureDevicesLoaded()
            SettingsSection.Offline -> holder.ensureOfflineLoaded()
            SettingsSection.Audiobooks -> holder.ensureAudiobooksLoaded()
            else -> Unit
        }
    }

    val selectSection: (SettingsSection) -> Unit = { section ->
        holder.openSection(section)
        onSectionSelected(section)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // One shared definition of "narrow" across Settings, the player and the library.
        val stacked = windowSizeClassFor(maxWidth).isCompact
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
                        SettingsSection.Account -> AccountPane(ui, session, capabilities, sessionStore, holder, selectSection, nowMs)
                        SettingsSection.Devices -> DevicesPane(ui, session, holder, nowMs)
                        SettingsSection.Playback -> PlaybackPane(
                            preferences,
                            canChangeSpeed,
                            canSkipSilence,
                            closeToTray,
                            onCloseToTrayChange,
                            traySupported,
                        )
                        SettingsSection.Listening -> ListeningPane(listeningStats, historyOwnerKey, nowMs)
                        SettingsSection.Offline -> OfflinePane(ui.offline, holder)
                        SettingsSection.Audiobooks -> AudiobookPane(ui.audiobooks, holder)
                        SettingsSection.About -> AboutPane(session, capabilities, sessionStore, updates)
                    }
                }
            }
        }

        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                SettingsNav(
                    current = ui.section,
                    sections = visibleSections,
                    stacked = true,
                    onSelect = selectSection,
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Box(Modifier.weight(1f).fillMaxWidth()) { pane() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                SettingsNav(
                    current = ui.section,
                    sections = visibleSections,
                    stacked = false,
                    onSelect = selectSection,
                )
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

const val AudiobookDownloadButtonTestTag: String = "audiobookDownloadButton"

@Composable
private fun AudiobookPane(ui: AudiobookExportsUiState, holder: SettingsStateHolder) {
    PaneTitle("Audiobooks", "Save finished whole-fiction M4B exports to this computer")
    when {
        ui.unsupported -> InfoCard(
            "This server has no mobile audiobook-export API. Existing chapter playback and " +
                "offline downloads are unaffected.",
        )
        ui.adminOnly -> InfoCard(
            "Audiobook exports are shared server files and are available to administrators only.",
        )
        ui.isInitialLoad -> Box(Modifier.fillMaxWidth().height(120.dp)) { CenterProgress() }
        ui.loaded == null -> {
            Text(
                ui.error ?: "Could not load audiobook exports",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = holder::refreshAudiobooks,
                enabled = !ui.isLoading,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("RETRY") }
        }
        else -> {
            InfoCard(
                "This list is read-only. Create or remove exports in the server admin; the " +
                    "desktop saves completed volumes for third-party audiobook players.",
            )
            if (!ui.loaded.ffmpegAvailable) {
                InfoCard(
                    "The server cannot create a new export right now because ffmpeg is unavailable. " +
                        "Finished exports below can still be downloaded.",
                )
            }
            if (ui.isLoading) ThinProgress(1f, Modifier.fillMaxWidth(), 2.dp)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.notice?.let { MetaText(it, color = AarisColor.Ok) }

            if (ui.loaded.exports.isEmpty()) {
                InfoCard("There are no finished audiobook exports on this server.")
            } else {
                ui.loaded.exports.forEach { export ->
                    AudiobookExportCard(export, ui, holder)
                }
            }
        }
    }
}

@Composable
private fun AudiobookExportCard(
    export: AudiobookExport,
    ui: AudiobookExportsUiState,
    holder: SettingsStateHolder,
) {
    val downloading = ui.downloadingExportId == export.id
    SettingsCard {
        Text(export.title, style = MaterialTheme.typography.titleMedium, color = AarisColor.Ink)
        export.fictionTitle?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
        MetaText(
            buildList {
                if (export.partCount > 1) add("Part ${export.partIndex} of ${export.partCount}")
                if (export.chapterCount > 0) add("${export.chapterCount} chapters")
                export.durationLabel?.takeIf { it.isNotBlank() }?.let(::add)
                export.sizeLabel?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ").ifBlank { export.filename },
            color = AarisColor.Dim,
        )
        export.completedAt?.let { completed ->
            formatServerTimestamp(completed)?.let { MetaText("Completed $it", color = AarisColor.Dim) }
        }
        if (downloading) {
            val progress = ui.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { holder.downloadAudiobook(export) },
                enabled = export.downloadable && ui.downloadingExportId == null,
                shape = RectangleShape,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(AudiobookDownloadButtonTestTag),
            ) { Text("SAVE M4B") }
            if (downloading) {
                OutlinedButton(
                    onClick = holder::cancelAudiobookDownload,
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) { Text("PAUSE") }
            }
        }
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

    SettingsConfirmation.DeleteAllDownloads -> ConfirmationCopy(
        title = "DELETE ALL DOWNLOADS",
        body = "Every chapter you explicitly downloaded for this account will be removed. " +
            "This cannot be undone; streamed cache data is not affected.",
        confirmLabel = "DELETE DOWNLOADS",
    )

    SettingsConfirmation.ClearStreamingCache -> ConfirmationCopy(
        title = "CLEAR STREAMING CACHE",
        body = "Rebuildable audio retained while listening will be removed. Your explicit " +
            "downloads stay available offline.",
        confirmLabel = "CLEAR CACHE",
    )
}

// --- Navigation ----------------------------------------------------------------------------

@Composable
private fun SettingsNav(
    current: SettingsSection,
    sections: List<SettingsSection>,
    stacked: Boolean,
    onSelect: (SettingsSection) -> Unit,
) {
    if (stacked) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PageGutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sections.forEach { NavEntry(it, it == current, onSelect) }
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
            sections.forEach { NavEntry(it, it == current, onSelect) }
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
    onSelectSection: (SettingsSection) -> Unit,
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
            onClick = { onSelectSection(SettingsSection.Devices) },
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
private fun PlaybackPane(
    preferences: PlaybackPreferencesStore,
    canChangeSpeed: Boolean,
    canSkipSilence: Boolean,
    closeToTray: Boolean,
    onCloseToTrayChange: (Boolean) -> Unit,
    traySupported: Boolean,
) {
    val prefs by preferences.preferences.collectAsState()

    PaneTitle("Playback", "Kept on this computer, not on your account")

    SettingsCard {
        if (canChangeSpeed) {
            ChoiceRow(
                label = "DEFAULT SPEED",
                // The offered list carries the stored value even when it is not a preset, so a
                // rate set by another build stays selectable instead of vanishing on first open.
                options = PlaybackPreferences.speedOptions(prefs.speed),
                selected = prefs.speed,
                labelOf = ::formatSpeed,
                onSelect = { value -> preferences.update { it.copy(speed = value) } },
            )
            MetaText(
                if (prefs.fictionSpeeds.isEmpty()) {
                    "The rate every book starts at. Changing speed in the player sets it for that " +
                        "book alone, since different narrators want different paces."
                } else {
                    "The rate every book starts at. ${prefs.fictionSpeeds.size} book(s) have " +
                        "their own rate, set from the player; each can be put back there."
                },
            )
        } else {
            SettingRow("DEFAULT SPEED", "Fixed at ${formatSpeed(1f)}")
            MetaText(
                "The audio backend on this computer cannot resample, so speed is not offered " +
                    "rather than offered and ignored. Installing GStreamer enables it.",
            )
        }

        RowDivider()

        ChoiceRow(
            label = "SKIP INTERVAL",
            options = PlaybackPreferences.SkipIntervals,
            selected = prefs.skipIntervalSeconds,
            labelOf = { "$it s" },
            onSelect = { value -> preferences.update { it.copy(skipIntervalSeconds = value) } },
        )

        RowDivider()

        ChoiceRow(
            label = "VOLUME BOOST",
            options = VolumeBoost.entries.toList(),
            selected = prefs.volumeBoost,
            labelOf = { it.label },
            onSelect = { value -> preferences.update { it.copy(volumeBoost = value) } },
        )
        MetaText("Boost stops at ${formatSpeed(VolumeBoost.High.gain.toFloat())} — louder than that clips quiet narration instead of raising it.")

        RowDivider()

        if (canSkipSilence) {
            ToggleRow(
                label = "SKIP SILENCE",
                description = "Drops dead air between sentences. Off by default, to match the " +
                    "mobile app and keep chapter timings where the server put them.",
                checked = prefs.skipSilence,
                onCheckedChange = { value -> preferences.update { it.copy(skipSilence = value) } },
            )
        } else {
            SettingRow("SKIP SILENCE", "Not available on this computer")
            MetaText(
                "Silence removal needs the GStreamer \"removesilence\" element, which ships in " +
                    "gst-plugins-bad. It is not installed here, so the control is not shown.",
            )
        }

        RowDivider()

        if (traySupported) {
            ToggleRow(
                label = "KEEP PLAYING WHEN THE WINDOW CLOSES",
                description = "Closing the window puts TTSRoad in the system tray and keeps the " +
                    "chapter playing. Off by default, because a close control that closes is what " +
                    "everyone expects. The tray icon's Quit entry always stops it for good.",
                checked = closeToTray,
                onCheckedChange = onCloseToTrayChange,
            )
        } else {
            SettingRow("KEEP PLAYING WHEN THE WINDOW CLOSES", "No system tray on this desktop")
            MetaText(
                "This desktop session does not offer a system tray, so closing the window would " +
                    "leave TTSRoad running with no way back to it. Closing quits instead.",
            )
        }
    }
}

/** `1.25×`, `1.0×` — one decimal unless the value needs two. */
private fun formatSpeed(speed: Float): String {
    val rounded = kotlin.math.round(speed * 100) / 100f
    val text = if (kotlin.math.abs(rounded * 10 - kotlin.math.round(rounded * 10)) < 0.01f) {
        String.format(java.util.Locale.ROOT, "%.1f", rounded)
    } else {
        String.format(java.util.Locale.ROOT, "%.2f", rounded)
    }
    return "$text×"
}

/**
 * A labelled row of mutually exclusive choices.
 *
 * Now a thin alias over [AarisChoiceRow]. The pattern started here and was reimplemented — badly —
 * on the player and in the reader, where the options ended up as plain clickable text with no role
 * and no selected state; it lives in `Components.kt` so there is one of it. See #82.
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) = AarisChoiceRow(label, options, selected, labelOf, onSelect)

/**
 * Hours, chapters and a streak, computed locally from `listening.json`.
 *
 * Nothing here is sent anywhere or read from the server: the totals are about time spent on *this*
 * machine, they have to survive a sign-out, and there is no account contract for them.
 */
@Composable
private fun ListeningPane(
    stats: ListeningStatsStore,
    ownerKey: String,
    nowMs: () -> Long,
) {
    val days by stats.days.collectAsState()
    // Keyed on the rows and the account, not on the clock: a pane that recomputed on every frame
    // would walk two years of history for a number that changes at midnight.
    val summary = remember(days, ownerKey) {
        ListeningStats.summarise(days, ownerKey, LocalDate.parse(ListeningStats.dateOf(nowMs())))
    }

    PaneTitle("Listening", "Counted on this computer, for this account")

    SettingsCard {
        if (!summary.hasAnything) {
            SettingRow("SO FAR", "Nothing yet")
            MetaText(
                "Totals start the first time a chapter plays. They are computed here from a local " +
                    "file and are never sent to the server.",
            )
            return@SettingsCard
        }

        SettingRow("TOTAL", formatListeningSpan(summary.seconds))
        RowDivider()
        SettingRow("CHAPTERS FINISHED", "${summary.chaptersFinished}")
        MetaText("A chapter counts once it has played to its end — marking one played by hand does not.")
        RowDivider()
        SettingRow("LAST 7 DAYS", formatListeningSpan(summary.last7DaysSeconds))
        RowDivider()
        SettingRow("LAST 30 DAYS", formatListeningSpan(summary.last30DaysSeconds))
        RowDivider()
        SettingRow("CURRENT STREAK", plural(summary.currentStreakDays, "day"))
        MetaText("Today or yesterday keeps a streak alive; a whole day missed ends it.")
        RowDivider()
        SettingRow("LONGEST STREAK", plural(summary.longestStreakDays, "day"))
        RowDivider()
        SettingRow("DAYS WITH ANY LISTENING", plural(summary.daysListened, "day"))
        RowDivider()
        SettingRow("BEST DAY", formatListeningSpan(summary.bestDaySeconds))
    }
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetaText(label)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = AarisColor.Muted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun OfflinePane(state: OfflineStorageUiState, holder: SettingsStateHolder) {
    PaneTitle("Offline", "Requested downloads and rebuildable streaming data")

    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = holder::refreshOffline,
            enabled = !state.isLoading && !state.isBusy,
            shape = RectangleShape,
        ) { Text("RETRY") }
    }
    state.notice?.let { InfoCard(it) }

    val summary = state.loaded
    when {
        state.isInitialLoad -> InfoCard("Measuring offline storage…")
        summary == null || !summary.available -> InfoCard(
            "Offline storage is unavailable for this session. No account-protected download " +
                "metadata is opened while signed out or when a private data directory cannot be used.",
        )
        else -> OfflineStorageContent(summary, state.isBusy, holder)
    }
}

@Composable
private fun OfflineStorageContent(
    summary: OfflineStorageSummary,
    busy: Boolean,
    holder: SettingsStateHolder,
) {
    SettingsCard {
        SettingRow(
            "Requested downloads",
            "${formatStorageBytes(summary.downloadBytes)} · ${summary.downloadedChapters} complete",
        )
        RowDivider()
        SettingRow(
            "Streaming cache",
            "${formatStorageBytes(summary.streamingCacheBytes)} · ${summary.streamingCacheFiles} chapters",
        )
    }

    MetaText(text = "// By fiction", color = AarisColor.Accent)
    SettingsCard {
        if (summary.fictions.isEmpty()) {
            MetaText("No requested audio occupies disk for this account.")
        } else {
            summary.fictions.forEachIndexed { index, fiction ->
                if (index > 0) RowDivider()
                SettingRow(
                    fiction.title,
                    "${formatStorageBytes(fiction.bytes)} · ${fiction.chapters} chapters",
                )
            }
        }
    }

    MetaText(
        "Signing out keeps requested downloads on disk, but closes this account's titles and " +
            "index until the same account signs in again. Streaming data is cache and may be evicted.",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = holder::askDeleteAllDownloads,
            enabled = !busy && summary.downloadBytes > 0L,
            shape = RectangleShape,
        ) { Text("DELETE ALL DOWNLOADS") }
        OutlinedButton(
            onClick = holder::askClearStreamingCache,
            enabled = !busy && summary.streamingCacheBytes > 0L,
            shape = RectangleShape,
        ) { Text("CLEAR STREAMING CACHE") }
        OutlinedButton(
            onClick = holder::refreshOffline,
            enabled = !busy,
            shape = RectangleShape,
        ) { Text("REFRESH") }
    }
}

/** Binary storage units, stable and locale-independent so Settings and tests agree exactly. */
fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = safe.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    val pattern = if (value >= 10.0) "%.0f %s" else "%.1f %s"
    return String.format(java.util.Locale.ROOT, pattern, value, units[unit])
}

// --- Updates & About -----------------------------------------------------------------------

@Composable
private fun AboutPane(
    session: SessionState,
    capabilities: ServerCapabilities,
    sessionStore: SessionStore,
    updates: UpdateStateHolder? = null,
) {
    PaneTitle("Updates & About", "Build, licences and diagnostics")

    SettingsCard {
        // BuildInfo is generated from the single `ttsroad.version` Gradle property, so this always
        // matches the installer the user actually ran.
        SettingRow("Application", "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}")
        RowDivider()
        SettingRow("Release channel", "Installed build")
        RowDivider()
        SettingRow("Java runtime", "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    }

    // Absent only where the caller supplied no updater — a preview or a screen test. Claiming to
    // be "up to date" without an updater would be a claim nothing checked.
    if (updates != null) UpdateCard(updates)

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
    var exported by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { copyToClipboard(diagnostics) },
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("COPY DIAGNOSTICS") }
        OutlinedButton(
            onClick = { exported = exportDiagnostics(diagnostics)?.toString() },
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("EXPORT DIAGNOSTICS") }
    }
    exported?.let { path ->
        Text(
            "Saved to $path",
            style = MaterialTheme.typography.bodySmall,
            color = AarisColor.Muted,
        )
    }
}

/**
 * The update card: what the last check found, and the two actions that follow from it.
 *
 * Download is offered only when the release actually carries a package for this machine. A release
 * that does not is still announced — with a link rather than a button, because a download that
 * cannot be installed here is worse than no button at all.
 */
@Composable
private fun UpdateCard(updates: UpdateStateHolder) {
    val state by updates.state.collectAsState()

    // Once per entry to the pane. The checker owns the throttle, so this is not a request per view.
    LaunchedEffect(Unit) { updates.checkAutomatically() }

    MetaText(text = "// Updates", color = AarisColor.Accent)
    SettingsCard {
        val status = state.status
        SettingRow(
            "Update check",
            when (status) {
                is UpdateStatus.Checking -> "Checking…"
                is UpdateStatus.UpToDate -> "${BuildInfo.VERSION} is the newest release"
                is UpdateStatus.Available -> "Version ${status.release.version} is available"
                is UpdateStatus.Failed -> status.reason
                is UpdateStatus.Unknown -> "Not checked yet"
            },
        )
        RowDivider()
        ToggleRow(
            label = "Check automatically",
            description = "At most once a day, and once per launch.",
            checked = state.automatic,
            onCheckedChange = updates::setAutomatic,
        )
    }

    state.available?.let { release ->
        if (release.notes.isNotBlank()) {
            SettingsCard {
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AarisColor.Muted,
                )
            }
        }
    }

    state.downloadError?.let { error ->
        Text(error, style = MaterialTheme.typography.bodyMedium, color = AarisColor.Danger)
    }
    state.downloadedName?.let { name ->
        Text(
            "Downloaded and verified $name. Your desktop's installer takes it from here.",
            style = MaterialTheme.typography.bodyMedium,
            color = AarisColor.Muted,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = updates::checkNow,
            enabled = state.status !is UpdateStatus.Checking,
            shape = RectangleShape,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) { Text("CHECK NOW") }

        if (state.available != null) {
            if (state.downloadable != null) {
                Button(
                    onClick = updates::download,
                    enabled = !state.isDownloading,
                    shape = RectangleShape,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) { Text(if (state.isDownloading) "DOWNLOADING…" else "DOWNLOAD") }
            }
            TextButton(
                onClick = updates::dismiss,
                shape = RectangleShape,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("DISMISS") }
        }
    }
    if (state.available != null && state.downloadable == null) {
        Text(
            "This release publishes nothing for this platform and architecture.",
            style = MaterialTheme.typography.bodyMedium,
            color = AarisColor.Muted,
        )
    }
}

/**
 * Writes the redacted diagnostics block to a file the user can attach to a report.
 *
 * The same text the Copy button produces, so there is one redaction boundary rather than two. A
 * failure returns null instead of throwing: not being able to write a support file is not a reason
 * to take Settings down.
 */
private fun exportDiagnostics(diagnostics: String): java.io.File? = runCatching {
    val directory = java.io.File(AppDirectories.cacheDir(), "diagnostics")
    directory.mkdirs()
    val stamp = java.time.Instant.now().toString().replace(':', '-').substringBefore('.')
    val file = java.io.File(directory, "ttsroad-diagnostics-$stamp.txt")
    file.writeText(diagnostics)
    file
}.getOrNull()

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
        if (capabilities.audiobookExport) add("Audiobook exports")
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
