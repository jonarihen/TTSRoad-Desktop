# ADR 0007: Offline downloads and a server-safe streaming cache

- Status: Accepted
- Date: 2026-08-09
- Issue: #7 (Phase 7)

## Context

The production media-source seam can stream authenticated MP3 bytes and seek with range requests,
but it originally retained nothing. A server outage therefore made every chapter unavailable, and
the in-memory library cache disappeared at process exit. Treating every local byte as one kind of
"cache" would be unsafe: an OS cleaner may delete cache data, while a chapter the user explicitly
asked to download is durable user data and must never be evicted automatically.

Raw URLs are addresses, not identities. The same private server may be reached over a LAN address,
a public hostname, or a tailnet, while two path-based deployments on one host may contain different
chapter 101s. Account identity matters too: fiction/chapter ids are not globally unique and cached
titles are reading history on a shared machine.

## Decision

### Separate config, data, and cache roots

`AppDirectories` follows the platform conventions:

| State | Linux | Meaning |
| --- | --- | --- |
| Config | `$XDG_CONFIG_HOME/TTSRoad` | small user settings |
| Data | `$XDG_DATA_HOME/TTSRoad` | explicit downloads; never auto-evicted |
| Cache | `$XDG_CACHE_HOME/TTSRoad` | streamed audio and API metadata; rebuildable |

Windows uses roaming AppData only for config and LocalAppData for large data/cache. macOS uses
Application Support for config/data and Library/Caches for cache.

Every offline root is namespaced `<serverKey>/<accountKey>`. `serverKey` prefers capability
discovery's advertised `server.base_url`; the fallback canonicalises the configured scheme, host,
effective port, and case-sensitive deployment path. `accountKey` hashes the server-returned username
without case-folding. Ambiguity is resolved toward splitting identities: a split costs bandwidth,
while a merge shows or plays one account's content as another's. File names are generated from the
numeric chapter id, with a content-hash slot reserved for the future `audio_content_hash` feature.

Owned directories and files are owner-only. Every resolution and cleanup re-checks generated names,
normalised containment, and symlinks. Cleanup never walks a symlinked owned root.

### Explicit downloads are transactional state

`downloads.json` is a schema-versioned, fully nullable index written with a sibling temporary file
and atomic rename. It stores ids, display titles, byte counts, state, and a generated filename. It
stores no token or URL. Mutations are synchronised because concurrent progress writers otherwise
lose each other's rows.

The queue exposes `Queued`, `Downloading`, `Downloaded`, `Failed`, and `Removing`, with two workers
by default. Transfers use the shared authenticated OkHttp client and:

- resume an existing `.part` with `Range` when supported, or truncate if the server answers 200;
- check free space with a 256 MiB safety margin;
- distinguish session expiry, gone content, disk full, corruption, and transient failures;
- retry only transient failures on a bounded backoff ladder;
- require a successful status, expected length when known, and an MP3 header/frame check;
- `fsync` before atomically renaming `.part` into the completed filename.

Only the atomic promotion makes a chapter Offline. A 401 ends the application session, just as it
does on an API or playback request. An explicit Cancel waits for the worker to release its handles
and removes the partial; process/network interruption keeps it for safe resume. Interrupted deletes
remain `Removing` across restart so startup can finish them rather than orphaning their bytes.

Queue rows do not persist audio URLs. Restart recovery resolves them from account-scoped cached
chapter metadata; opening the fiction supplies live metadata if that cache was unavailable.

### Playback chooses durable data, cache, then network

`OfflineFirstMediaSourceFactory` is above every controller/UI path in `AppContainer`:

1. validated explicit download;
2. validated completed streaming-cache file;
3. authenticated network source, wrapped for possible retention.

A corrupt or missing explicit file is deleted from the index and streamed, so the UI cannot keep
claiming Offline while silently failing. Local `FileMediaSource` remains seekable and all playback
preferences are applied by the controller, so offline playback uses the same seek/rate path.

The streaming cache tees only a sequential read. It promotes at clean EOF after length and MP3
validation; seeking abandons that attempt rather than creating an apparently complete file with a
hole. Completed entries use last-modified time as an LRU clock and are pruned to 1 GiB. Cache
failure never changes playback behaviour.

### Metadata is rebuildable but account-protected

Successful library and per-fiction chapter responses are atomically cached under the same identity.
On first open after restart, `LibraryCache` publishes the disk snapshot immediately with its original
last-success time, then refreshes. A network failure appears above retained content, which remains
clearly dated. Stored models remove backend error text and server-local audio filename/path fields.

Signing out deletes none of these roots, but `DownloadCoordinator` closes the live stack unless a
real bearer credential is present. Retained login hints are not authority to enumerate another
account's index or fiction titles. Signing the same account back in resolves the same namespace.

### Settings treats durable and rebuildable cleanup separately

The Offline pane measures audio files rather than trusting index byte counters, groups explicit
usage by fiction, and reports streamed cache separately. **Delete all downloads** and **Clear
streaming cache** are different confirmed operations. The former never runs automatically.

## Rejected alternatives

- **One cache root for everything.** An OS cleaner could erase a user-requested Offline chapter.
- **Raw connect URL or host-only keys.** The former duplicates one moving server; the latter merges
  distinct path deployments. Raw fiction/title/filename keys also allow collisions and traversal.
- **Store the audio URL in the download index.** It leaves bearer-protected addresses in durable
  user data and becomes stale; cached/live chapter metadata is the resolver.
- **Promote a streaming partial after close or seek.** EOF is the only proof that every byte was
  observed. A sparse/range map would be substantially more complex for an optional optimisation.
- **Delete downloads on sign-out.** Sign-out is credential lifecycle, not a request to destroy
  potentially gigabytes of user-owned data.
- **Trust the index for Settings totals or offline playback.** Filesystem and index can disagree
  after a crash, manual deletion, corruption, or disk cleanup; measured/validated bytes win.

## Consequences

The download tree can outlive credentials, but its names are hashes/generated ids and its account
metadata is owner-only and inaccessible through the app while signed out. Explicit downloads have
no automatic size bound by design; users control them in Settings. The 1 GiB streaming bound is a
policy constant and can become a preference later without changing the storage model.

An older server without capability discovery falls back to its canonical configured address, so a
later address change may require a re-download. This is preferable to merging unknown deployments;
servers that advertise `base_url` get stable identity across addresses.
