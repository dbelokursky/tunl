#!/usr/bin/env bash
#
# One-command Linux QA in a Docker container: builds the CURRENT COMMIT of
# this repo on Linux, runs the full test suite incl. the real-binary smoke
# gates, then launches the app under a virtual X server and captures a
# screenshot — proof the UI actually renders on Linux.
#
# Usage:
#   scripts/linux-qa.sh                 # container arch = Docker default
#   PLATFORM=linux/amd64 scripts/linux-qa.sh   # force amd64 (emulated on ARM)
#
# Outputs land in target/linux-qa/: build.log, linux-screenshot.png and
# app-stdout.log.
#
# Scope: build, tests, JavaFX rendering, updater startup. NOT covered here
# (needs a real desktop VM): TUN mode, system tray, GNOME system proxy.
#
# Uncommitted changes are NOT tested — the container clones the repo, so
# commit (or stash-apply on a branch) first.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${REPO_ROOT}/target/linux-qa"
IMAGE="${IMAGE:-eclipse-temurin:27-jdk}"
PLATFORM_ARGS=()
[[ -n "${PLATFORM:-}" ]] && PLATFORM_ARGS=(--platform "${PLATFORM}")
BRANCH="$(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD)"

command -v docker >/dev/null || { echo "[linux-qa] docker is required" >&2; exit 1; }
mkdir -p "${OUT_DIR}"
docker volume create vless-client-qa-m2 >/dev/null

echo "[linux-qa] image=${IMAGE} platform=${PLATFORM:-default} branch=${BRANCH}"

# The [@]+ idiom keeps `set -u` happy on bash 3.2 (macOS) with an empty array.
docker run --rm ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
  -v "${REPO_ROOT}":/src:ro \
  -v vless-client-qa-m2:/root/.m2 \
  -v "${OUT_DIR}":/out \
  -e BRANCH="${BRANCH}" \
  "${IMAGE}" bash -euo pipefail -c '
echo "[linux-qa] container: $(uname -m), $(. /etc/os-release && echo "$PRETTY_NAME")"

# unzip: without it ./mvnw fetches Maven as a .tar.gz and checks that against
# the .zip digest in maven-wrapper.properties, so the build failed before it
# began. The image has none, and nothing below pulls it in.
apt-get update -q >/dev/null
DEBIAN_FRONTEND=noninteractive apt-get install -yq --no-install-recommends \
  git maven curl unzip ca-certificates procps psmisc libglib2.0-bin \
  xvfb xauth imagemagick \
  libgtk-3-0t64 libglib2.0-0t64 libxtst6 libxi6 libxrender1 libxrandr2 \
  libfreetype6 fontconfig libasound2t64 libgl1 libpango-1.0-0 libcairo2 \
  fonts-dejavu-core >/dev/null 2>&1

# The bind mount keeps the host user as the owner of /src, and git refuses
# to clone a repository another user owns: on a Linux host with rootful
# Docker the clone stopped with "detected dubious ownership".
git config --global --add safe.directory /src
git config --global --add safe.directory /src/.git
git clone -q -b "$BRANCH" /src /work && cd /work
echo "[linux-qa] testing commit: $(git log --oneline -1)"

# A failed build fails the script and shows why. The status of the pipeline
# used to be the status of tail, so a red verify printed "BUILD FAILURE"
# among four lines, launched no jar and still exited 0 with "done"; then the
# grep that picks those lines dropped the error that said why it failed.
echo "[linux-qa] === ./mvnw clean verify -Psmoke ==="
if ! ./mvnw -B clean verify -Psmoke > /out/build.log 2>&1; then
  tail -30 /out/build.log
  echo "[linux-qa] the build failed; the whole log is target/linux-qa/build.log" >&2
  exit 1
fi
grep -E "bundled|Tests run: [0-9]{3}|coverage checks|BUILD" /out/build.log | tail -4

echo "[linux-qa] === GUI probe under Xvfb ==="
JAR=$(ls target/vless-client-*-SNAPSHOT.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
  echo "[linux-qa] the build left no target/vless-client-*.jar; nothing to launch" >&2
  exit 1
fi
xvfb-run -a -s "-screen 0 1280x800x24" bash -c "
  java -Dprism.order=sw -jar \"$JAR\" > /out/app-stdout.log 2>&1 &
  APP=\$!
  sleep 30
  import -window root /out/linux-screenshot.png
  kill \$APP 2>/dev/null || true
  sleep 2
"
grep -E "Tunl started|ERROR" /out/app-stdout.log | tail -3 || true
echo "[linux-qa] done — see target/linux-qa/"
'