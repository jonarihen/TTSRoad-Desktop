# ADR 0002 — Credential storage, centralized auth, and server capability discovery

- **Status:** Accepted
- **Date:** 2026-08-06
- **Context issue:** [#6 — Secure sessions and add server capability discovery](https://github.com/jonarihen/TTSRoad-Desktop/issues/6) (part of #1, depends on #2)

## Context

After Phase 0 the desktop client still had four security-relevant problems:

1. `session.json` contained the raw bearer token in plaintext with default file permissions —
   readable by any other account on a shared machine and picked up by every cloud-sync tool.
2. The `Authorization` header was threaded by hand: as an explicit parameter on all six Retrofit
   methods, and separately in the audio download path. Cover images went through a third code path
   that had no auth at all. Nothing enforced *where* the token could be sent.
3. A `401` did nothing. The stored token stayed, every screen showed "HTTP 401 Unauthorized", and
   the user had to find Settings → Sign out. There was no way to tell "your session was revoked"
   from "the wifi dropped".
4. Optional server features were assumed rather than discovered, and the login screen could not
   tell the user what server they were about to hand a password to.

## Decision

### 1. `CredentialStore`: an OS keyring, or nothing

```
interface CredentialStore {
    val persistsAcrossRestarts: Boolean
    fun store(key: String, secret: String)
    fun retrieve(key: String): String?
    fun delete(key: String)
}
```

| Platform | Implementation | Mechanism |
| --- | --- | --- |
| Windows | `WindowsCredentialStore` | `CredWriteW`/`CredReadW`/`CredDeleteW` in `Advapi32.dll` through `java.lang.foreign` |
| macOS | `MacKeychainCredentialStore` | `/usr/bin/security`, secret on stdin |
| Linux | `SecretServiceCredentialStore` | libsecret's `secret-tool`, secret on stdin |
| anything else / unavailable | `InMemoryCredentialStore` | process lifetime only |

**There is deliberately no file-backed fallback.** Encrypting the token with a key stored beside
the ciphertext is plaintext with extra steps and, worse, it looks safe. A machine with no keyring
gets a session that ends when the process does, and the login screen says so.

**Why FFM on Windows rather than JNA.** The build already targets a JDK 25 toolchain where
`java.lang.foreign` is final API, so the alternative was adding a native-bridge dependency to the
dependency graph *and* the jlink image for four function calls. The cost is that
`Linker.downcallHandle`, `SymbolLookup.libraryLookup` and `MemorySegment.reinterpret` are
*restricted* methods; `--enable-native-access=ALL-UNNAMED` is now passed to both the test JVM and
the packaged launcher, which also silences the pre-existing Skiko warning Phase 0 recorded as
unresolved. `WindowsCredentialStore.createOrNull()` catches `Throwable` and performs a live probe
before promising a keyring, so a future JVM that blocks the access degrades to session-only rather
than crashing at startup.

**Why `secret-tool` rather than a D-Bus client on Linux.** The freedesktop Secret Service API is
D-Bus, and using it correctly means a session-bus connection, the `org.freedesktop.Secret.Session`
handshake (including the DH algorithm when the transport is untrusted), collection unlocking and
prompt handling. There is no maintained pure-JVM D-Bus client we could verify resolves for this
build, and hand-rolling that wire protocol on the credential path is precisely the kind of code
that fails open. `secret-tool` ships with libsecret — a hard dependency of GNOME Keyring, present
on effectively every desktop Linux — performs the whole session/unlock/prompt dance, and is
maintained by the project that defines the API. Both command-driven stores go through a
`CommandRunner` seam, so their argument shapes and (critically) the fact that the secret travels on
**stdin and never in `argv`** are asserted on any platform.

**Note:** the macOS path is written against Apple's documented `security(1)` interface but has not
been executed on macOS — see *Consequences*.

### 2. The settings file holds no secret, by construction

`PersistedSettings` — the only type that is ever serialized to `session.json` — has **no token
field at all**. The token is not "omitted when null"; it has no representation, so no future edit
to `SessionState` can leak it onto disk by accident. What is stored is `serverUrl`, `serverName`,
`serverVersion`, `username`, and `credentialKey` — a SHA-256-derived identifier of the keyring
entry, which opens nothing on its own and keeps the keyring's own UI from listing every server the
user has ever signed in to.

Writes go through `SecureFiles.writeAtomically`: temp file in the same directory, permissions
restricted *before* any content is written, then `ATOMIC_MOVE`. Owner-only means
`rw-------` on POSIX and a replaced, single-owner ACL on Windows.

### 3. One-time migration, biased toward destroying exposure

A settings file from an older build is migrated on construction: store into the keyring, rewrite
the file without the token, then **re-read the file and verify the plaintext is gone**. The
verification is the point — a `store` and a `write` that both report success still have to be
checked, because a write that silently landed elsewhere would leave the token on disk with every
step reporting OK.

If any part fails, the plaintext is removed anyway (rewrite without it; if even that fails, delete
the file) and the user signs in again. **A working session is never worth leaving a readable token
behind.**

### 4. One interceptor, and an origin rule

`TtsRoadAuthInterceptor` on the app's single OkHttp client is now the only place a bearer token is
attached — for API calls, chapter audio, and Coil's cover fetches alike. Two rules make that safe:

- **Same origin only.** Scheme, host and effective port must match the signed-in server. Cover
  images are routinely absolute URLs on a third-party CDN and go through the same client; an
  interceptor keyed on "do we have a token?" would hand the user's credential to Royal Road's image
  host on every library screen.
- **An explicit opt-out header wins.** `X-TtsRoad-No-Auth` marks `login` and `capabilities`; the
  interceptor strips it before the request leaves the process. Capability discovery runs against a
  URL the user is still typing, which may resolve to a host that happens to share an origin with a
  stale session.

All six Retrofit methods lost their `@Header("Authorization")` parameter as a result.

### 5. `401` ends the session; nothing else does

`RetrofitTtsRoadRepository.withAuthorizedApi` turns a `401` into `endSession(parseSessionEnd(body))`
and rethrows. The audio path — which does not go through Retrofit — raises a typed
`SessionExpiredException`, and `Mp3PlaybackController` funnels it to the same `endSession`. Both
land the app on the login screen with the server's own reason
(`token_expired` / `token_revoked` / `invalid_token` / unknown).

The inverse is enforced just as hard: a `500`, a socket error, a DNS failure or a timeout says
nothing about the credential and must not sign anyone out. `login()` deliberately does not go
through this path, because it answers `401` for a wrong password and for `totp_required`.

Non-secret hints (`serverUrl`, `serverName`, `serverVersion`, `username`) survive sign-out so the
login form prefills; claims about a session that no longer exists (`isAdmin`, `deviceId`,
`expiresAt`) do not. This is a deliberate change from Phase 0, which cleared `username`.

### 6. `429` is modelled separately from failure

`LoginResult.RateLimited(message, retryAfterSeconds)` reads `Retry-After` first and the body's
`detail.retry_after` second (a proxy that strips the header should not lose the wait). It is a
distinct result because it is the one failure where retrying *will* work and the server said when —
so the sign-in button stays disabled instead of letting the user extend their own lockout.

### 7. Redaction at the boundary

`redactSecrets` scrubs bearer tokens, `Authorization` headers, sensitive query parameters, URL
userinfo and credential-shaped JSON fields. `describeNetworkFailure` maps DNS/TLS/timeout/connect
failures to one human sentence with **no stack trace and no class names**. `AppLog` — the entire
logging surface of the app — routes every line through `redactSecrets`, and server-supplied 401/429
messages are redacted before they are shown, because nothing stops a future backend from quoting
the offending credential.

### 8. Additive capability discovery

Ported from the mobile client's `ServerCapabilities.kt`, with the same rules: only a literal JSON
`true` enables a feature, unknown keys are ignored, a non-numeric limit is absent rather than zero,
and `api_version` is never a proxy for a feature. A `404` means baseline **and is cached** (an old
server will not grow the endpoint); a transient failure keeps the last known answer rather than
downgrading, because making features flicker on one dropped request is worse than being briefly
stale. The in-memory TTL is six hours, and login forces a refresh.

Concretely: a 1.4.0 server reports `batch_progress: false` while happily accepting a multi-id
`playback/mark`, because the flag tracks a registered FastAPI route name. That is exactly why
capabilities are never inferred from observed behaviour.

## Consequences

### Good

- No bearer token reaches a file, a log line, an error string, or a process argument list.
- One answer to "when is the token sent", enforced by an origin check rather than by convention.
- Revoked / expired / invalid tokens each produce a safe sign-out with a useful message; a network
  outage produces neither.
- A keyring-less machine is *visibly* keyring-less rather than silently insecure.
- 201 tests, covering migration, file permissions, redaction, capability TTL/404/unknown flags,
  cross-origin cover fetches, structured 401s, audio 401s, and 429 — including a real round-trip
  through Windows Credential Manager on a Windows host.

### Costs and risks

- **Login is now two round-trips** (login, then the forced capability refresh). Discovery never
  throws, so this cannot fail the sign-in, but it does add one request against a server we have
  just successfully talked to.
- **The macOS keychain path is unverified on macOS.** It is written against the documented
  `security(1)` interface and covered by tests against a fake `CommandRunner` (argument shapes,
  exit-code 44 handling, stdin), but no `security` binary has actually run. Same for the Linux
  `secret-tool` path.
- **`SessionStore` remains synchronous.** `current()` is called from the OkHttp interceptor, which
  cannot suspend, so the keyring is read once at construction and written on sign-in/sign-out —
  never on the request path. The cost is blocking I/O (a subprocess on Linux/macOS) during the
  first composition. Making it suspending would mean changing the interceptor, the audio path, and
  every non-suspend caller.
- **FFM restricted methods.** Fine on JDK 25 with `--enable-native-access=ALL-UNNAMED`; a future
  JVM that denies the access degrades Windows to session-only credentials rather than failing.
- **Discovery is in-memory only.** Every launch re-asks. That is intentional (a stale flag
  surviving a reinstall would be worse) but it does mean one extra unauthenticated request per run.
