# ADR 0001 — The 2026 build, toolchain and test baseline

- **Status:** Accepted
- **Date:** 2026-08-06
- **Context issue:** [#2 — Establish the 2026 build, architecture, test and CI baseline](https://github.com/jonarihen/TTSRoad-Desktop/issues/2) (part of #1)

## Context

Before this change the desktop client built on Kotlin 2.1.0, Compose Multiplatform 1.8.2,
Gradle 8.10.2 and JVM 17, with every dependency written as an inline string literal in
`build.gradle.kts`. There was no version catalog, no test source set, no CI, no dependency
automation, and two independent version numbers (`version = "0.1.0"` for Gradle/Maven,
`packageVersion = "1.0.1"` for jpackage). `gradlew` was committed without its executable bit, so a
fresh Linux/macOS checkout could not run the build without `chmod +x`.

The object graph — session store, repository, playback controller — was constructed inline in the
root `App()` composable, which made any realistic unit test impossible: there was no way to
substitute an HTTP client, a clock, a dispatcher, an audio device, or the user's config directory.

## Decision

### Toolchain

| Component | Version | Notes |
| --- | --- | --- |
| JDK (build + bundled runtime) | **25** | via Gradle toolchains, auto-provisioned |
| Gradle | **9.6.1** | wrapper, SHA-256 pinned |
| Kotlin | **2.4.10** | `kotlin.jvm` + `kotlin.plugin.compose`, same version |
| Compose Multiplatform | **1.12.0** | |
| Bytecode target | **25** | `jvmToolchain(25)` + explicit `jvmTarget` / `--release` |

The JDK 25 toolchain is real, not aspirational: `settings.gradle.kts` applies
`org.gradle.toolchains.foojay-resolver-convention` 1.0.0, so Gradle downloads Eclipse Temurin
25 when it is not installed. Verified end-to-end on a machine with only JDK 17 and 21 present —
compiled output is class-file major 69, and the packaged app runs on the bundled JDK 25 runtime.
**There is no holdback to 21.**

We use `kotlin { jvmToolchain(25) }` plus an explicit `jvmTarget` and `options.release`, and we
deleted the legacy `java { sourceCompatibility/targetCompatibility }` block. Both the toolchain
and the bytecode level are derived from one catalog entry (`libs.versions.jdk`) so they cannot
drift — the mismatch between them is exactly what broke the build once before (commit 5a79614).

### Single version source

`gradle.properties` now carries `ttsroad.version=1.0.1`, and that one value feeds:

- the Gradle/Maven `version`,
- jpackage's `packageVersion` (and therefore the MSI/DMG/DEB filenames),
- a generated `BuildInfo.kt` (`VERSION`, `APP_NAME`) rendered in the settings screen and the
  window title.

`packageVersion` may never have MAJOR 0 — jpackage rejects it, especially for the macOS `.dmg` —
which is why the single number starts at 1.0.1 rather than at the old `0.1.0`.

### Version catalog and repositories

All plugins and libraries moved to `gradle/libs.versions.toml`. Two entries deliberately do **not**
track the Compose Multiplatform version, because since CMP 1.11.1 the `compose.material3` and
`compose.materialIconsExtended` accessors became **error-level** deprecations ("Specify dependency
directly") that stop the build script from compiling:

- `org.jetbrains.compose.material3:material3` is independently versioned; **1.9.0** is the newest
  *stable* release (1.10.x / 1.11.x / 1.12.x exist only as alphas).
- `org.jetbrains.compose.material:material-icons-extended` is **permanently frozen at 1.7.3** and
  will not receive updates. Migrating to Material Symbols vector resources is tracked separately;
  it is not a build-baseline concern. Dependabot is configured to ignore it.

The JetBrains `compose/dev` Maven repository was **removed** — the whole graph resolves from
`google()` + `mavenCentral()`. `google()` is **mandatory**: CMP 1.11 depends on real androidx
artifacts (`androidx.compose.runtime`, `androidx.collection`, `androidx.lifecycle`,
`androidx.savedstate`) even for a desktop-only JVM build.

### Libraries

Coroutines 1.11.0, OkHttp 5.4.0 (via `okhttp-bom`), Retrofit 3.0.0, Moshi 1.15.2, Coil 3.5.0.
Retrofit 3.x is binary-compatible with 2.x; the only change was raising its OkHttp floor. The old
`Coil 3.2.0` pin — held back because Coil 3.3+ required a `kotlin-stdlib` newer than the Kotlin
2.1.0 metadata reader accepted — is **gone**, that constraint disappears at Kotlin 2.4.10.

Two source changes fell out of the OkHttp 4 → 5 bump: `Response.body` is non-null (the `!!` is now
an error-level "unnecessary non-null assertion"), and MockWebServer moved from
`okhttp3.mockwebserver` to the `mockwebserver3` package (`mockwebserver3-junit5`).

### ProGuard

CMP hardcodes ProGuard 7.7.0, which **cannot parse Java 25 bytecode**
(`Unsupported version number [69.0]`). `buildTypes.release.proguard.version` is pinned to
**7.9.1**; 7.8.0 was the first release with Java 25 support. The release/minified path is not
currently used, but the override is set so it does not surprise the first person who tries it.
Enabling minification will additionally require a `proguard-rules.pro` with `-dontwarn` entries
for Skiko/OkHttp/Retrofit optional references.

### jpackage runtime image

`compose.desktop.application.javaHome` is explicitly pointed at the resolved JDK 25 toolchain.
jlink/jpackage otherwise default to the JVM *running Gradle*, which silently produced a JDK 21
runtime image that then failed at launch with `UnsupportedClassVersionError ... class file
version 69.0`. `modules(...)` also names `java.desktop`, `java.naming`, `java.management`,
`jdk.crypto.ec` and `jdk.unsupported`, since module inference does not reliably find the ones
reached only through reflection, SPI or TLS negotiation.

### Configuration cache

**Enabled** (`org.gradle.configuration-cache=true`). Verified storing and reusing across
`compileKotlin`, `test`, `check` and `createDistributable` with Compose Multiplatform 1.12.0.

### Architecture seams

Narrow interfaces, not a framework:

| Seam | Interface | Production implementation |
| --- | --- | --- |
| Session / credential storage | `SessionStore` | `FileSessionStore` (`InMemorySessionStore` for tests) |
| API repository | `TtsRoadRepository` | `RetrofitTtsRoadRepository` |
| Chapter audio download | `AudioDownloadStore` | `HttpAudioDownloadStore` |
| Audio backend | `AudioEngine` / `AudioLine` | `JavaSoundAudioEngine` |
| Playback | `PlaybackController` | `Mp3PlaybackController` |

`AppContainer` is the single composition root; `main()` owns it and closes it when the window
closes. Dispatchers (`AppDispatchers`), a `clock`, and the `OkHttpClient` are constructor
parameters with production defaults.

A side effect worth naming: the app used to build **three** independent `OkHttpClient` instances
(repository, audio downloads, Coil). There is now exactly one, so audio downloads inherit the
60 s read timeout instead of OkHttp's 10 s default.

`StateHolder` gives screens a coroutine scope that is cancelled when the composable leaves the
composition — `LibraryStateHolder`, `FictionDetailStateHolder` and `LoginStateHolder` hold logic
that previously lived inside `LaunchedEffect` blocks and could only be reached through the Compose
runtime. Login *credentials* deliberately stay in Compose state rather than moving into the
holder: routing keystrokes through a `StateFlow` buys nothing and the credentials should live as
briefly as possible.

### Tests

JUnit 5 (`junit-bom` 6.1.2) on the JUnit Platform, plus the Vintage engine because
`ui-test-junit4` is a JUnit 4 API. `kotlinx-coroutines-test`, `mockwebserver3-junit5`, and
Compose desktop UI tests.

## Consequences

- A fresh checkout needs **any** JDK on `PATH` to bootstrap Gradle; Gradle then downloads JDK 25.
- The Kotlin plugin and the Compose *compiler* plugin must always be bumped together — the
  compiler plugin ships with Kotlin, not with Compose Multiplatform. Dependabot groups them.
- `--warning-mode fail` in CI means a Gradle deprecation is a red build, which is the point, but
  a Gradle upgrade that deprecates something used by the Compose plugin will block CI until the
  plugin catches up.
- Compose UI tests need a display. Locally on Windows they run against the desktop; in CI they run
  under Xvfb. `java.awt.headless` is set to `false` for the `test` task.

## Alternatives considered and rejected

- **Gradle 9.5.1 instead of 9.6.1.** kotlinlang.org's KGP compatibility table lists 7.6.3–9.5.0
  for KGP 2.4.x, so 9.6.1 is formally outside the documented range. 9.5.1 was verified to work as
  well. We took 9.6.1 because it produced no "has not been tested" warning and passed the full
  compile/test/jlink/jpackage/config-cache matrix. If a KGP/Gradle incompatibility ever appears,
  dropping to 9.5.1 is the documented, verified fallback.
- **Compose Multiplatform 1.12.0.** Beta only; no stable date.
- **material3 aligned to 1.11.x.** No such stable release exists; only alphas.
- **Holding back to JDK 21.** Not needed — auto-provisioning demonstrably works.
- **Dependency locking / verification metadata.** Deferred. Gradle dependency locking would need
  lockfiles regenerated on every Dependabot PR, and verification metadata over the Compose +
  Skiko + androidx graph is a large, separately-reviewable change. Weekly Dependabot PRs plus a
  fully pinned version catalog is the interim position. **This is a known gap against issue #2.**
- **detekt / ktlint.** Deferred. Static analysis in CI today is Kotlin `allWarningsAsErrors` plus
  Gradle `--warning-mode fail`. Introducing detekt over ~2,400 lines of existing Compose UI would
  either need a suppression baseline (which hides exactly the findings it is supposed to surface)
  or a wave of edits to UI code during a phase whose contract is "do not change product
  behaviour". It should land as its own change. **This is a known gap against issue #2.**
