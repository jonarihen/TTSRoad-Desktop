# ADR 0003 — Device-session management and a two-pane Settings screen

- **Status:** Accepted
- **Date:** 2026-08-06
- **Context issue:** [#8 — Add device-session management and complete Settings](https://github.com/jonarihen/TTSRoad-Desktop/issues/8) (part of #1, depends on #6)

## Context

Settings was one scrolling column with three read-only rows and a Sign-out button, and the client
had no way to see or end the other sessions on the account. The server has had
`GET /api/mobile/me`, `GET /api/mobile/devices`, `DELETE /api/mobile/devices/{token_id}` and
`POST /api/mobile/devices/revoke-others` since 1.4.0, and Phase 1 already discovers the
`device_management` capability — nothing consumed either.

Three properties of the server make this more than a CRUD screen:

1. The device API is **additive**: `api_version` did not change when it shipped, so a `404` is the
   only signal that a backend predates it.
2. `DELETE /api/mobile/devices/{token_id}` answers **404 for three different things** — no such
   endpoint, no such token, and a token that is already revoked (`app/routers/mobile.py`).
3. Timestamps come from **two different serializers**: `app/routers/mobile.py::_utc_iso` keeps
   microseconds, `app/services/mobile_auth.py::_utc_iso` truncates to seconds, and other paths emit
   naive values. The same client reads all of them.

## Decision

### 1. Two gates on the device UI, not one

`SettingsStateHolder.loadDevices()` skips the request only when discovery **succeeded** and said
`device_management: false`. A baseline capability set means discovery never got an answer (404,
offline, a proxy) — refusing to try on that basis would hide a working feature behind an unrelated
failure. The second gate is the endpoint's own 404, which `RetrofitTtsRoadRepository.devices()`
turns into `null`.

`null` and `emptyList()` are therefore *load-bearing and different*: "nothing else is signed in" is
a correct, normal answer, while "this server cannot answer" has to render the concise unsupported
card. That distinction is asserted in both `DevicesRepositoryTest` and `SettingsStateHolderTest`.

**A 404 never signs anyone out.** `ifEndpointExists` catches exactly `404`; every other status,
including `401`, keeps its Phase 1 meaning and still ends the session.

### 2. The 404-on-DELETE ambiguity is resolved by context, not by guessing

The repository reports the 404 as `false` and does not interpret it. The state holder does: a
client that just rendered a successfully loaded list is demonstrably talking to a server that has
the endpoint, so a 404 there means *that session is already gone* and the list is simply re-read.
Only a 404 with nothing loaded is treated as an old server.

### 3. Every revoke re-reads the list

No local patching. After "revoke others" the client cannot know how many rows the server actually
took, and after a single revoke a partial failure is possible; the server is the authority on what
survived. The cost is one extra `GET` per action, which is the right trade for a screen whose whole
purpose is showing the truth about live sessions.

A failure message is re-applied **after** that reload. A successful reload legitimately clears a
stale load error, and without re-applying it the screen would look exactly as if the revoke had
worked.

### 4. The current session is unrevokable from its row, twice over

`DeviceSession.isCurrentFor(session)` is `is_current || session.deviceId == id` — the server's flag
plus the `device_id` kept from the login response, because the flag is only set when the request
itself carried a bearer token. The row renders with no revoke control, *and*
`SettingsStateHolder.askRevoke` refuses for that device, so a future UI change cannot reintroduce
the accident. Ending this session stays Sign out, which has its own confirmation.

### 5. Timestamps are parsed permissively and rendered locally

`parseServerInstant` tries `Instant.parse`, then `OffsetDateTime`, then `LocalDateTime` read as UTC,
and returns `null` rather than throwing — a device row with one unreadable date is still worth
showing. Rendering goes through `formatServerTimestamp(zone = systemDefault())`, and expiry is
deliberately coarse (`expires in 42 days`): tokens last 90 days and renew on every authenticated
request, so the exact hour is noise.

### 6. Settings state is hoisted above navigation

`App` owns the `SettingsStateHolder`. Navigation is a `when (screen)`, which disposes the screen it
leaves, so a holder created inside `SettingsScreen` would drop the selected pane and the loaded
device list every time the user glanced at the library. It is reset by `sessionEnded()` when the
session goes, because device rows name other machines on *that* account.

### 7. Placeholder panes describe the gap instead of faking it

> Phase 7 supersedes the Offline half of this decision; see ADR 0007. The paragraph below records
> what Phase 2 intentionally shipped, not the current screen.

Playback and Offline render one honest paragraph each and no controls. A switch that looks live and
changes nothing is worse than an empty pane, because the user cannot tell it did not work. The
Offline pane also states the thing the issue asks the app to guarantee: nothing is stored for
offline use, so signing out cannot delete anything the user asked to keep.

The About pane says "Update check: not available" rather than "you're up to date" — there is no
updater in this build, and claiming otherwise would be a claim nothing checked.

### 8. Accessibility is in the widgets, not in a pass afterwards

- Pane entries are `Modifier.selectable(role = Role.Tab)`: Tab-reachable, Enter/Space-activatable,
  and announced with their selected state.
- Each device row carries a single `contentDescription` sentence, so a screen reader gets
  "Pixel 9, active, last used …, last IP …" instead of eight unlabelled fragments in layout order.
  The revoke button sits outside that merged node with its own "Sign out <device>" description,
  which is also what the UI tests click.
- The confirmation dialog sets `paneTitle`, handles `Escape` explicitly via `onPreviewKeyEvent`
  rather than relying on the platform back-press mapping, and **focuses CANCEL**, so the key a user
  hits reflexively is the safe one.

## Consequences

- Four new endpoints, one new model file, one new state holder, one new screen. `Repository`'s
  interface grew by four methods; `FakeRepository` implements them, so every existing test still
  compiles unchanged.
- Sign-out now requires a confirmation click. That is one more interaction on a common action; it
  was accepted because the issue asks for confirmation on irreversible actions and because the same
  dialog component then covers all three.
- `GET /api/mobile/me` is called once when the Account pane opens. It is a cheap way to notice an
  `is_admin` change made from the web console while the desktop session was open; a failure is
  silent and the stored session still stands.
- The device list is *not* cached across sign-ins and is not persisted. It is only ever looked at
  deliberately, and a stale answer is worse than a short spinner.

## What is not covered

- **No admin device view.** `GET /api/admin/devices` exists and adds a `username` per row; this
  phase is deliberately account-scoped.
- **`revoke-others` over a cookie session revokes everything.** The server keeps
  `request.state.mobile_token_id`, which is only set for bearer auth. The desktop client is always
  bearer, so this cannot happen here, but it is worth knowing before the code is reused.
- **Diagnostics are read-only.** "Copy diagnostics" puts a `redactSecrets`-scrubbed block on the
  AWT clipboard; there is no log file to attach because the app still has no file logging.
