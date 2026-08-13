# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Compose for Desktop (JVM, Skia-rendered) client for the private TTSRoad audiobook server. Single
Gradle module, Kotlin only, package root `dk.perspektiva.ttsroad.desktop`. It talks to the same
`/api/mobile/*` endpoints as the Android client.

## Commands

```bash
./gradlew clean check          # compile + unit tests + Compose UI tests + compile the prototype
./gradlew run                  # run from source
./gradlew createDistributable  # app image with a bundled JDK 25 runtime
./gradlew packageDistributionForCurrentOS   # .msi / .dmg / .deb
./gradlew packageReleaseDeb    # release Linux .deb (run on Linux)
./gradlew runReleaseDistributable  # run the release app image

# Prove the packaged image starts (renders a frame, exits 0):
./build/compose/binaries/main/app/TTSRoad/bin/TTSRoad --smoke-test

# Inspect a Linux package, including launcher diagnostics and native linkage:
packaging/linux/inspect-deb.sh build/compose/binaries/main-release/deb/ttsroad_*.deb
```

Single test class or method — the `test` task uses the JUnit Platform:

```bash
./gradlew test --tests 'dk.perspektiva.ttsroad.desktop.data.ServerUrlsTest'
./gradlew test --tests '*.QueuePlaybackControllerTest.auto advances*'
```

**Tests need a display.** `tasks.test` sets `java.awt.headless=false` because Skiko renders through
AWT even off-screen. Locally they use your desktop; headless, prefix with `xvfb-run -a`.

**Tests need an unencrypted filesystem.** eCryptfs caps filenames at 143 bytes and a few generated
test class files exceed it, so `check` fails with `error while writing … (Permission denied)` under
an encrypted home. Build from a path on ext4 instead.

Reproduce a CI failure locally — CI adds two gates that local builds do not have:

```bash
./gradlew --no-daemon clean check --warning-mode fail -Pttsroad.warningsAsErrors=true
```

You do **not** need JDK 25 installed; the foojay resolver in `settings.gradle.kts` provisions the
toolchain. Any JDK 17+ on `PATH` bootstraps the wrapper.

## Architecture

### One composition root

`di/AppContainer.kt` is the only place that constructs a repository, session store, credential
store, HTTP client, playback engine or playback controller. Every collaborator is a constructor
parameter with a production default, so a test builds a fully substituted container in one
expression. Do not construct these types anywhere else — the ordering in that file (credential
store → session store → HTTP client's auth interceptor) is what makes "one place attaches the
bearer token" true.

`AppDispatchers` passes dispatchers as data rather than touching global `Dispatchers`;
`Dispatchers.Main` is the Swing/AWT queue and is resolved lazily because it throws on a
non-UI-capable JVM.

### One HTTP client, one interceptor

A single `OkHttpClient` serves API calls, chapter audio and Coil cover images.
`data/AuthInterceptor.kt` attaches the bearer token **only when scheme, host and port match the
signed-in server** — that same-origin rule is the thing that stops an absolute audio or cover URL
elsewhere from leaking the credential. Server discovery opts out explicitly. Any new outbound call
should go through this client rather than building its own.

A `401` on any authenticated request (including `/audio/…`) is a session end: drop the credential,
stop playback, clear capabilities, return to login. A 5xx, a timeout or a dropped connection is
explicitly *not* — an outage is not a revocation. `data/SessionEnd.kt` holds the structured reasons.

### Playback: two layers, split so the interesting half needs no sound card

- `player/PlaybackEngine.kt` — the backend seam. One chapter at a time: decode, clock, output
  device. `GstPlaybackEngine` (GStreamer via gst1-java-core) in production,
  `JavaSoundPlaybackEngine` (mp3spi/JLayer → `SourceDataLine`) where GStreamer is absent, selected
  by `GstPlaybackEngine.createOrNull() ?: JavaSoundPlaybackEngine()` in the container.
- `player/QueuePlaybackController.kt` — queue, auto-advance, progress saving, retry ladder, session
  expiry. All of it unit-tested against a fake engine.

Two invariants worth preserving:

- **Capabilities gate the UI.** Each engine reports `EngineCapabilities`; the speed and skip-silence
  controls are drawn only when the backend can honour them (`variableSpeed`, `skipSilence`). The
  predecessor API accepted a speed number that no backend acted on — don't reintroduce a control the
  engine ignores. Gain is the deliberate exception: *every* backend must honour attenuation, because
  the sleep timer's fade is an attenuation.
- **Failures are typed.** `PlaybackFailure` is `SessionExpired` / `Transient` / `Fatal`, and the
  three are handled differently: end the session, retry on a timer, don't retry. Engine events go
  through a synchronous listener rather than a hot flow, so a failure raised inside `prepare` cannot
  be lost before a collector starts.

Chapter MP3s are bearer-protected and pushed *into* GStreamer (`appsrc`) rather than fetched by it,
via `HttpMediaSource` on the shared client — that is how the auth interceptor and its origin rule
apply to audio.

### Listening preferences, the sleep timer and history

- `data/PlaybackPreferences.kt` — speed, skip interval, skip silence, volume boost, in
  `playback.json`. **Machine-local, not session data**: signing out must not reset them and a second
  account must not inherit them, so the store has no reference to a session at all. The on-disk type
  is separate and fully nullable, so a file from another build loads degraded rather than throwing;
  out-of-range values are *snapped*, not defaulted. The offered speed list always includes the
  stored value even when it isn't a preset.
- **The controller applies preferences, not the player screen.** `QueuePlaybackController` collects
  the store and pushes rate/gain/skip-silence to the engine, so an auto-advanced chapter and a
  media-key start use the same values as one the user pressed play on. `setSpeed` writes the
  *preference*; the collector is the only thing that touches the engine.
- `player/SleepTimer.kt` — a state machine over an injected clock, ticked by the controller's
  existing 250 ms loop. No coroutine, no `delay`: determinism under a fake clock is an acceptance
  criterion. Fade gain is published as a multiplier and combined with the volume boost in one place,
  so cancelling a fade restores the boost rather than unity. End-of-chapter is checked *before* the
  auto-advance and disarms itself.
- `data/PlaybackHistory.kt` — bounded to 60 entries, one per chapter. The snapshot type has **no URL
  field of any kind**; covers are re-resolved from the library cache by fiction id. Dismissal is
  per snapshot and is *inherited* when the same chapter is re-recorded, or the next progress save
  would undo it. History is written at transitions only, never on the progress tick.

### Offline storage

- Config, explicit downloads and rebuildable cache use different platform roots from
  `data/AppDirectories.kt`. Explicit downloads are data and are never evicted automatically;
  streamed audio and cached library/chapter responses are cache.
- Every root uses `StorageIdentity` (`server.base_url` from capability discovery when advertised,
  canonical connect address only as fallback) plus the case-preserved account name, both hashed.
  Titles, URLs and server filenames never become path components. A signed-out login hint is not
  authority: `DownloadCoordinator.current` must be null without a bearer credential, while files
  remain for the same account's next sign-in.
- `download/DownloadManager.kt` owns the bounded/restart-safe queue;
  `ChapterDownloader.kt` owns range resume, free-space checks, validation, fsync and atomic rename.
  Only `Downloaded` after rename means Offline. Explicit Cancel waits for the worker and removes
  its partial; an interrupted transfer keeps `.part` for resume. A download 401 ends the session.
- Playback selection is explicit download → completed streaming cache → authenticated network in
  `OfflineFirstMediaSourceFactory`, wired once in the container. The streaming cache promotes only
  a sequential stream at clean EOF, abandons retention on seek, validates it, and evicts LRU files
  above 1 GiB. Never surface streaming cache as a requested Offline download.
- `LibraryDiskCache` holds owner-only rebuildable metadata under the same identity. It strips
  server-local paths/errors, publishes cached content with the original last-refresh time, and
  refreshes underneath. Settings measures real files, groups downloads by fiction, and keeps
  confirmed Delete all downloads separate from Clear streaming cache.

### Read-along

- `ReadAlongDocument` validates compact server offsets once, then resolves active cue, sentence,
  paragraph and word seeks with binary lookup. Its only timing input is `PlayerUiState.positionMs` —
  never extrapolate with wall time or scale by playback speed. Disable highlighting when timing
  rows are malformed, requested/returned chapter ids differ, or audio duration is stale.
- `ReadAlongCache` is memory → identity-scoped disk → authenticated conditional network. A 304
  reuses the parsed object, 404 is normal no-text, and network/5xx falls back to disk. A 401 never
  falls back because the session no longer authorizes account content. `App` clears memory and
  `DownloadCoordinator` makes the retained disk namespace unreachable on sign-out.
- `ReaderScreen` renders one lazy item per paragraph, not one composable per word. Wheel, drag and
  scrollbar input permanently surrender follow until Back to current. Highlighting a chapter other
  than the one playing is forbidden; queue advance changes the document only after the reader has
  actually followed its own chapter.
- Reader appearance is local-first in `reader.json` and synchronized only through the four known
  `/api/me/preferences` keys. PATCH must never echo unrelated account settings. Preserve the server
  ranges (font 14–30, line height 1.3–2.4) and dark/sepia/light + sentence/word/off vocabularies.

### The server queue, which is not the player's queue

`data/ServerQueue.kt` + `ui/ServerQueueStateHolder.kt` model the account's cross-library queue
(`/api/mobile/queue`, capability `queue`). It is a **browsable surface**: playing a row opens that
row's fiction and starts it through the ordinary player, so end-of-chapter behaviour is untouched
and the local queue keeps working offline. `advance` is deliberately never called — it would put the
network in the path of auto-advance — so `queue_when_empty` is displayed and not honoured, and the
screen says so. The `Server` prefix separates it from `player.QueueItem`: one is a curated
cross-fiction list, the other is derived reading order for one book. Every mutation republishes the
server's whole answer rather than predicting it; `reorder` sends the complete order and an
out-of-range move answers the *same* list; removal addresses queue-row ids, never chapter ids,
because the same chapter can be queued twice. A 404 is null ("no shared queue on this server") and
is not an empty queue. See ADR 0011.

### MPRIS

`player/MprisState.kt` is pure Kotlin with no D-Bus type in it — that's where the audiobook mapping
lives (chapter as title, serial as album *and* artist) and it's what the tests target.
`player/MprisService.kt` is the plumbing. `createOrNull` catches `Throwable` (a missing transport
provider is a `ServiceConfigurationError`) and returning null is a normal outcome: no session bus
means no MPRIS and a fully working player. `Position` is never announced via `PropertiesChanged` —
the spec excludes it; discontinuities go out as `Seeked`. `AppContainer.startMpris` is called from
`Main` rather than the constructor because Raise/Quit need the window.

### Keyboard shortcuts

`nav/Shortcuts.kt` is a pure matcher plus an `AppShortcut.firesWhileTyping` classification. `App`
installs it on **two** handlers, and that split is what makes typing safety structural: shortcuts no
text field claims go on `onPreviewKeyEvent` (so F5 works inside the search box), and the editing keys
— Space, arrows, Ctrl+arrow — go on `onKeyEvent`, which a focused text field has already consumed
them from. Don't replace this with focus tracking. No global hotkeys are registered.

### Navigation and state

`nav/AppNavigation.kt` is a real back stack of `Destination` values. `Destination.key` is stable
identity: re-opening an already-open destination **pops back to it** instead of stacking a copy, and
retained per-destination UI state (search text, scroll offset) is filed under that key. A destination
carries arguments only, never screen state.

- `ui/StateHolder.kt` — the desktop equivalent of a ViewModel. It owns a scope cancelled exactly
  once via `RememberObserver` (which also covers abandoned compositions, where effects never run).
  New screen logic belongs in a holder, driven from plain `runTest`, not in `rememberCoroutineScope`.
- `data/LibraryCache.kt` lives in the container, above the screens, so Library → Fiction → Back
  costs zero requests. `data/Cached.kt` tracks value, error, `isRefreshing` and last-success time
  independently — which is why a failed refresh shows a banner over retained content instead of
  blanking the screen. Full-screen errors are only for the nothing-cached case, and always carry a
  Retry.
- `data/ChapterLists.kt` holds filter/sort, bulk id selection and the optimistic played patch.
  Sorting is a *view*: the queue is always built in reading order. Marking is optimistic and atomic
  (one `playback/mark` request for the whole id set), and rollback restores each row's exact previous
  position rather than un-marking it — un-marking would zero real progress.

### Credentials

The token lives in the OS credential store only (`security/`): Windows Credential Manager via
`java.lang.foreign`, macOS Keychain and freedesktop Secret Service via CLI with the secret on
**stdin**, never `argv`. Where no keyring exists the session is memory-only and the UI says so.
There is deliberately no file-based fallback — encrypting with a key stored beside the ciphertext is
plaintext with extra steps. `session.json` holds non-secret hints plus the keyring entry id, written
atomically and owner-only.

### Releases and update checking

A release tag is `v` plus `ttsroad.version`; `release.yml` refuses to publish when the tag,
`gradle.properties` and the `CHANGELOG.md` section disagree, and
`packaging/release/changelog-section.sh` is both the notes extractor and that gate. Each installer
builds on its own OS; only the `.deb` gets the clean-container install/upgrade/uninstall run. The
release is created as a **draft**, and `workflow_dispatch` is the dry run that publishes nothing.

The update check reads the *public* GitHub release feed **over the shared `OkHttpClient`** — that is
what keeps the bearer token off api.github.com, via the same origin rule. It never installs: a
download is verified against the release's `SHA256SUMS`, a mismatch is deleted and never opened, and
the verified file goes to the desktop's own handler. No `sudo`, no package manager call. Throttling
is once per launch and once per day, dismissal is per version, and a manual check bypasses both. A
release with no asset for this platform/architecture is announced *without* a download button.

### Server capability discovery

`GET /api/mobile/capabilities` is unauthenticated and additive. Only a literal JSON `true` enables a
feature; unknown keys are ignored; `404` means baseline and is cached; a transient failure keeps the
last known answer rather than downgrading; `api_version` is never a proxy for a feature.

### Linux package and operational diagnostics

The Debian identity and installation directory are lowercase `ttsroad` (`/opt/ttsroad`); the display
name and launcher stay `TTSRoad`. `ttsroad.version` is the application SemVer and
`ttsroad.debRevision` is only Debian's revision for packaging-only rebuilds. Never fork the
application version into packaging metadata.
The desktop file name generated by JDK 25 jpackage is `ttsroad-TTSRoad.desktop`; MPRIS's
`DesktopEntry` must match it without the `.desktop` suffix. The finalizer moves that file to
`/usr/share/applications/` and strips jpackage's `xdg-desktop-menu` calls from `postinst`/`prerm` —
under `set -e` they abort the whole install where no writable system menu directory exists.
In Compose's DSL `appCategory` is the Debian `Section` and `menuGroup` is the desktop entry's
`Categories` (registered freedesktop names, not a readable group); `debMaintainer` is a bare address
because jpackage renders `Maintainer` as `<vendor> <<debMaintainer>>`.

The package requires GStreamer Base/Good/PulseAudio and `libsecret-tools`, recommends Bad for
skip-silence, and bundles the Java runtime. Keep `jdk.accessibility`, `java.instrument`,
`jdk.security.auth`, and the other explicit jlink modules: inference misses reflective/runtime
access. Linux package resources and checks live under `packaging/linux/`; update the inspection and
clean-install/upgrade/uninstall lifecycle whenever package layout or metadata changes.
Compose clears its private jpackage template directory immediately before execution, so Gradle's
Debian tasks run `packaging/linux/finalize-deb.sh` after jpackage to add Section/Recommends, the
extended description, the copyright and generated `changelog.Debian.gz`, and the desktop fields its
DSL cannot express. Do not bypass that finalizer when publishing an artifact. `inspect-deb.sh` runs
`lintian --fail-on error` and suppresses exactly three tags that follow from bundling a JDK
(`dir-or-file-in-opt`, `embedded-library`, `unstripped-binary-or-object`); do not widen that list.

Release minification is disabled intentionally. Moshi, Retrofit, JNA, GStreamer, D-Bus and mp3spi
all cross reflection, native or service-provider boundaries; do not enable ProGuard until explicit
keep rules and release-image tests cover those paths.

`--version` and `--diagnostics` must return before UI or credential-store construction. Diagnostics
may report existence/availability but must never load a session, inspect Secret Service entries, or
print environment values without redaction. `AppLog` is the single logging boundary: persistent
output is redacted, size-rotated and written below XDG state, separate from config/data/cache.

## Repo conventions

- **One application version**: `ttsroad.version` in `gradle.properties`. It feeds the Gradle
  coordinate, jpackage's `packageVersion`, and the generated `BuildInfo.kt` the About text,
  diagnostics and window title render. `ttsroad.debRevision` is separate package revision metadata,
  never another app version. jpackage rejects MAJOR `0`.
- **`BuildInfo.kt` is generated** by the `generateBuildInfo` task into `build/generated/` — don't
  edit or commit it.
- **Version catalog**: all dependency and toolchain versions live in `gradle/libs.versions.toml`,
  including `jdk`. Compose Material 3 and the extended icons are versioned independently of the CMP
  version on purpose (see ADR 0001).
- **`src/prototype/`** is a separate source set that `main` never sees, so an unaccepted evaluation
  can't reach the shipped app or its jlink image. `check` compiles it; running it needs a real
  GStreamer install: `./gradlew runPlaybackPrototype`.
- **jlink module list** in `build.gradle.kts` (`java.desktop`, `java.naming`, `jdk.crypto.ec`,
  `java.instrument`, `jdk.accessibility`, `jdk.security.auth`, …) is load-bearing — module inference
  misses these, and dropping one breaks
  the packaged app at runtime rather than at build time. The `--smoke-test` launch is what catches
  it, and it only catches what it *asserts*: each such module needs a check there, because the
  failures are silent by nature. `jdk.security.auth` is the MPRIS one — without it dbus-java cannot
  read the uid, and the packaged app reports "no MPRIS integration" exactly as a machine with no
  session bus would.
- **`--enable-native-access=ALL-UNNAMED`** is applied to run, test and the packaged app; Skiko,
  the Windows credential store and JNA all use restricted methods on JDK 25.

## Tests

JUnit 5 (plus Vintage, because Compose UI tests use the JUnit 4 `createComposeRule()` API).
`ServerFixtures.kt` carries real server-1.4.0 payloads including unknown additive fields;
`Fakes.kt` / `player/PlayerFakes.kt` carry the fake repository, playback controller, keyring and
command runner. Prefer these over new ad-hoc doubles.

`GstPlaybackEngineIntegrationTest` runs the real engine against a real GStreamer install using
`fakesink`, so it needs GStreamer but not a sound card. It **skips** where GStreamer is absent —
that is a supported configuration, not a broken one. CI installs
`gstreamer1.0-plugins-base`/`-good` and fails the build if a named element is missing, so the test
can't quietly skip its way to green.

## Decisions live in ADRs

`docs/adr/` records the reasoning and rejected alternatives behind the build baseline (0001),
credential storage and capability discovery (0002), the playback engine (0002-playback-engine),
device sessions and Settings (0003), navigation (0004), chapter browsing (0005), and listening
preferences / sleep timer / MPRIS / shortcuts (0006). Read the relevant one before changing any of
the invariants above; offline storage and caching are in 0007, read-along is in 0008, and Debian
packaging/operations are in 0009, and releases/update checking are in 0010. They exist because
the alternative was tried or measured. The cross-library server queue is in 0011.

## CI

`.github/workflows/ci.yml` runs on PRs and `master`: wrapper checksum validation, `clean check`
under Xvfb, a configuration-only re-run for build-script deprecations, release-distributable tests,
Debian metadata/payload inspection, and an Ubuntu 24.04 clean-install/upgrade/uninstall lifecycle.
Note it has **no base-branch filter** on purpose — roadmap phases land as stacked PRs whose base is
the preceding phase branch, not `master`.
