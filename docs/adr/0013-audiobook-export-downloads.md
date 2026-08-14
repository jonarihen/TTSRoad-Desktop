# ADR 0013: Read-only audiobook export downloads

- **Status:** Accepted
- **Date:** 2026-08-14
- **Context issue:** [#35 — Three-client parity](https://github.com/jonarihen/TTSRoad-Desktop/issues/35)

## Context

The server can assemble completed fiction chapters into M4B volumes and advertises the
`audiobook_export` capability. Its mobile contract deliberately exposes a read-only admin list:
`GET /api/mobile/exports` returns finished exports and authenticated download URLs, while creation
and deletion remain in the web admin.

A desktop is the natural client for saving these large files, but an export is not another chapter
download. It is meant for a third-party audiobook player, has no useful per-chapter resume contract,
and belongs wherever the user chooses rather than inside TTSRoad's account-scoped offline store.

## Decision

### Settings is a read-only export shelf

An **Audiobooks** pane appears only when capability discovery reports `audiobook_export`. It verifies
the current `/api/mobile/me` response is an administrator before requesting the list. A 404 remains
an explicit unsupported state, and a server without ffmpeg can still offer already-finished files.

The desktop never creates, deletes or plays an export. It says that management stays in the web
admin and that normal per-chapter playback remains the in-app path. This keeps server policy and
long-running export jobs out of a client API that does not define them.

### A saved M4B is user data, not offline cache

The native save dialog chooses the final path and offers a sanitized server filename. Server paths,
control characters and directory traversal never become a local path. The completed file is not
indexed as an offline fiction, counted in Settings storage, evicted, or removed on sign-out.

Downloads use the shared OkHttp client, so bearer authentication and the same-origin interceptor
apply exactly as they do to chapter audio. A 401 ends the local session. A 404 or 410 explains that
the server export is gone rather than retrying forever.

### Large transfers resume and promote atomically

A partial file sits beside the selected destination as
`.filename.ttsroad-<export-id>.part`. Re-selecting the same destination sends a range request and
resumes it. If the server ignores the range and answers 200, the partial is truncated before the
new body is written. Pause or application shutdown retains the partial; a corrupt completed body
is deleted.

Before transfer, the client checks available space with a 64 MiB margin. It reports byte progress,
fsyncs the partial, rejects a short response, verifies the ISO base-media `ftyp` header, and then
atomically replaces the destination where the filesystem supports it. The final file and partial
are restricted to the owner where the platform supports POSIX permissions.

The API does not publish a checksum, so exact size plus a structural M4B check is the strongest
client-side validation available. This is not presented as cryptographic integrity.

## Consequences

- Administrators can save finished whole-fiction M4Bs to any local or mounted destination without
  duplicating them into the managed chapter-download store.
- Interrupted multi-gigabyte transfers can continue without starting over.
- The desktop cannot create or delete exports; those operations require a future explicit mobile
  contract if they are ever wanted.
- Export files intentionally remain after sign-out because the user placed them outside app-owned
  storage.

## Alternatives considered

**Store exports under the offline-download root.** Rejected because that root is account-scoped,
managed and removable from Settings. A user-selected M4B is an exported document and must not be
silently swept up by app cleanup.

**Play the M4B in TTSRoad.** Rejected because `playable_in_app` is false and the per-chapter API has
the queue, progress and read-along semantics the player needs.

**Expose create/delete by copying web routes.** Rejected because those are not part of the mobile
contract and would make the client depend on HTML/admin implementation details.
