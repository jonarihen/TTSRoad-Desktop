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
    # Append as a new field. Inserting immediately after Depends could split a wrapped dependency
    # continuation line and accidentally make those packages part of Recommends.
    printf 'Recommends: gstreamer1.0-plugins-bad\n' >> "$control"
fi

if ! grep -q '^GenericName=' "$desktop"; then
    sed -i '/^Name=/a GenericName=Audiobook Player' "$desktop"
fi
if ! grep -q '^StartupWMClass=' "$desktop"; then
    sed -i '/^Categories=/a StartupWMClass=dk-perspektiva-ttsroad-desktop-MainKt' "$desktop"
fi

# `--license-file` is an installer option on Windows/macOS; Debian policy expects copyright and
# license information in this package-owned location instead.
install -D -m 0644 "$script_directory/LICENSE.txt" "$payload/usr/share/doc/ttsroad/copyright"

# Replacing the desktop file and adding copyright invalidates jpackage's original checksums.
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
