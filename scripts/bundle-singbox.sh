#!/usr/bin/env bash
#
# Downloads and bundles the sing-box binary for the build host's OS and
# architecture into the Maven build output directory. Invoked by the
# exec-maven-plugin during the generate-resources phase.
#
# Arguments:
#   $1 — output directory (e.g. target/classes/native)
#   $2 — sing-box version (e.g. 1.13.8), passed by Maven from
#        src/main/resources/singbox.properties
#
# The version and the SHA-256 checksums both come from singbox.properties —
# the single source of truth also read by pom.xml and SingBoxInstaller. The
# $2 argument is cross-checked against the file to catch a stale Maven
# property cache.
#
# Reuses a ~/.cache/vless-client-build/sing-box-<version> directory so repeated
# builds don't re-download. A .singbox-version stamp next to each bundled
# binary makes incremental builds re-bundle after a version bump instead of
# silently keeping the old binary.
#
set -euo pipefail

OUT_DIR="${1:?usage: $0 <out_dir> <version>}"
VERSION="${2:?usage: $0 <out_dir> <version>}"

PROPS_FILE="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/singbox.properties"
if [[ ! -f "${PROPS_FILE}" ]]; then
    echo "[bundle-singbox] missing ${PROPS_FILE}" >&2
    exit 1
fi

# Bash-3.2-safe .properties lookup: last value wins, whitespace trimmed.
prop() {
    grep -E "^[[:space:]]*$1[[:space:]]*=" "${PROPS_FILE}" \
        | tail -n 1 \
        | cut -d= -f2- \
        | tr -d '[:space:]'
}

PROPS_VERSION="$(prop singbox.version)"
if [[ "${PROPS_VERSION}" != "${VERSION}" ]]; then
    echo "[bundle-singbox] version mismatch: Maven passed '${VERSION}' but" >&2
    echo "  ${PROPS_FILE} says '${PROPS_VERSION}'." >&2
    echo "  Run 'mvn clean' — the Maven property cache is stale." >&2
    exit 1
fi

CACHE_DIR="${HOME}/.cache/vless-client-build/sing-box-${VERSION}"
mkdir -p "${CACHE_DIR}" "${OUT_DIR}"

# The build host decides which binary gets bundled: its own OS and its own
# architecture, nothing else. jpackage links a runtime for the host's
# architecture only, so the DMG built on an arm64 runner cannot start on an
# Intel Mac at all — a darwin-amd64 core inside it was 29 MB of the download
# that no machine ever executed. An Intel DMG needs an Intel build host, which
# bundles darwin-amd64 by the same rule. Windows hosts use bundle-singbox.ps1.
case "$(uname -s)" in
    Darwin) os="darwin" ;;
    Linux)  os="linux" ;;
    *)
        echo "[bundle-singbox] unsupported build host: $(uname -s)" >&2
        exit 1
        ;;
esac
case "$(uname -m)" in
    aarch64|arm64) arch="arm64" ;;
    x86_64|amd64)  arch="amd64" ;;
    *)
        echo "[bundle-singbox] unsupported ${os} arch: $(uname -m)" >&2
        exit 1
        ;;
esac
# Kept as a list so a host that must ship more than one binary can be added
# back with one line; today it is always exactly one entry.
targets="${os}:${arch}"

# shasum on macOS, sha256sum on most Linux distros.
sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

for target in ${targets}; do
    os="${target%%:*}"
    arch="${target##*:}"
    target_dir="${OUT_DIR}/${os}-${arch}"
    target_binary="${target_dir}/sing-box"
    stamp_file="${target_dir}/.singbox-version"

    if [[ -x "${target_binary}" && -f "${stamp_file}" ]] \
            && [[ "$(cat "${stamp_file}")" == "${VERSION}" ]]; then
        echo "[bundle-singbox] already present: ${target_binary} (${VERSION})"
        continue
    fi

    tarball="${CACHE_DIR}/sing-box-${VERSION}-${os}-${arch}.tar.gz"
    if [[ ! -f "${tarball}" ]]; then
        url="https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/sing-box-${VERSION}-${os}-${arch}.tar.gz"
        echo "[bundle-singbox] downloading ${url}"
        # Retries and timeouts: a runner's flaky connection to the GitHub CDN
        # is one retry away from a green build, and a stalled transfer must
        # fail here rather than sit on the job's timeout.
        curl --fail --silent --show-error --location \
            --retry 3 --retry-all-errors --retry-delay 2 \
            --connect-timeout 20 --max-time 300 \
            --output "${tarball}.part" "${url}"
        mv "${tarball}.part" "${tarball}"
    fi

    expected=$(prop "singbox.sha256.${os}-${arch}")
    if [[ -z "${expected}" ]]; then
        echo "[bundle-singbox] no singbox.sha256.${os}-${arch} in ${PROPS_FILE}" >&2
        exit 1
    fi
    actual=$(sha256_of "${tarball}")
    if [[ "${actual}" != "${expected}" ]]; then
        echo "[bundle-singbox] SHA-256 mismatch for ${os}-${arch}:" >&2
        echo "  expected ${expected}" >&2
        echo "  got      ${actual}" >&2
        rm -f "${tarball}"
        exit 1
    fi

    mkdir -p "${target_dir}"
    rm -f "${target_binary}" "${stamp_file}"
    tar -xzf "${tarball}" -C "${target_dir}" \
        --strip-components=1 \
        "sing-box-${VERSION}-${os}-${arch}/sing-box"
    chmod +x "${target_binary}"
    printf '%s' "${VERSION}" > "${stamp_file}"
    echo "[bundle-singbox] bundled ${target_binary} (${VERSION})"
done
