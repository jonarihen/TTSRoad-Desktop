# ADR 0006 — Listening preferences, the sleep timer, and desktop integration

- **Status:** Accepted
- **Date:** 2026-08-07
- **Context issue:** [#9 — Phase 6: Add playback preferences, sleep timer, history, MPRIS, and media keys](https://github.com/jonarihen/TTSRoad-Desktop/issues/9) (part of #1)

## Context

Phase 5 made playback real: a GStreamer backend that can actually resample, seek without
re-decoding, and stream a chapter before it has finished downloading. What it did not do is let
anyone *keep* a choice. Speed reset to 1.0 on every launch, the skip buttons were hard-coded to 30
seconds, and there was no sleep timer, no local history, and nothing on the session bus — so
Cinnamon's media applet and the keyboard's transport row saw no player at all.

Four questions had to be answered before any of that could be built.

1. **Where do listening settings live?** At the time, the server had no preference endpoint and
   they were not session data either. The account preference contract added later is reconsidered
   below rather than silently changing the original device-local semantics.
2. **How is a sleep timer made testable?** The acceptance criteria require deterministic behaviour
   across pause, resume, seek, chapter boundary, manual stop and app close, including a fade.
3. **What speaks D-Bus?** MPRIS is the only way media keys and the Cinnamon applet reach a Linux
   media player, and the JVM has no D-Bus support of its own.
4. **How do keyboard shortcuts coexist with text fields?** Space, the arrows and Ctrl+arrow are all
   editing keys, and the app already installs a window-level preview handler so F5 works inside the
   search box.

## Decision

### Preferences are machine-local, in their own file

`playback.json` in the existing config directory, beside `session.json` and `window.json`, written
owner-only through `SecureFiles`. It holds speed, skip interval, skip-silence and volume boost.

It is deliberately **not** part of the session:

- signing out must not reset someone's speed and skip interval;
- these controls describe this OS profile's output and listening environment, not server identity;
- the file therefore has no notion of a TTSRoad user. Two accounts used under the same OS login see
  the same values; different OS users already have separate config directories.

The on-disk shape is a separate type (`StoredPlaybackPreferences`) in which every field is nullable
and the enum is a plain string. A file written by an older build is missing keys; a file written by
a newer one can carry a `volumeBoost` this build has never heard of. Both load — degraded, never as
an exception during startup. Out-of-range numbers are **snapped, not defaulted**: a stored skip of
20 seconds means somebody chose 20, and 15 is a closer answer than falling back to 30.

Speed gets one extra rule. The offered list always contains the *stored* value even when it is not
one of this build's presets, so a rate set by another build stays selectable instead of being
silently rounded away the first time the menu is opened.

### The later account preference API does not change that ownership

Issue #35 revisited this decision after the server added `player_preferences`, with account keys for
speed, skip interval, skip silence, volume boost and a sleep-timer default. Desktop deliberately
does not synchronize them:

- speed, skip interval, silence removal and gain depend on the current device, output, engine and
  listening environment. Moving a speaker-specific boost or a plugin-dependent silence setting to
  every client is surprising, and an unsupported engine cannot faithfully apply the value;
- the sleep timer remains an explicit action. The server's `sleep_timer_default_minutes` does not
  arm playback on the web — it only marks a preferred choice in the menu — while desktop already
  presents the complete short ladder. Persisting or syncing a highlighted default adds state but
  does not make the safety-critical act of arming a timer any clearer;
- sign-out and offline playback keep the same predictable local behaviour. No best-effort network
  merge can overwrite a choice while audio is already running.

Reader appearance remains account-synchronized because it describes content presentation and is
portable across devices. Listening controls are local because they describe audio output. The
different ownership is intentional, not an unfinished capability flag.

### Volume boost stops at 2×

Off / Low / Medium / High map to 1.0, 1.3, 1.6 and 2.0. Above roughly 2× on already-mastered speech
the peaks clip rather than get louder, and the listener who reaches for "louder" because the
narration is quiet is exactly the listener who will not recognise the distortion as something *this
app* did. The ladder stops where the artefacts start.

Both engines honour gain, and that is not symmetric with speed on purpose. Speed needs a resampler
the Java Sound fallback does not have; gain is a multiply over PCM its decode loop is already
copying. The sleep timer's fade **is** a gain, so it has to work on every backend — a timer that cut
the audio off abruptly on one engine and faded it on the other would be worse than no boost at all.
The fallback's implementation saturates rather than wrapping, because an overflowing 16-bit sample
wraps from full positive to full negative, which is a click on every peak.

### Skip-silence is capability-gated, like speed

It needs GStreamer's `removesilence`, which ships in `gst-plugins-bad` and is absent from most
installs — this repository's own CI included. `EngineCapabilities` grew a `skipSilence` flag,
probed once at construction, and the UI draws the control only where the backend can honour it.
This is the same rule Phase 5 established for the speed control, and for the same reason: the
predecessor API accepted a number that no backend acted on.

The element sits **before** `scaletempo` in the chain. Dropping buffers changes the timeline, and
doing it after the tempo scaler would apply the rate to a stream whose length had already changed
underneath it.

```
appsrc → decodebin → audioconvert → [removesilence] → scaletempo → audioconvert → audioresample → volume → autoaudiosink
```

Toggling it takes effect at the next chapter rather than mid-stream. Inserting or removing an
element under a playing pipeline means a state change and a re-link, and the audible glitch is worse
than the toggle being one chapter late.

### The sleep timer is a state machine over an injected clock

`SleepTimer` has no coroutine and no `delay`. It exposes `arm`, `cancel`, `extendBy`,
`onPlaybackPaused`, `onPlaybackResumed`, `shouldStopAtChapterEnd` and `tick`, and the playback
controller drives `tick` from the 250 ms loop it already runs.

That is the whole reason the fade and the expiry are testable. "Deterministic under a fake clock" is
an acceptance criterion, and a real timer makes every one of the required cases — pause, resume,
seek, chapter boundary, manual stop, app close — a race against a scheduler. Here a test steps an
hour in one line.

Details that fall out of the design:

- **Pause freezes, resume continues.** A manual pause stores the remainder and drops the deadline;
  resuming rebuilds the deadline from the remainder. An hour paused costs the timer nothing.
- **The fade is the last 30 seconds**, published as a `fadeGain` multiplier rather than written to
  the engine directly. The controller multiplies it by the volume-boost gain, so the two compose:
  cancelling a fade restores the *boost*, not unity.
- **"+5 min" adds to what is left**, and clears the fade immediately rather than on the next tick —
  a listener who is still awake should hear the audio come back as they press it. It keeps the
  original mode, so the chosen duration stays selected in the UI.
- **End-of-chapter is not a duration in disguise.** A chapter's remaining time changes under a seek
  and a rate change; expressing it as "stop at the boundary" keeps it correct when either happens.
  It is checked *before* the advance, and disarms itself so a still-loaded queue is not stopped at
  every subsequent boundary.
- **Expiry pauses, it does not stop.** The queue and position stay put, so the morning's resume is
  one keypress rather than a search for the chapter.

### MPRIS: dbus-java, and a mapping that is tested without a bus

`com.github.hypfvieh:dbus-java-core` plus its `native-unixsocket` transport. It implements the D-Bus
wire protocol in pure Java and reaches the session bus through the JDK's own AF_UNIX
`SocketChannel`, so it adds **no native library** to the jlink image.
`slf4j-nop` is pinned alongside it: without a provider, SLF4J 2 prints "No SLF4J providers were
found" to stderr on first use, and a desktop app should not narrate its dependency wiring.

It does, however, add a **jlink module**: the transport reads the caller's uid through
`com.sun.security.auth.module.UnixSystem`, which lives in `jdk.security.auth` and is reached only by
reflection, so module inference misses it. Leaving it out produced the worst available failure mode
— the packaged app started, played audio, and logged "no MPRIS integration on this desktop", which
is exactly what a machine with no session bus logs, while the same code worked from `./gradlew run`
because a full JDK was on the module path. The `--smoke-test` launch now asserts the class resolves
(`verifyMprisRuntimeModulesArePresent`), because the smoke test deliberately does *not* claim a bus
name and so could never have caught this by connecting. "No session bus" stays a passing
configuration; "no module" does not.

The integration is split in two:

- `MprisState.kt` is pure Kotlin with no D-Bus type in it. The audiobook mapping lives here —
  chapter as `xesam:title`, serial as `xesam:album` **and** `xesam:artist` — and is unit-tested on
  any machine, CI included. There is no author in the mobile API payload, and an empty artist
  renders as a dangling separator in several shells, so repeating the serial reads better than a
  blank. That is a display choice, not a claim about authorship.
- `MprisService.kt` is the plumbing: one exported object answering the root interface, the player
  interface and `Properties` at `/org/mpris/MediaPlayer2`.

Rules that are easy to get wrong and are therefore written down:

- **`Position` is never in a `PropertiesChanged`.** The spec excludes it, because a player emitting
  it every tick is a signal storm. A discontinuity — a seek, a skip, a chapter change — is reported
  as `Seeked` instead, and clients interpolate between the two.
- **A stale `SetPosition` track id is ignored.** Clients send an absolute position for the track
  they last saw; honouring a stale one seeks the *new* chapter to the old one's offset.
- **`OpenUri` does nothing.** Every URI this player can open is bearer-protected and belongs to the
  signed-in server. Accepting an arbitrary one off the session bus would be an open redirect into an
  authenticated client.
- **`Volume` snaps.** The shell's slider is continuous and the boost is a four-step ladder; Set
  picks the nearest step and the property reports the snapped value back, so the slider does not
  drift away from what is playing.
- **Bus name collisions fall back to the spec's `.instanceN`.** Two windows publishing under one
  name would fight over the applet's display.
- **Absence is normal.** `createOrNull` catches `Throwable`, not `Exception` — a missing transport
  provider arrives as a `ServiceConfigurationError`. No session bus means no MPRIS and a fully
  working player, with a diagnostic in `AppLog`.
- **`Properties.Get` returns the `Variant`, not `variant.value`.** `Get` is declared to return `v`,
  and dbus-java re-wraps a bare value in an *unqualified* Variant, which cannot marshal `Metadata`
  — its `a{sv}` signature exists only because `playerProperties` attached it. Unwrapping made every
  scalar property work and `Metadata` alone fail, which is why `GetAll` (typed `a{sv}`, Variants
  intact) looked fine while a client that reads properties one at a time got an error instead of a
  title. Verified against a real session bus with `busctl`; CI has no bus and cannot catch it.

`mpris:artUrl` does name the private server's host to other processes in the user's login session.
That is the cost of the desktop being able to draw artwork at all; the cover endpoint is
unauthenticated and nothing authenticating travels with it.

### Shortcuts: two handlers, not a focus tracker

The obvious way to keep Space from pausing audio while someone types is to track whether a text
field has focus. We do not do that. Instead the shortcut table is split by a
`AppShortcut.firesWhileTyping` classification and installed on two handlers:

- `onPreviewKeyEvent` runs **before** the focused component and carries only the combinations no
  text field claims — F5, Ctrl+R, Escape, Alt+Left, Ctrl+L, Ctrl+comma, F1. This is what keeps F5
  working inside the library's search box, which Phase 3 established.
- `onKeyEvent` runs **only if nothing else consumed the key**. A focused text field has already
  taken Space, the arrows and Ctrl+arrow for editing by the time it runs, so the transport shortcuts
  never see them.

The guard is therefore structural rather than a check somebody has to remember, and the
classification is a pure value so a test can assert it with no toolkit, no display and no focus.

Media keys are bound in-app too (`Key.MediaPlayPause` and friends), for when the window has focus.
With no focus they go to the desktop, which routes them over MPRIS — the same actions through the
other door. **No global hotkeys are installed**, per the issue: grabbing keys system-wide without an
explicit opt-in is hostile.

### History is local-first and shared through automatic bookmarks

`history.json`, at most 60 entries, one per chapter, newest first, remains the offline fallback.
`SyncedPlaybackHistoryStore` also writes each observation as a `kind=auto` bookmark and merges the
account's live automatic bookmarks when the library opens. Those rows are the server-side home the
web client also uses, so a moment recorded on one device is discoverable on another without putting
the network in the path of playback or startup.

The controller records on pause and chapter change and once every five minutes of active playback,
matching the web cadence. The ten-second progress tick does not write history. A suspended laptop
counts as no listening time, and failed background writes are silent: progress sync still owns the
resumable position, while the local snapshot survives for the next offline launch.

Two decisions worth recording:

- **The snapshot type has no URL field of any kind** — not the server, not the audio object, not the
  cover. It holds identifiers and titles; covers are re-resolved from the live library cache by
  fiction id at display time. The guarantee is that there is nowhere in the type to put a leak.
- **Dismissal belongs to the snapshot, not to a day.** Hiding "continue chapter 12" must not also
  hide chapter 13 tomorrow, and must not un-hide chapter 12 tomorrow either. Recording a new
  position for a chapter that is already dismissed *inherits* the dismissal — otherwise the next
  progress save would undo a dismissal within a tick of the user making it. A different chapter is a
  different snapshot and appears normally.
- **Remote merge never replaces newer offline work.** The newest observation for a chapter wins,
  but a duration already resolved locally and a local dismissal survive because neither exists in
  the bookmark contract. The server's rolling window owns remote retention; the desktop's 60-row
  bound still owns its local file.

This amends the original local-only decision after the shared bookmark contract landed. Keeping a
local fallback was retained rather than replacing it because offline playback is a first-class path.

### The tray, and what the window's close control means

Where the desktop offers a system tray, TTSRoad puts an icon there with the chapter and serial as
its tooltip, play/pause, both skips, *Show TTSRoad* and *Quit*. `ui/TrayPresentation.kt` holds the
rules as pure functions and `Main` holds the plumbing — the same split as `MprisState` and
`MprisService`, and for the same reason: the interesting half is testable without a session bus, a
tray, or a display.

The tooltip reuses the MPRIS mapping (chapter as the title, serial as what it belongs to) because
the two are answering the same question on the same desktop, and two different answers would look
like a bug in one of them.

Two decisions carry the weight:

- **Closing quits, unless the user asked otherwise.** This is the question the idea raised —
  closing to tray surprises people who meant to quit, quitting surprises people who meant to keep
  listening — and the tie is broken by which surprise is worse. A close control that closes is what
  every window on the desktop does; the other way round leaves a process running and holding a media
  session for someone who believed they had quit. So it is a setting, it defaults to off, and the
  first close-to-tray sends one tray notification saying TTSRoad is still running. Once: somebody
  who asked for this knows it by the second time, and a notification on every close is exactly the
  tray noise the feature exists to avoid.
- **The platform gets a veto.** `windowCloseIntent` requires *both* the preference and
  `isTraySupported`. Several Wayland sessions ship without a tray, and hiding a window into an icon
  the platform will not draw produces a running process the user can neither see nor quit — strictly
  worse than either honest answer. Where there is no tray, Settings says so instead of offering a
  switch that promises nothing.

`WindowPlacement` carries the flag, which makes `window.json` the file with both halves of "what
this window does". They are written by different hands at different times — geometry once as the
window closes, against the placement loaded at startup; the preference whenever Settings is touched
— so the close handler merges through `withBehaviourOf` rather than writing the startup snapshot
wholesale, which would silently revert a setting changed five minutes earlier.

MPRIS `Raise` un-hides as well as raising, since after a close-to-tray the window is not merely
behind something, and `Quit` from a shell quits rather than hiding: a client asking a player to quit
is not asking it to get out of the way.

## Consequences

- Speed, skip interval, skip-silence and volume boost survive a restart and a sign-out, and are
  applied by the controller, so an auto-advanced chapter and a media-key start use the same values
  as one the user pressed play on.
- Accounts under one OS profile share those machine-level controls; they are not presented as
  account preferences and are never synchronized through `player_preferences`.
- The sleep timer's whole surface is testable without audio, a display or wall-clock waiting.
- Cinnamon's applet, the lock screen where present, and hardware media keys control playback and
  show correct metadata; seek and rate stay synchronised.
- The app gains three runtime dependencies (`dbus-java-core`, its unix-socket transport, and
  `slf4j-nop`) and no new native ones.
- Skip-silence is unavailable on most installs, CI included, and says so rather than pretending.
- The `.deb` in phase 9 (#3) recommends `gstreamer1.0-plugins-bad` so skip-silence is available
  where a user wants it. JDK 25 jpackage names the desktop entry from the Debian package and
  launcher, so it installs `ttsroad-TTSRoad.desktop` and `Mpris.DesktopEntry` reports
  `ttsroad-TTSRoad`; keeping those identical is what gives Cinnamon's player applet the app icon.

## Alternatives considered and rejected

**Preferences on the server.** There was no endpoint when this decision was first made. The later
`player_preferences` contract is real but does not change the ownership: a phone on a commute and a
desktop on speakers do not want the same speed, gain or silence-removal support. Synchronizing only
`sleep_timer_default_minutes` was also considered; because it merely highlights a menu choice and
never arms the timer, desktop keeps the simpler explicit timer ladder.

**Preferences inside `session.json`.** Simpler, and wrong: signing out would reset controls that
belong to the OS profile and output setup.

**A coroutine-based sleep timer with `delay`.** The natural implementation, and untestable against
the acceptance criteria without either sleeping through real minutes or mocking `delay` at the
dispatcher level. The state machine is more code and answers every required case exactly.

**Mapping the sleep fade with a second engine volume control.** Two independent volume paths writing
one element is how a fade ends up being undone by a preference change mid-fade. One multiplied gain,
computed in one place.

**`MASTER_GAIN` on the `SourceDataLine` for the fallback engine's gain.** Its range is
device-dependent and it is frequently absent altogether, so a fade built on it would silently do
nothing on some machines — the one outcome a sleep timer cannot have.

**Global hotkeys.** Explicitly out of scope per the issue, and rightly: a media player that grabs
keys system-wide without being asked is the kind of thing users uninstall.

**Tracking text-field focus to gate shortcuts.** Needs every text field in the app to register with
a shared tracker, and silently breaks the moment somebody adds one that does not. The two-handler
split gets the same result from Compose's own focus behaviour.
