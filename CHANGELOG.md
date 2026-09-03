# Changelog

All notable changes to TTSRoad Desktop are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Versioning and tag policy

- `ttsroad.version` in `gradle.properties` is the one application version. A release tag is that
  version prefixed with `v`, so `1.0.1` is released as `v1.0.1`. The release workflow refuses to
  publish when the two disagree.
- MAJOR must stay at or above `1`, because jpackage rejects a `0` major in installer metadata.
- `ttsroad.debRevision` is Debian packaging metadata, never a second application version. A
  packaging-only rebuild moves `1.0.1-1` to `1.0.1-2` and does **not** get its own tag or entry
  here — the application did not change.
- Every released version needs a section below whose heading matches the tag. The workflow reads
  its body as the release notes and fails the release when the section is missing.

## [Unreleased]

### Added

- **New chapters announce themselves, and stay announced until they play.** Following a serial
  decided only whether it appeared on your shelf; it now also tells you when that serial gains a
  chapter. The notice is raised the moment the chapter is *pulled* and stays open until it is
  actually listenable — the promise and the keeping of it are one row rather than two
  notifications, because being told twice about one chapter is what gets a feature like this
  muted. A converting chapter offers no Dismiss at all: the server refuses one, and that notice is
  the only record that the chapter is on its way.

  A header entry carries the count of everything still unresolved, converting chapters included,
  and the system notification fires only when a chapter becomes playable — never on arrival, and
  never for a chapter that was already ready when the app started, which would otherwise
  re-announce the backlog on every launch. A failed conversion reads as stalled rather than as
  either ready or gone. Shown only where the server advertises the `notifications` capability, and
  the desktop needs no push credential of any kind: its notification is a rendering of state it
  already polls. ([#105](https://github.com/jonarihen/TTSRoad-Desktop/issues/105))

## [1.2.0] - 2026-09-03

The browsing and control-hierarchy pass the Android client had in its 0.14.0, plus the fiction
editor the desktop was missing. Everything server-dependent here was already on the wire: this
release is mostly the client finally reading what it was being sent.

### Added

- **The shelf can be put in an order.** Recently updated, recently added, title, author, rating,
  most left to hear, least finished, most chapters and % converted. The dates turned out to have
  been on the wire the whole time — the backend has serialised `created_at` and `updated_at` on
  every fiction since before this client existed, and the client had simply never decoded them,
  which is why no order but the server's own was ever possible. **Recently updated** is deliberately
  not called *new chapters*: it follows the fiction row, and the poller touches that row whether or
  not a check found anything, so it means *recently active*. The control's label is the order in
  force, so the grid never has to be read to work out how it is arranged.
- **The shelf reads the progress answer the server already sends.** Every library row carries one
  caller-scoped aggregate — chapters ready, played and left, plus total and remaining listening
  time — computed in one grouped query. The desktop discarded it and could only recover the same
  answer by opening every book and downloading its complete chapter list. Cards now state what is
  left directly from that aggregate, including the server's own rounding, so this client and the web
  shelf do not maintain separate arithmetic. An older server's missing key renders no invented
  `0 left`.
- **Browse filters by tag, the way the web console always has.** Multi-select, drawn from whatever
  the server's own vocabulary happens to be, and the tags on a card filter to themselves. Ticking
  two tags means **both** — a filter that widens the list as you add to it is one nobody uses twice.
  An active filter states itself above the grid with its count and offers a way to clear it.
- **The browse order, tag filter and scope survive a restart**, in `browse.json` beside the existing
  playback and reader settings. The search text deliberately does not: a search is a question being
  asked now, and restoring last week's would open the shelf onto a near-empty grid with no visible
  cause.
- **A fiction's description, tags and cover art can be corrected.** Editing is a screen rather than
  a dialog now, because its fields are shared with every account and, on a server that tracks hand
  edits, writing one takes it away from the source permanently. The editor sends only the fields
  that actually changed, and offers an explicit way to hand a field back to the source. Cover art is
  an upload rather than a URL, because the server only embeds art it holds itself.
- **Visible tooltips on every icon-only row action.** A chapter row can show eight at once, several
  of them near-identical glyphs in front of a state change that is awkward to undo; the label they
  already carried for a screen reader is now the label a mouse user sees.

### Changed

- **Controls have a rank.** At most one primary per screen, secondaries in a row beside it, and
  anything rare or destructive in a housekeeping row that states its consequence. The fiction header
  was four equal-weight buttons with colour doing the only differentiating — and colour here carries
  severity, not rank. Editing metadata and deleting a fiction have moved out of that header into a
  disclosure, because both need a sentence to be safe to press and neither sentence fits on a button.
- **An empty grid names the filter that emptied it.** An empty shelf, a tag that excludes
  everything, a search with no matches and a genuinely empty server are four different messages;
  one of them used to be shown for all four, and it read as the server having lost the library.
- Compose Multiplatform 1.12.0.

### Fixed

- **Adding a fiction converted the entire backlog.** `POST /api/mobile/fictions` accepts
  `sync_limit` and `sync_direction`; the desktop sent neither, and the backend reads their absence as
  *every chapter*. Tracking a 400-chapter serial from here queued four hundred chapters of TTS while
  the web form, posting the same body, has always defaulted to the newest 25. The dialog now opens on
  that same default and offers newest, oldest or everything, with the whole-backlog choice saying
  what it costs before it is made. Speech rate and the auto-poll switch are the other two fields the
  endpoint accepts and the dialog did not offer.
- **The now-playing bar ignored the configured skip interval** and always moved 30 seconds, so the
  same-looking control jumped twice as far as the full player after choosing 15.
- **"Jump back in" cards could be clickable no-ops.** The fiction was looked up in the followed shelf
  only, so a moment recorded on another client — or one for a serial since unfollowed, or while
  browsing Everything — dropped the click on a card that still said Open.
- **Opening a bookmark failed silently.** An unreachable server, a deleted fiction and a click that
  never registered were the same thing on screen. Marks on a serial this session already has now play
  offline, and everything else says what went wrong.
- **A failed refresh could report itself off-screen.** Both banners were items inside the scrolling
  grid, and a lazy list anchors its scroll on the key of whatever is at the top — so inserting a
  notice above that anchor scrolled by exactly its height.
- **Fiction detail did not fit the window size it promises.** At the supported 720 dp the header's
  fixed cover and the four bulk actions ran out of room, and the last control squeezed its own label
  onto two lines rather than wrapping the group.
- **Speed, reader theme and reader highlight choices had no role, no selected state and no visible
  keyboard focus** — announced as generic clickable text, with the current choice carried by colour
  alone. Transport buttons carried their label on the icon rather than on the node that is actually
  clickable.


## [1.1.0] - 2026-08-17

Three-client parity and the desktop-native features that follow from having a real filesystem, a
real window manager and a real keyboard. Every server-dependent feature here is capability-gated,
so an older server keeps its existing login, library and playback behaviour unchanged.

### Added

- EPUB upload, from the Add fiction dialog. The desktop is the client with a real filesystem and a
  real file picker, so this is where whole-book import belongs. Shown only where the server
  advertises `epub_upload`, which the backend publishes separately from add/edit/delete because a
  deployment can accept the one without the other. The extension, emptiness and the server's
  published byte ceiling are checked before anything is sent.
- Listening statistics, in a Settings pane of their own: total hours, chapters finished, the last
  seven and thirty days, a current and longest streak, days with any listening, and the best day.
  Computed locally from a bounded two-year file, kept per account on a shared machine, and never
  sent anywhere.
- Per-book playback speed. Changing speed in the player now sets it for the serial that is playing,
  since different narrators want different paces; Settings owns the default every book starts at,
  and a book with its own rate offers a one-click way back to that default.
- A system tray icon with the transport on it, so the app can get out of the way without stopping.
  It names the chapter and serial, offers play/pause and the two skips, and its Quit entry always
  stops the app for good. **Closing the window still quits by default** — the new Playback setting
  "Keep playing when the window closes" is what changes that, and the first close-to-tray says once
  that TTSRoad is still running. A desktop session with no system tray says so and keeps closing.
- Distraction-free reading. `F11` in the reader — or the new toolbar button — hides the header, the
  now-playing bar and the reader's own toolbar, and narrows the text to a comfortable measure. The
  frame returns when the pointer reaches either edge, along with a transport that can pause and skip
  so hiding the now-playing bar never leaves the audio unreachable. `Escape` restores the frame
  rather than leaving the chapter.
- Timestamp-based metadata refresh for the library and chapter lists. Servers that advertise the
  delta-sync contract now receive one small change-index request followed only by the sparse data
  that changed; older servers continue to receive ordinary full refreshes.
- Cross-device jump-back history through the server's `kind=auto` bookmarks. The desktop writes a
  breadcrumb every five minutes of playback and at its existing transition points, merges moments
  recorded by other clients into the Jump back shelf, and retains the bounded local copy offline.
- Capability- and admin-gated fiction management: add a Royal Road fiction by URL/id, edit its
  title/author/voice, and delete it behind a warning that the shared chapters and every account's
  progress will be destroyed.
- The account's cross-library queue, shared with the web client, as a browsable surface of its own.
  Chapter rows gain "Play next" and "Add to queue", the chapter list gains "Queue unplayed", and the
  queue screen can reorder, remove and clear. Shown only on a server that advertises the capability.

  Playing from the queue starts that chapter's fiction through the ordinary player, so what happens
  at the end of a chapter — including offline — is unchanged. The account's "when the queue is
  empty" preference is therefore displayed rather than acted on; see `docs/adr/0011`.
- A capability-gated Audiobooks settings pane for administrators. It lists finished server M4B
  exports and saves them to a user-selected path with authenticated range resume, free-space and
  container validation, fsync, atomic promotion, and resumable partials. Export creation/deletion
  remains in the web admin and saved files stay outside TTSRoad-managed offline storage.

### Changed

- Completed the release quality gate with a documented accessibility/performance/compatibility
  matrix, maintainer architecture/API/release runbook, 200% text-scale and long-session/navigation
  soak coverage, and automated WCAG AA contrast checks. Secondary/error/sepia text tokens now meet
  the 4.5:1 normal-text threshold on their supported surfaces.
- The README now shows how to bind global skip-back and skip-forward keys yourself, over the MPRIS
  `Seek` method the app already implements. TTSRoad still registers no global hotkeys of its own.
- The README now states the platform support policy once and plainly: Linux is supported and proven
  by a clean-container install/upgrade/uninstall run, while the Windows and macOS installers are
  built and smoke-launched but have no clean-machine lifecycle coverage.

## [1.0.2] - 2026-08-10

### Fixed

- Starting a chapter from the library queued only that one chapter, so the Next control was
  disabled and playback stopped at the end of the chapter instead of advancing. A queue is now
  always the whole fiction in reading order, wherever playback was started from.
- Chapter audio was resampled at GStreamer's default quality. Speech crossing the common
  44.1 kHz to 48 kHz boundary is now resampled at full quality.

## [1.0.1] - 2026-08-10

First packaged release: a feature-complete, Linux-native desktop client for a private TTSRoad
server.

### Added

- Sign-in with two-factor authentication, with the bearer token held in the OS credential store
  (Windows Credential Manager, macOS Keychain, freedesktop Secret Service) and never on disk.
- Library browsing, search, fiction details and chapter lists with filtering, sorting, bulk
  selection and optimistic mark-played.
- Queue playback through GStreamer, falling back to Java Sound where GStreamer is absent, with
  auto-advance, progress saving, a typed retry ladder and explicit session-expiry handling.
- Listening preferences, a sleep timer with fade, local playback history, MPRIS/media keys and a
  keyboard shortcut table.
- Offline downloads under the XDG data directory and a bounded streaming cache under the XDG cache
  directory, both namespaced by server and account identity.
- An audio-synchronized read-along reader with ETag-cached documents, media-time highlighting,
  word-click seeking and synchronized reader appearance.
- Server capability discovery, so an older server keeps baseline login, library and playback
  behaviour and unavailable features are never advertised.
- A Debian package with a bundled JDK 25 runtime, desktop entry, upgrade-safe revisioning and XDG
  rotating logs, plus credential-safe `--version` and `--diagnostics` commands.

[Unreleased]: https://github.com/jonarihen/TTSRoad-Desktop/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.2.0
[1.1.0]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.1.0
[1.0.2]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.0.2
[1.0.1]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.0.1
