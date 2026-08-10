#!/usr/bin/env bash
# Print the CHANGELOG body for one version, for use as generated release notes.
#
# The release workflow uses this as a gate as much as a formatter: a version with no section, or
# with an empty one, exits non-zero and stops the release before anything is published.
set -euo pipefail

fail() {
    echo "Changelog extraction failed: $*" >&2
    exit 1
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "usage: $0 <version> [changelog-path]" >&2
    exit 2
fi

version=$1
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
changelog=${2:-$(cd -- "$script_dir/../.." && pwd)/CHANGELOG.md}

[[ -f $changelog ]] || fail "no changelog at $changelog"

# Match "## [1.0.1]" with or without a trailing date, and stop at the next second-level heading.
# The link-reference block at the bottom of the file starts with "[", never "#", so it is only
# reached when the requested section is the last one; awk drops those lines explicitly.
section=$(
    awk -v version="$version" '
        $0 ~ "^## \\[" version "\\]" { collecting = 1; next }
        collecting && /^## / { exit }
        collecting && /^\[[^]]+\]:/ { next }
        collecting { print }
    ' "$changelog"
)

# A section that is only blank lines is a section nobody wrote.
[[ -n ${section//[[:space:]]/} ]] || fail "CHANGELOG.md has no entry for $version"

# Trim leading and trailing blank lines without touching the interior spacing.
printf '%s\n' "$section" | awk '
    { line[NR] = $0; if ($0 ~ /[^[:space:]]/) { if (!first) first = NR; last = NR } }
    END { for (i = first; i <= last; i++) print line[i] }
'
