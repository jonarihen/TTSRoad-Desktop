<div align="center">

# 🎧 TTSRoad Desktop

**A native desktop client for the private TTSRoad audiobook server.**

Built with Compose for Desktop — real Skia-rendered UI, real OS installers, no Electron, no browser.

<br>

![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white)
![JVM](https://img.shields.io/badge/JVM-17-ED8B00?logo=openjdk&logoColor=white)
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
| Full player UI (play/pause, seek, speed) | ✅ |
| **MP3 audio playback** | ✅ |
| Settings / logout | ✅ |
| Dedicated fiction-detail screen | 🚧 planned |

## 🛠️ Stack

- **Kotlin 2.1.0** on the JVM (toolchain 17)
- **Compose Multiplatform 1.8.2** — `compose.desktop.currentOs`, Material 3
- **Retrofit + OkHttp + Moshi** for networking (identical to the Android client)
- **mp3spi / JLayer** — pure-JVM MP3 decoding via the `javax.sound.sampled` SPI
- **Coil 3** — async cover-image loading & caching
- **Gradle 8.10.2** (wrapper included)

## 🚀 Run it

You need a **JDK 17+**. If `java` isn't on your `PATH`, point `JAVA_HOME` at one first (Android Studio bundles one):

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat run
```

```bash
# macOS / Linux
./gradlew run
```

## 📦 Package a native installer

```bash
./gradlew packageDistributionForCurrentOS
# output under build/compose/binaries/main/<format>/
```

> **Note:** `jpackage` (used to build installers) is **not** in Android Studio's bundled JBR — point `JAVA_HOME` at a full JDK 17+ for packaging.

## 🗂️ Project layout

```
src/main/kotlin/dk/perspektiva/ttsroad/desktop/
├── Main.kt                      app entry + window + Coil image loader
├── App.kt                       root UI: login, library, player, settings
├── ui/Theme.kt                  AARIS theme + reusable components (ported from Android)
├── data/
│   ├── Models.kt                mobile API models (Moshi)
│   ├── TtsRoadApi.kt            Retrofit interface
│   ├── Repository.kt            shared OkHttpClient, login/library/chapters/progress
│   └── SessionStore.kt          file-backed token persistence (per-user config dir)
└── player/
    └── PlaybackController.kt     Mp3PlaybackController — OkHttp download + SPI decode → SourceDataLine
```

## 🔊 How audio playback works

Chapter MP3s are **bearer-protected**, so `Mp3PlaybackController`:

1. Downloads the chapter to a temp file with OkHttp, attaching the `Authorization` header from `TtsRoadRepository.authHeaderValue()`.
2. Decodes it to PCM through the **mp3spi/JLayer** `javax.sound.sampled` SPI.
3. Streams the PCM to a `SourceDataLine` — no external native player (e.g. VLC) required.

Seeking re-decodes from the start of the (already-local) temp file and discards up to the target offset, since a streamed MP3 decoder has no random-access index — simple and exact, at the cost of a brief pause on long seeks.

## 🔐 Session storage

The session token is stored per-OS in the user config directory:

| OS | Location |
| --- | --- |
| Windows | `%APPDATA%\TTSRoad` |
| macOS | `~/Library/Application Support/TTSRoad` |
| Linux | `~/.config/TTSRoad` |

---

<div align="center">
<sub>Private project · <code>dk.perspektiva.ttsroad.desktop</code></sub>
</div>
