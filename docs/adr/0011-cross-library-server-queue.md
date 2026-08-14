# 11. The cross-library server queue as a browsable surface

Date: 2026-08-13

## Status

Accepted. Options 1 and 2 below remain open; this decision does not foreclose either.

## Context

The backend has a server-side, cross-library queue — `GET /api/mobile/queue` and a single
`POST /api/mobile/queue` carrying an `action` of `add`, `fill`, `reorder`, `remove`, `clear` or
`advance`. The web client drives it. This client did not call it at all.

What this client had instead was `QueuePlaybackController`, which builds a queue in reading order
from one fiction's chapter list. Release 1.0.2 fixed the case where starting from the library queued
only a single chapter, so a queue is now always the whole fiction. That is correct as far as it
goes, but it is always *one* fiction, and it does not survive a restart. There was no way to line up
the next chapter of a different book.

Both things are reasonably called "the queue", and that collision is the actual problem.
`data/ChapterLists.kt` holds the rule that sorting is a *view* and the playback queue is always
built in reading order. A server queue is a different object: an explicit, ordered, cross-fiction
list that the user curates and that is shared with every other client on the account.

Issue #38 set out three ways to reconcile them:

1. **The server queue replaces the local one.** The cleanest model, and the one that makes the three
   clients agree completely.
2. **The server queue sits above the local one** — an "Up Next" that is consulted when the current
   fiction runs out.
3. **The server queue is a separate browsable surface** that can be played from, leaving
   end-of-chapter behaviour alone.

## Decision

### Option 3, and the reason is offline

The server queue is a surface of its own. Playing a row opens that row's fiction and hands the
chapter to the ordinary player, exactly as the chapter list does. What happens when a chapter ends
is unchanged.

Options 1 and 2 both put the network in the path of auto-advance. This client supports explicit
offline downloads and a streaming cache, and the local queue is the thing that keeps working when
the server is unreachable — a listener with the next six chapters on disk must not stop at a chapter
boundary because a request failed. Option 3 adds the feature without taking that on. It is the
smallest decision that still delivers a cross-library queue, and the other two remain reachable from
it.

### `advance` is deliberately not called

`advance` pops the head of the queue and decides what plays next, falling back to the oldest
unplayed chapter when the queue is empty and the account's `queue_when_empty` is `continue`. It is
the natural way to get that preference honoured for free, and it is exactly the coupling above.

The consequence is real and is stated in the UI rather than hidden: `when_empty` is read and
displayed, with the queue screen saying that it governs what *other clients* do. A user who finds
the two behaving differently should find that written down before they find it by surprise.

### The server owns the result of every mutation

Every action answers with the whole queue as the server now holds it, and the client republishes
that wholesale instead of predicting what its own request did. Ordering, de-duplication and the
500-item cap are server rules, and a second client may have changed the queue between two requests
from this one.

This is also why the success notice is computed from the difference between the list before and the
list after, not from what was asked for. "Added 3 chapters" beside a queue that grew by one is a lie
the user can see.

`fill` follows the same principle one step further: "Queue unplayed" sends the fiction id and lets
the backend choose the chapters, rather than sending the ids this client believes are unplayed.
Three clients computing that separately is three chances to disagree at the edges.

### Reorder sends the whole order; removal addresses rows

`reorder` takes the complete desired order, so `movedTo` computes the full list and posts it. An
out-of-range index answers the *same* list rather than a shorter one — a reorder built from a
truncated list would delete the missing rows server-side.

Removal addresses queue-row ids, never chapter ids. The same chapter can legitimately sit in the
queue twice, which makes "remove chapter 12" ambiguous and "remove row 4801" exact.

### Named for the server

`ServerQueueItem`, `ServerQueueStateHolder`, `ServerQueueScreen`. The `Server` prefix is load-bearing
next to `player.QueueItem`: one is a curated cross-fiction list, the other is derived reading order
for a single book, and a reader who confuses them will make exactly the wrong change.

### Capability-gated, and null is not empty

The surface exists only when the server advertises `queue`. Under that, the repository answers
**null** for a 404 and an empty list for an empty queue, and the two render completely differently —
"your queue is empty" offers actions that fill it, "this server has no shared queue" offers none.
That is the same distinction the device-sessions pane already makes, for the same reason.

## Consequences

- A cross-library queue exists, is shared with the web, and survives a restart.
- End-of-chapter behaviour, offline playback and the local queue are untouched.
- `queue_when_empty` is displayed and not honoured here. Honouring it means adopting `advance`,
  which means option 1 or 2 — a later decision, not an oversight.
- Playing a queue row does not remove it. Removal stays explicit, because a surface that silently
  consumed its own rows would be `advance` by another name.
- Android faces the identical question (`jonarihen/TTSRoad-App#65`). Nothing here prevents that
  client from choosing differently, but option 3 is the cheaper one to converge on later.
