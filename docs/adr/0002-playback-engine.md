# ADR 0002 — The production playback engine

- **Status:** Proposed
- **Date:** 2026-08-06
- **Context issue:** [#4 — Phase 5: Replace the prototype MP3 path with a production playback engine](https://github.com/jonarihen/TTSRoad-Desktop/issues/4) (part of #1)

## Context

`Mp3PlaybackController` plus `JavaSoundAudioEngine` (mp3spi/JLayer over `javax.sound.sampled`) was
built in Phase 0 as a deliberate prototype: an interface seam with the simplest implementation that
could make a chapter audible. It has four defects that cannot be fixed inside that backend.

1. **Nothing plays until everything is downloaded.** `HttpAudioDownloadStore.download` copies the
   entire chapter to a temp file before `runPlaybackLoop` opens it. On an audiobook chapter over a
   slow link that is a multi-second stall with no feedback.
2. **Seeking decodes from zero.** A streamed MP3 decoder has no random-access index, so
   `runPlaybackLoop` closes the decoder, reopens it at byte 0 and `skip`s to the target. Seeking
   near the end of a long chapter is the slowest operation in the app.
3. **There is no speed control.** `SourceDataLine` cannot resample, so `setSpeed` only writes a
   number into `PlayerUiState`. The README lists variable-rate playback as the one unimplemented
   feature. For an audiobook client this is not a minor gap.
4. **Pause holds the audio line and polls.** The loop `delay(80)`s in a `while` that keeps the line
   open, so a paused player occupies the output device and wakes 12 times a second.

Progress is also saved only on a 10-second tick and at natural end-of-stream — not on pause, seek,
chapter switch, sign-out or window close.

Issue #4 requires a design step before the replacement: evaluate maintained backends and record
licensing, bundle size, Linux Mint/PipeWire behaviour, ARM64 feasibility, authenticated
streaming/range support, pitch-preserving rate control, packaging dependencies and failure modes.

## Decision

**Use GStreamer through [gst1-java-core](https://github.com/gstreamer-java/gst1-java-core), fed
from the JVM through `appsrc`, with `scaletempo` in the audio chain.** Keep the existing Java Sound
path as a fallback engine on platforms where GStreamer is absent.

### The pipeline

```
appsrc → decodebin → audioconvert → scaletempo → audioconvert → audioresample → autoaudiosink
```

`autoaudiosink` picks `pulsesink` on Mint, which is what PipeWire's Pulse server presents, so
PipeWire and PulseAudio are the same code path.

### Bytes come from the JVM, not from GStreamer

The obvious way to play an authenticated URL is `souphttpsrc` with an `extra-headers` structure
carrying the bearer token. **We deliberately do not do that**, for two reasons.

*It is not reliably installed.* On the machine this ADR was verified on, `gstreamer1-plugins-good`
is installed and `/lib64/gstreamer-1.0/libgstsoup.so` exists on disk, yet
`gst-inspect-1.0 souphttpsrc` reports "No such element or plugin" — the plugin dlopens libsoup at
runtime and silently fails to register without it. A backend whose network source can vanish
depending on an unrelated package is not one to build a login-gated player on.

*We already have a better HTTP client.* Phase 1 established a single `OkHttpClient` whose auth
interceptor attaches the token **if and only if** the resolved URL is on the signed-in server's
origin — the rule that stops an absolute audio URL pointing elsewhere from leaking the credential.
Feeding `appsrc` from that client keeps the credential rule, the 60 s read timeout and the typed
`SessionExpiredException` 401 path in Kotlin, where they already exist and are already tested — and
it puts the 2s/5s/15s retry ladder issue #4 asks for in the same place, rather than splitting
network behaviour across two stacks. It also makes Phase 7's offline cache just another byte
source rather than a second network path.

`appsrc` is in `gst-plugins-base`, which the `.deb` must depend on regardless. Using it instead of
`souphttpsrc` therefore adds nothing to the dependency list, and drops the one plugin that was
demonstrated above to be unreliable.

### Speed

`scaletempo` scales tempo with a WSOLA-style cross-correlation and leaves pitch alone. It is
applied by sending a seek event carrying `rate`, not by setting a property. Note that it must sit
in the chain explicitly — `playbin` does not insert it, and if it is installed as `playbin`'s
`audio-filter` while the sink can take the format in passthrough, it is bypassed and rates other
than 1.0 stop working. Building the bin by hand rather than using `playbin` avoids that trap.

### Measured evidence

Not a paper evaluation. A prototype (`src/prototype/kotlin`, run with `./gradlew
runPlaybackPrototype`) plays a generated 32.5 s / 440 Hz MP3 through the real pipeline and measures
the output. Frequency is recovered by zero-crossing rate, so pitch preservation is measured rather
than assumed:

| Requested rate | Output length | Expected | Dominant frequency |
| ---: | ---: | ---: | ---: |
| 0.50× | 65.010 s | 65.097 s | 440.1 Hz |
| 1.00× | 32.549 s | 32.549 s | 440.9 Hz |
| 1.50× | 21.690 s | 21.699 s | 440.3 Hz |
| 2.00× | 16.260 s | 16.274 s | 440.5 Hz |
| 3.00× | 10.860 s | 10.850 s | 440.7 Hz |

Tempo tracks the request to within 0.15% across the whole 0.5×–3.0× range issue #4 asks for, and
the tone stays at 440 Hz throughout — the pitch does not shift.

The two other defects, measured in real time on the same fixture:

| Measurement | Result | Today |
| --- | --- | --- |
| Time to first audio | **11–12 ms**, after reading 4,096 of 150,960 bytes (2.7%) | 100% of the chapter downloaded first |
| Seek from 1 s to 30 s | **26–35 ms**, first sample at exactly 30.000 s | decode and discard from byte 0 |

Verified against GStreamer 1.22.12, gst1-java-core 1.4.0, JNA 5.19.1, on JDK 25.

**Where this was verified matters.** The host has no sound card — `/dev/snd` carries no PCM
device — so the measurements were taken through `appsink`, not through `autoaudiosink`. That is
what makes them measurements rather than impressions: the numbers come from counting output
samples, and nothing depends on a working output device. But it also means **nothing here proves a
sound was audible**. The audible checks in issue #4's acceptance criteria — speed changes without
pitch shift *to the ear*, output-device disappearance, PipeWire behaviour — still have to be done
on a real Mint machine with working audio. The pitch measurement above is the strongest available
substitute, not a replacement.

### Versions, licensing and size

| | |
| --- | --- |
| gst1-java-core | 1.4.0 (Jan 2025), **LGPL-3.0-only** |
| JNA | 5.19.1 — the POM asks for the range `[5.2.0,6.0)`, which we pin in the catalog |
| GStreamer core + base/good plugins | LGPL-2.1, **not bundled** — an apt dependency |
| Added to the distributable | **2.5 MB** of jars (gst1-java-core 0.6 MB + JNA 1.9 MB) |

`jna-platform` is *not* required — gst1-java-core's only compile dependency is `jna`.

LGPL-3.0 is satisfied by linking against an unmodified jar the user can replace, which is what
shipping it as a separate jar in the app image does.

### Packaging

GStreamer cannot go inside the jlink image, so the `.deb` must declare it (Phase 9):

```
Depends: gstreamer1.0-plugins-base, gstreamer1.0-plugins-good, gstreamer1.0-pulseaudio
```

Every element the pipeline names, and where it comes from (read off the installed packages, not
from documentation):

| Element | Shared object | Package |
| --- | --- | --- |
| `appsrc` | `libgstapp.so` | plugins-base |
| `decodebin` | `libgstplayback.so` | plugins-base |
| `audioconvert` | `libgstaudioconvert.so` | plugins-base |
| `audioresample` | `libgstaudioresample.so` | plugins-base |
| `scaletempo` | `libgstaudiofx.so` | plugins-good |
| `mpg123audiodec` | `libgstmpg123.so` | plugins-good |
| `autoaudiosink` | `libgstautodetect.so` | plugins-good |
| `pulsesink` | `libgstpulseaudio.so` | plugins-good *on Rocky* — **Debian/Mint split this into `gstreamer1.0-pulseaudio`**, which is why it is a third dependency above |

These packages are present on a default Linux Mint install (Cinnamon pulls them in through its
media stack), so in practice apt should have nothing to fetch — but they are declared rather than
assumed. **This mapping was read on Rocky Linux 9 and must be re-checked on a real Mint image in
Phase 9**, precisely because the `pulsesink` row already differs between the two distributions.

ARM64 is feasible: Debian/Ubuntu ship GStreamer for arm64 and JNA carries an arm64 native, so the
same `.deb` recipe applies. Not verified here.

### JDK 25, and the JNA question

JNA calls `System.load`, which JEP 472 made a restricted method: a warning on JDK 24/25, and
"blocked in a future release". Nothing new is needed for it — `build.gradle.kts` already defines
`nativeAccessArgs = ["--enable-native-access=ALL-UNNAMED"]` and applies it to both the test JVM and
the packaged app, because Skiko and the Windows credential store hit the same restriction. Verified
on the prototype: with the flag the warning disappears and behaviour is unchanged.

**This does contradict an earlier decision, and the contradiction is worth naming.**
`WindowsCredentialStore` explicitly chose `java.lang.foreign` over "adding a native-bridge library
to the dependency graph and to the jlink image". gst1-java-core is exactly such a library, and it
brings JNA 5.19.1 (1.9 MB) with it.

The distinction is scale, not principle. That decision covered **four** Win32 calls, where hand-
written FFM downcalls are less code than the dependency. GStreamer is GObject: elements, pads,
buses, dynamic pad-added callbacks, signal marshalling, refcounting and a type system. Hand-rolling
FFM bindings for that is a project, and getting GObject signal marshalling wrong produces JVM
crashes rather than exceptions. gst1-java-core is 600 KB of maintained, API-stable binding code
that already does it. There is no FFM-based GStreamer binding in the ecosystem to choose instead.

So: FFM stays the rule for small, fixed native surfaces; a maintained binding wins for a large one.
If that trade is unacceptable, the pure-JVM alternative below is the option to take instead — it is
the only other one that keeps JNA out.

### The seam

`AudioEngine`/`AudioLine` is the wrong shape for this — it assumes the app decodes and pushes PCM.
Phase 5 replaces it with a `PlaybackEngine` interface that owns transport (prepare/play/pause/
seek/rate/position/duration + an event stream), because GStreamer owns the clock and the decode
loop. `PlaybackController` keeps the queue, progress-saving and session-expiry logic and stays
testable against a fake engine — that is the property Phase 0 bought and it must not be lost.

## Consequences

- **Linux gets a real engine; Windows and macOS do not.** GStreamer is not present by default on
  either, and the roadmap's target is a Linux-native client (#1). `JavaSoundAudioEngine` is
  retained behind the new interface as a fallback: those platforms keep today's behaviour,
  including no speed control, and the UI must gate the speed control on the engine's reported
  capability rather than showing a dead knob.
- **A native dependency enters the app.** A JNA crash is a JVM crash, not an exception. Engine
  construction must fail soft: if `Gst.init` throws or the required elements are missing, fall back
  rather than failing to start.
- **Two engines to maintain.** Accepted, because the fallback already exists and the alternative is
  dropping two platforms.
- The `.deb` gains dependencies, so Phase 9's verification must include a container with only the
  declared packages present.

## Alternatives considered and rejected

- **JavaFX Media.** Rejected. `Media` takes a URI and offers no way to attach an `Authorization`
  header, so bearer-protected chapters would need a localhost proxy — a second HTTP server inside
  the app purely to re-attach a header we already own. It also does not guarantee pitch-preserving
  rate, and adds far more than 2.5 MB.
- **Bundled FFmpeg (JavaCV or a packaged binary).** Rejected for now. It is the only option that
  would give all three platforms the same engine with no apt dependency, and `atempo` does
  pitch-preserving rate. But the per-platform natives are ~100 MB each, the licensing depends on
  the build flags of whichever binary is shipped, and it still needs a separate output sink. If
  Windows/macOS parity ever becomes a requirement, this is the option to revisit.
- **VLC via vlcj.** Rejected. Same "native dependency" cost as GStreamer with a heavier one to
  install, and VLC is not on a default Mint install the way GStreamer is.
- **Stay pure-JVM: keep Java Sound and write a WSOLA time-stretch.** Rejected, but it was close.
  It would preserve the "bundled runtime, no apt dependencies, all three platforms" property that
  is the README's headline claim. It needs three things built and tested by hand: progressive
  decode from a stream, an MP3 frame index for seeking (Xing/VBRI TOC plus a frame scan), and a
  WSOLA implementation. The first two are tractable. The one that decides it is
  `javax.sound.sampled`'s device handling on Linux: it has no usable notification for the default
  output changing or a device disappearing, which issue #4 requires handling without freezing.
  GStreamer solves that at the sink. We would be writing DSP to end up with worse device
  behaviour.
- **TarsosDSP for the time-stretch.** Rejected on licensing. It is GPL-3.0, and the Maven Central
  artifacts are third-party republications of it rather than releases by the author.
- **`souphttpsrc` for authenticated streaming.** Rejected — see above. Not reliably registered, and
  it would duplicate credential-scoping rules that Phase 1 already implements and tests.
