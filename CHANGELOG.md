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

[Unreleased]: https://github.com/jonarihen/TTSRoad-Desktop/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/jonarihen/TTSRoad-Desktop/releases/tag/v1.0.1
