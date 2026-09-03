#!/usr/bin/env bash
#
# Builds the macOS DMG from the shaded JAR via jpackage. Shared by the two
# CI paths so they can never drift: build.yml packages every merge to main
# (uploaded as a workflow artifact), release.yml packages tagged releases.
#
# Usage:
#   scripts/package-dmg.sh <app-version-label> [cfbundle-version]
#
#   app-version-label — human-readable version passed to the app via
#                       -Dapp.version (e.g. "1.0.0" or "1.0.0-dev-abc1234")
#   cfbundle-version  — numeric x.y.z for macOS CFBundleVersion, major >= 1
#                       ("The first number in an app-version cannot be zero
#                       or negative"). Defaults to the label, which only works
#                       for plain x.y.z labels — dev builds must pass this
#                       explicitly.
#
# Expects `mvn package` to have produced the shaded JAR already.
#
# --enable-native-access=ALL-UNNAMED (here and in the .deb/.msi scripts):
# JavaFX loads its native libraries from the classpath's unnamed module, which
# JDK 24+ reports as a "restricted method" warning on every launch (JEP 472)
# and a later release will refuse outright. Granting it up front keeps the
# packaged app one JDK bump away from a startup failure.
#
# Code signing (optional, off by default): when MACOS_SIGN_IDENTITY is set,
# jpackage signs the .app with that Developer ID Application identity (which
# must already be in an unlocked keychain — the release workflow imports it).
# Without it the DMG is unsigned exactly as before. Notarization/stapling of
# the finished DMG is a separate CI step. See docs/SIGNING.md.
#
set -euo pipefail

VERSION="${1:?usage: $0 <app-version-label> [cfbundle-version]}"
MAC_VERSION="${2:-${VERSION}}"

if ! [[ "${MAC_VERSION}" =~ ^[1-9][0-9]*(\.[0-9]+){0,2}$ ]]; then
    echo "[package-dmg] CFBundle version '${MAC_VERSION}' is not a valid" >&2
    echo "  numeric x.y.z with a non-zero major — pass it explicitly as \$2" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

JAR_NAME="vless-client-1.0.0-SNAPSHOT.jar"
if [[ ! -f "target/${JAR_NAME}" ]]; then
    echo "[package-dmg] missing target/${JAR_NAME} — run 'mvn package' first" >&2
    exit 1
fi

# The module list is shared with the other packaging scripts through
# scripts/runtime-modules.txt: one module per line, '#' starts a comment.
RUNTIME_MODULES="$(awk '{ sub(/#.*/, ""); if ($1 != "") { printf "%s%s", sep, $1; sep = "," } }' \
    "${REPO_ROOT}/scripts/runtime-modules.txt")"
if [[ -z "${RUNTIME_MODULES}" ]]; then
    echo "[package-dmg] scripts/runtime-modules.txt lists no modules" >&2
    exit 1
fi

# Without --add-modules jpackage links every module that exports an API, so
# the installer carries a ~290 MB runtime for an app that uses a dozen of
# them. --jlink-options REPLACES jpackage's defaults rather than adding to
# them, so the four it would have passed are repeated here.
#
# Deliberately WITHOUT --compress: every installer is itself a compressed
# archive, and a pre-compressed lib/modules is one the DMG's zlib, the MSI's
# cabinet and the .deb's zstd can no longer squeeze. Measured on the same jar:
# the DMG went 86.6 MB -> 91.8 MB with --compress=zip-6, and the amd64 .deb
# 78.8 MB -> 86.9 MB. Compression only helps a runtime that ships loose.
JLINK_OPTIONS="--strip-native-commands --strip-debug --no-man-pages --no-header-files"

# Stage just the shaded jar (not the original-*.jar the shade plugin
# leaves alongside it).
rm -rf staging dist
mkdir -p staging
cp "target/${JAR_NAME}" staging/

# Optional signing args, appended only when an identity is configured, so the
# default (unsigned) invocation is byte-for-byte unchanged.
SIGN_ARGS=()
if [[ -n "${MACOS_SIGN_IDENTITY:-}" ]]; then
    echo "[package-dmg] signing with identity: ${MACOS_SIGN_IDENTITY}"
    SIGN_ARGS=(--mac-sign --mac-signing-key-user-name "${MACOS_SIGN_IDENTITY}")
    [[ -n "${MACOS_SIGN_KEYCHAIN:-}" ]] \
        && SIGN_ARGS+=(--mac-signing-keychain "${MACOS_SIGN_KEYCHAIN}")
fi

jpackage \
    --type dmg \
    --name "Tunl" \
    --app-version "${MAC_VERSION}" \
    --input staging \
    --main-jar "${JAR_NAME}" \
    --main-class com.vlessclient.app.Launcher \
    --icon src/main/resources/icons/app-icon.icns \
    --dest dist \
    --mac-package-name "Tunl" \
    --java-options "-Dapp.version=${VERSION}" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --add-modules "${RUNTIME_MODULES}" \
    --jlink-options "${JLINK_OPTIONS}" \
    "${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}" \
    --verbose

# Normalise the file name. jpackage names the DMG after --name
# ("Tunl-<v>.dmg"); rename it to the lowercase, underscore-versioned form the
# .deb and .msi share so all three installers use one scheme.
ASSET="dist/tunl_${MAC_VERSION}.dmg"
mv dist/*.dmg "${ASSET}"

echo "[package-dmg] built: ${ASSET} (app-version=${VERSION}, CFBundleVersion=${MAC_VERSION})"
