#!/usr/bin/env bash
#
# Installs the .deb in containers of the oldest releases Tunl supports,
# Debian 12 and Ubuntu 22.04, and checks that the packaged runtime's
# libraries resolve there.
#
# The package is built on Ubuntu 24.04, and jpackage names the libraries the
# runtime links by that host's packages. 24.04 renamed some of them for the
# 64-bit time_t transition (libasound2 became libasound2t64), and the older
# releases have only the old names, so a package that installs on the build
# host did not install on them. linux-deb-smoke.sh checks the build host;
# this checks the others.
#
# Usage: scripts/linux-deb-older-releases.sh <path/to/tunl_*.deb>
# Needs docker, which the GitHub-hosted Ubuntu runners have.
#
set -euo pipefail

DEB="${1:?usage: $0 <deb>}"
[[ -f "${DEB}" ]] || { echo "[deb-older] no such file: ${DEB}" >&2; exit 1; }
DIR="$(cd "$(dirname "${DEB}")" && pwd)"
NAME="$(basename "${DEB}")"

# What must load: the launcher, the JVM, and AWT's X11 library, which links
# the X client libraries the package depends on. Single-quoted on purpose:
# the container's shell expands it, with the package name as $1.
# shellcheck disable=SC2016
CHECK='
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
# What a desktop system has and a bare container does not: the post-install
# step of the package adds its menu entry with xdg-desktop-menu, which writes
# into these. The output of the install is shown when it fails.
mkdir -p /etc/xdg/menus/applications-merged /usr/share/applications \
    /usr/share/desktop-directories
apt-get update -qq
if ! apt-get install -yqq --no-install-recommends "/dist/$1" > /tmp/install.log 2>&1; then
    tail -n 40 /tmp/install.log
    exit 1
fi
dpkg -s tunl | grep -qx "Status: install ok installed"
# The runtime finds its own libraries (libjvm among them) itself; only the
# system libraries the package depends on are in question.
runtime=/opt/tunl/lib/runtime/lib
missing="$(LD_LIBRARY_PATH="${runtime}/server:${runtime}" ldd /opt/tunl/bin/tunl \
    "${runtime}/server/libjvm.so" "${runtime}/libawt_xawt.so" 2>&1 \
    | grep "not found" || true)"
if [ -n "${missing}" ]; then
    echo "${missing}"
    exit 1
fi
'

for image in debian:12 ubuntu:22.04; do
    echo "[deb-older] ${image}: installing ${NAME}"
    if ! docker run --rm -v "${DIR}:/dist:ro" "${image}" bash -c "${CHECK}" _ "${NAME}"; then
        echo "[deb-older] ${image}: ${NAME} does not install or its libraries do not resolve" >&2
        exit 1
    fi
    echo "[deb-older] ${image}: installed, libraries resolve"
done
