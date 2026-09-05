#!/usr/bin/env bash
#
# Mounts the Tunl DMG and inspects the app bundle inside it: launcher
# present and executable, embedded runtime, launcher config carrying the
# expected app version, exactly one application jar with this host's darwin
# core in it, and the CFBundleVersion jpackage stamped. macOS counterpart to
# linux-deb-smoke.sh and windows-msi-smoke.ps1, deliberately without a
# launch: a JavaFX app on a CI Mac can stop on a Keychain or Gatekeeper
# prompt that nothing dismisses, so this stays a mount-and-inspect check.
#
# Usage:
#   scripts/macos-dmg-smoke.sh <dmg> <expected-app-version> [expected-cfbundle-version]
#
#   dmg                        — the .dmg built by scripts/package-dmg.sh
#   expected-app-version       — the -Dapp.version label Tunl.cfg must carry
#   expected-cfbundle-version  — the CFBundleVersion Info.plist must carry;
#                                defaults to expected-app-version, as in
#                                package-dmg.sh
#
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "usage: $0 <dmg> <expected-app-version> [expected-cfbundle-version]" >&2
    exit 2
fi
DMG="$1"
EXPECTED_VERSION="$2"
EXPECTED_BUNDLE_VERSION="${3:-$2}"
if [[ ! -f "${DMG}" ]]; then
    echo "[macos-dmg-smoke] no such file: ${DMG}" >&2
    exit 1
fi

fail() {
    echo "[macos-dmg-smoke] $*" >&2
    exit 1
}

MOUNT="$(mktemp -d "${TMPDIR:-/tmp}/tunl-dmg.XXXXXX")"
attached=0
detach() {
    local status=$?
    trap - EXIT
    if [[ ${attached} -eq 1 ]]; then
        hdiutil detach "${MOUNT}" -quiet || hdiutil detach "${MOUNT}" -force -quiet || true
    fi
    rmdir "${MOUNT}" 2>/dev/null || true
    exit "${status}"
}
trap detach EXIT

echo "[macos-dmg-smoke] mounting ${DMG} at ${MOUNT}"
hdiutil attach "${DMG}" -mountpoint "${MOUNT}" -nobrowse -readonly -noautoopen -quiet
attached=1

# jpackage's macOS layout for `--name Tunl`.
APP="${MOUNT}/Tunl.app"
LAUNCHER="${APP}/Contents/MacOS/Tunl"
LAUNCHER_CFG="${APP}/Contents/app/Tunl.cfg"
RUNTIME_JVM="${APP}/Contents/runtime/Contents/Home/lib/server/libjvm.dylib"
INFO_PLIST="${APP}/Contents/Info.plist"

[[ -d "${APP}" ]] || fail "no Tunl.app on the DMG"
for required in "${LAUNCHER}" "${LAUNCHER_CFG}" "${RUNTIME_JVM}" "${INFO_PLIST}"; do
    [[ -e "${required}" ]] || fail "app bundle is missing: ${required#"${MOUNT}"/}"
done
[[ -x "${LAUNCHER}" ]] || fail "Tunl.app/Contents/MacOS/Tunl is not executable"

shopt -s nullglob
jars=("${APP}"/Contents/app/vless-client-*.jar)
shopt -u nullglob
[[ ${#jars[@]} -eq 1 ]] \
    || fail "expected one application jar in Contents/app, found ${#jars[@]}"

grep -qF -- "-Dapp.version=${EXPECTED_VERSION}" "${LAUNCHER_CFG}" \
    || fail "Tunl.cfg does not carry -Dapp.version=${EXPECTED_VERSION}"

bundle_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${INFO_PLIST}")"
[[ "${bundle_version}" == "${EXPECTED_BUNDLE_VERSION}" ]] \
    || fail "CFBundleVersion is '${bundle_version}', expected '${EXPECTED_BUNDLE_VERSION}'"

# The jar must carry the darwin core for this host's architecture — the one
# scripts/bundle-singbox.sh chose from uname, and the only one a DMG built
# on this host can run.
case "$(uname -m)" in
    arm64)  core_arch=arm64 ;;
    x86_64) core_arch=amd64 ;;
    *)      fail "unsupported host arch: $(uname -m)" ;;
esac
core_entry="native/darwin-${core_arch}/sing-box"
if command -v jar >/dev/null 2>&1; then
    entries="$(jar tf "${jars[0]}")"
else
    entries="$(unzip -Z1 "${jars[0]}")"
fi
grep -qxF "${core_entry}" <<<"${entries}" \
    || fail "${jars[0]##*/} does not contain ${core_entry}"

echo "[macos-dmg-smoke] Tunl.app verified: launcher, runtime," \
    "app.version=${EXPECTED_VERSION}, CFBundleVersion=${bundle_version}," \
    "${core_entry} in ${jars[0]##*/}"
