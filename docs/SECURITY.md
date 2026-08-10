# Security policy

## Supported versions

The most recent released version is supported. There is no long-term-support branch: fixes ship in
a new release rather than as patches to an older one.

## Reporting a vulnerability

Report privately through GitHub's **Security → Report a vulnerability** form on this repository, or
by email to the address in the package `Maintainer` field. Please do not open a public issue for
anything that affects credential handling, the update path or the packaged runtime.

Include the application version (`TTSRoad --version`), the operating system, and what an attacker
would gain. A `--diagnostics` export is useful and is redacted by design — but read it before
attaching it.

## Response times

These are the commitments, measured from a report being acknowledged:

| Severity | Acknowledge | Fix released |
| --- | --- | --- |
| Critical — credential disclosure, remote code execution, update-path compromise | 2 working days | 7 days |
| High — privilege or origin boundary crossed, local data disclosure | 5 working days | 30 days |
| Moderate and low | 10 working days | next scheduled release |

A dependency advisory follows the same table, judged by its severity *in this application* rather
than by its headline score: a vulnerability in a code path the desktop client never executes is not
critical here.

## Dependency updates

- Dependabot opens grouped Gradle and GitHub Actions pull requests weekly. Kotlin and the Compose
  compiler plugin are grouped because their versions must match.
- `dependency-review-action` runs on every pull request and fails on a high-severity advisory. It
  requires GitHub's dependency graph, so it is inactive while the repository is private.
- A security update that is not blocked by a failing test is merged as soon as CI is green, ahead of
  feature work.

## What the application protects

- **The bearer token** lives only in the OS credential store — Windows Credential Manager, macOS
  Keychain, freedesktop Secret Service. There is deliberately no encrypted-file fallback; where no
  keyring exists the session is memory-only and the UI says so.
- **The token's origin** is enforced in one place. `AuthInterceptor` attaches it only when scheme,
  host and port match the signed-in server, which is what keeps it off audio URLs, cover images and
  the GitHub update feed.
- **Logs and diagnostics** pass through one redaction boundary before they are written or shown.
- **Downloaded updates** are verified against the release's published SHA-256 before being handed to
  the desktop, and the application never installs anything itself.

## What it does not do

There is no telemetry and no crash reporting. The application makes no network request that is not
either to the server the user signed in to or to the public release feed described in
[`docs/adr/0010-releases-and-update-checking.md`](adr/0010-releases-and-update-checking.md).
