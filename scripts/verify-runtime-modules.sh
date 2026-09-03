#!/usr/bin/env bash
#
# Proves that a runtime linked from scripts/runtime-modules.txt can still do
# the things the app needs but no test covers.
#
# The unit suite runs on a full JDK, so a module missing from the packaged
# runtime is invisible to it: the app would start and then fail at the first
# Russian date, TLS handshake or MCP request. This script links exactly the
# listed modules, then runs a probe against that image.
#
# Usage: scripts/verify-runtime-modules.sh [output-dir]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-${REPO_ROOT}/target/runtime-probe}"
MODULES="$(awk '{ sub(/#.*/, ""); if ($1 != "") { printf "%s%s", sep, $1; sep = "," } }' \
    "${REPO_ROOT}/scripts/runtime-modules.txt")"

if [[ -z "${MODULES}" ]]; then
    echo "[verify-runtime] no modules listed in scripts/runtime-modules.txt" >&2
    exit 1
fi

echo "[verify-runtime] linking: ${MODULES}"
rm -rf "${OUT_DIR}"
# Deliberately WITHOUT --strip-native-commands, unlike the packaged runtime:
# the probe needs this image's own bin/java to run on.
jlink --add-modules "${MODULES}" \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output "${OUT_DIR}/image"

mkdir -p "${OUT_DIR}/classes"
javac -d "${OUT_DIR}/classes" "${REPO_ROOT}/scripts/RuntimeProbe.java"
"${OUT_DIR}/image/bin/java" -cp "${OUT_DIR}/classes" RuntimeProbe

echo "[verify-runtime] image size: $(du -sh "${OUT_DIR}/image" | cut -f1)"
