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
install_root=/opt/ttsroad

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
maintainer=$(field Maintainer)
# jpackage composes this from vendor plus --linux-deb-maintainer, which is easy to feed a value
# that nests angle brackets. Debian policy wants exactly one "Name <address>" pair.
# The pattern lives in a variable because bash parses a bare `<` inside `[[ ]]` as a redirection.
maintainer_pattern='^[^<>]+ <[^<>@[:space:]]+@[^<>@[:space:]]+>$'
[[ $maintainer =~ $maintainer_pattern ]] ||
    fail "Maintainer must be \"Name <address>\", got $maintainer"

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

require_payload "$install_root/bin/TTSRoad"
require_payload "$install_root/lib/runtime/release"
require_payload "$install_root/lib/runtime/lib/modules"
require_payload "$install_root/lib/TTSRoad.png"
require_payload /usr/share/doc/ttsroad/copyright
require_payload /usr/share/doc/ttsroad/changelog.Debian.gz
require_payload /usr/share/applications/ttsroad-TTSRoad.desktop

mapfile -d '' -t desktop_candidates < <(
    find "$payload" -type f -name 'ttsroad-TTSRoad.desktop' -print0
)
[[ ${#desktop_candidates[@]} -eq 1 ]] ||
    fail "payload must contain exactly one ttsroad-TTSRoad.desktop (found ${#desktop_candidates[@]})"
desktop=${desktop_candidates[0]}

# Symlink modes are always rendered as lrwxrwxrwx and are ignored by Linux. Check their targets
# separately below, and apply the writable-bit policy only to paths whose mode is meaningful.
if awk 'substr($1, 1, 1) != "l" && substr($1, 9, 1) == "w" { print; found=1 } END { exit found ? 0 : 1 }' \
    "$work_dir/contents.txt"; then
    fail "payload contains a world-writable path"
fi
if awk '$2 !~ /^root\/root$/ { print; found=1 } END { exit found ? 0 : 1 }' \
    "$work_dir/contents.txt"; then
    fail "payload contains a path not owned by root:root"
fi

while IFS= read -r -d '' link; do
    resolved=$(readlink -f -- "$link") || fail "payload contains a broken symlink: ${link#"$payload"}"
    [[ $resolved == "$payload"/* ]] || fail "payload symlink escapes package root: ${link#"$payload"}"
done < <(find "$payload" -type l -print0)

if grep -R -a -F -q -- "$project_dir" "$payload" "$work_dir/control"; then
    fail "payload contains the absolute build path $project_dir"
fi
if grep -R -a -E -q -- '/home/runner/work/TTSRoad-Desktop|/tmp/gradle|/private/tmp/gradle' \
    "$payload" "$work_dir/control"; then
    fail "payload contains an absolute CI/build path"
fi
# Only the maintainer scripts run as root against a live system, so they are what must never touch
# per-user state. `control` is excluded because declaring the libsecret-tools dependency there is
# both required and harmless; matching it as "secret-tool" would fail the package for its own
# Depends line. Absolute build paths inside `control` are still covered by the two checks above.
mapfile -d '' -t maintainer_scripts < <(
    find "$work_dir/control" -maxdepth 1 -type f ! -name control ! -name md5sums -print0
)
if [[ ${#maintainer_scripts[@]} -gt 0 ]] &&
    grep -a -E -q -- '\bsecret-tool\b|XDG_(CONFIG|DATA|CACHE|STATE)_HOME|/home/' \
        "${maintainer_scripts[@]}"; then
    fail "package maintainer scripts attempt to inspect or delete per-user state"
fi
# The desktop entry is shipped as a file, not registered from a script that aborts the install
# wherever no writable system menu directory exists.
if [[ ${#maintainer_scripts[@]} -gt 0 ]] &&
    grep -a -F -q -- 'xdg-desktop-menu' "${maintainer_scripts[@]}"; then
    fail "package maintainer scripts still register the desktop entry with xdg-desktop-menu"
fi

grep -Fxq 'Name=TTSRoad' "$desktop" || fail "desktop entry has the wrong display name"
grep -Fxq 'Categories=AudioVideo;Audio;' "$desktop" || fail "desktop entry has the wrong categories"
grep -Fxq 'StartupWMClass=dk-perspektiva-ttsroad-desktop-MainKt' "$desktop" ||
    fail "desktop entry has the wrong StartupWMClass"
if command -v desktop-file-validate >/dev/null; then
    desktop-file-validate "$desktop"
fi

runtime_release="$payload$install_root/lib/runtime/release"
modules=$(sed -n 's/^MODULES="\([^"]*\)"/\1/p' "$runtime_release")
[[ -n $modules ]] || fail "bundled runtime release metadata has no module list"
for module in java.desktop java.instrument jdk.accessibility jdk.security.auth jdk.unsupported; do
    [[ " $modules " == *" $module "* ]] || fail "bundled runtime is missing $module"
done

launcher="$payload$install_root/bin/TTSRoad"
[[ $("$launcher" --version) == "TTSRoad $app_version" ]] || fail "installed launcher --version is wrong"
diagnostics=$("$launcher" --diagnostics)
grep -Fq "Debian package version: $expected_version" <<< "$diagnostics" ||
    fail "installed launcher diagnostics report the wrong package version"
grep -Fq 'Accessibility module: present' <<< "$diagnostics" ||
    fail "installed launcher diagnostics cannot see jdk.accessibility"
if grep -E -q 'ttsr_[A-Za-z0-9_-]+|Authorization:[[:space:]]*Bearer' <<< "$diagnostics"; then
    fail "installed launcher diagnostics contain credential-shaped text"
fi

runtime_library_path="$payload$install_root/lib/runtime/lib/server:$payload$install_root/lib/runtime/lib"
while IFS= read -r -d '' candidate; do
    if file --brief "$candidate" | grep -q 'ELF'; then
        if env LD_LIBRARY_PATH="$runtime_library_path" ldd "$candidate" 2>&1 | grep -q 'not found'; then
            env LD_LIBRARY_PATH="$runtime_library_path" ldd "$candidate" >&2 || true
            fail "ELF payload has an unresolved native dependency: ${candidate#"$payload"}"
        fi
    fi
done < <(find "$payload" -type f -print0)

changelog_entry=$(zcat "$payload/usr/share/doc/ttsroad/changelog.Debian.gz" | head -1)
[[ $changelog_entry == "ttsroad ($expected_version) "* ]] ||
    fail "Debian changelog does not open with $expected_version: $changelog_entry"

if command -v lintian >/dev/null; then
    # Three error tags are unavoidable properties of a self-contained jpackage bundle and are
    # accepted deliberately (docs/adr/0009):
    #   dir-or-file-in-opt          — the whole application is installed under /opt/ttsroad.
    #   embedded-library            — the bundled JDK carries its own expat/freetype/lcms2/jpeg/zlib.
    #   unstripped-binary-or-object — the runtime is the upstream Temurin build, shipped as built.
    # Everything else stays a release blocker; do not widen this list to silence a real defect.
    suppressed=dir-or-file-in-opt,embedded-library,unstripped-binary-or-object
    if [[ $deb_revision == 0 ]]; then
        # Revision 0 is only ever the synthetic predecessor CI upgrades away from, never a
        # published artifact, and Debian's "a revision must not be zero" rule is about uploads.
        suppressed="$suppressed,debian-revision-is-zero"
    fi
    lintian --fail-on error --suppress-tags "$suppressed" "$deb"
fi

echo "Verified ttsroad $expected_version amd64: metadata, payload, runtime, desktop entry and native linkage OK"
