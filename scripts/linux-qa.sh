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
# Outputs land in target/linux-qa/: linux-screenshot.png + app-stdout.log.
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
IMAGE="${IMAGE:-eclipse-temurin:25-jdk}"
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
  "${IMAGE}" bash -euc '
echo "[linux-qa] container: $(uname -m), $(. /etc/os-release && echo "$PRETTY_NAME")"

apt-get update -q >/dev/null
DEBIAN_FRONTEND=noninteractive apt-get install -yq --no-install-recommends \
  git maven curl ca-certificates procps psmisc libglib2.0-bin \
  xvfb xauth imagemagick \
  libgtk-3-0t64 libglib2.0-0t64 libxtst6 libxi6 libxrender1 libxrandr2 \
  libfreetype6 fontconfig libasound2t64 libgl1 libpango-1.0-0 libcairo2 \
  fonts-dejavu-core >/dev/null 2>&1

git clone -q -b "$BRANCH" /src /work && cd /work
echo "[linux-qa] testing commit: $(git log --oneline -1)"

echo "[linux-qa] === mvn clean verify -Psmoke ==="
mvn -B clean verify -Psmoke 2>&1 \
  | grep -E "bundled|Tests run: [0-9]{3}|coverage checks|BUILD" | tail -4

echo "[linux-qa] === GUI probe under Xvfb ==="
JAR=$(ls target/vless-client-*-SNAPSHOT.jar | head -1)
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