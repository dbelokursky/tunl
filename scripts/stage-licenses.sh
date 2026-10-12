#!/usr/bin/env bash
#
# Puts the licence files every installer carries into the jpackage input
# directory, beside the jar, so they install with the app (/opt/tunl/lib/app,
# Tunl.app/Contents/app, %LOCALAPPDATA%\Tunl\app):
#
#   LICENSE               Tunl's own licence, Apache-2.0
#   NOTICE                the bundled components and their licences, ending
#                         with where the source of this build's sing-box is
#   sing-box-LICENSE      sing-box's GPL-3.0 notice, from the core's archive
#                         (bundle-singbox.sh puts it beside the binary)
#   sing-box-GPL-3.0.txt  the licence that notice refers to
#
# The installers used to carry the GPL-3.0 sing-box binary with none of
# these: no copy of the GPL (section 4) and no directions to the
# corresponding source (section 6(d)). package-windows.ps1 does the same in
# PowerShell; the smoke scripts check the installed files.
#
# Usage: scripts/stage-licenses.sh <staging-dir>
# Expects `./mvnw package` to have run, which bundled the core.
#
set -euo pipefail

STAGING="${1:?usage: $0 <staging-dir>}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS_FILE="${REPO_ROOT}/src/main/resources/singbox.properties"

fail() {
    echo "[stage-licenses] $*" >&2
    exit 1
}

prop() {
    { grep -E "^[[:space:]]*$1[[:space:]]*=" "${PROPS_FILE}" || true; } \
        | tail -n 1 \
        | cut -d= -f2- \
        | tr -d '[:space:]'
}

version="$(prop singbox.version)"
release="$(prop singbox.release)"
[[ -n "${version}" ]] || fail "no singbox.version in ${PROPS_FILE}"
if [[ -n "${release}" ]]; then
    source_url="https://github.com/dbelokursky/tunl/releases/tag/${release}"
    built_as="built by this project as ${release}"
    source_what="sing-box's source at that version and the patch"
else
    source_url="https://github.com/SagerNet/sing-box/releases/tag/v${version}"
    built_as="upstream's own build"
    source_what="sing-box's source at that version"
fi

shopt -s nullglob
core_notices=("${REPO_ROOT}"/target/classes/native/*/LICENSE)
shopt -u nullglob
[[ ${#core_notices[@]} -eq 1 ]] \
    || fail "expected one target/classes/native/*/LICENSE from bundle-singbox, found ${#core_notices[@]}; run './mvnw clean package'"

mkdir -p "${STAGING}"
cp "${REPO_ROOT}/LICENSE" "${STAGING}/LICENSE"
cp "${core_notices[0]}" "${STAGING}/sing-box-LICENSE"
cp "${REPO_ROOT}/packaging/sing-box/GPL-3.0.txt" "${STAGING}/sing-box-GPL-3.0.txt"
{
    cat "${REPO_ROOT}/NOTICE"
    printf '\n%s\n\n' '--------------------------------------------------------------------------'
    printf 'The sing-box in this installer is version %s,\n' "${version}"
    printf '%s. It is licensed under the GPL-3.0\n' "${built_as}"
    printf '(sing-box-LICENSE and sing-box-GPL-3.0.txt, beside this file). Its\n'
    printf 'corresponding source, %s, is at\n' "${source_what}"
    printf '\n    %s\n' "${source_url}"
} > "${STAGING}/NOTICE"
echo "[stage-licenses] LICENSE, NOTICE, sing-box-LICENSE, sing-box-GPL-3.0.txt -> ${STAGING} (source: ${source_url})"
