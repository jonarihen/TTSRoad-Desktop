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

- Cross-device jump-back history through the server's `kind=auto` bookmarks. The desktop writes a
  breadcrumb every five minutes of playback and at its existing transition points, merges moments
  recorded by other clients into the Jump back shelf, and retains the bounded local copy offline.
- The account's cross-library queue, shared with the web client, as a browsable surface of its own.
  Chapter rows gain "Play next" and "Add to queue", the chapter list gains "Queue unplayed", and the
  queue screen can reorder, remove and clear. Shown only on a server that advertises the capability.

  Playing from the queue starts that chapter's fiction through the ordinary player, so what happens
  at the end of a chapter — including offline — is unchanged. The account's "when the queue is
  empty" preference is therefore displayed rather than acted on; see `docs/adr/0011`.

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

[Unreleased]: https://github.com/jonarihen/TTSRoad-Desktop/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.0.2
[1.0.1]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.0.1
