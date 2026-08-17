# Release quality gate

This matrix is the repeatable evidence for issue #10's final release gate. “Pass” means an automated
assertion exists and the full command below passed on 2026-08-14; it does not mean a visual claim was
made from source inspection alone.

## Recorded runs

| Gate | Result | Evidence |
| --- | --- | --- |
| clean compile, unit and Compose UI suite with warnings fatal | Pass | `xvfb-run -a ./gradlew --no-daemon clean check --warning-mode fail -Pttsroad.warningsAsErrors=true` on Rocky Linux 9.8, JDK 25 toolchain |
| packaged application image | Pass | `createDistributable`, release-distributable launch and packaged `--smoke-test` on the same checkout |
| Ubuntu 24.04/Mint 22 ABI package build and clean install/upgrade/uninstall | Pass | [master CI run 31777210303](https://github.com/jonarihen/TTSRoad-Desktop/actions/runs/31777210303); every PR reruns the same jobs |
| three-platform release dry run | Pass | [release dry run 31407730692](https://github.com/jonarihen/TTSRoad-Desktop/actions/runs/31407730692) |
| tagged 1.0.2 release gate | Pass | [release run 31426739896](https://github.com/jonarihen/TTSRoad-Desktop/actions/runs/31426739896) |
| tagged 1.1.0 release gate | Pass | [release run 32030943255](https://github.com/jonarihen/TTSRoad-Desktop/actions/runs/32030943255) — all three installers built, release `.deb` install/upgrade/uninstall in a clean Ubuntu 24.04 container, SBOM, checksums and provenance attestation |

The first two rows must be refreshed on the candidate commit. The CI/Release links establish that
the package and publishing machinery have completed end to end; the candidate's own PR and dry-run
checks remain authoritative for code changed afterwards.

## Accessibility

| Requirement | Status and executable evidence |
| --- | --- |
| meaningful semantics/content descriptions | Pass — `ChapterBrowsingUiTest`, `ServerQueueScreenUiTest`, `SettingsScreenUiTest`, `ReaderScreenUiTest` query actions and state through the semantics tree |
| logical keyboard focus/order and keyboard-only primary flows | Pass — `NavigationUiTest`, `SettingsScreenUiTest`, `ShortcutsTest`; dialogs focus Cancel and Escape closes an overlay before navigating |
| visible focus | Pass — shared cards, header/nav rows, chapter/queue rows and transport controls consume focused interaction state and draw an accent border; focusability is asserted in Compose tests |
| 100–200% scaling | Pass — ordinary UI tests cover 100%; `NavigationUiTest` drives library → Settings at `fontScale=2.0` and reaches content through scrolling |
| reader zoom | Pass — `ReaderScreenUiTest` changes the keyboard-reachable reading settings; `ReaderPreferencesTest` clamps 14–30 pt and 1.3–2.4 line height |
| sufficient contrast | Pass — `ThemeAccessibilityTest` calculates WCAG relative luminance and requires 4.5:1 for every normal app text token on every app surface and for every reader palette |
| no color-only state | Pass — played/playing/converting/failed/queued/excluded, focus, progress, errors and selection all have text or semantics in the screen tests; color is a redundant cue |

Compose semantics and the bundled `jdk.accessibility` module are automated. A native Orca/NVDA/
VoiceOver announcement pass is a release-candidate smoke check because those assistive technology
processes are outside the deterministic JVM test harness; a failure is release-blocking and should
be recorded here.

## Performance and soak

| Scenario | Status and executable evidence |
| --- | --- |
| 1,000 fictions | Pass — `NavigationUiTest` asserts lazy composition stays under 100 cards before and after scrolling to item 900 |
| 1,000-chapter fiction | Pass — `ChapterBrowsingUiTest` asserts under 100 rows before and after a deep scroll |
| 10,000-word reader | Pass — `ReaderScreenUiTest` builds 500 × 20-word paragraphs and asserts only visible paragraph nodes compose |
| four-hour playback | Pass — `QueuePlaybackControllerTest` advances the fake engine to exactly four hours, asserts the Long position is preserved and the 14,400-second progress save is emitted |
| repeated navigation | Pass — `AppNavigationTest` runs 10,000 library/fiction/player loops and returns to the one-entry root stack; Compose round trips assert retained filters/scroll |
| bounded state | Pass — `StreamingCacheTest`, `ReadAlongDiskCacheTest`, `AppLoggingTest` and `PlaybackHistoryTest` pin byte/file/backup/entry bounds |

The fake-engine soak deliberately advances media time rather than sleeping for four wall-clock
hours. GStreamer linkage, duration, seek, rate and failure paths run separately in
`GstPlaybackEngineIntegrationTest` on CI with real plugins and a fake audio sink.

## Compatibility and recovery

| Scenario | Status and executable evidence |
| --- | --- |
| fresh install, in-place upgrade, uninstall on supported ABI | Pass — `verify-install-lifecycle.sh` installs two Debian revisions in Ubuntu 24.04, launches both against a mock server, preserves settings/downloads, removes app files and leaves user data |
| 2FA login | Pass — `LoginTest`, `StateHolderTest` cover missing, wrong and accepted code states and exact wire bodies |
| older server without capabilities | Pass — `CapabilityDiscoveryTest` maps 404 to baseline; screen/repository tests hide or explain each additive endpoint without breaking library/playback |
| server 1.4.0 | Pass — complete `ServerFixtures` payloads cover login, capabilities, library, chapters and sessions while unknown additive fields are ignored |
| session expiry/revocation | Pass — `RepositoryTest`, `DevicesRepositoryTest`, `AudioSessionExpiryTest`, `ScreensUiTest` end the session, stop audio, clear protected state and explain the server reason |
| offline start | Pass — `LibraryCacheTest` restores library/chapters from identity-scoped disk while refresh fails; `DownloadCoordinatorTest` reopens the advertised namespace |
| corrupt/truncated download | Pass — `ChapterDownloaderTest` and `OfflineFirstMediaSourceTest` reject/de-index bad data and never advertise it as Offline |
| transient network/audio failure | Pass — repository, download manager and queue controller tests retain content, use bounded retries and expose Retry after exhaustion |
| keyring unavailable | Pass — `CredentialStoreTest`, `SessionStoreTest`, `DiagnosticsTest` prove session-only fallback, visible diagnostics and no file-secret fallback |

## Documentation gate

- Architecture, ownership, API/capability rules, fixtures, contributor flow, release runbook and
  dependency maintenance: [MAINTAINER-GUIDE.md](MAINTAINER-GUIDE.md).
- Storage/security model and incident SLA: [SECURITY.md](SECURITY.md), ADR 0002 and ADR 0007.
- Playback design: ADR 0002-playback-engine and ADR 0006.
- Packaging/release/update design: ADR 0009 and ADR 0010.
- Bootstrap, supported/buildable platform distinction and user troubleshooting: repository README.

Before closing a candidate, update the recorded command results, inspect failed/skipped checks and
record any exception with an owner. “Not run” is not a pass; a release-blocking failure keeps the
candidate and its issue open.
