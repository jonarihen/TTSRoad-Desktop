# ADR 0005 — Chapter browsing: one view model, one request, one rollback

- **Status:** Accepted
- **Date:** 2026-08-06
- **Context issue:** [#11 — Complete large-fiction chapter browsing and bulk controls](https://github.com/jonarihen/TTSRoad-Desktop/issues/11) (part of #1, depends on #12)

## Context

Phase 3 already moved the chapter list into a `LazyColumn` and gave it an All/Unplayed/Ready
filter, so the worst of the original problem — `chapters.forEach` inside a `verticalScroll` column,
which composed every row of a 500-chapter serial before the first frame — was gone.

What was left was everything that makes a *long* serial usable:

- No ordering control. The list was always the server's order.
- The filter was `rememberSaveable` inside the screen, so it lived exactly as long as the
  destination stayed on the back stack. Popping a fiction and reopening it reset it.
- Nothing connected the list to the player. A listener 300 chapters into a serial reopened it at
  chapter 1 and had to scroll to find where they were.
- Marking was one chapter at a time, and the patch was applied only *after* the server answered.
- Row affordances were composed only while the row was hovered, which is unreachable by keyboard
  (see decision 5).
- The models dropped `chapter_number`, `player_index`, `audio_filesize`, `has_timings`,
  `remaining_seconds` and `last_listened_at`, so read-along could not be gated, ordering had only
  one signal, and a chapter's status could not be described beyond "pending".

## Decision

### 1. Filter and sort are *data*, held in `LibraryCache`, not screen state

`ChapterListOptions(filter, sort)` lives in `LibraryCache` keyed by fiction id, alongside the
chapter data itself. The screen reads a `StateFlow` and writes through `setChapterOptions`.

The alternative — keep using `rememberSaveable` under the destination's saveable key — was already
in place and is what this replaces. Its lifetime is the *back-stack entry*: Phase 3 deliberately
releases a destination's retained state when it leaves the stack, because otherwise every fiction
ever opened keeps its scroll offset alive forever. That is the right rule for scroll offsets and
the wrong one for a deliberate choice like "show me this serial newest-first". Putting the options
in the cache gives them the cache's lifetime — the signed-in session — and `clear()` drops them
with everything else when the session ends.

`ChapterSort` is named for what the reader sees (`Oldest`/`Newest`) rather than for a boolean
direction, and both filter and sort are rendered as `selectable` tabs with `Role.Tab` rather than
as a cycling toggle button. A toggle that relabels itself announces the state it will move *to*,
which is the wrong half of the information for a screen reader.

### 2. Sorting is a view; the queue is always in reading order

`sortedByDisplayNumber` orders by `display_number`, falling back to `chapter_number` (the flat
library-shelf payload carries only the latter), and keeps unnumbered rows at the end in **both**
directions — excluded chapters have a null `display_number`, and floating them to the top of a
newest-first list would put the least identifiable rows exactly where the newest chapter belongs.

`playbackOrder()` is the canonical ascending order and is what `Mp3PlaybackController.playQueue`
now builds its queue from, regardless of the order the caller passed. Sorting the screen
newest-first therefore does not make the serial play backwards. `chaptersBefore` is defined on
`playbackOrder()` for the same reason: "mark all previous as played" has to mean the same thing
whether the reader is looking at the list forwards, backwards, or filtered to three rows.

### 3. Marking is optimistic, sends one request, and rolls back by restoring — not by inverting

`LibraryCache.setPlayed` patches the rows first, sends **one** `POST /api/mobile/playback/mark`
with the whole id set, and on failure restores the exact `PlaybackInfo` each row had.

Restore rather than invert is the load-bearing part. The obvious rollback — "un-mark what we
marked" — destroys real data: a chapter the reader was 6:52 into, marked played by mistake on a
flaky connection, would come back at 0:00 because that is what un-marking does on the server. The
snapshot/restore pair (`playbackSnapshot` / `withRestoredPlayback`) is asserted for exactly that
case.

The same mechanism handles a *partial* success. `playback/mark` echoes only the ids it actually
touched — unknown, excluded, and (when un-marking) never-started chapters are silently dropped — so
anything missing from the echo is restored on its own while the confirmed ids stay marked.

`markableIds` drops rows already in the target state before the request is built. That keeps a bulk
mark from rewriting `position_seconds` on chapters that were already finished, and an empty result
is how the UI knows the bulk button has nothing to do and should be disabled.

### 4. Library counters are recomputed, never guessed

Marking a chapter from the detail screen also patches the same chapter where it appears on the
library's shelves, so a row cannot go on offering a resume position it no longer has.

The per-fiction counters (`played_count`, `remaining_count`) are **recomputed from this client's own
chapter list** rather than adjusted by a delta. If the chapters are not cached there is nothing to
count from and the counters are left alone, because a plausible wrong number that survives until
the next library refresh is worse than a stale right one. Note that `done_chapters` /
`error_chapters` on `FictionSummary` are *conversion* counters, not listening ones — nothing here
touches them.

### 5. Row actions are always composed and merely dim

Phase 3 revealed the row's actions when `hovered || focused` on the row's own interaction source.
That reads correctly with a mouse and cannot work with a keyboard: taking focus into a revealed
button removes focus from the row, which un-reveals the button that was trying to take it.

Every row action is now composed unconditionally and brightens on hover or focus. The cost is
bounded by the lazy list — only visible rows exist — and the benefit is that "Play chapter",
"Mark played", "Mark all previous chapters as played" and "Read along" are in the semantics tree,
tab-reachable, and assertable by content description without simulating a pointer.

`contentDescription` is set on the clickable node rather than on the `Icon`, because that is the
node a screen reader lands on.

### 6. Current-chapter resolution is a pure function over the published player state

`PlayerUiState` gained `fictionId`, and `QueueItem` gained `displayNumber`.

`fictionId` exists because a chapter id alone cannot answer "is the row I am drawing the one that
is playing?" — two serials can be open in one session, and the one that is not playing must show no
highlight rather than a highlight on whatever row shares an index.
`PlayerUiState.playingChapterIdIn(fictionId)` is total: an empty queue, an out-of-range index, an
id-less payload, and a queue from another fiction all answer null.

`QueueItem.displayNumber` exists because the queue holds only *playable* chapters, so queue
position and chapter number diverge the moment one chapter in the middle is still converting. The
up-next panel labels rows with the chapter's own number.

### 7. Scroll arithmetic is made trivial by one header item

Everything above the chapter rows — back link, stale banner, fiction header, inline error, count
and controls — is emitted as a **single** lazy item. Row `i` is therefore always at lazy index
`i + 1`, whatever the header happens to be showing this frame, which is what makes "scroll to the
playing chapter" and "is the playing chapter on screen" arithmetic instead of guesswork.

Auto-scroll fires **only** for the currently playing chapter, and only once per fiction. It
deliberately does not scroll to whatever Resume would start: opening a serial you are not listening
to should show you its beginning, and a screen that silently jumps on every visit is disorienting.
Returning from the player lands where the reader left, which the destination's retained scroll
offset already does.

"Jump to current" is driven by `derivedStateOf` over `layoutInfo.visibleItemsInfo`, so scrolling
recomputes one boolean rather than the screen.

### 8. Status is described in the client's vocabulary, not the server's

`ChapterAvailability` (Ready / Excluded / Failed / Converting / Queued) and `statusLabel()` replace
showing `status` verbatim. `error_message` is modelled — it is how "failed" is distinguished from
"queued" when `status` is absent — and deliberately never rendered: it carries server paths and
stack fragments that a listener cannot act on. A test asserts the label for a chapter whose
`error_message` contains a server path does not contain that path.

`hasAudio` (the presence of an `audio` object) is the single source of truth for "can this be
played". Neither the `playable` flag nor `status == "done"` is used: only an `audio` object proves
there is a URL to fetch. Rows without one are not clickable, have no play affordance, and are
filtered out of any queue.

### 9. The up-next panel gets a search box past eight items

Below eight, a serial is faster to scan than to type into. Above it, hunting for "Chapter 217" in a
lazy list is the exact problem this phase exists to solve. Filtering narrows what is listed and
never renumbers or reorders anything: rows carry their real queue index, so clicking a filtered row
jumps to its real position.

## Consequences

**Good.**

- A 1,000-chapter fiction composes ~20 rows, and still ~20 after scrolling to chapter 900 (asserted).
- Marking anything is one request and one frame; a 40-chapter bulk mark is one request, not forty.
- A failed mark leaves the list on screen, the row exactly as it was, and an inline message.
- Every mouse-reachable row action is keyboard-reachable and named.
- Sorting and filtering provably do not touch the queue or the server's data.

**Costs and things deliberately not done.**

- **Read-along still had no reader in this phase (superseded by ADR 0008).** The row action was gated on `capabilities.readalong` *and*
  per-chapter `has_timings`, so it only appears where the server could actually serve it — but it
  navigated to the `Reader` destination, which was still the honest placeholder from Phase 3. This
  is the one place in this phase where a control leads somewhere unfinished; it is wired now so
  that the reader phase is a screen swap rather than a change to the chapter list.
- **The download slot draws nothing (superseded by ADR 0007).** `ChapterDownloadState` and
  `downloadStateFor` were a real
  seam with a default of `Unavailable`, which renders no pixels. Shipping a greyed-out download
  button that cannot be pressed would have been worse than shipping none.
- **`player_index` is modelled but not used for ordering.** The client orders by
  `display_number`/`chapter_number` and cross-checks nothing. Using the server's own playable index
  would be marginally more faithful; it is null for every non-playable chapter, so it cannot order
  the list the reader sees, only the queue — and the queue already agrees with it.
- **Bulk marking is not gated on `capabilities.batch_progress`,** and must not be: a 1.4.0 server
  reports that flag false while accepting a multi-id `playback/mark`, because the flag tracks a
  named FastAPI route rather than the ability (see ADR 0002).
- **The "n of m" count is the client's view of the list**, not the server's `total`. With no
  pagination on this endpoint they agree today; if `max_chapters_per_page` ever starts being
  enforced, this label becomes a lie and the phase that adds paging owns fixing it.
- **Marking still refetches nothing, by design.** That means the fiction's `done_chapters` header
  and the library's shelves can drift from the server if another device marks chapters at the same
  time, until the next explicit refresh. That is the same trade Phase 3 made for the cache.
