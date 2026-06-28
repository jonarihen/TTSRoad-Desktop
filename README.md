# TTSRoad Desktop

A native desktop client for a private TTSRoad server, built with **Compose for Desktop**
(JetBrains Compose Multiplatform on the JVM). It talks to the same mobile API (`/api/mobile/*`)
as the Android app and wears the same AARIS design language.

It is **not** a browser/Electron app — the UI is rendered natively with Skia and ships as real
OS installers (`.msi` on Windows, `.dmg` on macOS, `.deb` on Linux) with a bundled Java runtime,
so end users don't need Java installed.

## Stack

- Kotlin 2.1.0 on the JVM (toolchain 17)
- Compose Multiplatform 1.7.3 (`compose.desktop.currentOs`, Material 3)
- Retrofit + OkHttp + Moshi for networking (same as the Android client)
- Gradle 8.10.2 (wrapper included)

## Run it

You need a JDK 17+. If `java` isn't on your PATH, point `JAVA_HOME` at one first
(Android Studio bundles one):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat run
```

```bash
./gradlew run
```

## Package a native installer

```bash
./gradlew packageDistributionForCurrentOS
# output under build/compose/binaries/main/<format>/
```

## Project layout

```
src/main/kotlin/dk/perspektiva/ttsroad/desktop/
├── Main.kt                     app entry + window
├── App.kt                      root UI: login, library, player, settings
├── ui/Theme.kt                 AARIS theme + reusable components (ported from Android)
├── data/
│   ├── Models.kt               mobile API models (Moshi)
│   ├── TtsRoadApi.kt           Retrofit interface
│   ├── Repository.kt           shared OkHttpClient, login/library/chapters/progress
│   └── SessionStore.kt         file-backed token persistence (per-user config dir)
└── player/PlaybackController.kt playback abstraction (audio backend = TODO)
```

Session token is stored under `%APPDATA%/TTSRoad` (Windows), `~/Library/Application Support/TTSRoad`
(macOS), or `~/.config/TTSRoad` (Linux).

## What's scaffolded vs. TODO

**Working skeleton:** login (incl. 2FA), library browsing (continue-listening + fictions grid),
settings/logout, and the full player UI.

**Not wired yet — audio playback.** The player screen drives a `PlaybackController` interface; the
default `StubPlaybackController` only tracks UI state and does not decode audio. The chapter MP3s
are **bearer-protected**, so any real backend must send the `Authorization` header from
`TtsRoadRepository.authHeaderValue()`. Recommended approaches, in order:

1. **VLCJ** (`uk.co.caprica:vlcj`) — robust streaming MP3; attach the auth header via media options,
   or run a tiny localhost proxy that injects it.
2. **Stream to a temp file** with OkHttp (auth header attached) and play with JavaFX `Media`.

Other follow-ups: async cover-image loading (placeholder letters for now — Coil 3 supports desktop),
and a dedicated fiction-detail screen.
