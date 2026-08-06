# ADR 0004 — A back stack, a repository-backed cache, and adaptive layout

- **Status:** Accepted
- **Date:** 2026-08-06
- **Context issue:** [#12 — Make library and navigation stateful, adaptive, and desktop-native](https://github.com/jonarihen/TTSRoad-Desktop/issues/12) (part of #1, depends on #2)

## Context

`App.kt` held a single `var screen: Screen`. There was no history, so Back was a per-screen lambda
that hard-coded where it went (`onBack = { screen = Screen.Library }`), and the player kept a second
variable — `playerReturn` — purely to remember what it had covered up.

Everything else followed from that. Because `when (screen)` disposes the screen it leaves, each
screen's state holder was created *inside* the screen: leaving the library and coming back
destroyed the holder, refetched the library, and replaced the whole surface with a spinner. Search
text and scroll offsets went with it. The fictions grid was a `BoxWithConstraints` +
`chunked(columns)` inside a `verticalScroll` column, so every card in the library was composed —
and every cover requested — before the first frame. The window opened at a fixed 1140×780 every
time, at whatever position the window manager chose.

## Decision

### 1. An explicit back stack over destinations with stable keys

`nav/AppNavigation.kt` declares `Destination` (Library, Fiction, Player, Reader, Settings, Devices)
and `Destination.key`, and implements the stack as **pure functions over `List<Destination>`**
(`navigateTo`, `popDestination`, `replaceTop`, `keysDroppedBy`) with a thin Compose holder
(`NavigationState`) on top. The rules are therefore asserted in a plain unit test rather than
inferred from a UI test.

Two rules are load-bearing:

- **`navigateTo` pops back to an already-open destination** instead of pushing a second copy.
  Library → Fiction → Player → Fiction → Player is an ordinary listening session; appending each
  step would grow a stack whose Back button walks a history the user never made.
- **The key ignores the payload.** `Fiction(fictionSummaryFromLibrary)` and
  `Fiction(fresherSummaryFromChaptersEndpoint)` are the same screen. Without that, a refreshed
  summary would read as a new destination and drop the scroll position.

`Reader` is declared and keyed but has no UI in this build. It is here so the retention rules and,
in particular, the `Reader:7` / `Fiction:7` key separation are settled before the read-along phase,
and so nothing has to change shape later. It renders an honest placeholder; nothing navigates to it.

`Devices` is a real destination rather than a pane flag, so a future "manage sessions" link
anywhere in the app lands somewhere addressable. Selecting a pane inside Settings **replaces** the
top entry (`replaceTop`) rather than pushing, so Back from the device pane returns to the library,
not to the pane the user just left.

### 2. Retained per-destination state, released deliberately

`rememberSaveableStateHolder()` + `SaveableStateProvider(destination.key)` is what makes
`rememberSaveable` search text and `rememberLazyGridState`/`rememberLazyListState` scroll offsets
survive a round trip. `NavigationState` reports every key that leaves the stack, and `App` passes
that straight to `removeState`. Without the release step, opening a second fiction would restore
the first one's scroll offset into it, and every screen ever visited would keep its state alive for
the life of the process.

### 3. Data moved above the screens: `LibraryCache` in `AppContainer`

`data/LibraryCache.kt` owns the library response and one chapter list per fiction, and lives in the
container — so its lifetime is the signed-in session, not the composition. Screens call
`ensureLibrary()` / `ensureChapters(id)` on appearance, which is a no-op when there is content or a
load in flight. That is the request coalescing, and it is why Library → Fiction → Back costs zero
requests.

`data/Cached.kt` replaces `Load.Loading | Ok | Err` for these two loads because the old type could
not say the thing that matters: *content on screen that the newest refresh failed to update*.
`Cached` carries `value`, `error`, `isRefreshing` and `lastSuccessMillis` independently, so:

- an error **never** clears the value;
- the only full-screen error is `value == null && error != null`, and it always carries a Retry;
- the UI can say how old what it is showing actually is, instead of presenting stale content as
  current.

`refreshLibrary`/`refreshChapters` cancel the load they supersede, and a cancelled load checks its
own job before publishing — so two fast refreshes cannot end with the older answer on screen.

Marking a chapter played now **patches the cached list in place** (`withPlayed`, identity-preserving
for untouched rows) instead of re-fetching the whole fiction. The old behaviour re-downloaded a
500-chapter list to move one checkmark, which flickered and threw away the scroll position.

`Load<T>` is deleted. Once the library and the chapter list moved to `Cached`, its only remaining
users were `PageScroll` and `CenterError`, which the lazy rewrite also removed; the login screen
never used it. Keeping a second, weaker async-state type around for nothing would have invited new
code to reach for the one that cannot express a failed refresh.

### 4. Lazy everything, with keys that cannot collide

The library is one `LazyVerticalGrid` — hero, shelves and section headers are full-width spans,
fiction cards are cells — and the fiction detail is one `LazyColumn` with the header as its first
item. Neither nests an eager list inside a scroll container any more.

Lazy keys come from `chapterKeys` / `fictionKeys` rather than from ids directly. `resolvedChapterId`
alone is not safe: the library's `continue_listening` and `recent_chapters` shelves are two
different server payloads whose ids can repeat, and a payload that fails to carry an id decodes to
`0` for *every* row. A duplicate key is a hard crash in a lazy list, so a repeat gets an occurrence
suffix while the first occurrence keeps the plain, stable key.

### 5. Window preferences: pixels, and clamped to the displays that exist now

`data/WindowPreferences.kt` persists size, position, maximised state and sidebar width to
`window.json` beside `session.json`, written atomically and owner-only through the existing
`SecureFiles`. The type has **no field** for anything transient or secret, which is stronger than
remembering not to write one.

`clampToDisplays` runs before the window is created. A laptop undocked since the last run leaves a
stored position on a monitor that no longer exists; a window that "did not open" is
indistinguishable from a crash. A position with almost nothing visible is discarded entirely rather
than dragged to a corner — the window system's own placement is a better answer.

Placement is applied through **AWT** (`window.setSize` / `setLocation` / `extendedState`) rather
than through `WindowState`. `WindowState` speaks `Dp` while the saved rectangle and the screen
bounds are device pixels; round-tripping through it would grow or shrink the window by the
display's scale factor on every restart. The cost is a brief default-sized frame before the stored
bounds are applied.

### 6. Breakpoints declared once

`ui/WindowLayout.kt` declares the supported minimum (720×560, enforced via `window.minimumSize`)
and three width classes: Compact (< 900 dp), Medium (< 1280 dp), Expanded. Settings' ad-hoc
`TwoPaneMinWidth = 780.dp` is gone; Settings, the player and the header all read the same
definition of "narrow". At Compact the player's up-next panel becomes a stacked section, the header
drops the server name (a label, whose room the navigation entries need), and the now-playing bar
drops its elapsed/total readout — the transport controls themselves never shrink.

The header is deliberately *not* horizontally scrollable. That was tried and reverted: the header's
right-aligned navigation depends on a `weight(1f)` spacer, and a weighted child inside a horizontal
scroll container has no bounded width to take a fraction of. Dropping the least important label is
the fallback that keeps the layout valid.

### 7. Keyboard: a pure shortcut table, and focus that is visible

`shortcutFor(key, type, alt, ctrl, meta)` is a pure function, so the whole table (Alt+Left, F5,
Ctrl/Cmd+R, Escape, the browser Back key, and key-up being ignored) is unit-tested — a Compose
`KeyEvent` cannot be constructed without a real toolkit event. `escapeAction(hasOpenOverlay,
canGoBack)` encodes Escape's precedence as a value, so "a dialog closes before anything navigates"
is an assertion rather than a hope about how a platform dialog routes keys.

The handler is `Modifier.onPreviewKeyEvent` on a focusable root, and root focus is **re-taken on
every destination change**. That is not cosmetic: navigating disposes whatever held focus, and
without it Alt+Left works on the first screen and silently stops working after the first click into
a second one. It also puts Tab traversal back at the top of the screen the user just arrived on.

Every custom control in this app passes `indication = null` (the AARIS look has no ripple), which
meant keyboard focus had **no visual at all**. Focus is now drawn explicitly — accent border or the
existing hover treatment — on the header actions, the nav tabs, cards, chapter rows, row actions
and the transport buttons.

## Consequences

- Returning to a screen no longer re-fetches, which also means it no longer *refreshes*. Refresh is
  now an explicit action (button, F5, Ctrl/Cmd+R) rather than a side effect of navigation. Nothing
  polls; a library left open all day shows what it last fetched, and says so once a refresh fails.
- `LibraryStateHolder` and `FictionDetailStateHolder` are deleted; their tests moved to
  `LibraryCacheTest` with stronger assertions (coalescing, cancellation, non-destructive failure,
  identity-preserving patching).
- Marking played no longer re-reads the server's authoritative list. The local patch mirrors what
  `app/routers/mobile.py` does (position = duration when played, 0 when un-marked) and only touches
  the ids the server said it changed; the next real refresh still wins.
- `Cached<T>` is now the only async-state type. `Load<T>`, `PageScroll` and `CenterError` are gone
  from `Components.kt` along with the eager scroll containers that were their only callers.
- Compose UI tests drive `LibraryCache` on `Dispatchers.Main.immediate`. Plain main dispatch is an
  `invokeLater` that `waitForIdle` does not track, which made assertions pass or fail with machine
  load; this was an observed flake, not a theoretical one.

## Not done, and why

- **No pane/sidebar drag handle.** `sidebarWidth` is persisted and clamped, but Settings' nav pane
  is still a fixed 220 dp — there is nothing to drag yet. The field is written so the placement file
  does not need a migration when there is.
- **No visual regression testing.** "Resize from the minimum to ultrawide clips nothing" is
  supported by the breakpoint tests, the enforced minimum window size and the Compact fallbacks —
  not by screenshots, and not by a manual drag through every width. It is the weakest claim here.
- **No screen-reader verification.** Focus, roles and the merged banner announcement are asserted at
  the Compose semantics layer only; no NVDA/VoiceOver run.
