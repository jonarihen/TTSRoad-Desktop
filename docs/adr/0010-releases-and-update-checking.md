# 10. Releases, update checking and the security update process

Date: 2026-08-10

## Status

Accepted.

## Context

Phase 9 produced an installable `.deb`, but nothing published it. A release had to become an
operation that is repeatable, verifiable after the fact, and safe to hand to a user — including the
part where the application tells that user a newer build exists.

The application version already has exactly one source (`ttsroad.version`). Everything below exists
to keep the release from introducing a second one, and to make sure a published artifact is one
that was actually tested.

## Decision

### One version, one tag, one changelog section

A release tag is the application version prefixed with `v`. The release workflow resolves the
version from `gradle.properties`, refuses to continue when the tag disagrees, and refuses again when
`CHANGELOG.md` has no non-empty section for it. `packaging/release/changelog-section.sh` is both the
extractor and that second gate, so the notes GitHub publishes are the notes a human wrote.

`ttsroad.debRevision` is deliberately outside this. A packaging-only rebuild moves the Debian
revision without a tag, because the application did not change.

### Publish only what was tested, and prove it afterwards

Each installer is built on its own operating system, because jpackage does not cross-compile. The
Linux job additionally inspects the package and a separate clean-container job installs, upgrades
and removes it before anything is published — a `.deb` that has never been installed is an untested
`.deb`. The publish job then computes SHA-256 checksums over the collected assets, attaches an SBOM
generated from the release application image, and requests signed build provenance for the three
installers.

Permissions are read-only at the top of the workflow; only the publishing job widens them, and only
to `contents: write` plus the two scopes the attestation action requires. No signing secret exists,
so no job can leak one. The release is created as a **draft**: publishing stays a human action after
a human has looked at the assets.

`workflow_dispatch` runs the identical build and verification and stops before creating anything,
which is the documented dry run.

### The update check reads a public release feed and installs nothing

The application asks `api.github.com` for the latest release. This is the reason the repository is
public: an authenticated feed would require shipping a credential inside a desktop application,
which is not a secret at all.

Three properties are load-bearing:

- **It uses the shared `OkHttpClient`.** `AuthInterceptor` attaches the TTSRoad bearer token only
  when scheme, host and port match the signed-in server, so the GitHub request carries no
  credential. A second client would put this call outside the rule that makes that true.
- **It never installs.** A downloaded file is verified against the release's published
  `SHA256SUMS` and, only then, handed to the desktop's own handler. A mismatch deletes the file and
  never opens it. The application does not run `sudo`, `dpkg` or any package manager: installing a
  system package is an authorisation the desktop's installer asks for, not one an audiobook player
  should take.
- **It is quiet.** At most one automatic check per launch and per day; a version the user dismissed
  is not announced again until a newer one exists; automatic checking can be turned off entirely,
  and a manual check ignores all of it because the user just asked.

A release with no asset for this platform and architecture is still announced, with no download
button. An aarch64 Linux desktop is told a version exists rather than handed an `amd64` package
`dpkg` would refuse.

Failures are deliberately vague in the UI ("Could not reach the update server") because a response
body can contain anything; the detail goes to the redacted log instead.

### Dependency and security updates

Dependabot already opens weekly grouped pull requests for Gradle dependencies and actions. CI adds
`dependency-review-action` on pull requests, failing on a high-severity advisory. That action needs
GitHub's dependency graph, so the job skips itself while the repository is private rather than
failing every pull request.

The response times the project commits to are in `docs/SECURITY.md`.

## Consequences

- The repository must be public for the update check to work. A private repository degrades to
  "could not reach the update server" rather than misbehaving, but the feature is off in practice.
- Windows and macOS release jobs exist because the README claims those platforms. Neither installer
  has a clean-machine install test the way the `.deb` does; they build and smoke-test the release
  image only. That difference is stated in the README rather than papered over.
- The SBOM is generated from the Linux application image. The same jars ship in all three
  installers, so one SBOM describes the application; it does not describe the Windows or macOS
  bundled runtimes.

## Rejected alternatives

- **Checking a version endpoint on the TTSRoad server.** It would work for a private repository and
  reuse existing auth, but it makes the desktop client's update path depend on a server change and
  on every deployment being current. The client would also have to trust the server to describe an
  artifact it does not host.
- **Silent or automatic installation.** It requires either running as root or holding a privileged
  helper. Neither is proportionate for this application, and both turn a checksum bug into a
  privileged-execution bug.
- **Publishing directly rather than as a draft.** A draft costs one click and makes "the gate
  passed but the notes are wrong" recoverable without deleting a published release.
- **A separate HTTP client for GitHub.** Simpler to reason about in isolation, but it would create a
  second outbound path not covered by the interceptor's origin rule — the exact thing that rule
  exists to prevent.
