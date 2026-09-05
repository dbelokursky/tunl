#!/usr/bin/env bash
#
# Bumps the pinned sing-box release in src/main/resources/singbox.properties —
# the single source of truth for pom.xml, bundle-singbox.sh, and
# SingBoxInstaller.
#
# Usage: scripts/bump-singbox.sh <version>       (e.g. 1.13.14)
#
# For each bundled asset (darwin and linux tar.gz per arch, windows amd64 zip)
# the script downloads the release archive, computes its SHA-256 locally, and
# cross-checks it against the digest published by the GitHub Releases API. The
# two values travel different paths (CDN download vs API metadata), so a
# tampered or corrupted download fails the bump instead of getting pinned.
# Downloads land in the same build cache the bundlers use, so the follow-up
# build doesn't re-download.
#
# After a successful bump, verify before committing:
#   mvn clean verify -Psmoke
#
set -euo pipefail

VERSION="${1:?usage: $0 <version>   (e.g. 1.13.14)}"
if ! [[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "[bump-singbox] '${VERSION}' is not a plain x.y.z version" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS_FILE="${REPO_ROOT}/src/main/resources/singbox.properties"
CACHE_DIR="${HOME}/.cache/vless-client-build/sing-box-${VERSION}"
API_URL="https://api.github.com/repos/SagerNet/sing-box/releases/tags/v${VERSION}"

mkdir -p "${CACHE_DIR}"

# Retries and timeouts on every download, as in bundle-singbox.sh: a flaky
# connection is one retry away from a bump, and a stalled transfer must fail
# rather than sit on the workflow's timeout.
CURL_OPTS=(--fail --silent --show-error --location
    --retry 3 --retry-all-errors --retry-delay 2
    --connect-timeout 20 --max-time 300)

echo "[bump-singbox] fetching release metadata: ${API_URL}"
release_json="$(curl "${CURL_OPTS[@]}" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "${API_URL}")"

# Extracts the sha256 digest the API publishes for one asset name.
api_digest_for() {
    python3 -c '
import json, sys
release = json.load(sys.stdin)
if release.get("prerelease") or release.get("draft"):
    sys.exit("refusing to pin a prerelease/draft release")
name = sys.argv[1]
for asset in release.get("assets", []):
    if asset.get("name") == name:
        digest = asset.get("digest") or ""
        if not digest.startswith("sha256:"):
            sys.exit(f"asset {name} has no sha256 digest in the API response")
        print(digest[len("sha256:"):])
        break
else:
    sys.exit(f"asset {name} not found in the release")
' "$1" <<<"${release_json}"
}

# Accumulates the "singbox.sha256.<os>-<arch>=<sha>" lines to pin, in order.
pinned_lines=()

# Downloads one release asset, verifies its bytes against the sha256 digest the
# GitHub API publishes, and records its pin line.
#   $1 os   $2 arch   $3 archive extension
process_asset() {
    local os="$1" arch="$2" ext="$3"
    local asset="sing-box-${VERSION}-${os}-${arch}.${ext}"
    local file="${CACHE_DIR}/${asset}"
    local url="https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/${asset}"

    if [[ ! -f "${file}" ]]; then
        echo "[bump-singbox] downloading ${url}"
        curl "${CURL_OPTS[@]}" --output "${file}.part" "${url}"
        mv "${file}.part" "${file}"
    fi

    local local_sha api_sha
    local_sha=$(shasum -a 256 "${file}" | awk '{print $1}')
    api_sha=$(api_digest_for "${asset}")
    if [[ "${local_sha}" != "${api_sha}" ]]; then
        echo "[bump-singbox] SHA-256 mismatch for ${os}-${arch}:" >&2
        echo "  downloaded bytes: ${local_sha}" >&2
        echo "  GitHub API says:  ${api_sha}" >&2
        echo "  The download may be corrupted or tampered with — NOT pinning." >&2
        rm -f "${file}"
        exit 1
    fi
    echo "[bump-singbox] ${os}-${arch}: ${local_sha} (matches API digest)"
    pinned_lines+=("singbox.sha256.${os}-${arch}=${local_sha}")
}

process_asset darwin arm64 tar.gz
process_asset darwin amd64 tar.gz
process_asset windows amd64 zip
process_asset linux amd64 tar.gz
process_asset linux arm64 tar.gz

# Sanity: the host's own binary must actually run and report the version.
#
# The OS is read from the host, not assumed. It used to be hardcoded to
# darwin, which held only because every bump had been run from a Mac; the
# first one on a Linux runner extracted a Mach-O binary and died with
# "Exec format error" after all five checksums had already verified.
case "$(uname -s)" in
    Darwin) probe_os=darwin ;;
    Linux)  probe_os=linux ;;
    # Windows ships a .zip rather than a tar.gz, and no runner bumps from
    # there. Say so instead of probing something that isn't the host.
    *)      probe_os='' ;;
esac
case "$(uname -m)" in
    arm64|aarch64) probe_arch=arm64 ;;
    *)             probe_arch=amd64 ;;
esac

if [[ -z "${probe_os}" ]]; then
    echo "[bump-singbox] no tar.gz asset for $(uname -s); skipping the run check"
else
    probe_dir="$(mktemp -d)"
    trap 'rm -rf "${probe_dir}"' EXIT
    tar -xzf "${CACHE_DIR}/sing-box-${VERSION}-${probe_os}-${probe_arch}.tar.gz" \
        -C "${probe_dir}" --strip-components=1
    reported="$("${probe_dir}/sing-box" version | head -n1)"
    if [[ "${reported}" != "sing-box version ${VERSION}" ]]; then
        echo "[bump-singbox] binary reports '${reported}', expected 'sing-box version ${VERSION}'" >&2
        exit 1
    fi
    echo "[bump-singbox] binary check OK: ${reported}"
fi

tmp_props="$(mktemp)"
{
    # Preserve everything except the managed keys, then append them in order.
    grep -vE '^[[:space:]]*singbox\.(version|sha256\.(darwin-(arm64|amd64)|windows-amd64|linux-(amd64|arm64)))[[:space:]]*=' \
        "${PROPS_FILE}"
    printf 'singbox.version=%s\n' "${VERSION}"
    for line in "${pinned_lines[@]}"; do
        printf '%s\n' "${line}"
    done
} > "${tmp_props}"
mv "${tmp_props}" "${PROPS_FILE}"

echo "[bump-singbox] pinned sing-box ${VERSION} in ${PROPS_FILE}"
echo "[bump-singbox] next: mvn clean verify -Psmoke"
