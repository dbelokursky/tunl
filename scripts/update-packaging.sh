#!/usr/bin/env bash
#
# Stamps a released version and its asset checksums into the packaging
# templates: the Homebrew cask (packaging/homebrew/tunl.rb) and the AUR
# PKGBUILD (packaging/aur/PKGBUILD), then regenerates packaging/aur/.SRCINFO to
# match. Run by the release workflow after the DMG/deb assets are built and
# their SHA-256 sums are known; also safe to run by hand.
#
# Usage:
#   scripts/update-packaging.sh <version> <dmg_sha256> <deb_amd64_sha256> <deb_arm64_sha256>
#
#   version           — release version, no leading 'v' (e.g. 1.2.0)
#   dmg_sha256        — SHA-256 of tunl_<version>.dmg
#   deb_amd64_sha256  — SHA-256 of tunl_<version>_amd64.deb
#   deb_arm64_sha256  — SHA-256 of tunl_<version>_arm64.deb
#
# Idempotent: re-running with the same arguments is a no-op. Portable across
# macOS bash 3.2 (BSD sed) and Linux (GNU sed) — it never relies on `sed -i`.
#
set -euo pipefail

usage() {
    echo "usage: $0 <version> <dmg_sha256> <deb_amd64_sha256> <deb_arm64_sha256>" >&2
    exit 2
}

[ "$#" -eq 4 ] || usage

VERSION="$1"
DMG_SHA="$2"
DEB_AMD64_SHA="$3"
DEB_ARM64_SHA="$4"

# version must be a plain x.y.z (matches the release tag vX.Y.Z minus the 'v').
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "[update-packaging] invalid version '$VERSION' (expected x.y.z)" >&2
    exit 1
fi

# Normalise checksums to lowercase and require exactly 64 hex chars.
to_hex64() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}
check_sha() {
    printf '%s' "$1" | grep -Eq '^[0-9a-f]{64}$' || {
        echo "[update-packaging] $2 is not a 64-char SHA-256 hex string" >&2
        exit 1
    }
}
DMG_SHA="$(to_hex64 "$DMG_SHA")";             check_sha "$DMG_SHA" "dmg_sha256"
DEB_AMD64_SHA="$(to_hex64 "$DEB_AMD64_SHA")"; check_sha "$DEB_AMD64_SHA" "deb_amd64_sha256"
DEB_ARM64_SHA="$(to_hex64 "$DEB_ARM64_SHA")"; check_sha "$DEB_ARM64_SHA" "deb_arm64_sha256"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOMEBREW_FILE="${REPO_ROOT}/packaging/homebrew/tunl.rb"
PKGBUILD_FILE="${REPO_ROOT}/packaging/aur/PKGBUILD"
SRCINFO_FILE="${REPO_ROOT}/packaging/aur/.SRCINFO"

for f in "$HOMEBREW_FILE" "$PKGBUILD_FILE"; do
    [ -f "$f" ] || { echo "[update-packaging] missing $f" >&2; exit 1; }
done

# Portable in-place edit: BSD and GNU sed disagree on `-i`, so filter to a temp
# file and move it back. Usage: sed_i <file> -e '<script>' [-e '<script>' ...]
sed_i() {
    local file="$1"; shift
    local tmp; tmp="$(mktemp "${TMPDIR:-/tmp}/vless-pkg.XXXXXX")"
    sed "$@" "$file" > "$tmp" && mv "$tmp" "$file"
}

# --- Homebrew cask: version + sha256 (the single DMG checksum). ---
sed_i "$HOMEBREW_FILE" \
    -e "s|^\( *\)version \".*\"|\1version \"${VERSION}\"|" \
    -e "s|^\( *\)sha256 \".*\"|\1sha256 \"${DMG_SHA}\"|"

# --- AUR PKGBUILD: pkgver, pkgrel (reset to 1), per-arch sha256sums. ---
sed_i "$PKGBUILD_FILE" \
    -e "s|^pkgver=.*|pkgver=${VERSION}|" \
    -e "s|^pkgrel=.*|pkgrel=1|" \
    -e "s|^sha256sums_x86_64=.*|sha256sums_x86_64=('${DEB_AMD64_SHA}')|" \
    -e "s|^sha256sums_aarch64=.*|sha256sums_aarch64=('${DEB_ARM64_SHA}')|"

# --- .SRCINFO: regenerate from the freshly-updated PKGBUILD. ---
# The PKGBUILD is bash, so sourcing it evaluates the pkgver-interpolated
# source_*/noextract arrays exactly as makepkg would, keeping .SRCINFO in sync
# without duplicating the field values here. Runs on any platform (no makepkg).
generate_srcinfo() {
    local tmp; tmp="$(mktemp "${TMPDIR:-/tmp}/vless-srcinfo.XXXXXX")"
    (
        set +u
        # shellcheck source=/dev/null
        . "$PKGBUILD_FILE"
        printf 'pkgbase = %s\n' "$pkgname"
        printf '\tpkgdesc = %s\n' "$pkgdesc"
        printf '\tpkgver = %s\n' "$pkgver"
        printf '\tpkgrel = %s\n' "$pkgrel"
        printf '\turl = %s\n' "$url"
        for _v in "${arch[@]}";      do printf '\tarch = %s\n' "$_v"; done
        for _v in "${license[@]}";   do printf '\tlicense = %s\n' "$_v"; done
        for _v in "${depends[@]}";   do printf '\tdepends = %s\n' "$_v"; done
        for _v in "${provides[@]}";  do printf '\tprovides = %s\n' "$_v"; done
        for _v in "${conflicts[@]}"; do printf '\tconflicts = %s\n' "$_v"; done
        for _v in "${options[@]}";   do printf '\toptions = %s\n' "$_v"; done
        for _v in "${noextract[@]}"; do printf '\tnoextract = %s\n' "$_v"; done
        printf '\tsource_x86_64 = %s\n'      "${source_x86_64[0]}"
        printf '\tsha256sums_x86_64 = %s\n'  "${sha256sums_x86_64[0]}"
        printf '\tsource_aarch64 = %s\n'     "${source_aarch64[0]}"
        printf '\tsha256sums_aarch64 = %s\n' "${sha256sums_aarch64[0]}"
        printf '\n'
        printf 'pkgname = %s\n' "$pkgname"
    ) > "$tmp"
    mv "$tmp" "$SRCINFO_FILE"
}
generate_srcinfo

echo "[update-packaging] stamped version ${VERSION} into:"
echo "  ${HOMEBREW_FILE}"
echo "  ${PKGBUILD_FILE}"
echo "  ${SRCINFO_FILE}"
