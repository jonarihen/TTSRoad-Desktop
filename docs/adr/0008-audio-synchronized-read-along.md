# ADR 0008: Audio-synchronized read-along

- Status: Accepted
- Date: 2026-08-09
- Issue: #5 (Phase 8)

## Context

The server exposes one bearer-protected read-along document per chapter. Its narration text is
indexed by compact paragraph `[start,end]` and cue `[start,end,time]` arrays, supports ETag
revalidation, and returns 404 normally when no narration text exists. Timing data may be absent for
older conversions or malformed/stale after an audio replacement. The reader must remain responsive
for long chapters, work offline for previously opened content, and never show one account's text in
another session.

Playback already publishes the engine's media position. That position, rather than elapsed wall
time, is the only clock that remains correct under pause, seek, speed changes, and skip-silence.

## Decision

### Validate once, look up with binary search

`ReadAlongDocument.from` validates and clamps paragraph spans, rejects malformed/non-monotonic cue
sets, derives paragraph-bounded sentence spans once, and keeps cue order safe for binary lookup.
Active cue, sentence, paragraph, find range, and clicked-word seek are pure operations over the
validated document. A duration mismatch greater than five seconds or two percent disables timing.
The reader states why instead of highlighting stale text confidently.

The reader consumes `PlayerUiState.positionMs` directly. Playback rate is deliberately absent from
the algorithm: 40 seconds of media remains cue time 40 at both 1× and 2×.

### Cache raw responses under the offline identity

`ReadAlongCache` resolves memory, then `ReadAlongDiskCache`, then an authenticated conditional
request. The raw response and ETag are stored so parsing improvements apply to old cache entries.
A 304 reuses the same parsed memory object; network/5xx falls back to cached content; 404 removes a
stale cache entry and becomes the normal no-text UI. A 401 ends the session and is never hidden by
fallback content.

Disk content lives below `cache/readalong/<stable-server>/<account>/chapter-<id>.json`, using the
same `StorageIdentity` as offline audio. Directories/files are owner-only, generated names defend
against traversal, and symlink roots are refused. The cache is bounded independently from audio to
64 MiB and 80 chapters using last access as its LRU signal. Signing out clears in-memory documents
and closes the live identity while leaving rebuildable disk bytes for the same account's return.

### One lazy item per paragraph

`ReaderScreen` renders a paragraph as one selectable `Text`; words are annotated spans, not
composables. This bounds live composition for a 10,000-word chapter while preserving sentence-band,
word, and find highlighting. Paragraph and heading semantics remain visible to assistive technology.

Auto-follow places the active paragraph approximately one-third down. Wheel, drag, scrollbar,
Page Up/Down, Home/End, and find navigation permanently surrender follow until **Back to current**.
Opening non-playing text never follows unrelated audio. Once the reader has followed its own
playing chapter, queue auto-advance replaces the document and restores follow. The next queue entry
is prefetched at 80 percent.

### Local-first, account-synchronized appearance

Font size, line height, theme, and highlight mode are immediately persisted in `reader.json`, then
synchronized through only these server keys:

- `reader_font_size` (14–30)
- `reader_line_height` (1.3–2.4)
- `reader_theme` (`dark`, `sepia`, `light`)
- `reader_highlight` (`sentence`, `word`, `off`)

GET merges supported values over the local fallback. PATCH contains exactly those four keys, so it
cannot overwrite unrelated preferences in the account JSON blob. A 404 or offline server leaves the
last-known local values active.

## Rejected alternatives

- Advancing highlights from a frame/wall clock: drifts after speed, pause, seek, and skip-silence.
- One composable per cue/word: makes long chapters retain thousands of live nodes.
- Hiding chapters without `has_timings`: discards useful narration text from older conversions.
- Serving cached text after 401: treats retained login hints as authority and exposes protected
  account content after revocation.
- A global chapter-id cache: collides across servers/accounts with overlapping database ids.
- Persisting only derived spans: freezes old parsing bugs into cache entries across upgrades.

## Consequences

Read-along remains useful without audio or timings, reopens offline, and tracks playback without
rate-specific logic. The cache adds another protected identity-scoped tree and the reader preference
file adds non-secret local state. Server preference synchronization is best effort by design; local
reading controls never wait on a network round trip.
