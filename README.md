<div align="center">

# 🎧 TTSRoad Desktop

**A native desktop client for the private TTSRoad audiobook server.**

Built with Compose for Desktop — real Skia-rendered UI, real OS installers, no Electron, no browser.

<br>

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4?logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-25-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white)
![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux-informational)
![Status](https://img.shields.io/badge/status-alpha-orange)

</div>

---

## ✨ Highlights

- 🖥️ **Truly native UI** — rendered with Skia via JetBrains Compose Multiplatform (Material 3), *not* a web view.
- 📦 **Real installers** — ships as `.msi` (Windows), `.dmg` (macOS), and `.deb` (Linux) with a **bundled Java runtime**, so end users never install Java.
- 🔗 **Shares the mobile API** — talks to the same `/api/mobile/*` endpoints as the Android app, and wears the same **AARIS** design language.
- 🔊 **Real audio playback** — bearer-protected chapter MP3s are decoded in pure JVM and streamed to the system audio device.

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
| Chapter list — All/Unplayed/Ready filter, oldest/newest sort, visible counts | ✅ |
| Highlight + auto-scroll to the playing chapter, "Jump to current" | ✅ |
| Bulk marks — all played / all unplayed / all previous, in one request | ✅ |
| Optimistic marking with rollback and an inline error | ✅ |
| Searchable up-next panel for long queues | ✅ |
| Player UI (play/pause, seek, configurable skip, next/previous, up-next queue) | ✅ |
| **MP3 audio playback** | ✅ |
| Settings — two-pane control centre (account, devices, playback, about) | ✅ |
| Device sessions — list, mark current, revoke one / revoke all others | ✅ |
| Playback preferences — speed, skip interval, skip silence, volume boost | ✅ |
| Sleep timer — 5/15/30/45/60 min or end of chapter, with a fade and "+5 min" | ✅ |
| Local listening history — "Jump back in", dismissible per snapshot | ✅ |
| MPRIS over D-Bus — Cinnamon applet, lock screen, hardware media keys | ✅ Linux |
| App shortcuts — Space, arrows, Ctrl+arrows, Ctrl+L, Ctrl+, plus an F1 list | ✅ |
| Offline downloads | ❌ |
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

## 🛠️ Supported build matrix

Everything below is verified together: `clean check`, `createDistributable`, a headless launch of
the packaged app, and `packageMsi`.

| Component | Version | Where it is declared |
| --- | --- | --- |
| JDK (build **and** bundled runtime) | **25** | `gradle/libs.versions.toml` → `jdk`, applied as `kotlin { jvmToolchain(25) }` |
| Gradle | **9.6.1** | `gradle/wrapper/gradle-wrapper.properties` (SHA-256 pinned) |
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
Listening preferences, the sleep timer, local history, MPRIS and the shortcut table are in
[`docs/adr/0006-listening-preferences-and-desktop-integration.md`](docs/adr/0006-listening-preferences-and-desktop-integration.md).

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

## 🔢 Versioning

There is exactly **one** version number: `ttsroad.version` in `gradle.properties`. It feeds the
Gradle/Maven coordinate, `jpackage`'s `packageVersion` (and therefore installer filenames), and the
generated `BuildInfo.kt` that the settings screen and the window title render. Bump it in one
place. jpackage rejects MAJOR `0`, so it must stay `>= 1.0.0`.

## 🤖 CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests and on `master`:

1. Gradle wrapper validation (checksum of `gradle-wrapper.jar`).
2. `clean check` under Xvfb — compile, unit tests, Compose UI tests.
3. Static analysis — Kotlin warnings-as-errors + Gradle deprecation-as-error.
4. `createDistributable`.
5. Headless launch smoke test of the packaged image, which also asserts the MP3
   `javax.sound.sampled` SPI survived jlink module minimisation.

Dependency updates arrive as weekly grouped Dependabot PRs
([`.github/dependabot.yml`](.github/dependabot.yml)).

## 🗂️ Project layout

```
src/main/kotlin/dk/perspektiva/ttsroad/desktop/
├── Main.kt                       entry point, window placement, Coil loader, --smoke-test
├── App.kt                        root UI: back stack, shortcuts, header, login
├── di/AppContainer.kt            the single composition root + AppDispatchers
├── nav/
│   ├── AppNavigation.kt          Destination + stable keys + the back-stack rules
│   └── Shortcuts.kt              the keyboard table and Escape's precedence, as pure functions
├── data/
│   ├── Models.kt                 mobile API models (Moshi)
│   ├── PlaybackPreferences.kt    speed/skip/silence/boost, machine-local, migrating on read
│   ├── PlaybackHistory.kt        bounded local snapshots, last-heard and jump-back selection
│   ├── Cached.kt                 value + error + isRefreshing + last success, independently
│   ├── LibraryCache.kt           library/chapter state held above the screens
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
└── ui/
    ├── Theme.kt                  AARIS theme tokens
    ├── Components.kt             CoverImage, stale/empty/initial-error states, "how old is this"
    ├── StateHolder.kt            lifecycle-aware state-holder base + rememberStateHolder
    ├── WindowLayout.kt           supported minimum size + the three width classes
    ├── LoginStateHolder.kt       login submit + result mapping
    ├── SettingsStateHolder.kt    settings panes, device sessions, confirmations
    ├── LibraryScreen.kt          LazyVerticalGrid: hero, shelves, search, fictions
    ├── FictionDetailScreen.kt    LazyColumn: one header item, then chapter rows + bulk controls
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

## 🎚️ Listening preferences, the sleep timer and the desktop

Speed, skip interval, skip silence and volume boost live in `playback.json` next to the session and
window files — **not** in the session. Signing out does not reset them, and a second account on a
shared machine does not inherit the first one's. The file has no notion of a user at all.

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

**Local history** (`history.json`, 60 entries, one per chapter) backs the "Jump back in" strip. It
holds ids and titles and *no URL of any kind* — covers are re-resolved from the live library cache.
Dismissing an entry hides that snapshot, not the day: a later chapter of the same serial comes back
on its own, and a progress save for a dismissed chapter does not resurrect it.

On Linux the player appears on the session bus over **MPRIS**, so Cinnamon's media applet, the lock
screen and hardware media keys show the right metadata and control playback. It is pure Java —
dbus-java over the JDK's own AF_UNIX socket — so it adds no native library to the bundled runtime.
No session bus (Windows, macOS, a bare SSH session) means no MPRIS and a fully working player, with
a note in the log rather than a failure.

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
- **Read-along** appears only when the server reports the `readalong` capability *and* the chapter
  has timings. It currently navigates to the reader placeholder; the reader itself lands in a later
  phase.
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

The discovered server name and version appear under the URL field **before** any credential is
sent, so a typo'd hostname is visible rather than password-shaped.

---

<div align="center">
<sub>Private project · <code>dk.perspektiva.ttsroad.desktop</code></sub>
</div>
