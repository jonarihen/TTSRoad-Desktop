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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.window.isTraySupported
import dk.perspektiva.ttsroad.desktop.data.Bookmark
import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
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
import dk.perspektiva.ttsroad.desktop.ui.BookmarksScreen
import dk.perspektiva.ttsroad.desktop.ui.BookmarksStateHolder
import dk.perspektiva.ttsroad.desktop.ui.ChapterNotificationsStateHolder
import dk.perspektiva.ttsroad.desktop.ui.NotificationsScreen
import dk.perspektiva.ttsroad.desktop.ui.ContentMaxWidth
import dk.perspektiva.ttsroad.desktop.ui.FictionDetailScreen
import dk.perspektiva.ttsroad.desktop.ui.FictionManagementDialogs
import dk.perspektiva.ttsroad.desktop.ui.FictionManagementStateHolder
import dk.perspektiva.ttsroad.desktop.ui.FictionMetadataScreen
import dk.perspektiva.ttsroad.desktop.ui.FictionMetadataStateHolder
import dk.perspektiva.ttsroad.desktop.ui.LibraryScreen
import dk.perspektiva.ttsroad.desktop.ui.LoginStateHolder
import dk.perspektiva.ttsroad.desktop.ui.MetaText
import dk.perspektiva.ttsroad.desktop.ui.NowPlayingBar
import dk.perspektiva.ttsroad.desktop.ui.PageGutter
import dk.perspektiva.ttsroad.desktop.ui.PlayerScreen
import dk.perspektiva.ttsroad.desktop.ui.ReaderScreen
import dk.perspektiva.ttsroad.desktop.ui.SearchScreen
import dk.perspektiva.ttsroad.desktop.ui.SearchStateHolder
import dk.perspektiva.ttsroad.desktop.ui.ServerQueueScreen
import dk.perspektiva.ttsroad.desktop.ui.ServerQueueStateHolder
import dk.perspektiva.ttsroad.desktop.ui.SettingsScreen
import dk.perspektiva.ttsroad.desktop.ui.SettingsSection
import dk.perspektiva.ttsroad.desktop.ui.SettingsStateHolder
import dk.perspektiva.ttsroad.desktop.ui.ShortcutsDialog
import dk.perspektiva.ttsroad.desktop.ui.WindowSizeClass
import dk.perspektiva.ttsroad.desktop.ui.fictionForHit
import dk.perspektiva.ttsroad.desktop.ui.hasSession
import dk.perspektiva.ttsroad.desktop.ui.rememberChapterDownloads
import dk.perspektiva.ttsroad.desktop.ui.rememberChapterQueue
import dk.perspektiva.ttsroad.desktop.ui.UpdateStateHolder
import dk.perspektiva.ttsroad.desktop.ui.ChapterMaintenanceStateHolder
import dk.perspektiva.ttsroad.desktop.data.fictionMaintenanceActions
import dk.perspektiva.ttsroad.desktop.ui.ChapterMaintenanceUi
import dk.perspektiva.ttsroad.desktop.ui.ManageShelfScreen
import dk.perspektiva.ttsroad.desktop.ui.PodcastFeedsStateHolder
import dk.perspektiva.ttsroad.desktop.ui.ManageShelfStateHolder
import dk.perspektiva.ttsroad.desktop.ui.rememberStateHolder
import dk.perspektiva.ttsroad.desktop.ui.windowSizeClassFor
import kotlinx.coroutines.launch

/**
 * Root composable. The object graph is *passed in*, not built here — see [AppContainer]. The
 * default argument keeps `App()` usable from a preview or a smoke test, but `main()` owns the
 * real container so it can be closed when the window closes.
 */
@Composable
fun App(
    container: AppContainer = remember { AppContainer() },
    /**
     * Raises a system notification. Supplied by `Main`, which owns the tray.
     *
     * A `(title, body) -> Unit` rather than the tray state, so nothing in this tree depends on a
     * tray existing — a desktop session without one still gets the badge and the list, and a
     * Compose test raises nothing at all.
     */
    notify: (String, String) -> Unit = { _, _ -> },
) {
    val sessionStore = container.sessionStore
    val repository = container.repository
    val playback = container.playback
    val cache = container.libraryCache
    val session by sessionStore.session.collectAsState()
    val closeToTray by container.closeToTray.collectAsState()
    // Whether this desktop session has a system tray at all. Read once — it cannot change while
    // the process runs — and `false` in a Compose test, where the setting is honestly unavailable.
    val traySupported = remember { runCatching { isTraySupported }.getOrDefault(false) }
    val sessionEnd by repository.sessionEnd.collectAsState()
    val capabilities by repository.currentCapabilities.collectAsState()
    // Hoisted above navigation on purpose: `when (destination)` disposes the screen it leaves, so a
    // holder created inside SettingsScreen would drop the selected pane and the loaded device list
    // every time the user glanced at the library.
    // Hoisted above navigation like the settings holder, so leaving the About pane and coming
    // back shows the answer the last check produced instead of checking again.
    val updates = rememberStateHolder(container) {
        UpdateStateHolder(container.updateChecker, container.updateDownloader, container.updateSettings)
    }
    val settings = rememberStateHolder(repository, sessionStore) {
        SettingsStateHolder(
            repository,
            sessionStore,
            offlineStorage = container.downloads,
            audiobookDownloader = container.audiobookExportDownloader,
        )
    }
    // Hoisted for the same reason: following a hit and coming back must find the results still
    // there. A search you have to run twice to use is not a search.
    val search = rememberStateHolder(repository) { SearchStateHolder(repository) }
    // Hoisted for a sharper reason than the other two: bookmarks are *written* from the player and
    // the reader and *read* on a destination the user may never open, so a holder owned by the
    // bookmarks screen would give Ctrl+B nowhere to put anything.
    val bookmarks = rememberStateHolder(repository, cache) {
        BookmarksStateHolder(repository, cachedChapters = { cache.chapters(it).value.value })
    }
    // Not hoisted for a writer elsewhere, unlike the three below: nothing outside this screen edits
    // a shelf in bulk. It lives here so the selection survives a trip into a fiction and back,
    // which is exactly what somebody deciding what to drop will do.
    val manageShelf = rememberStateHolder(cache) { ManageShelfStateHolder(cache) }
    val chapterMaintenance = rememberStateHolder(repository, cache) {
        ChapterMaintenanceStateHolder(repository, cache)
    }
    val podcastFeeds = rememberStateHolder(repository) { PodcastFeedsStateHolder(repository) }
    // Hoisted for the same reason: "Add to queue" is pressed on a chapter list, so the queue has to
    // exist and report what happened whether or not the queue screen was ever opened.
    val serverQueue = rememberStateHolder(repository, playback) {
        ServerQueueStateHolder(repository, playback)
    }
    // Hoisted, and for the sharpest reason of the four: the header badge and the system
    // notification are driven by a poll that has to keep running whether or not this destination
    // has ever been opened. A holder owned by the screen would mean the app only found out about a
    // new chapter while you were already looking at the list of them.
    val chapterNotifications = rememberStateHolder(repository) {
        ChapterNotificationsStateHolder(repository, notify = notify)
    }
    val notificationState by chapterNotifications.state.collectAsState()
    // Started here rather than in the screen, for the same reason it is hoisted here — and gated on
    // the *session*, not only on the capability. Discovery is unauthenticated, so a capable server
    // reports `notifications: true` while the login form is still on screen; without this the poll
    // would start there and fail every minute against a request that has no credential to send.
    LaunchedEffect(chapterNotifications, capabilities.notifications, session.isLoggedIn) {
        if (capabilities.notifications && session.isLoggedIn) chapterNotifications.start()
    }

    val fictionManagement = rememberStateHolder(repository, cache) {
        FictionManagementStateHolder(repository, cache)
    }
    val fictionManagementState by fictionManagement.state.collectAsState()
    val chapterMaintenanceState by chapterMaintenance.state.collectAsState()
    // Hoisted for the same reason the management holder is: the editor is a form, a save is a
    // request, and neither may be lost because the user glanced at the library mid-edit.
    val fictionMetadata = rememberStateHolder(repository, cache) {
        FictionMetadataStateHolder(repository, cache)
    }

    // Capability discovery is the only source of the server's stable advertised identity. Feed it
    // into the download namespace as soon as it arrives; using it only for feature flags would
    // leave downloads tied to a transient LAN/public address despite the server explicitly naming
    // itself.
    LaunchedEffect(capabilities.serverBaseUrl) {
        container.downloads.advertisedBaseUrl = capabilities.serverBaseUrl
    }

    // Retained per-destination UI state: search text, filters and scroll offsets are stored under
    // the destination's stable key and released when that destination leaves the back stack.
    val screenState = rememberSaveableStateHolder()
    val nav = remember { NavigationState(onDestinationDropped = screenState::removeState) }
    val playerState by playback.state.collectAsState()

    val scope = rememberCoroutineScope()

    LaunchedEffect(
        session.isLoggedIn,
        capabilities.fictionManagement,
        capabilities.epubUpload,
        capabilities.voiceCatalogue,
    ) {
        if (session.isLoggedIn) {
            fictionManagement.ensureAccess(
                capabilities.fictionManagement,
                epubUpload = capabilities.epubUpload,
                maxEpubBytes = capabilities.maxEpubBytes,
                voiceCatalogue = capabilities.voiceCatalogue,
            )
        }
    }

    LaunchedEffect(fictionManagementState.deletedFictionId) {
        val deleted = fictionManagementState.deletedFictionId ?: return@LaunchedEffect
        val current = nav.current
        val editingDeleted = current is Destination.FictionMetadata && current.fiction.id == deleted
        if (current is Destination.Fiction && current.fiction.id == deleted || editingDeleted) nav.back()
        fictionManagement.consumeDeletedFiction()
    }

    fun refreshCurrentScreen() {
        when (val destination = nav.current) {
            Destination.Library -> {
                cache.refreshLibrary()
                fictionManagement.ensureAccess(
                    capabilities.fictionManagement,
                    epubUpload = capabilities.epubUpload,
                    maxEpubBytes = capabilities.maxEpubBytes,
                    voiceCatalogue = capabilities.voiceCatalogue,
                    forceRefresh = true,
                )
            }
            is Destination.Fiction -> {
                cache.refreshChapters(destination.fiction.id)
                fictionManagement.ensureAccess(
                    capabilities.fictionManagement,
                    epubUpload = capabilities.epubUpload,
                    maxEpubBytes = capabilities.maxEpubBytes,
                    voiceCatalogue = capabilities.voiceCatalogue,
                    forceRefresh = true,
                )
            }
            Destination.Settings, Destination.Devices -> settings.refreshCurrentSection()
            Destination.Search -> search.refresh()
            Destination.Bookmarks -> bookmarks.refresh()
            Destination.ManageShelf -> manageShelf.refresh()
            Destination.Notifications -> chapterNotifications.refresh()
            Destination.Queue -> serverQueue.refresh()
            // A form and a player have nothing a refresh could improve; see `isRefreshable`.
            Destination.Player, is Destination.Reader, is Destination.FictionMetadata -> Unit
        }
    }

    /**
     * Marks the spot that is playing.
     *
     * Takes the position from the player rather than from whatever screen is on top, so Ctrl+B in
     * the library marks the chapter that is *playing* — the only position the app can honestly
     * claim the user meant. The reader passes its own sentence text as a label; see below.
     */
    fun addBookmark(label: String? = null) {
        val chapterId = playerState.queue.getOrNull(playerState.currentIndex)?.chapterId ?: 0
        if (chapterId > 0) bookmarks.add(chapterId, playerState.positionMs, label)
    }

    /**
     * Opens a mark: loads its fiction, queues it, and starts *at the mark* rather than at the
     * chapter's saved resume position — the whole point of having clicked it.
     *
     * Loaded through the bookmarks holder, which prefers chapters this session already has and
     * otherwise fetches them: a bookmark can point at a serial the session has never opened, and a
     * failure has to be visible on the screen the click came from.
     */
    /**
     * Opens the fiction a notice points at.
     *
     * Resolved through the library cache rather than the notice's own payload: the notice carries
     * enough to *draw* a row, deliberately not enough to be a fiction — one is a message, the other
     * is the object every screen downstream expects.
     */
    fun openNotificationFiction(notification: ChapterNotification) {
        scope.launch {
            runCatching { cache.resolveFiction(notification.fiction.id) }
                .onSuccess { nav.open(Destination.Fiction(it)) }
        }
    }

    /** Plays the chapter a ready notice announced, straight from the list. */
    fun playNotification(notification: ChapterNotification) {
        scope.launch {
            val loaded = runCatching { repository.chapters(notification.fiction.id) }.getOrNull()
                ?: return@launch
            // Guarded rather than assumed: the list can be a minute old, and a chapter that has
            // since been excluded would otherwise start the queue at whatever sorted first.
            if (loaded.chapters.none { it.id == notification.chapter.id }) return@launch
            playback.playQueue(loaded.chapters, notification.chapter.id, loaded.fiction)
            nav.open(Destination.Player)
        }
    }

    fun openBookmark(bookmark: Bookmark) {
        val chapterId = bookmark.chapterId ?: return
        scope.launch {
            // The holder owns the request *and* the sentence about it failing; this navigator only
            // acts on a success. Discarding the exception here is what made a Play against an
            // unreachable server look like a click that never registered.
            val loaded = bookmarks.loadForPlayback(bookmark) ?: return@launch
            playback.playQueue(loaded.chapters, chapterId, loaded.fiction, bookmark.positionMs)
            nav.open(Destination.Player)
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
    var showShortcuts by remember { mutableStateOf(false) }

    /**
     * Distraction-free reading, owned here because half of what it hides is this file's chrome.
     *
     * Not a stored preference: it is a posture the reader takes for one sitting, and a client that
     * silently reopened with no header a week later would look broken rather than focused. Leaving
     * the reader drops it for the same reason — there is nothing to be distraction-free *from* on a
     * settings pane.
     */
    var readingMode by remember { mutableStateOf(false) }
    LaunchedEffect(nav.current) { if (nav.current !is Destination.Reader) readingMode = false }

    fun dismissOrGoBack(): Boolean {
        // The shortcuts dialog is owned here rather than by a screen, so it is the first thing
        // Escape closes — ahead of a settings confirmation that may also be open behind it.
        if (showShortcuts) {
            showShortcuts = false
            return true
        }
        // Escape restores the frame before it leaves the chapter: the first press of the key that
        // means "undo the last mode change" should not also lose the reader's place.
        if (readingMode) {
            readingMode = false
            return true
        }
        if (fictionManagementState.hasOpenOverlay) {
            fictionManagement.dismissOverlay()
            return true
        }
        val ownsOverlay = nav.current == Destination.Settings || nav.current == Destination.Devices
        return when (escapeAction(settings.hasOpenOverlay && ownsOverlay, nav.canGoBack)) {
            EscapeAction.CloseOverlay -> settings.dismissTopOverlay()
            EscapeAction.GoBack -> nav.back()
            EscapeAction.None -> false
        }
    }

    // Each branch reports whether it actually did something: a Back, an Escape or a transport key
    // with nothing to act on must fall through rather than be swallowed at the root.
    fun handleShortcut(shortcut: AppShortcut?): Boolean {
        // Transport shortcuts need something loaded. Without this guard, Space on the login screen
        // would report itself handled and never reach the button under the cursor.
        fun whenPlaying(action: () -> Unit): Boolean {
            if (!playerState.hasMedia) return false
            action()
            return true
        }
        return when (shortcut) {
            AppShortcut.Back -> nav.back()

            AppShortcut.Refresh -> {
                refreshCurrentScreen()
                true
            }

            AppShortcut.Dismiss -> dismissOrGoBack()

            AppShortcut.PlayPause -> whenPlaying(playback::togglePlayPause)
            AppShortcut.SeekBackward -> whenPlaying(playback::skipBackward)
            AppShortcut.SeekForward -> whenPlaying(playback::skipForward)
            AppShortcut.PreviousChapter -> whenPlaying(playback::skipToPreviousChapter)
            AppShortcut.NextChapter -> whenPlaying(playback::skipToNextChapter)

            AppShortcut.OpenLibrary -> {
                nav.open(Destination.Library)
                true
            }

            AppShortcut.OpenSettings -> {
                nav.open(Destination.Settings)
                true
            }

            // Both are inert on a server with no bookmark API, and report themselves unhandled so
            // the key falls through rather than being silently swallowed by a feature that is not
            // there.
            AppShortcut.AddBookmark -> capabilities.bookmarks && whenPlaying { addBookmark() }

            AppShortcut.OpenBookmarks -> capabilities.bookmarks.also {
                if (it) nav.open(Destination.Bookmarks)
            }

            // Only the reader has a frame worth hiding, so anywhere else the key reports itself
            // unhandled rather than arming a mode with nothing to show.
            AppShortcut.ToggleReadingMode -> (nav.current is Destination.Reader).also {
                if (it) readingMode = !readingMode
            }

            AppShortcut.ShowShortcuts -> {
                showShortcuts = true
                true
            }

            null -> false
        }
    }

    // One reaction to "there is no session any more", whether that came from Sign out or from a
    // 401 on an API or audio request: stop the audio, drop the cached library, and reset
    // navigation. Without it a revoked token leaves a chapter playing behind the login screen.
    LaunchedEffect(session.isLoggedIn) {
        if (!session.isLoggedIn) {
            // Close account-protected indexes immediately; retained login hints are not authority.
            container.downloads.refresh()
            playback.stop()
            nav.resetToRoot()
            // The library belonged to the account that just ended; the next person to sign in on
            // this machine must not be shown it.
            cache.clear()
            container.readAlongCache.clear()
            settings.sessionEnded()
            search.sessionEnded()
            bookmarks.sessionEnded()
            serverQueue.sessionEnded()
            fictionManagement.sessionEnded()
            fictionMetadata.sessionEnded()
            chapterNotifications.sessionEnded()
        } else {
            // Cheap, and it is what makes optional UI correct after a restart, where login did
            // not run but a keyring-backed session was restored.
            val discovered = repository.refreshCurrentCapabilities()
            // Discovery has to precede the first namespace open. Opening against the connect URL
            // and moving to server.base_url a moment later would strand a download made during
            // startup in an address-derived directory.
            container.downloads.advertisedBaseUrl = discovered.serverBaseUrl
            container.downloads.refresh()
            // Local values are already usable; a capable server can now replace them with this
            // account's cross-device reader settings. Older/offline servers leave them alone.
            container.readerPreferences.refreshFromServer()
            // Relaunching is the reconnect that matters: positions recorded while the last run was
            // offline have been on disk since, and this is the first chance to send them. After
            // discovery, so the flush knows whether the server can order writes. Failure is fine —
            // the queue survives and the next save retries it.
            runCatching { repository.flushProgress() }
        }
    }

    val rootFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(AarisColor.Bg)
            .focusRequester(rootFocus)
            .focusable()
            // Two handlers, and the split is what makes "shortcuts do not fire while typing"
            // structural rather than a check somebody has to remember.
            //
            // The *preview* pass runs before the focused component, so it is limited to the
            // combinations no text field claims — F5 in the library's search box still refreshes.
            // Passing `textInputFocused = true` is how that limit is expressed: it asks the table
            // for exactly the shortcuts that are safe mid-word.
            .onPreviewKeyEvent { event -> handleShortcut(shortcutFor(event, textInputFocused = true)) }
            // The ordinary pass runs only if nothing else consumed the key. A focused text field
            // has already taken Space, the arrows and Ctrl+arrow for editing by the time this
            // runs, so the transport shortcuts simply never see them — no focus tracking needed.
            .onKeyEvent { event ->
                val shortcut = shortcutFor(event, textInputFocused = false)
                // Anything the preview pass already had a chance at must not run twice.
                if (shortcut == null || shortcut.firesWhileTyping) false else handleShortcut(shortcut)
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
                    // Distraction-free reading takes the whole window: the header and the
                    // now-playing bar are exactly the two things framing the page, so hiding the
                    // reader's own toolbar while leaving these would not be the mode at all.
                    if (!readingMode) {
                        HeaderBar(
                            serverName = session.serverName,
                            current = nav.current,
                            canGoBack = nav.canGoBack,
                            canRefresh = nav.current.isRefreshable,
                            // Gated the way the speed and skip-silence controls are: no entry at all
                            // where the server cannot answer, rather than one that leads to a 404.
                            showBookmarks = capabilities.bookmarks,
                            compact = sizeClass == WindowSizeClass.Compact,
                            queueAvailable = capabilities.queue,
                            showNotifications = capabilities.notifications && !notificationState.unsupported,
                            unreadNotifications = notificationState.unread,
                            onBack = { nav.back() },
                            onRefresh = ::refreshCurrentScreen,
                            onSelect = { nav.open(it) },
                        )
                        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    }
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
                                    history = container.playbackHistory,
                                    browsePreferences = container.browsePreferences,
                                    historyOwnerKey = container.historyOwnerKey(),
                                    onOpenFiction = { nav.open(Destination.Fiction(it)) },
                                    onOpenPlayer = { nav.open(Destination.Player) },
                                    // The local filter stays the instant path; this is the second,
                                    // explicit one that can reach narration text.
                                    serverSearchAvailable = capabilities.search,
                                    onSearchServer = { query ->
                                        search.search(query)
                                        nav.open(Destination.Search)
                                    },
                                    // On a server without per-user libraries there is no shelf to
                                    // distinguish from the catalogue, so there is no mode to pick.
                                    followsAvailable = capabilities.follows,
                                    fictionManagement = fictionManagementState,
                                    onAddFiction = fictionManagement::openAdd,
                                )

                                Destination.Search -> SearchScreen(
                                    holder = search,
                                    // Same gate the chapter rows use: without the endpoint there
                                    // is no reader to land a text hit in.
                                    readAlongAvailable = capabilities.readAlong,
                                    onOpenFiction = { hit ->
                                        nav.open(
                                            Destination.Fiction(
                                                fictionForHit(
                                                    cache.library.value.value?.fictions.orEmpty(),
                                                    hit,
                                                ),
                                            ),
                                        )
                                    },
                                    onOpenReader = { chapterId, title ->
                                        nav.open(Destination.Reader(chapterId, title))
                                    },
                                    onBack = { nav.back() },
                                )

                                is Destination.Fiction -> FictionDetailScreen(
                                    fiction = destination.fiction,
                                    cache = cache,
                                    repository = repository,
                                    playback = playback,
                                    onBack = { nav.back() },
                                    downloads = rememberChapterDownloads(
                                        coordinator = container.downloads,
                                        fiction = destination.fiction,
                                        chapters = cache.chapters(destination.fiction.id)
                                            .collectAsState().value.value?.chapters.orEmpty(),
                                    ),
                                    // Capability-gated at the row. A chapter without timings still
                                    // opens as selectable plain narration text.
                                    onOpenReader = { chapter ->
                                        nav.open(
                                            Destination.Reader(chapter.resolvedChapterId, chapter.resolvedTitle),
                                        )
                                    },
                                    queue = rememberChapterQueue(
                                        holder = serverQueue,
                                        available = capabilities.queue,
                                        fictionId = destination.fiction.id,
                                    ),
                                    maintenance = ChapterMaintenanceUi(
                                        // Retry needs only the capability: the server leaves it
                                        // open to any account on purpose.
                                        retryAvailable = capabilities.chapterMaintenance,
                                        // Exclude and delete change a shared object, so they take
                                        // the same two gates the fiction controls do — and
                                        // `fictionManagementState.canManage` is the *verified*
                                        // is_admin, not the role cached at login.
                                        canModerate = capabilities.chapterMaintenance &&
                                            fictionManagementState.canManage,
                                        busyChapterId = chapterMaintenanceState.busyChapterId,
                                        notice = chapterMaintenanceState.notice,
                                        error = chapterMaintenanceState.error,
                                        onRetry = chapterMaintenance::retry,
                                        onSetExcluded = chapterMaintenance::setExcluded,
                                        onDelete = chapterMaintenance::delete,
                                        fictionActions = fictionMaintenanceActions(
                                            capabilities,
                                            // The verified is_admin, not the role cached at login.
                                            isAdmin = fictionManagementState.canManage,
                                        ),
                                        busyAction = chapterMaintenanceState.busyAction,
                                        confirming = chapterMaintenanceState.confirming,
                                        onFictionAction = { action ->
                                            chapterMaintenance.startFictionAction(
                                                destination.fiction,
                                                action,
                                            )
                                        },
                                        onConfirmAction = {
                                            chapterMaintenance.confirmFictionAction(destination.fiction)
                                        },
                                        onDismissConfirmation = chapterMaintenance::dismissConfirmation,
                                    ),
                                    fictionManagement = fictionManagementState,
                                    onEditFiction = { nav.open(Destination.FictionMetadata(it)) },
                                    onDeleteFiction = fictionManagement::askDelete,
                                )

                                is Destination.FictionMetadata -> {
                                    // Re-pointed on every fresher copy the cache publishes, and on
                                    // the destination's own payload before one has arrived. The
                                    // holder keeps whatever is typed across both.
                                    val cached by cache.chapters(destination.fiction.id).collectAsState()
                                    LaunchedEffect(cached.value?.fiction, destination.fiction) {
                                        fictionMetadata.load(
                                            cached.value?.fiction ?: destination.fiction,
                                            maxCoverBytes = capabilities.maxCoverBytes,
                                            voiceCatalogue = capabilities.voiceCatalogue,
                                        )
                                    }
                                    FictionMetadataScreen(
                                        holder = fictionMetadata,
                                        repository = repository,
                                        onBack = { nav.back() },
                                    )
                                }

                                Destination.Player -> PlayerScreen(
                                    playback = playback,
                                    sizeClass = sizeClass,
                                    preferences = container.playbackPreferences,
                                    readAlongAvailable = capabilities.readAlong,
                                    bookmarksAvailable = capabilities.bookmarks,
                                    onAddBookmark = { addBookmark() },
                                    onOpenReader = { chapterId, title ->
                                        nav.open(Destination.Reader(chapterId, title))
                                    },
                                    onBack = { nav.back() },
                                )

                                Destination.Notifications -> NotificationsScreen(
                                    holder = chapterNotifications,
                                    repository = repository,
                                    onPlay = ::playNotification,
                                    onOpenFiction = ::openNotificationFiction,
                                )

                                Destination.Bookmarks -> BookmarksScreen(
                                    holder = bookmarks,
                                    onOpen = ::openBookmark,
                                    onBack = { nav.back() },
                                )

                                Destination.ManageShelf -> ManageShelfScreen(
                                    holder = manageShelf,
                                    onBack = { nav.back() },
                                )

                                Destination.Settings, Destination.Devices -> SettingsScreen(
                                    sessionStore = sessionStore,
                                    repository = repository,
                                    holder = settings,
                                    preferences = container.playbackPreferences,
                                    // Read off the player state rather than the engine, so the
                                    // Settings pane and the player agree about what the backend
                                    // can do by construction.
                                    canChangeSpeed = playerState.canChangeSpeed,
                                    canSkipSilence = playerState.canSkipSilence,
                                    closeToTray = closeToTray,
                                    onCloseToTrayChange = container::setCloseToTray,
                                    traySupported = traySupported,
                                    listeningStats = container.listeningStats,
                                    historyOwnerKey = container.historyOwnerKey(),
                                    updates = updates,
                                    // Keeps the destination and the open pane in step, so the
                                    // Devices deep link and the in-screen pane list are the same
                                    // thing rather than two competing notions of "where am I".
                                    onOpenShelf = { nav.open(Destination.ManageShelf) },
                                    feeds = podcastFeeds,
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

                                Destination.Queue -> ServerQueueScreen(
                                    holder = serverQueue,
                                    playback = playback,
                                    onBack = { nav.back() },
                                    // A queue row routinely names a fiction the user has never
                                    // opened, so the cached library is often a miss. Prefer its
                                    // richer summary when it is there, and fall back to the one
                                    // the row itself carries rather than doing nothing.
                                    onOpenFiction = { item ->
                                        val known = cache.library.value.value?.fictions
                                            ?.firstOrNull { it.id == item.fictionId }
                                        nav.open(Destination.Fiction(known ?: item.toFictionSummary()))
                                    },
                                )

                                is Destination.Reader -> ReaderScreen(
                                    chapterId = destination.chapterId,
                                    fallbackTitle = destination.title,
                                    cache = container.readAlongCache,
                                    preferences = container.readerPreferences,
                                    playback = playback,
                                    bookmarksAvailable = capabilities.bookmarks,
                                    // The reader knows which *sentence* is being narrated, so it
                                    // supplies both a precise position and a label made of the
                                    // words themselves — the thing this client can do that a
                                    // phone reaching for a transport button cannot.
                                    onAddBookmark = { positionMs, label ->
                                        bookmarks.add(destination.chapterId, positionMs, label)
                                    },
                                    readingMode = readingMode,
                                    onToggleReadingMode = { readingMode = !readingMode },
                                    onBack = { nav.back() },
                                    onChapterAdvanced = { chapterId, title ->
                                        nav.replaceTop(Destination.Reader(chapterId, title))
                                    },
                                )
                            }
                        }
                    }
                    if (playerState.hasSession && nav.current != Destination.Player && !readingMode) {
                        NowPlayingBar(playback, compact = sizeClass == WindowSizeClass.Compact) {
                            nav.open(Destination.Player)
                        }
                    }
                }
            }
        }
    }

    // Outside the login branch on purpose: F1 is a reasonable thing to press on the sign-in
    // screen, and the list is useful there too.
    if (showShortcuts) ShortcutsDialog(onDismiss = { showShortcuts = false })
    if (session.isLoggedIn) FictionManagementDialogs(fictionManagement)

    // The Devices destination is a deep link into the settings screen: entering it selects the
    // pane, so a future "manage sessions" link from anywhere lands on the right place.
    LaunchedEffect(nav.current) {
        if (nav.current == Destination.Devices) settings.openSection(SettingsSection.Devices)
    }
}

/** Whether the Refresh action means anything on this destination. */
private val Destination.isRefreshable: Boolean
    get() = when (this) {
        Destination.Library, is Destination.Fiction, Destination.Settings, Destination.Devices,
        Destination.Bookmarks, Destination.Queue, Destination.Notifications, Destination.ManageShelf,
        -> true
        // A form has nothing to refresh into: the fields hold what somebody is halfway through
        // typing, and replacing them with the server's copy is the opposite of what F5 promises.
        is Destination.FictionMetadata -> false
        // Refresh re-runs the query the results belong to; it is dead until one has been run, but
        // enabling it is cheaper to reason about than a state-dependent header button.
        Destination.Search -> true
        Destination.Player, is Destination.Reader -> false
    }

@Composable
private fun HeaderBar(
    serverName: String,
    current: Destination,
    canGoBack: Boolean,
    canRefresh: Boolean,
    showBookmarks: Boolean,
    compact: Boolean,
    /** Capability-gated: no entry at all on a server with no shared queue. */
    queueAvailable: Boolean,
    showNotifications: Boolean,
    /** Everything not dismissed, including chapters still converting. */
    unreadNotifications: Int,
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
                active = current == Destination.Library || current is Destination.Fiction ||
                    current is Destination.FictionMetadata,
            ) { onSelect(Destination.Library) }
            if (showBookmarks) {
                NavItem("Bookmarks", active = current == Destination.Bookmarks) {
                    onSelect(Destination.Bookmarks)
                }
            }
            if (showNotifications) {
                NavItem(
                    // The count is everything unresolved, converting chapters included: the point
                    // of the feature is that a chapter you were told about stays counted until it
                    // can actually be played.
                    if (unreadNotifications > 0) "New ($unreadNotifications)" else "New",
                    active = current == Destination.Notifications,
                ) { onSelect(Destination.Notifications) }
            }
            if (queueAvailable) {
                NavItem("Queue", active = current == Destination.Queue) { onSelect(Destination.Queue) }
            }
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
