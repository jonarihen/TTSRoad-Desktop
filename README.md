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
| Library — continue-listening + fictions grid | ✅ |
| Async cover images (cached) | ✅ |
| Dedicated fiction-detail screen | ✅ |
| Player UI (play/pause, seek, ±30 s, next/previous, up-next queue) | ✅ |
| **MP3 audio playback** | ✅ |
| Settings / logout / version | ✅ |
| Variable-rate playback ("speed") | ❌ |

> The `SourceDataLine` backend cannot resample, so `PlaybackController.setSpeed` only records a
> value — there is no speed control in the UI and no effect on audio. Changing that means a new
> `AudioEngine` implementation.

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
[`docs/adr/0001-2026-build-baseline.md`](docs/adr/0001-2026-build-baseline.md).

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
├── Main.kt                       entry point, window, Coil loader, --smoke-test
├── App.kt                        root UI: navigation, login, settings
├── di/AppContainer.kt            the single composition root + AppDispatchers
├── data/
│   ├── Models.kt                 mobile API models (Moshi)
│   ├── TtsRoadApi.kt             Retrofit interface
│   ├── Repository.kt             TtsRoadRepository (interface) + RetrofitTtsRoadRepository
│   ├── SessionStore.kt           SessionStore (interface) + File-/InMemory- implementations
│   └── ServerUrls.kt             normalizeBaseUrl / resolveAgainstServer
├── player/
│   ├── PlaybackController.kt     PlaybackController (interface) + Mp3PlaybackController
│   ├── AudioDownloadStore.kt     bearer-authenticated chapter download seam
│   └── AudioEngine.kt            javax.sound.sampled seam (decode + output line)
└── ui/
    ├── Theme.kt                  AARIS theme tokens
    ├── Components.kt             PageScroll, Load<T>, CoverImage, ...
    ├── StateHolder.kt            lifecycle-aware state-holder base + rememberStateHolder
    ├── LibraryStateHolder.kt     library load
    ├── FictionDetailStateHolder.kt   chapter list + mark-played
    ├── LoginStateHolder.kt       login submit + result mapping
    ├── LibraryScreen.kt
    ├── FictionDetailScreen.kt
    └── PlayerScreen.kt           full player + NowPlayingBar

src/test/kotlin/dk/perspektiva/ttsroad/desktop/
├── ServerFixtures.kt             real server-1.4.0 payloads, incl. unknown additive fields
├── Fakes.kt                      FakeRepository, FakePlaybackController
├── data/                         URL, model-parsing, login, repository, session-store tests
├── player/                       playback state machine against a fake download store + engine
└── ui/                           state-holder tests + Compose player smoke tests
```

## 🔊 How audio playback works

Chapter MP3s are **bearer-protected**, so `Mp3PlaybackController`:

1. Downloads the chapter to a temp file through `AudioDownloadStore`, attaching the
   `Authorization` header from `TtsRoadRepository.authHeaderValue()`.
2. Decodes it to PCM through `AudioEngine` — in production the **mp3spi/JLayer**
   `javax.sound.sampled` SPI.
3. Streams the PCM to the engine's output line (a `SourceDataLine`) — no external native player
   (e.g. VLC) required.

Seeking re-decodes from the start of the (already-local) temp file and discards up to the target
offset, since a streamed MP3 decoder has no random-access index — simple and exact, at the cost of
a brief pause on long seeks.

Both the download and the audio backend are interfaces, so the whole queue / auto-advance /
progress-save state machine is unit-tested with no network and no sound card.

## 🔐 Session storage

The session token is stored per-OS in the user config directory:

| OS | Location |
| --- | --- |
| Windows | `%APPDATA%\TTSRoad\session.json` |
| macOS | `~/Library/Application Support/TTSRoad/session.json` |
| Linux | `$XDG_CONFIG_HOME/TTSRoad/session.json` (default `~/.config/TTSRoad/session.json`) |

> ⚠️ The bearer token is currently stored **in plaintext** with default file permissions — no OS
> keychain, no encryption. `SessionStore` is the single seam that change has to go through.

---

<div align="center">
<sub>Private project · <code>dk.perspektiva.ttsroad.desktop</code></sub>
</div>
