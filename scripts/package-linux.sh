#!/usr/bin/env bash
#
# Builds the Linux .deb from the shaded JAR via jpackage. Linux counterpart
# to package-dmg.sh / package-windows.ps1, shared by the two CI paths so they
# can never drift: build.yml packages merges to main (workflow artifact),
# release.yml packages tagged releases.
#
# Usage:
#   scripts/package-linux.sh <app-version-label> [deb-version]
#
#   app-version-label — human-readable version passed to the app via
#                       -Dapp.version (e.g. "1.0.0" or "1.0.0-dev-abc1234")
#   deb-version       — Debian package Version field. Defaults to the label,
#                       which Debian's permissive version grammar accepts for
#                       both release (x.y.z) and dev labels.
#
# Expects `mvn package` to have produced the shaded JAR already; a JAR built
# on a Linux host carries the Linux JavaFX natives and the bundled linux
# sing-box.
#
set -euo pipefail

VERSION="${1:?usage: $0 <app-version-label> [deb-version]}"
DEB_VERSION="${2:-${VERSION}}"

# Debian version grammar: must start with a digit; alnum plus .+-~ after.
if ! [[ "${DEB_VERSION}" =~ ^[0-9][A-Za-z0-9.+~-]*$ ]]; then
    echo "[package-linux] deb version '${DEB_VERSION}' is not a valid Debian" >&2
    echo "  Version field — pass it explicitly as \$2" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

# Resolve the shaded jar by glob, not by name: the artifact version lives in
# pom.xml, and a bump there must not strand the packagers. The shade plugin
# leaves the unshaded original-vless-client-*.jar next to the shaded one; the
# glob's prefix already skips it, and the filter says so out loud.
shopt -s nullglob
JAR_CANDIDATES=(target/vless-client-*.jar)
shopt -u nullglob
JARS=()
for jar in ${JAR_CANDIDATES[@]+"${JAR_CANDIDATES[@]}"}; do
    [[ "$(basename "${jar}")" == original-* ]] || JARS+=("${jar}")
done
if [[ ${#JARS[@]} -eq 0 ]]; then
    echo "[package-linux] no target/vless-client-*.jar — run 'mvn package' first" >&2
    exit 1
elif [[ ${#JARS[@]} -gt 1 ]]; then
    echo "[package-linux] expected exactly one target/vless-client-*.jar, found ${#JARS[@]}:" >&2
    printf '  %s\n' "${JARS[@]}" >&2
    echo "  Run 'mvn clean package' so only the current build's jar remains." >&2
    exit 1
fi
JAR_PATH="${JARS[0]}"
JAR_NAME="$(basename "${JAR_PATH}")"

# The module list is shared with the other packaging scripts through
# scripts/runtime-modules.txt: one module per line, '#' starts a comment.
RUNTIME_MODULES="$(awk '{ sub(/#.*/, ""); if ($1 != "") { printf "%s%s", sep, $1; sep = "," } }' \
    "${REPO_ROOT}/scripts/runtime-modules.txt")"
if [[ -z "${RUNTIME_MODULES}" ]]; then
    echo "[package-linux] scripts/runtime-modules.txt lists no modules" >&2
    exit 1
fi

# The jlink options are shared the same way through scripts/jlink-options.txt
# (one option per line, '#' starts a comment); the reasoning behind each
# option lives there. Joined with spaces: --jlink-options takes one string.
JLINK_OPTIONS="$(awk '{ sub(/#.*/, ""); gsub(/^[[:space:]]+|[[:space:]]+$/, ""); if ($0 != "") { printf "%s%s", sep, $0; sep = " " } }' \
    "${REPO_ROOT}/scripts/jlink-options.txt")"
if [[ -z "${JLINK_OPTIONS}" ]]; then
    echo "[package-linux] scripts/jlink-options.txt lists no options" >&2
    exit 1
fi

# Stage just the shaded jar (not the original-*.jar the shade plugin
# leaves alongside it).
rm -rf staging dist
mkdir -p staging
cp "${JAR_PATH}" staging/

# Per-user data lives under XDG paths at runtime; the package itself installs
# to /opt/tunl with a menu entry and launcher symlink.
jpackage \
    --type deb \
    --name tunl \
    --app-version "${DEB_VERSION}" \
    --input staging \
    --main-jar "${JAR_NAME}" \
    --main-class com.vlessclient.app.Launcher \
    --icon src/main/resources/icons/app-icon-512.png \
    --dest dist \
    --linux-package-name tunl \
    --linux-menu-group Network \
    --linux-shortcut \
    --linux-deb-maintainer "dbelokursky@gmail.com" \
    --vendor "Tunl" \
    --java-options "-Dapp.version=${VERSION}" \
    --java-options "-Djava.awt.headless=false" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --add-modules "${RUNTIME_MODULES}" \
    --jlink-options "${JLINK_OPTIONS}" \
    --verbose

echo "[package-linux] built: $(ls dist/*.deb) (app-version=${VERSION}, deb Version=${DEB_VERSION})"
