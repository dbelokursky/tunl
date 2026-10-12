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
# Expects `./mvnw package` to have produced the shaded JAR already; a JAR built
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
    echo "[package-linux] no target/vless-client-*.jar — run './mvnw package' first" >&2
    exit 1
elif [[ ${#JARS[@]} -gt 1 ]]; then
    echo "[package-linux] expected exactly one target/vless-client-*.jar, found ${#JARS[@]}:" >&2
    printf '  %s\n' "${JARS[@]}" >&2
    echo "  Run './mvnw clean package' so only the current build's jar remains." >&2
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

# The launcher's JVM options (heap sizing) are shared the same way through
# scripts/java-options.txt, one option per line; each becomes its own
# --java-options, which is how jpackage writes one line of tunl.cfg per option.
JAVA_OPTIONS=()
while IFS= read -r option; do
    JAVA_OPTIONS+=(--java-options "${option}")
done < <(awk '{ sub(/#.*/, ""); gsub(/^[[:space:]]+|[[:space:]]+$/, ""); if ($0 != "") print }' \
    "${REPO_ROOT}/scripts/java-options.txt")
if [[ ${#JAVA_OPTIONS[@]} -eq 0 ]]; then
    echo "[package-linux] scripts/java-options.txt lists no options" >&2
    exit 1
fi

# Stage just the shaded jar (not the original-*.jar the shade plugin
# leaves alongside it), and the licence files that install beside it.
rm -rf staging dist
mkdir -p staging
cp "${JAR_PATH}" staging/
"${REPO_ROOT}/scripts/stage-licenses.sh" staging

# Per-user data lives under XDG paths at runtime; the package itself installs
# to /opt/tunl with a menu entry and launcher symlink.
#
# libsecret-tools is what seals the credentials: secret-tool talks to the
# desktop's keyring. jpackage only lists the libraries the runtime links, and
# without secret-tool the credentials went into the JSON files while Settings
# said they were in the keychain. It is in Debian's main and Ubuntu's
# universe, which a desktop install enables.
#
# The rest is what JavaFX's own native libraries link: glass (GTK 3),
# the fonts (FreeType, fontconfig, Pango) and the ES2 pipeline (libGL,
# libXxf86vm). They live inside the jar and are unpacked at run time, so
# jpackage never sees them; the package installed on a system without GTK 3
# and the app died in toolkit start-up, while the CI smoke passed because the
# runner had the libraries put in by hand first. The t64 name is the build
# host's (Ubuntu 24.04); the rewrite below adds the old one.
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
    --linux-package-deps "libsecret-tools, libgtk-3-0t64, libfreetype6, libfontconfig1, libpangoft2-1.0-0, libgl1, libxxf86vm1" \
    --vendor "Tunl" \
    --java-options "-Dapp.version=${VERSION}" \
    --java-options "-Djava.awt.headless=false" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    "${JAVA_OPTIONS[@]}" \
    --add-modules "${RUNTIME_MODULES}" \
    --jlink-options "${JLINK_OPTIONS}" \
    --verbose

# jpackage builds the package with dpkg-deb's default compression, which is
# zstd on the Ubuntu runners. xz packs the same files smaller, and every dpkg
# that can install Tunl reads it. The rebuild keeps the files, their modes
# and the maintainer scripts, all owned by root as jpackage had them.
#
# The rebuild also widens Depends. jpackage names the libraries the runtime
# links by the build host's packages, and Ubuntu 24.04 renamed some of them
# for the 64-bit time_t transition (libasound2 became libasound2t64). Debian
# 12 and Ubuntu 22.04 have only the old names, so each renamed package also
# accepts its old one: "libasound2t64 | libasound2".
DEB="$(ls dist/*.deb)"
REPACK="$(mktemp -d)"
dpkg-deb --raw-extract "${DEB}" "${REPACK}/tree"
sed -i -E '/^Depends:/ s/([a-z0-9][a-z0-9.+-]*)t64(,|[[:space:]]*$)/\1t64 | \1\2/g' \
    "${REPACK}/tree/DEBIAN/control"

# Depends also names the oldest glibc the package runs on. jpackage writes a
# bare "libc6", but JavaFX's libraries, inside the jar where it never looks,
# can need a newer one than the runtime does: the linux-aarch64 build of
# JavaFX 27 links glibc 2.38 symbols, so the arm64 package installed cleanly
# on Debian 12 and Ubuntu 22.04 (2.36, 2.35) and the app died loading its
# window toolkit. With the version declared, apt refuses it there instead.
# The minimum is read from the files themselves: the highest GLIBC_x.y
# symbol version the launcher, the runtime or a JavaFX library requires.
command -v objdump >/dev/null \
    || { echo "[package-linux] objdump (binutils) is needed to read the glibc the package needs" >&2; exit 1; }
GLIBC_SCAN="$(mktemp -d)"
unzip -qq -o "${REPACK}"/tree/opt/tunl/lib/app/vless-client-*.jar 'lib*.so' -d "${GLIBC_SCAN}"
GLIBC_MIN="$(find "${REPACK}/tree/opt/tunl" "${GLIBC_SCAN}" -type f \
        \( -name '*.so' -o -path '*/bin/*' \) -print0 \
    | { xargs -0 objdump -T 2>/dev/null || true; } \
    | grep -o 'GLIBC_[0-9][0-9.]*' | sed 's/^GLIBC_//' | sort -uV | tail -n 1)"
rm -rf "${GLIBC_SCAN}"
[[ "${GLIBC_MIN}" =~ ^2\.[0-9]+(\.[0-9]+)?$ ]] \
    || { echo "[package-linux] could not read the glibc the package needs (got '${GLIBC_MIN}')" >&2; exit 1; }
sed -i -E "/^Depends:/ s/\$/, libc6 (>= ${GLIBC_MIN})/" "${REPACK}/tree/DEBIAN/control"
echo "[package-linux] $(grep '^Depends:' "${REPACK}/tree/DEBIAN/control")"

# tunl:// links (DeepLinks): the menu entry names Tunl's URL scheme and hands
# the link to the launcher (%u); jpackage has no option for either. The
# maintainer script then refreshes the desktop database, where the system has
# the tool, so xdg-open finds the entry at once rather than after a login.
shopt -s nullglob
desktop_entries=("${REPACK}"/tree/opt/tunl/lib/*.desktop)
shopt -u nullglob
[[ ${#desktop_entries[@]} -eq 1 ]] \
    || { echo "[package-linux] expected one .desktop entry, found ${#desktop_entries[@]}" >&2; exit 1; }
sed -i -E '/^Exec=/ { / %[uU]$/! s/$/ %u/ }' "${desktop_entries[0]}"
# jpackage writes "MimeType=", empty without file associations: one line with
# whatever it names and the scheme replaces it.
mime_types="$(sed -n 's/^MimeType=//p' "${desktop_entries[0]}" | tr -d '\n')"
mime_types="${mime_types%;}"
sed -i '/^MimeType=/d' "${desktop_entries[0]}"
echo "MimeType=${mime_types:+${mime_types};}x-scheme-handler/tunl;" >> "${desktop_entries[0]}"
POSTINST="${REPACK}/tree/DEBIAN/postinst"
if [[ -f "${POSTINST}" ]]; then
    # Before the script's closing "exit 0", or at its end when it has none.
    awk -v line='command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || true' '
        { lines[NR] = $0 }
        END {
            at = 0
            for (i = NR; i >= 1; i--) if (lines[i] == "exit 0") { at = i; break }
            for (i = 1; i <= NR; i++) { if (i == at) print line; print lines[i] }
            if (at == 0) print line
        }' "${POSTINST}" > "${REPACK}/postinst"
    cat "${REPACK}/postinst" > "${POSTINST}"
fi
echo "[package-linux] $(grep -E '^(Exec|MimeType)=' "${desktop_entries[0]}" | tr '\n' ' ')"

# jpackage's copyright file says "License: Unknown" for everything. This one
# gives each part of the package its licence, in the machine-readable format
# (the last Files stanza matching a path applies), and points at the texts
# stage-licenses.sh and jlink installed. It used to have one "Files: *"
# stanza, Apache-2.0, with sing-box only in a Comment: a scanner read the
# whole package as Apache-2.0, the GPL-2.0 runtime and the GPL-3.0 core in it
# too. linux-deb-smoke.sh checks the licence it gives the runtime and the jar.
COPYRIGHT="${REPACK}/tree/opt/tunl/share/doc/copyright"
[[ -f "${COPYRIGHT}" ]] || { echo "[package-linux] jpackage wrote no ${COPYRIGHT#"${REPACK}/tree"}" >&2; exit 1; }
cat > "${COPYRIGHT}" <<'COPYRIGHT_EOF'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Tunl
Upstream-Contact: Dmitry Belokursky <dbelokursky@gmail.com>
Source: https://github.com/dbelokursky/tunl
Comment: The paths below are relative to /opt/tunl, where the package
 installs. /opt/tunl/lib/app/NOTICE lists every bundled component with its
 licence, and says where the corresponding source of the bundled sing-box is.

Files: *
Copyright: 2026 Dmitry Belokursky
License: Apache-2.0

Files: bin/tunl lib/libapplauncher.so lib/runtime/*
Copyright: Oracle and/or its affiliates, and the other contributors to OpenJDK
License: GPL-2 with Classpath exception
Comment: The jpackage launcher, and the Java runtime jlink made from Azul
 Zulu, a build of OpenJDK. lib/runtime/legal/ holds, module by module, the
 notices of the third-party code the runtime includes.

Files: lib/app/vless-client-*.jar
Copyright: 2026 Dmitry Belokursky
 2022 nekohasekai <contact-sagernet@sekai.icu>
 Oracle and/or its affiliates
 FasterXML, LLC and contributors
 MaxMind, Inc.
 QOS.ch Sarl
License: Apache-2.0 and GPL-3+ and GPL-2 with Classpath exception and Expat, and EPL-1.0 or LGPL-2.1
Comment: The application jar holds Tunl's classes (Apache-2.0); the sing-box
 binary, native/*/sing-box (GPL-3+, with its notice native/*/LICENSE);
 OpenJFX's classes and native libraries (GPL-2 with Classpath exception);
 Jackson and the MaxMind DB Reader (Apache-2.0); SLF4J (Expat); and Logback
 (EPL-1.0 or LGPL-2.1, at the user's choice).

License: Apache-2.0
 The full text is in /opt/tunl/lib/app/LICENSE.

License: GPL-3+
 sing-box is licensed under the GNU General Public License, version 3 or (at
 your option) any later version. Its notice is in
 /opt/tunl/lib/app/sing-box-LICENSE, and the full text of the GPL-3.0 in
 /opt/tunl/lib/app/sing-box-GPL-3.0.txt.

License: GPL-2 with Classpath exception
 The full text, with the exception, is in
 /opt/tunl/lib/runtime/legal/java.base/LICENSE.

License: Expat
 The full text is at https://www.slf4j.org/license.html.

License: EPL-1.0
 The full text is at https://www.eclipse.org/legal/epl-v10.html.

License: LGPL-2.1
 The full text is at https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.
COPYRIGHT_EOF
dpkg-deb --root-owner-group -Zxz --build "${REPACK}/tree" "${DEB}"
rm -rf "${REPACK}"

echo "[package-linux] built: ${DEB} (app-version=${VERSION}, deb Version=${DEB_VERSION})"
