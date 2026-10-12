#!/usr/bin/env bash
#
# Installs the .deb in bare containers of the oldest releases Tunl supports,
# Debian 12 and Ubuntu 22.04, of the newest Debian, 13, and of the build
# host's, Ubuntu 24.04, and checks that the libraries of the packaged runtime
# and of JavaFX resolve there from what the package's Depends pulled in. A
# release older than the glibc the package declares must refuse it instead:
# the arm64 build of JavaFX 27 needs glibc 2.38, newer than Debian 12's and
# Ubuntu 22.04's.
#
# The package is built on Ubuntu 24.04, and jpackage names the libraries the
# runtime links by that host's packages. 24.04 renamed some of them for the
# 64-bit time_t transition (libasound2 became libasound2t64), and the older
# releases have only the old names, so a package that installs on the build
# host did not install on them. linux-deb-smoke.sh launches the app on the
# build host, but a hosted runner already carries GTK and most of the rest,
# so only a bare system shows what Depends leaves out: the package named no
# GTK 3, FreeType or fontconfig, which JavaFX's own libraries (inside the
# jar, unseen by jpackage) link, and installed on a system without them.
#
# Usage: scripts/linux-deb-older-releases.sh <path/to/tunl_*.deb>
# Needs docker, which the GitHub-hosted Ubuntu runners have.
#
set -euo pipefail

DEB="${1:?usage: $0 <deb>}"
[[ -f "${DEB}" ]] || { echo "[deb-older] no such file: ${DEB}" >&2; exit 1; }
DIR="$(cd "$(dirname "${DEB}")" && pwd)"
NAME="$(basename "${DEB}")"

# What must load: the launcher, the JVM, AWT's X11 library, which links the
# X client libraries the package depends on, and JavaFX's libraries, unpacked
# from the jar. Single-quoted on purpose: the container's shell expands it,
# with the package name as $1.
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
# A release whose glibc is older than the one the package declares is too
# old for this build: apt must refuse the package there, not install it.
need="$(dpkg-deb -f "/dist/$1" Depends | grep -o "libc6 (>= [0-9.]*)" \
    | sed "s/^libc6 (>= \([0-9.]*\))$/\1/" | sort -V | tail -n 1 || true)"
have="$(dpkg-query -W -f="\${Version}" libc6)"
if [ -n "${need}" ] && dpkg --compare-versions "${have}" lt "${need}"; then
    if apt-get install -yqq --no-install-recommends "/dist/$1" > /tmp/install.log 2>&1; then
        echo "installed although libc6 ${have} is older than the ${need} the package needs"
        exit 1
    fi
    echo "libc6 ${have} is older than the ${need} the package needs, and apt refused it"
    exit 3
fi
if ! apt-get install -yqq --no-install-recommends "/dist/$1" > /tmp/install.log 2>&1; then
    tail -n 40 /tmp/install.log
    exit 1
fi
dpkg -s tunl | grep -qx "Status: install ok installed"
# unzip only reads the jar here; installed after the package, so it cannot
# stand in for anything the package should have pulled in.
apt-get install -yqq --no-install-recommends unzip > /tmp/unzip.log 2>&1 \
    || { tail -n 20 /tmp/unzip.log; exit 1; }
fx=/tmp/javafx
mkdir -p "${fx}"
unzip -qq -o /opt/tunl/lib/app/vless-client-*.jar "lib*.so" -d "${fx}"
fx_libs=("${fx}"/lib*.so)
if [ ! -e "${fx_libs[0]}" ] || [ ! -e "${fx}/libglassgtk3.so" ]; then
    echo "no JavaFX libraries (libglassgtk3.so among them) in the jar"
    exit 1
fi
# The runtime finds its own libraries (libjvm among them) itself; only the
# system libraries the package depends on are in question.
runtime=/opt/tunl/lib/runtime/lib
missing="$(LD_LIBRARY_PATH="${runtime}/server:${runtime}" ldd /opt/tunl/bin/tunl \
    "${runtime}/server/libjvm.so" "${runtime}/libawt_xawt.so" "${fx_libs[@]}" 2>&1 \
    | grep -e "not found" -e "^ldd: " || true)"
if [ -n "${missing}" ]; then
    echo "${missing}"
    exit 1
fi
'

# Each release either installs the package with every library resolving, or
# refuses it for the glibc its Depends names (the arm64 build of JavaFX needs
# a newer one than Debian 12 and Ubuntu 22.04 have); installing and then
# failing to load is what this rules out. The build host's release and the
# newest Debian must always install it.
for image in debian:12 debian:13 ubuntu:22.04 ubuntu:24.04; do
    echo "[deb-older] ${image}: installing ${NAME}"
    status=0
    docker run --rm -v "${DIR}:/dist:ro" "${image}" bash -c "${CHECK}" _ "${NAME}" || status=$?
    case "${status}:${image}" in
        0:*)
            echo "[deb-older] ${image}: installed, libraries resolve" ;;
        3:debian:12 | 3:ubuntu:22.04)
            echo "[deb-older] ${image}: refused for the glibc ${NAME} declares, as it should be" ;;
        *)
            echo "[deb-older] ${image}: ${NAME} does not install or its libraries do not resolve" >&2
            exit 1 ;;
    esac
done
