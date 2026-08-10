#!/usr/bin/env bash
set -euo pipefail

fail() {
    echo "DEB finalization failed: $*" >&2
    exit 1
}

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <package-directory> <application-version> <debian-revision>" >&2
    exit 2
fi

package_directory=$1
application_version=$2
debian_revision=$3
package="$package_directory/ttsroad_${application_version}-${debian_revision}_amd64.deb"
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# A failed/incompatible jpackage invocation may still trigger a Gradle finalizer. Preserve the
# original task failure instead of replacing it with a misleading "package missing" error.
[[ -f $package ]] || exit 0
command -v dpkg-deb >/dev/null || fail "dpkg-deb is required"

work_directory=$(mktemp -d "${TMPDIR:-/tmp}/ttsroad-finalize.XXXXXX")
trap 'rm -rf -- "$work_directory"' EXIT
payload="$work_directory/payload"
rebuilt="$work_directory/$(basename -- "$package")"

mkdir -p "$payload"
dpkg-deb --raw-extract "$package" "$payload"
control="$payload/DEBIAN/control"
[[ -f $control ]] || fail "package has no DEBIAN/control"

# Jpackage owns where the desktop-integration source lives inside its package payload. Keep the
# public filename stable for MPRIS, but do not couple this Debian-specific finishing pass to a
# private Jpackage directory layout that can move between JDK updates.
mapfile -d '' -t desktop_candidates < <(
    find "$payload" -type f -name 'ttsroad-TTSRoad.desktop' -print0
)
[[ ${#desktop_candidates[@]} -eq 1 ]] ||
    fail "package must contain exactly one ttsroad-TTSRoad.desktop (found ${#desktop_candidates[@]})"
desktop=${desktop_candidates[0]}

sed -i 's/^Section:.*/Section: sound/' "$control"
if grep -q '^Recommends:' "$control"; then
    sed -i 's/^Recommends:.*/Recommends: gstreamer1.0-plugins-bad/' "$control"
else
    # Insert inside the one package stanza. Appending after jpackage's trailing blank line creates
    # a second stanza, while inserting immediately after Depends could split its continuation lines.
    grep -q '^Description:' "$control" || fail "DEBIAN/control has no Description field"
    sed -i '/^Description:/i Recommends: gstreamer1.0-plugins-bad' "$control"
fi

if ! grep -q '^GenericName=' "$desktop"; then
    sed -i '/^Name=/a GenericName=Audiobook Player' "$desktop"
fi
if ! grep -q '^StartupWMClass=' "$desktop"; then
    sed -i '/^Categories=/a StartupWMClass=dk-perspektiva-ttsroad-desktop-MainKt' "$desktop"
fi

# jpackage keeps the desktop entry inside its own /opt tree and registers it from postinst with
# `xdg-desktop-menu install`, under `set -e`. That call exits non-zero wherever no writable system
# menu directory exists, which aborts the whole installation on a minimal or container system.
# Debian expects the file to be shipped at its final location instead, so move it there and drop
# the registration calls: the entry then appears through dpkg alone and disappears on removal.
install -D -m 0644 "$desktop" "$payload/usr/share/applications/ttsroad-TTSRoad.desktop"
rm -f -- "$desktop"
for script in postinst prerm; do
    [[ -f "$payload/DEBIAN/$script" ]] || continue
    sed -i '/xdg-desktop-menu/d' "$payload/DEBIAN/$script"
done

# jpackage emits only the one-line synopsis. Debian policy requires an extended description, and
# it is what `apt show` and the graphical installers actually display.
if ! grep -A1 '^Description:' "$control" | tail -n +2 | grep -q '^ '; then
    cat > "$work_directory/extended-description" <<'EOF'
 TTSRoad Desktop is a client for a private TTSRoad audiobook server. It signs in
 with two-factor authentication, browses the library, queues and plays chapters,
 keeps listening progress synchronised, downloads chapters for offline listening
 and shows the audio-synchronised read-along text.
 .
 The Java runtime the application needs is bundled, so neither a system JDK
 nor a JRE has to be installed.
EOF
    sed -i "/^Description:/r $work_directory/extended-description" "$control"
fi

# `--license-file` is an installer option on Windows/macOS; Debian policy expects copyright and
# license information in this package-owned location instead.
install -D -m 0644 "$script_directory/LICENSE.txt" "$payload/usr/share/doc/ttsroad/copyright"

# Debian requires a changelog for a non-native package. It is generated rather than committed
# because CI builds the same application version at more than one Debian revision, and a committed
# file would have to be rewritten for each of them. The timestamp is fixed (overridable through
# SOURCE_DATE_EPOCH) so two builds of the same revision stay byte-identical.
maintainer=$(sed -n 's/^Maintainer: //p' "$control")
[[ -n $maintainer ]] || fail "DEBIAN/control has no Maintainer field"
changelog_date=$(LC_ALL=C date -u -d "@${SOURCE_DATE_EPOCH:-1767225600}" '+%a, %d %b %Y %H:%M:%S +0000')
{
    printf 'ttsroad (%s-%s) unstable; urgency=medium\n\n' "$application_version" "$debian_revision"
    printf '  * TTSRoad Desktop %s packaged for Debian-based systems.\n\n' "$application_version"
    printf ' -- %s  %s\n' "$maintainer" "$changelog_date"
} > "$work_directory/changelog.Debian"
gzip -9n -- "$work_directory/changelog.Debian"
install -D -m 0644 "$work_directory/changelog.Debian.gz" \
    "$payload/usr/share/doc/ttsroad/changelog.Debian.gz"

# Replacing the desktop file and adding the documentation files invalidates jpackage's checksums.
# Regenerate them from the final payload so `dpkg --verify` remains meaningful after installation.
(
    cd "$payload"
    find . -type f ! -path './DEBIAN/*' -printf '%P\0' |
        LC_ALL=C sort -z |
        xargs -0 -r md5sum > DEBIAN/md5sums
)
chmod 0644 "$payload/DEBIAN/md5sums"

dpkg-deb --root-owner-group --build "$payload" "$rebuilt" >/dev/null
mv -- "$rebuilt" "$package"
echo "Finalized $(basename -- "$package"): Debian metadata and desktop integration"
