#!/usr/bin/env bash
#
# Installs the Tunl .deb on this host, checks the installed payload, runs the
# packaged launcher under a virtual X server until the app logs its own
# startup line, then uninstalls it and checks the cleanup. Linux twin of
# windows-msi-smoke.ps1: build.yml runs it on every merge to main,
# release.yml on every tagged release. A packaging smoke, not a TUN/network
# test.
#
# Usage:
#   scripts/linux-deb-smoke.sh <deb> <expected-app-version>
#
#   deb                  — the .deb built by scripts/package-linux.sh
#   expected-app-version — the -Dapp.version label the installed launcher
#                          config must carry ("1.2.0", "1.0.0-dev-abc1234")
#
# Needs passwordless sudo for dpkg, xvfb-run (apt-get install xvfb xauth) and
# the GTK/X libraries JavaFX loads at runtime — the set scripts/linux-qa.sh
# installs. The app runs as the calling user, so its log lands in that
# user's XDG data dir, the same place linux-qa.sh greps "Tunl started" from.
# Leaves the package removed and the user's data dir in place.
#
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <deb> <expected-app-version>" >&2
    exit 2
fi
DEB="$1"
EXPECTED_VERSION="$2"
if [[ ! -f "${DEB}" ]]; then
    echo "[linux-deb-smoke] no such file: ${DEB}" >&2
    exit 1
fi

# jpackage's Linux layout for `--name tunl --linux-package-name tunl`.
APP_ROOT=/opt/tunl
LAUNCHER="${APP_ROOT}/bin/tunl"
LAUNCHER_CFG="${APP_ROOT}/lib/app/tunl.cfg"
RUNTIME_JVM="${APP_ROOT}/lib/runtime/lib/server/libjvm.so"
# Where the app writes its log on Linux (LinuxPlatformPaths + logback.xml).
APP_LOG="${XDG_DATA_HOME:-${HOME}/.local/share}/vless-client/logs/tunl.log"
STDOUT_LOG="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/tunl-deb-smoke.log"
# How long the app gets to log its startup line before it is stopped. The
# line follows the main window's show(), a few seconds in on a runner.
LAUNCH_SECONDS=30

for tool in sudo dpkg xvfb-run timeout; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "[linux-deb-smoke] ${tool} is required" >&2
        exit 1
    fi
done

fail() {
    echo "[linux-deb-smoke] $*" >&2
    exit 1
}

dump_logs() {
    if [[ -s "${STDOUT_LOG}" ]]; then
        echo "--- launcher stdout/stderr (${STDOUT_LOG}) ---"
        tail -n 50 "${STDOUT_LOG}"
    fi
    if [[ -f "${APP_LOG}" ]]; then
        echo "--- app log (${APP_LOG}) ---"
        tail -n 50 "${APP_LOG}"
    else
        echo "--- app log ${APP_LOG} was never written ---"
    fi
}

remove_package() {
    echo "[linux-deb-smoke] uninstalling tunl"
    sudo dpkg --remove tunl || fail "dpkg --remove failed"
    [[ ! -e "${LAUNCHER}" ]] || fail "${LAUNCHER} remains after uninstall"
    # shellcheck disable=SC2016  # dpkg-query's own format string
    if dpkg-query -W -f='${Status}' tunl 2>/dev/null | grep -qx 'install ok installed'; then
        fail "dpkg still lists tunl as installed"
    fi
}

# On failure, remove whatever got installed; the check that failed has
# already reported itself, so this is best effort and keeps the status.
installed=0
on_exit() {
    local status=$?
    trap - EXIT
    if [[ ${status} -ne 0 && ${installed} -eq 1 ]]; then
        sudo dpkg --remove tunl || true
    fi
    exit "${status}"
}
trap on_exit EXIT

echo "[linux-deb-smoke] installing ${DEB}"
installed=1
# dpkg cannot fetch a dependency the host lacks: it leaves the package
# unconfigured and exits 1, and apt-get -f then installs the dependency and
# finishes the configuration. The payload checks below catch anything else.
if ! sudo dpkg -i "${DEB}"; then
    sudo apt-get install -f -y
fi

for required in "${LAUNCHER}" "${LAUNCHER_CFG}" "${RUNTIME_JVM}"; do
    [[ -e "${required}" ]] || fail "installed payload is missing: ${required}"
done
[[ -x "${LAUNCHER}" ]] || fail "${LAUNCHER} is not executable"

shopt -s nullglob
jars=("${APP_ROOT}"/lib/app/vless-client-*.jar)
desktop_entries=("${APP_ROOT}"/lib/*.desktop)
shopt -u nullglob
[[ ${#jars[@]} -eq 1 ]] \
    || fail "expected one application jar in ${APP_ROOT}/lib/app, found ${#jars[@]}"
[[ ${#desktop_entries[@]} -ge 1 ]] \
    || fail "no .desktop entry in ${APP_ROOT}/lib — the --linux-shortcut menu entry is missing"

grep -qF -- "-Dapp.version=${EXPECTED_VERSION}" "${LAUNCHER_CFG}" \
    || fail "${LAUNCHER_CFG} does not carry -Dapp.version=${EXPECTED_VERSION}"

# The jar must carry the core for this package's architecture: without it
# the app starts and the first Connect fails.
arch="$(dpkg --print-architecture)"
core_entry="native/linux-${arch}/sing-box"
if command -v jar >/dev/null 2>&1; then
    entries="$(jar tf "${jars[0]}")"
elif command -v unzip >/dev/null 2>&1; then
    entries="$(unzip -Z1 "${jars[0]}")"
else
    fail "need jar or unzip to inspect ${jars[0]}"
fi
grep -qxF "${core_entry}" <<<"${entries}" \
    || fail "${jars[0]} does not contain ${core_entry}"
echo "[linux-deb-smoke] installed payload verified at ${APP_ROOT}"

echo "[linux-deb-smoke] launching ${LAUNCHER} under Xvfb for ${LAUNCH_SECONDS}s"
rm -f "${APP_LOG}"
: > "${STDOUT_LOG}"
# Software rendering, as in scripts/linux-qa.sh: Xvfb has no GPU. The option
# goes through JAVA_TOOL_OPTIONS because the JVM itself reads that, so it
# reaches the embedded runtime the jpackage launcher starts. timeout's 124
# is the healthy outcome: the app was still running when its time was up.
set +e
JAVA_TOOL_OPTIONS='-Dprism.order=sw' \
    xvfb-run -a -s '-screen 0 1280x800x24' \
    timeout --kill-after=15 "${LAUNCH_SECONDS}" "${LAUNCHER}" > "${STDOUT_LOG}" 2>&1
launch_status=$?
set -e
if [[ ${launch_status} -ne 124 ]]; then
    dump_logs
    fail "the app exited on its own with status ${launch_status} within ${LAUNCH_SECONDS}s"
fi
if ! grep -qF 'Tunl started' "${APP_LOG}" 2>/dev/null; then
    dump_logs
    fail "no 'Tunl started' line in ${APP_LOG}"
fi
echo "[linux-deb-smoke] startup line found in ${APP_LOG}"
# Informational, as in linux-qa.sh: a startup ERROR is worth a look, but a
# first run on a bare runner (no proxy schema, no tray) is allowed one.
grep -E 'ERROR' "${APP_LOG}" | tail -n 3 || true

installed=0
remove_package
echo "[linux-deb-smoke] install, payload, launch and uninstall checks passed"
