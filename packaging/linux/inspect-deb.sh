#!/usr/bin/env bash
set -euo pipefail

fail() {
    echo "DEB verification failed: $*" >&2
    exit 1
}

if [[ $# -lt 1 || $# -gt 3 ]]; then
    echo "usage: $0 <package.deb> [application-version] [debian-revision]" >&2
    exit 2
fi

deb=$1
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/../.." && pwd)
properties="$project_dir/gradle.properties"
app_version=${2:-$(sed -n 's/^ttsroad\.version=//p' "$properties")}
deb_revision=${3:-$(sed -n 's/^ttsroad\.debRevision=//p' "$properties")}
expected_version="$app_version-$deb_revision"

[[ -f "$deb" ]] || fail "package does not exist: $deb"
command -v dpkg-deb >/dev/null || fail "dpkg-deb is required"
command -v file >/dev/null || fail "file is required"
command -v ldd >/dev/null || fail "ldd is required"

field() {
    dpkg-deb --field "$deb" "$1"
}

[[ $(field Package) == "ttsroad" ]] || fail "Package must be ttsroad, got $(field Package)"
[[ $(field Version) == "$expected_version" ]] ||
    fail "Version must be $expected_version, got $(field Version)"
[[ $(field Architecture) == "amd64" ]] || fail "Architecture must be amd64, got $(field Architecture)"
[[ $(field Section) == "sound" ]] || fail "Section must be sound, got $(field Section)"
[[ -n $(field Maintainer) ]] || fail "Maintainer is empty"

depends=$(field Depends)
for package in \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-pulseaudio \
    libsecret-tools; do
    [[ $depends == *"$package"* ]] || fail "Depends is missing $package: $depends"
done
recommends=$(field Recommends)
[[ $recommends == *"gstreamer1.0-plugins-bad"* ]] ||
    fail "Recommends is missing gstreamer1.0-plugins-bad: $recommends"

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/ttsroad-deb.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT
payload="$work_dir/payload"
mkdir -p "$payload"
dpkg-deb --extract "$deb" "$payload"
dpkg-deb --control "$deb" "$work_dir/control"
dpkg-deb --info "$deb"
dpkg-deb --contents "$deb" > "$work_dir/contents.txt"

require_payload() {
    [[ -e "$payload$1" ]] || fail "payload is missing $1"
}

require_payload /opt/TTSRoad/bin/TTSRoad
require_payload /opt/TTSRoad/lib/runtime/release
require_payload /opt/TTSRoad/lib/runtime/lib/modules
require_payload /opt/TTSRoad/lib/ttsroad-TTSRoad.desktop
require_payload /opt/TTSRoad/lib/TTSRoad.png
require_payload /usr/share/doc/ttsroad/copyright

if awk 'substr($1, 9, 1) == "w" { print; found=1 } END { exit found ? 0 : 1 }' \
    "$work_dir/contents.txt"; then
    fail "payload contains a world-writable path"
fi
if awk '$2 !~ /^root\/root$/ { print; found=1 } END { exit found ? 0 : 1 }' \
    "$work_dir/contents.txt"; then
    fail "payload contains a path not owned by root:root"
fi

if grep -R -a -F -q -- "$project_dir" "$payload" "$work_dir/control"; then
    fail "payload contains the absolute build path $project_dir"
fi
if grep -R -a -E -q -- '/home/runner/work/TTSRoad-Desktop|/tmp/gradle|/private/tmp/gradle' \
    "$payload" "$work_dir/control"; then
    fail "payload contains an absolute CI/build path"
fi
if grep -R -a -E -q -- 'secret-tool|XDG_(CONFIG|DATA|CACHE|STATE)_HOME|/home/' \
    "$work_dir/control"; then
    fail "package maintainer metadata/scripts attempt to inspect or delete per-user state"
fi

desktop="$payload/opt/TTSRoad/lib/ttsroad-TTSRoad.desktop"
grep -Fxq 'Name=TTSRoad' "$desktop" || fail "desktop entry has the wrong display name"
grep -Fxq 'Categories=AudioVideo;Audio;' "$desktop" || fail "desktop entry has the wrong categories"
grep -Fxq 'StartupWMClass=dk-perspektiva-ttsroad-desktop-MainKt' "$desktop" ||
    fail "desktop entry has the wrong StartupWMClass"
if command -v desktop-file-validate >/dev/null; then
    desktop-file-validate "$desktop"
fi

runtime_release="$payload/opt/TTSRoad/lib/runtime/release"
modules=$(sed -n 's/^MODULES="\([^"]*\)"/\1/p' "$runtime_release")
[[ -n $modules ]] || fail "bundled runtime release metadata has no module list"
for module in java.desktop java.instrument jdk.accessibility jdk.security.auth jdk.unsupported; do
    [[ " $modules " == *" $module "* ]] || fail "bundled runtime is missing $module"
done

launcher="$payload/opt/TTSRoad/bin/TTSRoad"
[[ $($launcher --version) == "TTSRoad $app_version" ]] || fail "installed launcher --version is wrong"
diagnostics=$($launcher --diagnostics)
grep -Fq "Debian package version: $expected_version" <<< "$diagnostics" ||
    fail "installed launcher diagnostics report the wrong package version"
grep -Fq 'Accessibility module: present' <<< "$diagnostics" ||
    fail "installed launcher diagnostics cannot see jdk.accessibility"
if grep -E -q 'ttsr_[A-Za-z0-9_-]+|Authorization:[[:space:]]*Bearer' <<< "$diagnostics"; then
    fail "installed launcher diagnostics contain credential-shaped text"
fi

runtime_library_path="$payload/opt/TTSRoad/lib/runtime/lib/server:$payload/opt/TTSRoad/lib/runtime/lib"
while IFS= read -r -d '' candidate; do
    if file --brief "$candidate" | grep -q 'ELF'; then
        if env LD_LIBRARY_PATH="$runtime_library_path" ldd "$candidate" 2>&1 | grep -q 'not found'; then
            env LD_LIBRARY_PATH="$runtime_library_path" ldd "$candidate" >&2 || true
            fail "ELF payload has an unresolved native dependency: ${candidate#"$payload"}"
        fi
    fi
done < <(find "$payload" -type f -print0)

if command -v lintian >/dev/null; then
    lintian --fail-on error "$deb"
fi

echo "Verified ttsroad $expected_version amd64: metadata, payload, runtime, desktop entry and native linkage OK"
