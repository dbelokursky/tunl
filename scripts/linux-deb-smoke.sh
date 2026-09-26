#!/usr/bin/env bash
#
# Installs the Tunl .deb on this host, checks the installed payload, runs the
# packaged launcher under a virtual X server until the app logs its own
# startup line, then uninstalls it and checks the cleanup. Linux twin of
# windows-msi-smoke.ps1: build.yml runs it on PRs and every merge to main,
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

# Desktop installations provide this XDG menu directory, but the headless
# runner does not. jpackage's maintainer scripts call xdg-desktop-menu for
# both install and removal, which otherwise exit 3 before we reach the app.
# Supply the desktop prerequisite rather than ignoring a dpkg failure.
sudo install -d -m 0755 /usr/share/desktop-directories

echo "[linux-deb-smoke] installing ${DEB}"
installed=1
# dpkg cannot fetch a dependency the host lacks: it leaves the package
# unconfigured and exits 1, and apt-get -f then installs the dependency and
# finishes the configuration. The payload checks below catch anything else.
if ! sudo dpkg -i "${DEB}"; then
    sudo apt-get install -f -y
fi

# The keyring client the credentials are sealed with comes with the package:
# jpackage's own dependency list names only the runtime's libraries, and the
# one package-linux.sh adds has to join that list, not replace it.
DEPENDS="$(dpkg-deb -f "${DEB}" Depends)"
secret_tools='(^|[ ,])libsecret-tools([ ,]|$)'
libc='(^|[ ,])libc6([ ,(]|$)'
[[ "${DEPENDS}" =~ ${secret_tools} ]] \
    || fail "the package does not depend on libsecret-tools: ${DEPENDS}"
[[ "${DEPENDS}" =~ ${libc} ]] \
    || fail "the package lost jpackage's own dependencies: ${DEPENDS}"
command -v secret-tool >/dev/null 2>&1 \
    || fail "secret-tool is not installed after installing the package"

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
# tunl:// links: package-linux.sh names the scheme in the menu entry and has
# the launcher take the link.
grep -qE '^MimeType=(.*;)?x-scheme-handler/tunl;' "${desktop_entries[0]}" \
    || fail "${desktop_entries[0]} does not register the tunl:// scheme: $(grep '^MimeType=' "${desktop_entries[0]}")"
grep -qE '^Exec=.* %u$' "${desktop_entries[0]}" \
    || fail "${desktop_entries[0]} does not pass the link to the launcher (%u)"
if command -v update-desktop-database >/dev/null 2>&1 && command -v xdg-mime >/dev/null 2>&1; then
    handler="$(xdg-mime query default x-scheme-handler/tunl 2>/dev/null || true)"
    [[ "${handler}" == *tunl*.desktop ]] \
        || fail "xdg-mime names '${handler}' as the tunl:// handler, not Tunl's entry"
    echo "[linux-deb-smoke] tunl:// links go to ${handler}"
fi

grep -qF -- "-Dapp.version=${EXPECTED_VERSION}" "${LAUNCHER_CFG}" \
    || fail "${LAUNCHER_CFG} does not carry -Dapp.version=${EXPECTED_VERSION}"

# Every option in scripts/java-options.txt must reach the launcher: a
# packaging change that lost them would ship the RAM-sized default heap again,
# and nothing else here would notice.
JAVA_OPTIONS_FILE="$(dirname "$0")/java-options.txt"
[[ -s "${JAVA_OPTIONS_FILE}" ]] || fail "missing ${JAVA_OPTIONS_FILE}"
while IFS= read -r option; do
    grep -qxF -- "java-options=${option}" "${LAUNCHER_CFG}" \
        || fail "${LAUNCHER_CFG} does not carry ${option} from scripts/java-options.txt"
done < <(awk '{ sub(/#.*/, ""); gsub(/^[[:space:]]+|[[:space:]]+$/, ""); if ($0 != "") print }' \
    "${JAVA_OPTIONS_FILE}")

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

# A tunl:// link reaches a running copy: a second launch with the link hands
# it over (SingleInstance) and exits, and the running copy opens it in the
# Subscriptions page's form. Through xdg-open when the desktop database names
# Tunl's entry, as a browser would; straight through the launcher otherwise.
echo "[linux-deb-smoke] handing a tunl:// link to a running copy"
LINK='tunl://install-config?url=https%3A%2F%2Fsub.example%2Fsmoke'
starts_before="$(grep -cF 'Tunl started' "${APP_LOG}" 2>/dev/null || true)"
set +e
# shellcheck disable=SC2016  # the inner script expands its own arguments
JAVA_TOOL_OPTIONS='-Dprism.order=sw' \
    xvfb-run -a -s '-screen 0 1280x800x24' \
    timeout --kill-after=15 90 bash -c '
        launcher=$1; link=$2; log=$3; before=$4; out=$5
        "${launcher}" >> "${out}" 2>&1 &
        app=$!
        for _ in $(seq 1 60); do
            starts=$(grep -cF "Tunl started" "${log}" 2>/dev/null)
            [ "${starts:-0}" -gt "${before}" ] && break
            sleep 1
        done
        if command -v xdg-open >/dev/null 2>&1 \
                && [ -n "$(xdg-mime query default x-scheme-handler/tunl 2>/dev/null)" ]; then
            xdg-open "${link}"
        else
            "${launcher}" "${link}"
        fi
        opened=1
        for _ in $(seq 1 30); do
            grep -qF "Opening a tunl link" "${log}" 2>/dev/null && { opened=0; break; }
            sleep 1
        done
        kill "${app}" 2>/dev/null
        wait "${app}" 2>/dev/null
        exit "${opened}"
    ' _ "${LAUNCHER}" "${LINK}" "${APP_LOG}" "${starts_before:-0}" "${STDOUT_LOG}"
link_status=$?
set -e
if [[ ${link_status} -ne 0 ]]; then
    dump_logs
    fail "the running copy did not open the tunl:// link (status ${link_status})"
fi
echo "[linux-deb-smoke] the running copy opened the tunl:// link"

installed=0
remove_package
echo "[linux-deb-smoke] install, payload, launch and uninstall checks passed"
