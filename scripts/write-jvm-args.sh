#!/usr/bin/env bash
#
# Writes a Java argfile with the JVM options that must include literal
# spaces (commons-exec splits plain <option> values on whitespace, but
# @argfile is a single token). Invoked by exec-maven-plugin in the
# generate-resources phase.
#
# Arguments:
#   $1 — output file path (e.g. target/jvm-args.txt)
#
set -euo pipefail

OUT="${1:?usage: $0 <out_file>}"
mkdir -p "$(dirname "${OUT}")"

# The Dock/app-name options exist only on macOS — an unknown -Xdock option
# stops the JVM from starting on Linux.
# --enable-native-access=ALL-UNNAMED is for every platform: JavaFX loads its
# native libraries from the classpath's unnamed module, which JDK 24+ (JEP
# 472) reports as a restricted-method warning and will eventually refuse. The
# packaging scripts pass the same flag to jpackage.
if [[ "$(uname -s)" == "Darwin" ]]; then
    cat > "${OUT}" <<'ARGFILE'
-Xdock:name="Tunl"
-Dapple.awt.application.name="Tunl"
-Dcom.apple.mrj.application.apple.menu.about.name="Tunl"
-Dvless.log.level=DEBUG
--enable-native-access=ALL-UNNAMED
ARGFILE
else
    # JavaFX-first startup leaves AWT headless by default on Linux, which
    # silently disables the system tray (AWT SystemTray) even where a tray
    # exists. Force it off so the tray works; the AWT-touching init paths
    # catch Throwable, so a display-less host degrades to "no tray" instead
    # of crashing. Found by the Lima desktop-VM QA scenario.
    cat > "${OUT}" <<'ARGFILE'
-Dvless.log.level=DEBUG
-Djava.awt.headless=false
--enable-native-access=ALL-UNNAMED
ARGFILE
fi
echo "[write-jvm-args] wrote ${OUT}"
