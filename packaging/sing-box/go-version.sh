#!/bin/bash
# Prints the Go version upstream's release workflow builds this sing-box
# version with, read from the sing-box tree it is given.
#
# core.yml used to install Go from the tree's go.mod. That names the oldest
# Go the module accepts (1.25.5 for 1.14.2 and 1.14.3) and no toolchain line,
# so every core-v…-tunlN was linked with it, while upstream built the same
# tags with 1.26.8: Tunl's build lacked the crypto/tls, crypto/x509 and
# net/url fixes of every Go release in between. Upstream pins the version in
# each setup-go step of .github/workflows/build.yml, the workflow that makes
# its release archives.
#
# Exits non-zero, saying why, when the file is missing or does not name
# exactly one plain x.y.z version: a guess here would build the core with a
# toolchain nobody chose.
#
# Usage: go-version.sh <sing-box source tree>
set -euo pipefail

tree=${1:?usage: $0 <sing-box source tree>}
workflow="$tree/.github/workflows/build.yml"
if [ ! -f "$workflow" ]; then
  echo "go-version: $workflow is missing, so the Go version upstream builds" \
    "this release with is unknown; read it from upstream's release workflow" \
    "and update this script" >&2
  exit 1
fi

versions=$(sed -n -E 's/^[[:space:]]*go-version:[[:space:]]*["'\'']?([^"'\''[:space:]#]+).*$/\1/p' \
  "$workflow" | sort -u)
if [ -z "$versions" ] || [ "$(wc -l <<<"$versions")" -ne 1 ] \
    || ! [[ "$versions" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "go-version: expected one plain x.y.z go-version in $workflow, found:" \
    "'${versions//$'\n'/"', '"}'" >&2
  exit 1
fi
echo "$versions"
