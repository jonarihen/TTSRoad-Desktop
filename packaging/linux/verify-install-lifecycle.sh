#!/usr/bin/env bash
set -euo pipefail

fail() {
    echo "Install lifecycle verification failed: $*" >&2
    exit 1
}

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <previous-revision.deb> <current.deb>" >&2
    exit 2
fi
[[ $(id -u) -eq 0 ]] || fail "run inside the clean root-owned CI container"

previous=$(readlink -f -- "$1")
current=$(readlink -f -- "$2")
install_root=/opt/ttsroad
[[ -f $previous ]] || fail "previous package is missing: $previous"
[[ -f $current ]] || fail "current package is missing: $current"
command -v java >/dev/null && fail "the clean image already has a java command"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/ttsroad-lifecycle.XXXXXX")
export HOME="$test_root/home"
export XDG_CONFIG_HOME="$test_root/config"
export XDG_DATA_HOME="$test_root/data"
export XDG_CACHE_HOME="$test_root/cache"
export XDG_STATE_HOME="$test_root/state"
mkdir -p "$HOME" "$XDG_CONFIG_HOME" "$XDG_DATA_HOME" "$XDG_CACHE_HOME" "$XDG_STATE_HOME"

ready_file="$test_root/server.url"
request_log="$test_root/server.requests"
python3 "$script_dir/mock-mobile-server.py" "$ready_file" "$request_log" &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
for _ in $(seq 1 100); do
    [[ -s $ready_file ]] && break
    sleep 0.05
done
[[ -s $ready_file ]] || fail "mock server did not start"
server_url=$(<"$ready_file")

apt-get install -y "$previous"
[[ $(dpkg-query -W -f='${Status}' ttsroad) == "install ok installed" ]] || fail "previous package is not installed"
dpkg --verify ttsroad
[[ $("$install_root/bin/TTSRoad" --version) == TTSRoad\ * ]] || fail "installed --version failed"
TTSROAD_SMOKE_TEST=1 TTSROAD_SMOKE_SERVER_URL="$server_url" \
    timeout --signal=TERM --kill-after=5s 40s xvfb-run -a "$install_root/bin/TTSRoad"
grep -Fxq 'GET /api/mobile/capabilities' "$request_log" ||
    fail "the installed login window never probed the mock server"

mkdir -p "$XDG_CONFIG_HOME/TTSRoad" "$XDG_DATA_HOME/TTSRoad/downloads"
printf 'keep settings\n' > "$XDG_CONFIG_HOME/TTSRoad/upgrade-sentinel"
printf 'keep download\n' > "$XDG_DATA_HOME/TTSRoad/downloads/upgrade-sentinel"

apt-get install -y "$current"
[[ $(dpkg-query -W -f='${Status}' ttsroad) == "install ok installed" ]] || fail "current package is not installed"
dpkg --verify ttsroad
[[ -f $XDG_CONFIG_HOME/TTSRoad/upgrade-sentinel ]] || fail "upgrade removed settings"
[[ -f $XDG_DATA_HOME/TTSRoad/downloads/upgrade-sentinel ]] || fail "upgrade removed downloads"
TTSROAD_SMOKE_TEST=1 TTSROAD_SMOKE_SERVER_URL="$server_url" \
    timeout --signal=TERM --kill-after=5s 40s xvfb-run -a "$install_root/bin/TTSRoad"

apt-get remove -y ttsroad
[[ ! -e $install_root ]] || fail "uninstall left application files under $install_root"
[[ -f $XDG_CONFIG_HOME/TTSRoad/upgrade-sentinel ]] || fail "uninstall removed settings"
[[ -f $XDG_DATA_HOME/TTSRoad/downloads/upgrade-sentinel ]] || fail "uninstall removed downloads"
command -v java >/dev/null && fail "installing the package added a system java command"

request_count=$(grep -Fc 'GET /api/mobile/capabilities' "$request_log")
[[ $request_count -ge 2 ]] || fail "expected both installed revisions to probe the mock server"
echo "Verified install, packaged login-window launch, in-place upgrade, data preservation and uninstall"
