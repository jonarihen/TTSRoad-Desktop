# ADR 0012: Admin fiction management uses the mobile contract and two gates

- Status: Accepted
- Date: 2026-08-14
- Issue: [#52](https://github.com/jonarihen/TTSRoad-Desktop/issues/52)
- Server dependency: [TTSRoad #122](https://github.com/jonarihen/TTSRoad/issues/122)

## Context

The desktop could browse and play the shared library but could not add, correct or remove its
fictions. The server now mirrors add-by-URL/id, edit and delete under `/api/mobile/fictions`, with a
`fiction_management` capability. These mutations are global: a delete cascades through chapters
and every account's progress. The session's login-time role can also become stale while it remains
valid.

EPUB import is different. It is still a multipart web route with no mobile payload/capability
contract. A native file picker makes desktop the right eventual client, but not a reason to bind a
released application to a route explicitly owned by the web UI.

## Decision

`FictionManagementStateHolder` is hoisted above navigation and owns one editor or destructive
confirmation at a time. Controls exist only when both conditions hold:

1. discovery advertises the literal `fiction_management: true` flag; and
2. a current authenticated `/api/mobile/me` response says `is_admin: true`.

The server remains authoritative on every write. Add sends a Royal Road URL or bare id and an
optional voice. Edit exposes title, author and voice but never the slug, because the slug names the
existing audio directory. Delete uses the shared safe-focus confirmation dialog and says plainly
that it removes every user's progress, not merely the caller's shelf entry.

Successful mutations patch visible metadata immediately and refresh the shelf/catalogue. Delete
also cancels the fiction's cache work, removes its in-memory rows and its account-scoped chapter
snapshot, then navigates away from the now-invalid detail destination. A mismatched or false delete
acknowledgement is not success.

EPUB upload remains unavailable until TTSRoad #122 supplies a separately advertised mobile
multipart contract. The desktop does not call `/api/fictions/upload-epub` as a fallback.

### EPUB upload waited for a mobile contract of its own

The web has had an EPUB import route for as long as it has had fictions, and it was reachable with
the bearer token this client already holds. It was still not used, for the reason the rest of this
ADR gives: the web surface carries no add-don't-rename guarantee, no contract test, and no capability
flag, so a client built on it would break silently on a server upgrade. `jonarihen/TTSRoad#122` asked
for the mirror; `TTSRoad#124` shipped it, and this is built on that.

Three details are worth recording:

- **`epub_upload` is a separate capability, never inferred from `fiction_management`.** The backend
  is explicit that a deployment can accept JSON fiction CRUD without accepting files, so the control
  is drawn from its own flag or not at all.
- **Validation happens before the upload, not only on the server.** Extension, emptiness and
  `limits.max_epub_bytes` are all 400s or 413s that would otherwise arrive *after* a forty-megabyte
  file had gone over the wire, which is the worst possible order to tell someone. The extension
  check is not redundant with the picker's filter either: AWT's `setFilenameFilter` is a hint that
  several Linux window managers ignore outright.
- **One dialog, two paths.** "Add a fiction" is one intention; making the user choose which *kind*
  of add they meant before showing them either form asks them to know the implementation. Choosing
  a file disables the URL field and says why, rather than hiding it out from under the cursor.

`rate` and `enabled` exist on the server contract and are deliberately left at their defaults: the
desktop has no per-fiction rate control, and uploading a fiction in order to disable it is not
something anyone asked for.

## Consequences

Older servers and non-admin accounts see no dead controls. A stale admin claim cannot expose the
surface after an administrator revokes the role. Adds and edits remain within the stable mobile API,
and destructive state does not survive locally after the server confirms deletion. Issue #52 stays
open only for the explicit EPUB dependency rather than silently claiming the full scope is done.
