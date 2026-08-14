<div align="center">

# 🎧 TTSRoad Desktop

**A native desktop client for the private TTSRoad audiobook server.**

Built with Compose for Desktop — real Skia-rendered UI, real OS installers, no Electron, no browser.

<br>

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4?logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-25-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.7.0-02303A?logo=gradle&logoColor=white)
![Platforms](https://img.shields.io/badge/Linux-supported-success)
![Platforms](https://img.shields.io/badge/Windows%20%7C%20macOS-best--effort-informational)
![Status](https://img.shields.io/badge/status-alpha-orange)

</div>

---

## ✨ Highlights

- 🖥️ **Truly native UI** — rendered with Skia via JetBrains Compose Multiplatform (Material 3), *not* a web view.
- 📦 **Real installers** — ships as `.deb` (Linux), `.msi` (Windows) and `.dmg` (macOS) with a **bundled Java runtime**, so end users never install Java. Linux is the platform with a clean-machine install/upgrade/uninstall test; see [Releases](#-releases).
- 🔗 **Shares the mobile API** — talks to the same `/api/mobile/*` endpoints as the Android app, and wears the same **AARIS** design language.
- 🔊 **Real audio playback** — bearer-protected chapter MP3s are decoded in pure JVM and streamed to the system audio device.

## 🖧 Platform support, stated once

This is a **Linux-native** client. The other two platforms are *buildable*, not supported, and the
presence of a `.msi`/`.dmg` target should not be read as more than that:

| Platform | Status | What CI actually proves |
| --- | --- | --- |
| Linux (Mint 22.x / Ubuntu 24.04, amd64) | **Supported** | full test suite, `.deb` metadata and payload inspection, and a clean-container install → upgrade → uninstall lifecycle on every PR |
| Windows x64 | Best-effort | the release workflow builds the `.msi` and starts the application image (`--smoke-test`); the Credential Manager path has its own round-trip test. No clean-machine installer run. |
| macOS | Best-effort | the release workflow builds the `.dmg` and starts the application image. No clean-machine installer run. |

Variable-rate playback, skip silence, seeking without re-decoding and MPRIS are GStreamer- and
D-Bus-backed, so they are Linux features; elsewhere the app falls back to the Java Sound engine and
hides the controls that backend cannot honour. A best-effort platform is still expected to sign in,
browse, download and play — bug reports for it are welcome — but a release is not blocked on it. The
full evidence table is in [`docs/QUALITY-GATE.md`](docs/QUALITY-GATE.md).

## 🧩 What works

| Feature | Status |
| --- | :---: |
| Login (incl. 2FA) | ✅ |
| Library — continue-listening hero, shelves, lazy fictions grid | ✅ |
| Back stack with retained search / filters / scroll | ✅ |
| Refresh action (button, F5, Ctrl/Cmd+R) with non-destructive failure | ✅ |
| Remembered window size, position and maximised state | ✅ |
| Async cover images (cached) | ✅ |
| Dedicated fiction-detail screen | ✅ |
| Admin fiction management — add by URL/id, edit metadata, confirmed delete | ✅ except [EPUB](https://github.com/jonarihen/TTSRoad/issues/122) |
| Chapter list — All/Unplayed/Ready filter, oldest/newest sort, visible counts | ✅ |
| Highlight + auto-scroll to the playing chapter, "Jump to current" | ✅ |
| Bulk marks — all played / all unplayed / all previous, in one request | ✅ |
| Optimistic marking with rollback and an inline error | ✅ |
| Searchable up-next panel for long queues | ✅ |
| Player UI (play/pause, seek, configurable skip, next/previous, up-next queue) | ✅ |
| **MP3 audio playback** | ✅ |
| Settings — two-pane control centre (account, devices, playback, offline, audiobooks, about) | ✅ |
| Device sessions — list, mark current, revoke one / revoke all others | ✅ |
| Playback preferences — default + per-book speed, skip interval, skip silence, volume boost | ✅ |
| Sleep timer — 5/15/30/45/60 min or end of chapter, with a fade and "+5 min" | ✅ |
| Cross-device listening history — local-first "Jump back in", dismissible per snapshot | ✅ |
| Listening statistics — hours, chapters finished, streaks, computed locally | ✅ |
| MPRIS over D-Bus — Cinnamon applet, lock screen, hardware media keys | ✅ Linux |
| System tray transport, and an optional keep-playing-on-close | ✅ where the desktop has a tray |
| App shortcuts — Space, arrows, Ctrl+arrows, Ctrl+L, Ctrl+, plus an F1 list | ✅ |
| Offline downloads — per chapter, next 10, restart-safe queue, storage controls | ✅ |
| Bounded streaming cache and previously loaded library browsing while offline | ✅ |
| Timestamped delta refresh for cached library and chapter metadata | ✅ |
| Admin audiobook exports — resumable M4B save to a user-selected file | ✅ |
| Audio-synchronized read-along — offline text, word seek, find, themes and zoom | ✅ |
| Distraction-free reading — F11 hides every frame, hover brings the transport back | ✅ |
| Streaming playback — audio starts before the chapter has downloaded | ✅ Linux |
| Seeking without decoding from the start of the chapter | ✅ Linux |
| Variable-rate playback ("speed"), pitch-preserving 0.5×–3.0× | ✅ Linux |
| Skip silence | ✅ with `gst-plugins-bad` |

> **The playback rows above are Linux-only, and the app says so rather than pretending otherwise.** The
> production backend is GStreamer, with `scaletempo` for pitch-preserving rate. Where GStreamer is
> absent — Windows and macOS by default — the app falls back to the original `javax.sound.sampled`
> engine, which cannot resample and has no random-access index. Each engine reports its own
> `EngineCapabilities`, and the UI draws the speed control only when the backend can honour it, so
> there is no longer a control that accepts a number and changes nothing.
>
> The choice, the rejected alternatives and the measurements behind them are in
> [`docs/adr/0002-playback-engine.md`](docs/adr/0002-playback-engine.md).

## 🐧 Install on Linux Mint

The supported Linux package is the **x86_64/amd64 `.deb`** built on Ubuntu 24.04, which is the
base used by Linux Mint 22.x. ARM64 is deferred: `jpackage` does not cross-compile, and the project
does not yet have a native ARM64 CI runner on which to verify its runtime and native libraries.

Install a downloaded package with APT so its GStreamer and Secret Service dependencies are resolved:

```bash
sudo apt install ./ttsroad_1.0.1-1_amd64.deb
```

Open **TTSRoad** from Cinnamon's Audio & Video menu, or run `/opt/ttsroad/bin/TTSRoad`. Java 25 is
included in the package; installing a system JRE or JDK is neither required nor used. To upgrade,
install the newer `.deb` with the same command. The lowercase Debian package identity remains
`ttsroad`, so APT replaces the existing version rather than installing a second copy.

Before opening the UI, these side-effect-free commands can identify the installed build and collect
safe environment information:

```bash
/opt/ttsroad/bin/TTSRoad --version
/opt/ttsroad/bin/TTSRoad --diagnostics
```

Diagnostics report versions, paths, runtime modules, GStreamer, D-Bus and keyring availability. They
do not open the credential store or read account data, and their output passes through the same
credential/URL redaction boundary as the rotating log.

### Uninstall and retained data

```bash
sudo apt remove ttsroad
```

Uninstalling removes the application and bundled Java runtime but deliberately preserves settings,
downloads, cache, logs and the Secret Service entry. This makes reinstall and upgrade safe. The
default Linux locations are:

| Kind | XDG location | Default |
| --- | --- | --- |
| Settings | `$XDG_CONFIG_HOME/TTSRoad` | `~/.config/TTSRoad` |
| Requested downloads | `$XDG_DATA_HOME/TTSRoad` | `~/.local/share/TTSRoad` |
| Rebuildable cache | `$XDG_CACHE_HOME/TTSRoad` | `~/.cache/TTSRoad` |
| Rotating logs | `$XDG_STATE_HOME/TTSRoad` | `~/.local/state/TTSRoad` |

For a full manual purge, first uninstall TTSRoad, back up anything wanted, then delete those four
directories using their effective XDG locations. This is irreversible and removes offline books.
Finally open **Passwords and Keys** (Seahorse), search for the `TTSRoad session` item whose service
is `dk.perspektiva.ttsroad.desktop`, and delete it. Signing out in the app normally clears the live
credential without deleting downloaded books.

### Linux troubleshooting

- Start with `--diagnostics` and `~/.local/state/TTSRoad/ttsroad.log`. Logs rotate at 1 MiB and keep
  three backups; crash dialogs intentionally contain no stack trace or credential-shaped detail.
- If playback has no output, confirm `gst-inspect-1.0 autoaudiosink` and
  `gst-inspect-1.0 pulsesink` succeed. Reinstall the package with APT to restore required GStreamer
  Base, Good and PulseAudio plugins. Skip-silence additionally needs the recommended
  `gstreamer1.0-plugins-bad` package.
- If sessions do not survive a restart, make sure Cinnamon's Secret Service is unlocked and
  `secret-tool` is available. TTSRoad deliberately falls back to memory-only credentials when the
  keyring cannot be reached.
- A missing D-Bus session disables MPRIS/media-key integration only; playback remains available.
- For a rendering failure, run from a terminal and attach the redacted diagnostics and rotating log
  to a bug report. The app's generic crash dialog also names the log location.

## 🛠️ Supported build matrix

Everything below is verified together: `clean check`, normal and release distributables, headless
launches of the packaged app, and an inspected/installed `packageReleaseDeb` on Ubuntu 24.04.

| Component | Version | Where it is declared |
| --- | --- | --- |
| JDK (build **and** bundled runtime) | **25** | `gradle/libs.versions.toml` → `jdk`, applied as `kotlin { jvmToolchain(25) }` |
| Gradle | **9.7.0** | `gradle/wrapper/gradle-wrapper.properties` (SHA-256 pinned) |
| Kotlin | **2.4.10** | `libs.versions.toml` → `kotlin` (also the Compose *compiler* plugin version) |
| Compose Multiplatform | **1.11.1** | `libs.versions.toml` → `composeMultiplatform` |
| Compose Material 3 | 1.9.0 | independently versioned; 1.9.0 is the newest **stable** |
| Material icons (extended) | 1.7.3 | permanently frozen upstream |
| Coroutines / OkHttp / Retrofit / Moshi / Coil | 1.11.0 / 5.4.0 / 3.0.0 / 1.15.2 / 3.5.0 | `libs.versions.toml` |
| Tests | JUnit 5 (BOM 6.1.2) + Vintage, `kotlinx-coroutines-test`, `mockwebserver3`, Compose UI test | `libs.versions.toml` |

**You do not need JDK 25 installed.** Gradle provisions it automatically through the
[foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) declared in
`settings.gradle.kts`. Any JDK 17+ on your `PATH` is enough to start the wrapper.

Rationale for every version above — including the two Compose artifacts that deliberately do *not*
track the CMP version, and the ProGuard override needed for Java 25 bytecode — is in
[`docs/adr/0001-2026-build-baseline.md`](docs/adr/0001-2026-build-baseline.md). Credential storage,
centralized bearer injection and capability discovery are covered by
[`docs/adr/0002-credential-storage-and-capability-discovery.md`](docs/adr/0002-credential-storage-and-capability-discovery.md).
Device-session management, the two-pane Settings screen and how an older server is detected are in
[`docs/adr/0003-device-sessions-and-settings.md`](docs/adr/0003-device-sessions-and-settings.md).
The back stack, the repository-backed cache, lazy lists and the adaptive breakpoints are in
[`docs/adr/0004-stateful-adaptive-navigation.md`](docs/adr/0004-stateful-adaptive-navigation.md).
Chapter filtering and ordering, bulk marking, optimistic rollback and current-chapter resolution are in
[`docs/adr/0005-chapter-browsing-and-bulk-controls.md`](docs/adr/0005-chapter-browsing-and-bulk-controls.md).
Listening preferences, the sleep timer, local-first cross-device history, MPRIS and the shortcut table are in
[`docs/adr/0006-listening-preferences-and-desktop-integration.md`](docs/adr/0006-listening-preferences-and-desktop-integration.md).
Offline namespaces, resumable downloads, streamed-audio retention and disk metadata are in
[`docs/adr/0007-offline-downloads-and-streaming-cache.md`](docs/adr/0007-offline-downloads-and-streaming-cache.md).
Read-along parsing, ETag caching, media-time highlighting and account preference sync are in
[`docs/adr/0008-audio-synchronized-read-along.md`](docs/adr/0008-audio-synchronized-read-along.md).
Linux package identity, dependencies, upgrade semantics, diagnostics and lifecycle verification are
in [`docs/adr/0009-linux-debian-package.md`](docs/adr/0009-linux-debian-package.md).
Read-only M4B export listing, resumable user-selected saves and their storage boundary are in
[`docs/adr/0013-audiobook-export-downloads.md`](docs/adr/0013-audiobook-export-downloads.md).

## 🚀 Bootstrap

```bash
git clone <repo> && cd TTSRoad-Desktop

# 1. Everything: compile, unit tests, Compose UI tests.
./gradlew --no-daemon clean check

# 2. Run the app from source.
./gradlew run

# 3. Build the distributable (app + bundled JDK 25 runtime image).
./gradlew createDistributable

# 4. Prove the packaged image actually starts (renders one frame, then exits 0).
#    On a headless machine, wrap it in Xvfb:  xvfb-run -a <command>
./build/compose/binaries/main/app/TTSRoad/bin/TTSRoad --smoke-test

# 5. Native installer for the current OS (.msi / .dmg / .deb).
./gradlew packageDistributionForCurrentOS
#    output under build/compose/binaries/main/<format>/
```

On Windows use `.\gradlew.bat` in place of `./gradlew`, and
`.\build\compose\binaries\main\app\TTSRoad\TTSRoad.exe --smoke-test` for step 4.

Two flags CI adds, which you can use locally to reproduce a CI failure:

```bash
./gradlew --no-daemon clean check --warning-mode fail -Pttsroad.warningsAsErrors=true
```

- `--warning-mode fail` — a Gradle deprecation that would break the next Gradle major fails the build.
- `-Pttsroad.warningsAsErrors=true` — Kotlin compiler warnings become errors.

> **Note:** Compose UI tests need a display (Skiko renders through AWT even off-screen). They run
> against your desktop locally, and under `xvfb-run` in CI.

New maintainers should read [`docs/MAINTAINER-GUIDE.md`](docs/MAINTAINER-GUIDE.md) for the
architecture/data-flow diagram, API and capability rules, fixture conventions, storage boundaries,
release runbook and dependency process. The executable accessibility/performance/compatibility
matrix and latest recorded evidence live in
[`docs/QUALITY-GATE.md`](docs/QUALITY-GATE.md).

## 🔢 Versioning

There is exactly **one application version**: `ttsroad.version` in `gradle.properties`. It feeds the
Gradle/Maven coordinate, `jpackage`'s `packageVersion`, and the generated `BuildInfo.kt` that the
settings screen, diagnostics and window title render. Bump it in one place. jpackage rejects MAJOR
`0`, so it must stay `>= 1.0.0`.

`ttsroad.debRevision` is deliberately separate Debian packaging metadata, not an application
version. Increment it when rebuilding the same application source with packaging-only changes;
reset it to `1` when `ttsroad.version` changes. APT compares the combined `VERSION-REVISION`.

## 🤖 CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests and on `master`:

1. Gradle wrapper validation (checksum of `gradle-wrapper.jar`).
2. `clean check` under Xvfb — compile, unit tests, Compose UI tests.
3. Static analysis — Kotlin warnings-as-errors + Gradle deprecation-as-error.
4. `createDistributable` plus `runReleaseDistributable` under Xvfb.
5. Headless launch smoke test of the packaged image, which also asserts the MP3
   `javax.sound.sampled` SPI survived jlink module minimisation.
6. Build two Debian revisions, inspect their metadata, payload, permissions, runtime modules,
   native linkage, desktop entry and safe diagnostics, then exercise clean install, login-window
   probe, in-place upgrade and uninstall in an Ubuntu 24.04 container.

7. Dependency review on pull requests, failing on a high-severity advisory. It needs GitHub's
   dependency graph, so it skips itself while the repository is private.

Dependency updates arrive as weekly grouped Dependabot PRs
([`.github/dependabot.yml`](.github/dependabot.yml)). Response times for a security report are in
[`docs/SECURITY.md`](docs/SECURITY.md).

> **Running the tests on an encrypted home directory.** eCryptfs (Linux Mint's "encrypt my home
> folder" option) caps a filename at 143 bytes, and a few generated test class files are longer
> than that. `./gradlew check` then fails with `error while writing … (Permission denied)`. Build
> from a path on an unencrypted filesystem instead — CI is unaffected.

## 🚢 Releases

A release is a tag: `ttsroad.version` prefixed with `v`, so `1.0.1` ships as `v1.0.1`.
[`.github/workflows/release.yml`](.github/workflows/release.yml) refuses to publish when the tag and
`gradle.properties` disagree, or when [`CHANGELOG.md`](CHANGELOG.md) has no entry for that version.

Each installer is built on its own operating system. The Linux `.deb` is additionally inspected and
then installed, upgraded and removed in a clean Ubuntu 24.04 container before anything is published;
the `.msi` and `.dmg` are built and smoke-tested but have no clean-machine install test. The publish
job attaches SHA-256 checksums, an SBOM and signed build provenance, and creates the release as a
**draft** — publishing stays a human action.

Run the workflow manually (`workflow_dispatch`) for a dry run: identical build and verification,
nothing published.

### Update checking

Settings → **Updates & About** checks the public GitHub release feed at most once per launch and
once per day, and can be turned off. A version you dismiss is not announced again until a newer one
appears; pressing **CHECK NOW** ignores all of that.

A download is verified against the release's published `SHA256SUMS` and only then handed to your
desktop's installer. A file that fails verification is deleted and never opened. The application
never runs `sudo` and never installs anything itself.

## 🗂️ Project layout

```
src/main/kotlin/dk/perspektiva/ttsroad/desktop/
├── Main.kt                       entry point, CLI diagnostics, logging, crash UI, window placement
├── RuntimeDiagnostics.kt         side-effect-free --version/--diagnostics output
├── App.kt                        root UI: back stack, shortcuts, header, login
├── di/AppContainer.kt            the single composition root + AppDispatchers
├── nav/
│   ├── AppNavigation.kt          Destination + stable keys + the back-stack rules
│   └── Shortcuts.kt              the keyboard table and Escape's precedence, as pure functions
├── data/
│   ├── Models.kt                 mobile API models (Moshi)
│   ├── AudiobookExports.kt       completed server M4B export API models
│   ├── PlaybackPreferences.kt    speed/skip/silence/boost, machine-local, migrating on read
│   ├── PlaybackHistory.kt        bounded local snapshots, last-heard and jump-back selection
│   ├── SyncedPlaybackHistoryStore.kt  cross-device auto-bookmark reconciliation
│   ├── Cached.kt                 value + error + isRefreshing + last success, independently
│   ├── LibraryCache.kt           library/chapter state held above the screens
│   ├── LibraryDiskCache.kt       account-scoped rebuildable metadata for offline browsing
│   ├── DeltaSync.kt              sparse metadata merge + deletion/cursor rules
│   ├── ReadAlongModels.kt        compact text/cue and account-preference API models
│   ├── ReadAlongDocument.kt      validated spans + binary highlight/seek/find lookup
│   ├── ReadAlongSentences.kt     paragraph-bounded sentence segmentation
│   ├── ReadAlongCache.kt         ETag revalidation + offline fallback
│   ├── ReadAlongDiskCache.kt     bounded identity-scoped text cache
│   ├── ReaderPreferences.kt      local fallback + account synchronization
│   ├── AppDirectories.kt         platform config/data/cache/log roots
│   ├── AppLogging.kt             redacted size-rotating persistent log
│   ├── StorageIdentity.kt        stable server/account disk namespace + generated audio names
│   ├── ChapterLists.kt           filter/sort, bulk id selection, status, played patch + rollback
│   ├── WindowPreferences.kt      persisted placement + clamping to attached displays
│   ├── TtsRoadApi.kt             Retrofit interface
│   ├── Repository.kt             TtsRoadRepository (interface) + RetrofitTtsRoadRepository
│   ├── SessionStore.kt           SessionStore (interface) + File-/InMemory- implementations
│   ├── AuthInterceptor.kt        the one place a bearer token is attached (same-origin only)
│   ├── ServerCapabilities.kt     additive /api/mobile/capabilities model
│   ├── SessionEnd.kt             structured 401 reasons + 429 Retry-After parsing
│   ├── Redaction.kt              secret scrubbing for logs and error text
│   └── ServerUrls.kt             normalizeBaseUrl / resolveAgainstServer
├── security/
│   ├── CredentialStore.kt        the keyring seam + session-only fallback + platform selection
│   ├── WindowsCredentialStore.kt Win32 Credential Manager via java.lang.foreign
│   ├── CommandCredentialStores.kt Secret Service (secret-tool) and macOS Keychain (security)
│   └── SecureFiles.kt            atomic, owner-only settings writes
├── player/
│   ├── PlaybackController.kt     the UI-facing interface + PlayerUiState
│   ├── QueuePlaybackController.kt queue, auto-advance, progress, retry ladder, session expiry
│   ├── PlaybackEngine.kt         backend seam: transport, capabilities, typed failures
│   ├── GstPlaybackEngine.kt      production backend — GStreamer via gst1-java-core
│   ├── SleepTimer.kt             the timer as a state machine over an injected clock
│   ├── MprisState.kt             PlayerUiState → MPRIS, pure and D-Bus-free
│   ├── MprisService.kt           the session-bus plumbing; optional at runtime
│   ├── JavaSoundPlaybackEngine.kt fallback where GStreamer is absent (no speed control)
│   ├── MediaSource.kt            bearer-authenticated range-request byte source
│   └── AudioEngine.kt            javax.sound.sampled seam used by the fallback engine
├── download/
│   ├── DownloadIndex.kt          transactional, migrating queue/index state
│   ├── DownloadStorage.kt        owner-only explicit-download root + safe cleanup
│   ├── ChapterDownloader.kt      range resume, validation, fsync and atomic promotion
│   ├── AudiobookExportDownloader.kt authenticated resumable save to a selected M4B path
│   ├── DownloadManager.kt        bounded queue, backoff, cancellation and restart recovery
│   ├── DownloadCoordinator.kt    current-account lifecycle, totals and cleanup seam
│   ├── StreamingCache.kt         bounded validated cache populated while playback reads
│   └── OfflineFirstMediaSource.kt explicit download → cache → authenticated network
└── ui/
    ├── Theme.kt                  AARIS theme tokens
    ├── Components.kt             CoverImage, stale/empty/initial-error states, "how old is this"
    ├── StateHolder.kt            lifecycle-aware state-holder base + rememberStateHolder
    ├── WindowLayout.kt           supported minimum size + the three width classes
    ├── LoginStateHolder.kt       login submit + result mapping
    ├── SettingsStateHolder.kt    settings panes, device sessions, confirmations
    ├── FictionManagementStateHolder.kt capability + current-admin gate and mutation state
    ├── FictionManagementDialogs.kt add/edit forms and destructive shared-delete warning
    ├── AudiobookSavePicker.kt    native user-selected M4B destination
    ├── LibraryScreen.kt          LazyVerticalGrid: hero, shelves, search, fictions
    ├── FictionDetailScreen.kt    LazyColumn: one header item, then chapter rows + bulk controls
    ├── ReaderScreen.kt           lazy selectable reader, follow/find/zoom/theme controls
    ├── SettingsScreen.kt         two-pane control centre
    ├── ShortcutsDialog.kt        the in-app keyboard reference (F1)
    └── PlayerScreen.kt           full player + NowPlayingBar (stacked when narrow)

src/test/kotlin/dk/perspektiva/ttsroad/desktop/
├── ServerFixtures.kt             real server-1.4.0 payloads, incl. unknown additive fields
├── Fakes.kt                      FakeRepository, FakePlaybackController, fake keyring/command runner
├── data/                         URLs, model parsing, login/429, capability discovery, structured
│                                 401s, redaction, auth-interceptor origin rules, session migration
├── security/                     credential stores (incl. a real Windows Credential Manager round-trip)
├── player/                       playback state machine, audio 401s, sleep timer, MPRIS mapping
├── nav/                          back-stack rules, destination keys, shortcut table
└── ui/                           state holders, adaptive breakpoints, Compose screen and
                                  navigation tests (retained state, refresh errors, lazy bounds)
```

## 🔊 How audio playback works

Two layers, split so the interesting half needs no sound card:

- **`PlaybackEngine`** owns one chapter — decoding, the clock, the output device. `GstPlaybackEngine`
  (GStreamer) in production, `JavaSoundPlaybackEngine` where GStreamer is missing.
- **`QueuePlaybackController`** owns everything above it: the queue, auto-advance, progress saving,
  the retry ladder and session expiry. All of that is unit-tested against a fake engine.

Chapter MP3s are **bearer-protected**, and the bytes are pushed *into* GStreamer rather than fetched
by it. `HttpMediaSource` reads them from the app's shared OkHttp client, so the auth interceptor
that serves the API attaches the token — under the same origin rule, which is what stops an absolute
audio URL pointing elsewhere from leaking the credential — and a `401` arrives as a typed
`SessionExpiredException` that ends the session exactly as it would on an API call.

```
appsrc → decodebin → audioconvert → scaletempo → audioconvert → audioresample → autoaudiosink
```

`scaletempo` scales tempo without shifting pitch. Because the source is a range-request stream,
audio starts after a few kilobytes instead of a whole chapter, and seeking moves the HTTP read head
instead of re-decoding from byte zero. Measured on a 32.5 s fixture: first audio in **11 ms** after
2.7% of the bytes, and a 1 s → 30 s seek in **26 ms**.

The fallback engine keeps the old behaviour — materialise the chapter, decode with **mp3spi/JLayer**,
write PCM to a `SourceDataLine`, re-decode from the start to seek, and no speed control.

## 📥 Offline storage

There are three deliberately different kinds of disk state:

- **Requested downloads** live under the platform data directory (`$XDG_DATA_HOME/TTSRoad` on
  Linux). They are never evicted automatically. A chapter becomes Offline only after a successful
  response has the expected length (when known), passes an MP3 structure check, is flushed, and is
  atomically renamed from `.part`.
- **Streamed audio** lives under the platform cache directory (`$XDG_CACHE_HOME/TTSRoad` on Linux).
  A sequential stream is retained only after clean EOF and the same validation. Seeking abandons
  that cache attempt rather than promoting a file with a hole. The cache is bounded to 1 GiB and
  evicts least-recently-used completed entries.
- **Library/chapter metadata and read-along documents** are rebuildable cache too. They let a
  restart show previously loaded fictions and chapters immediately and keep previously opened
  narration readable offline. Read-along text/cues retain their ETag and are bounded separately
  from audio to 64 MiB / 80 chapters. Server-local paths and backend errors are stripped.

All three use `<stable-server>/<account>/<generated-chapter-id>` namespaces. Capability discovery's
advertised `server.base_url` is the preferred server identity, so changing from a LAN address to a
public address does not duplicate a library; scheme/host/path fallback is used for older servers.
Titles, raw server filenames and URLs never become filesystem names, and cleanup refuses traversal
or symlinked owned roots.

The chapter row exposes Download, Cancel, Retry and Delete according to its current state. “Download
next 10” starts at the first unplayed chapter in reading order, regardless of how the list is sorted.
Interrupted transfers retain `.part` state for safe range resume; an explicit Cancel waits for the
worker to release its handles and removes the partial. Settings measures requested audio by fiction
and keeps **Delete all downloads** separate from **Clear streaming cache**, each behind confirmation.
Signing out keeps bytes but closes the account's index and fiction titles until that account signs
in again.

## 🎧 Audiobook exports

When an administrator's server advertises `audiobook_export`, Settings → **Audiobooks** lists its
finished M4B volumes. The surface is intentionally read-only: create and remove exports in the web
admin, then save a completed volume to a path chosen through the native file dialog. Exported files
are for third-party players and are never offered as chapters inside TTSRoad.

Large saves resume from a partial file beside the destination. The downloader uses the same
same-origin bearer-authenticated client as playback, checks free space and expected length, fsyncs
and validates the M4B container, then promotes it atomically. Pausing or closing retains a resumable
partial; a corrupt completed body is removed. Saved exports are user-owned files outside TTSRoad's
managed offline/cache roots, so sign-out and storage cleanup do not delete them.

## 📖 Read-along reader

The reader is offered only when capability discovery reports `readalong`. `has_timings` changes the
mode, not whether useful text exists: timed chapters follow audio; older chapters remain selectable,
searchable plain narration. The player and each chapter row can open it, and opening a chapter that
is not playing never highlights it against unrelated audio.

- Compact cue arrays are validated into half-open text spans. Malformed/non-monotonic cues, a
  mismatched chapter id, or an audio-duration mismatch disable highlighting with an explanation
  instead of confidently pointing at stale text.
- Cue, sentence, paragraph and word-seek lookup are binary. Highlighting consumes the playback
  engine's reported **media position**, so 2× playback needs no wall-clock scaling and cannot drift.
- Paragraphs are lazy items, never one composable per word. Text stays selectable/copyable, Ctrl+F
  finds within the chapter, and Page Up/Down plus Home/End work without a pointer.
- Follow mode holds the active paragraph roughly one-third down. Wheel, drag, or scrollbar input
  yields until **Back to current**; queue auto-advance loads the next document and restores follow.
  The next queue chapter is prefetched at 80%.
- Font size (14–30), line height (1.3–2.4), dark/sepia/light theme, and sentence/word/off highlight
  are saved locally first and synchronized through `GET/PATCH /api/me/preferences` when supported.
  An older or offline server leaves the last-known local values usable.
- **Distraction-free reading** (`F11`, or the toolbar button) hides the header, the now-playing bar
  and the reader's own toolbar, and narrows the text from 920 dp to a 760 dp measure. Move the
  pointer to either edge and the frame comes back, with a transport that can pause and skip — the
  mode never leaves the audio without a visible way to stop it. `Escape` restores the frame rather
  than leaving the chapter, and the mode is dropped on leaving the reader rather than remembered.

## 🎚️ Listening preferences, the sleep timer and the desktop

Speed, skip interval, skip silence and volume boost live in `playback.json` next to the session and
window files — **not** in the session. Signing out does not reset them. The file belongs to the OS
profile and has no TTSRoad account key, so accounts under the same OS login intentionally share the
machine's output settings; different OS users have separate config directories.

The server's later `player_preferences` capability does not change that boundary. Speed, skip,
silence removal and gain are output/engine-shaped, and the sleep timer remains an explicit action
rather than inheriting an account default. Reader appearance is still account-synchronized because
it describes portable content presentation rather than this machine's audio path.

- **They are applied by the controller, not the player screen.** An auto-advanced chapter and a
  media-key start use the same values as a chapter you pressed play on, because only the controller
  sees all three.
- **A file this build did not write still loads.** Missing keys fall back, unknown enum values fall
  back to the safe end, and out-of-range numbers are *snapped* rather than defaulted — a stored
  20-second skip becomes 15, which is closer to what that listener chose than 30 is.
- **A custom speed survives.** The menu always offers the stored value even when it is not one of
  this build's presets, so a rate set elsewhere is not silently rounded away on first open.
- **Boost stops at 2×.** Higher clips quiet narration instead of raising it. Both engines honour
  gain — including the Java Sound fallback, which scales PCM in its own decode loop with saturation
  rather than wrapping, because the sleep timer's fade has to work everywhere.
- **Skip silence needs `gst-plugins-bad`.** Where `removesilence` is absent the control is not
  drawn, exactly as the speed control is not drawn without GStreamer.

The **sleep timer** offers 5/15/30/45/60 minutes or the end of the current chapter.

- A manual pause **freezes** a countdown; resuming continues it. An hour paused costs it nothing.
- The last 30 seconds **fade**, and the fade multiplies with the volume boost rather than replacing
  it — so cancelling restores your boost, not unity.
- **"+5 min"** appears during the fade, adds to what is left, and brings the volume back as you
  press it rather than on the next tick.
- **End of chapter** stops at the boundary and prevents the auto-advance, rather than stopping the
  next chapter a moment after it has already started.
- Expiry **pauses**; it does not clear the queue. Resuming in the morning is one keypress.

None of that is timed by a scheduler: the timer is a state machine ticked by the playback loop over
an injected clock, so a test steps an hour in one line.

**Listening history** is local-first: `history.json` keeps 60 entries, one per chapter, while
`kind=auto` server bookmarks share the same moments with the web and other native clients. The
desktop records at playback transitions and every five minutes of active listening, then merges a
server refresh without replacing newer offline work. The local file holds ids and titles and *no
URL of any kind* — covers are re-resolved from the live library cache. Dismissing an entry hides
that snapshot on this machine, not the day: a later chapter of the same serial comes back on its
own, and a later refresh or progress save does not resurrect the dismissed chapter.

On Linux the player appears on the session bus over **MPRIS**, so Cinnamon's media applet, the lock
screen and hardware media keys show the right metadata and control playback. It is pure Java —
dbus-java over the JDK's own AF_UNIX socket — so it adds no native library to the bundled runtime.
No session bus (Windows, macOS, a bare SSH session) means no MPRIS and a fully working player, with
a note in the log rather than a failure.

Where the desktop offers a **system tray**, TTSRoad puts an icon there with the chapter and serial
as its tooltip, play/pause, both skips, *Show TTSRoad* and *Quit*. Closing the window **quits** by
default; Settings → Playback → *Keep playing when the window closes* is what changes that, and the
first time it happens the tray says once that TTSRoad is still running. The default is the way round
that cannot surprise anyone — a close control that closes — and a session with no tray at all says
so and keeps closing, rather than hiding a window nobody could get back to.

## ⌨️ Keyboard

| Keys | Action |
| --- | --- |
| `Space` | Play or pause |
| `Left` / `Right` | Skip back or forward by your skip interval |
| `Ctrl+Left` / `Ctrl+Right` | Previous or next chapter |
| `Alt+Left` | Back |
| `Ctrl+L` | Library |
| `Ctrl+,` | Settings |
| `F5` / `Ctrl+R` | Refresh the current screen |
| `Escape` | Close a dialog, or go back |
| `Ctrl+B` / `Ctrl+Shift+B` | Bookmark this spot / your bookmarks |
| `F11` | Distraction-free reading, in the reader |
| `F1` / `Ctrl+/` | The in-app shortcut list |

**Shortcuts that would type do not fire while you are typing.** That is structural rather than a
check: the combinations no text field claims are installed on the window's *preview* handler, so F5
still refreshes from inside the search box, while Space, the arrows and Ctrl+arrow are installed on
the ordinary handler, which a focused text field has already consumed them from. No global hotkeys
are registered — media keys reach the app through the desktop's own MPRIS routing instead.

## 🧭 Navigation, state and window behaviour

Browsing is a real back stack over destinations (Library, Fiction, Player, Reader, Settings,
Devices) with stable keys. Re-opening a destination that is already open **pops back to it** rather
than stacking a second copy, so Fiction ↔ Player loops stay bounded.

- **Nothing is rebuilt on the way back.** Search text and scroll offsets are filed under the
  destination's key and handed back on the next visit; library and chapter data — and each
  fiction's chosen filter and sort — live in a `LibraryCache` above the screens, so
  Library → Fiction → Back costs zero requests and re-opening a serial keeps how you were reading it.
- **Refresh is explicit**: a header button, `F5`, and `Ctrl+R` (`Cmd+R` on macOS). Duplicate
  requests are coalesced and a superseded load is cancelled before it can publish a stale answer.
- **A failed refresh never blanks the screen.** Content stays, a banner reports the failure and says
  *when* what you are looking at was actually fetched. The only full-screen error is the one with
  nothing cached behind it, and it always carries a Retry.
- **Keyboard**: `Alt+Left` goes back, `Escape` closes an open dialog *before* it navigates, and Tab
  reaches every primary action with a visible focus treatment (the AARIS look has no ripple, so
  focus is drawn explicitly).
- **The window remembers itself** — size, position, maximised state — in `window.json` beside the
  settings file, clamped on startup to the displays attached *now*. A position saved against a
  monitor that has since been unplugged is discarded rather than restored off-screen. Nothing
  transient and nothing secret is persisted. The supported minimum window size is **720×560**;
  below 900 dp wide the player's up-next panel stacks and the header drops the server-name label
  rather than clipping the navigation entries. (Settings' own pane strip does scroll horizontally;
  the window header does not.)

## 📖 Browsing a long serial

A 1,000-chapter fiction composes roughly twenty rows — the ones on screen — and still roughly
twenty after scrolling to chapter 900. Everything above the rows is a single lazy item, which is
what makes "scroll to the chapter that is playing" arithmetic rather than a guess.

- **Filter and sort** are All / Unplayed / Ready and oldest / newest, with a visible
  "*n* of *m*" count while filtered. *Ready* means the chapter has an audio object the player can
  actually open — not the `playable` flag and not `status == "done"`.
- **Sorting is a view.** The queue is always built in reading order, so flipping the list to
  newest-first never makes the serial play backwards, and "mark all previous" means the same thing
  whichever way the list is facing.
- **The playing chapter** is highlighted, opening the fiction lands on it, and "Jump to current"
  appears once you scroll away. A queue belonging to a different serial highlights nothing here.
- **Marking is optimistic and atomic.** One `playback/mark` request carries the whole id set —
  forty chapters is one request, not forty — the rows move in the frame you click, and a failure
  restores each row's *exact* previous position rather than un-marking it. (Un-marking would zero
  out real progress on a chapter that was already finished.) Ids the server silently drops are
  rolled back on their own.
- **Non-playable chapters** are legible (`Converting 41%`, `Failed`, `Queued`, `Excluded`) and
  never queued. The server's own `error_message` is deliberately not shown — it carries paths and
  stack fragments a listener cannot act on.
- **Every row action is keyboard-reachable**: play, mark played/unplayed, mark all previous, and
  read-along are always in the semantics tree with a content description, and merely dim when the
  row is not hovered or focused.
- **Read-along** appears when the server reports the `readalong` capability. `has_timings` chooses
  synchronized versus text-only mode; it does not hide narration text from older chapters.
- The **up-next panel** grows a search box once a queue passes eight chapters. Filtering it never
  reorders or renumbers anything.

## 🔐 Sessions and credentials

The bearer token is kept in the **OS credential store**, never on disk:

| OS | Credential store | How |
| --- | --- | --- |
| Windows | Credential Manager | `CredWriteW`/`CredReadW` in `Advapi32.dll`, via `java.lang.foreign` |
| macOS | login Keychain | `/usr/bin/security`, secret on **stdin** (never in `argv`) |
| Linux | freedesktop Secret Service | libsecret's `secret-tool`, secret on **stdin** |
| none available | *nothing* | session-only, with a visible "sign in again after restart" notice |

There is deliberately no fourth option. Writing the token to a file — or encrypting it with a key
stored next to the ciphertext — is plaintext with extra steps, so a machine without a keyring gets
a session that ends with the process.

A small **settings file** holds only non-secret hints plus the identifier of the keyring entry:

| OS | Location |
| --- | --- |
| Windows | `%APPDATA%\TTSRoad\session.json` |
| macOS | `~/Library/Application Support/TTSRoad/session.json` |
| Linux | `$XDG_CONFIG_HOME/TTSRoad/session.json` (default `~/.config/TTSRoad/session.json`) |

It is written atomically (temp file + rename) and restricted to the owner (`rw-------`, or a
single-owner ACL on Windows). A file left by an older build that still contains a plaintext
`token` is migrated once — into the keyring, then rewritten without it, then re-read to verify the
plaintext is gone. If any step fails the plaintext is destroyed anyway and the user signs in again.

### Where the token is attached

One OkHttp client, one interceptor, three consumers (API calls, chapter audio, cover images). The
token is attached only when the request's **scheme, host and port match the signed-in server**, so
a cover image on a third-party CDN is fetched bare. Server discovery opts out explicitly, so a
stale session can never be offered to a URL the user is still typing.

### When the session ends

A `401` on any authenticated request — including `/audio/…` — drops the credential, stops
playback, clears discovered capabilities, and returns to the login screen showing the server's own
reason (expired / revoked / invalid / unknown). A `500`, a timeout, or a dropped connection does
none of that: an outage is not a revocation.

## 🧭 Server capability discovery

`GET /api/mobile/capabilities` is unauthenticated and additive. Rules:

- only a literal JSON `true` enables a feature; unknown keys are ignored;
- `404` means "baseline" and is cached — an old server will not grow the endpoint;
- a transient failure keeps the last known answer instead of downgrading;
- results are cached in memory for six hours, and forcibly refreshed after login;
- `api_version` is never used as a proxy for a feature.

Global fiction mutations use two independent gates: the server must advertise
`fiction_management`, then authenticated `/api/mobile/me` must still say this account is an admin.
The server enforces the same rule on every request. Add accepts a Royal Road URL or bare id; edit
is deliberately limited to title, author and voice; delete warns that chapters and every user's
progress disappear. EPUB remains absent until the server publishes a stable multipart mobile
contract ([TTSRoad #122](https://github.com/jonarihen/TTSRoad/issues/122)).

The discovered server name and version appear under the URL field **before** any credential is
sent, so a typo'd hostname is visible rather than password-shaped.

---

<div align="center">
<sub>Private project · <code>dk.perspektiva.ttsroad.desktop</code></sub>
</div>
