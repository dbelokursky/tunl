# The core Tunl bundles

Tunl bundles sing-box at the version `src/main/resources/singbox.properties`
pins. It is built from upstream's source at that tag with one patch,
`reality-xray.patch`, by `.github/workflows/core.yml`, and published here as
the pre-release `singbox.release` names (`core-v<version>-tunl<N>`), whose
archives the same file pins by digest.

## Why a patch

Two Xray changes shut every client built on upstream sing-box out of
REALITY servers:

- **Xray 26.7.11** (`af7eb680`, 2026-07-11) makes a REALITY server with no
  `minClientVer` require client version 26.3.27. sing-box writes 1.8.1 into
  the session id (`common/tls/reality_client.go`), so such a server answers
  it as the site it poses as, and the core reports
  `reality verification failed`. Remnawave node 3.x ships Xray 26.7.28.
- **Xray 26.9.8** takes XTLS/REALITY `8cdf7bf9` (2026-09-08), which rejects a
  ClientHello without an X25519MLKEM768 key share. sing-box cut that share
  out of the Chrome fingerprint for REALITY. No server setting brings the old
  behaviour back.

The version is not about the number. Xray asks for the hello of a current,
unmodified Chrome, and a Chrome hello with the hybrid share cut out is one no
browser sends, which a filter can single out. Upstream issue:
[SagerNet/sing-box#4520](https://github.com/SagerNet/sing-box/issues/4520).

## What the patch changes

All in `common/tls/reality_client.go`:

1. **The hello is left as the fingerprint builds it,** X25519MLKEM768
   included. Upstream's filter was a workaround for utls 1.7, whose hybrid
   and plain X25519 keys were one key; metacubex/utls 1.8, which 1.14 uses,
   keeps them apart.
2. **The session id names client version 26.3.27,** the minimum Xray checks
   for (the comparison is `>=`), and the Xray whose Chrome hello this now
   matches.
3. **The auth key falls back to the X25519 half of the hybrid share** when a
   fingerprint sends no plain X25519 share. This is the order the server
   reads them in.

The approach matches the maintained fork Leadaxe/sing-box-lx, which also
keeps a per-server "classical" hello for networks that drop the larger
hybrid one. Tunl has no such switch yet.

## How it is built

- `core.yml` builds `darwin-arm64`, `darwin-amd64`, `windows-amd64`,
  `linux-amd64` and `linux-arm64` with `CGO_ENABLED=0`. It uses upstream's
  `-trimpath` and `release/LDFLAGS`, and the version string stays
  upstream's, so the app's version checks hold.
- The build tags are what Tunl's configurations use, and nothing more. On
  macOS the core runs as administrator, so tailscale, acme, cloudflared,
  openvpn and the like stay out. The extra tag `tunl` shows in
  `sing-box version`.
- `verify.sh` then starts the linux-amd64 build against `verify/`, which
  plays an Xray 26.9 server. It fails the run unless the hello carries the
  X25519MLKEM768 share and its session id, opened with the server's private
  key, names 26.3.27.
- Every pull request that touches the core runs this.
- A run by hand (**Core**, *Run workflow*, from any branch), or one
  **Bump sing-box** starts, also publishes the archives, their `SHA256SUMS`,
  the upstream source and the patch as the pre-release
  `core-v<version>-tunl<N>`. That release is never "latest", which the app's
  updater reads.
- `bundle-singbox.sh`/`.ps1` and the runtime fallback in `SingBoxInstaller`
  download from that release, and `SingBoxRealBinarySmokeTest` fails on every
  system if the bundled core lacks the `tunl` tag while `singbox.release` is
  set. Upstream's archives have the same names, so the build cache keeps them
  apart by release.

To check the patch locally (Go 1.25), build the tree the workflow builds and
run `verify.sh` on the archive.

## Moving to a new sing-box version

- **Bump sing-box** runs daily. For a new upstream version with no build of
  Tunl's yet, it has **Core** build, check and publish `core-v<version>-tunl1`,
  then pins it (`scripts/bump-singbox.sh <version> 1`) and opens the pull
  request. It pins an existing build of the version instead of making one.
- A patch that no longer applies fails that run at *Apply the patch*, every
  day until it is rebased. Rebase it onto the new tag on a branch, run
  **Core** by hand on that branch with the new version and revision 1, run
  `scripts/bump-singbox.sh <version> 1` there, and open one pull request with
  both.
- A change to the patch for the version already pinned needs a new build:
  after merging it, run **Core** on `main` with the next revision, then
  **Bump sing-box** with that version, which pins the newest build.
- When upstream fixes #4520, the patch goes: `scripts/bump-singbox.sh
  <version>` with no revision pins upstream's release again and empties
  `singbox.release`, and **Bump sing-box** has to go back to pinning
  upstream's releases.

## Licence

sing-box is GPL-3.0. Each `core-v…` release carries the corresponding source:
upstream's source at the tag, and this patch. The patch is also in this
directory under the same licence. Tunl's own code stays Apache-2.0 and runs
the core as a separate program; see `NOTICE`.
