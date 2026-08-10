# ADR 0009: Linux Debian package and operational contract

- Status: Accepted
- Date: 2026-08-09
- Issue: #3 (Phase 9)

## Context

The first production desktop package targets Linux Mint 22.x on x86_64. A useful `.deb` is more
than a jpackage artifact: it must upgrade in place, advertise the correct desktop identity, include
the Java modules reached only at runtime, declare native audio/keyring dependencies, preserve user
data on removal, and provide enough redacted diagnostics to investigate failures without asking for
credentials. Packaging also has to be tested from the installed payload, not only from Gradle's app
image.

JDK `jpackage` builds native packages only for its host architecture and operating system. The CI
baseline is therefore Ubuntu 24.04, the base of Linux Mint 22.x, with JDK 25 supplied by the Gradle
toolchain. No native Linux ARM64 runner currently verifies the resulting runtime or libraries.

## Decision

### One stable Debian identity and two version components

The Debian package name and installation directory are `ttsroad` and `/opt/ttsroad`; the product,
menu label and launcher are `TTSRoad`. JDK 25 jpackage names the desktop entry
`ttsroad-TTSRoad.desktop`, which is also MPRIS's `DesktopEntry` value. This identity remains stable
so APT upgrades replace the installed package.

`ttsroad.version` remains the only application SemVer and supplies Gradle, jpackage, About,
`--version` and `--diagnostics`. `ttsroad.debRevision` is Debian's separate revision for rebuilding
the same application with packaging-only changes. A normal application release changes the former
and resets the latter to `1`.

### Bundle Java; declare native desktop services

The package bundles a trimmed JDK 25 runtime. The explicit jlink module set includes
`jdk.accessibility`, `java.instrument`, `jdk.security.auth` and the existing desktop, management,
TLS, naming and unsupported modules. `suggestModules` is advisory; the explicit superset covers
reflection and service-provider paths that static inference cannot see.

System dependencies remain native packages: GStreamer Base, Good and PulseAudio plugins plus
`libsecret-tools` are required. GStreamer Bad is recommended because it supplies skip-silence but is
not necessary for basic playback. The desktop entry is in Audio/AudioVideo and uses the checked-in
brand artwork, deterministically scaled to jpackage's 512-pixel Linux icon during the build.

Two Compose options feed different files and are easy to swap: `appCategory` becomes the Debian
`Section`, while `menuGroup` is written verbatim into the desktop entry's `Categories` key and must
therefore be registered freedesktop categories rather than a readable group name. `debMaintainer` is
a bare address because jpackage renders the control field as `<vendor> <<debMaintainer>>`, so a
`Name <address>` value there produces nested angle brackets and an RFC-invalid `Maintainer`.

Compose clears its private jpackage resource directory immediately before invoking the tool, and
its DSL cannot express Debian `Recommends`, `Section`, `GenericName`, or `StartupWMClass`. The Gradle
Debian tasks therefore finalize jpackage's archive with `dpkg-deb`: unpack, change only those control
and desktop fields, add the extended description, install the Debian copyright/license file and a
generated `changelog.Debian.gz`, rebuild with root ownership, and leave the conventional task output
in place. The changelog is generated rather than committed because CI builds the same application
version at more than one Debian revision; its timestamp is fixed, and `SOURCE_DATE_EPOCH` overrides
it, so two builds of one revision stay byte-identical. Inspection runs after this pass and treats any
missing field as a release failure.

### Ship the desktop entry, do not register it from a script

jpackage keeps the entry inside `/opt/ttsroad/lib` and calls `xdg-desktop-menu install` from
`postinst` under `set -e`. That command exits non-zero wherever no writable system menu directory
exists, which aborts the entire installation on a minimal or container system — the exact
environment the lifecycle job installs into. The finalizing pass moves the file to
`/usr/share/applications/ttsroad-TTSRoad.desktop` and deletes the registration lines from `postinst`
and `prerm`, so dpkg alone makes the entry appear and removal makes it disappear. Inspection fails
the build if a maintainer script mentions `xdg-desktop-menu` again.

### Lint the package, and name the three tags a bundled runtime cannot avoid

`lintian --fail-on error` runs over every package. Three error tags are suppressed deliberately and
individually: `dir-or-file-in-opt` (jpackage installs the whole application under `/opt/ttsroad`),
`embedded-library` (the bundled JDK carries its own expat, freetype, lcms2, libjpeg and zlib) and
`unstripped-binary-or-object` (the runtime ships as upstream Temurin built it). Each is a direct
consequence of bundling Java, which is the decision above. The synthetic revision `0` predecessor
additionally suppresses `debian-revision-is-zero`, because it is an upgrade fixture and never a
published artifact. Nothing else is suppressed; a widened list would hide a real defect.

### Treat state according to XDG and preserve it on removal

Configuration, requested downloads, rebuildable cache and logs use XDG config, data, cache and state
roots respectively. The package owns none of those user directories, so `apt remove ttsroad`
removes the app and bundled runtime while retaining user state. A full purge is an explicit manual
operation over the documented effective XDG paths and the Secret Service entry labelled
`TTSRoad session`; it is never an uninstall script side effect.

### Diagnostics cross one redaction boundary

The app writes a size-rotating log below XDG state and shows a generic crash message naming that
location. Every persistent line and crash trace passes through `Redaction`. `--version` and
`--diagnostics` exit before Compose, the composition root, SessionStore or CredentialStore are
constructed. Diagnostics probe only runtime/module/process availability and redact their complete
output; they do not retrieve a credential or account record.

### Inspect and exercise the installed artifact

CI first runs strict tests and both normal/release distributables. It then builds revision `0` and
the current revision, checking package fields, required/recommended dependencies, payload paths,
desktop entry, root ownership, absence of world-writable files/build paths, bundled runtime modules,
ELF linkage and redacted launcher diagnostics. A clean Ubuntu 24.04 job installs revision `0`, opens
the real login window against a local mock capability endpoint, upgrades to the current revision,
checks retained XDG sentinels, removes the package, and verifies that application files disappear
while user state remains. The clean container starts without Java, proving the package is self
contained.

## Rejected alternatives

- Naming the package `TTSRoad`: Debian package identities are lowercase and would not provide a
  conventional stable APT identity.
- Encoding packaging rebuilds in application SemVer: reports an application release when only
  Debian metadata changed and makes About disagree with source.
- Bundling GStreamer or Secret Service: duplicates distribution security updates and desktop
  integration while making native linkage less inspectable.
- Deleting user data or keyring entries from package removal scripts: turns routine reinstall into
  irreversible data loss and cannot safely identify every user's home/keyring.
- Logging under config or cache: logs are neither durable configuration nor freely evictable cache;
  XDG state is the intended home.
- Treating `suggestModules` as exhaustive: misses reflection/service paths and permits a package
  that builds successfully but loses accessibility, MPRIS or instrumentation at runtime.

## Consequences

The amd64 package has a conventional upgrade path, a bundled Java runtime, explicit native
dependencies and an operationally useful but credential-safe failure trail. CI takes longer because
it builds two native packages and performs a privileged package lifecycle inside a disposable
container. ARM64 remains unsupported until a native ARM64 build-and-lifecycle job can uphold the
same contract.
