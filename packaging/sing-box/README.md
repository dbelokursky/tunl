# The core Tunl bundles

Tunl bundles sing-box at the version `src/main/resources/singbox.properties`
pins. `.github/workflows/core.yml` builds it from upstream's source at that
tag with two patches: `reality-xray.patch` to sing-box, and
`utls-presets.patch` to the uTLS sing-box builds with. The build is
published here as the pre-release `singbox.release` names
(`core-v<version>-tunl<N>`), whose archives the same file pins by digest.

## Why the patches

Two Xray changes shut every client built on upstream sing-box out of
REALITY servers:

- **Xray 26.7.11** (`af7eb680`, 2026-07-11) makes a REALITY server with no
  `minClientVer` require client version 26.3.27. sing-box writes 1.8.1 into
  the session id (`common/tls/reality_client.go`), so such a server answers
  it as the site it poses as, and the core reports
  `reality verification failed`. Xray 26.9.8 dropped that default again
  (`47cfe999`), but 26.7.11 and 26.7.28 keep it (Remnawave node 3.4.2 pins
  26.7.28), and an operator can set `minClientVer` on any version.
- **Xray 26.9.8** takes XTLS/REALITY `8cdf7bf9` (2026-09-08), which rejects a
  ClientHello without an X25519MLKEM768 key share ahead of any X25519 one.
  sing-box cut that share out of the Chrome fingerprint for REALITY, and the
  uTLS it builds with, metacubex/utls, has no Firefox or Safari hello that
  sends it: `firefox` is Firefox 120 there, and `safari` Safari 16.0. Xray's
  own client has sent Firefox 148 and Safari 26.3, which carry the share,
  since 26.3.27, so a link with `fp=firefox` or `fp=safari` that connected
  from Xray clients failed from Tunl. No server setting brings the old
  behaviour back.

The version is not about the number. Xray asks for the hello of a current,
unmodified browser, and a Chrome hello with the hybrid share cut out is one
no browser sends, which a filter can single out. Upstream issue:
[SagerNet/sing-box#4520](https://github.com/SagerNet/sing-box/issues/4520).

## What reality-xray.patch changes

In `common/tls/reality_client.go`:

1. **The hello is left as the fingerprint builds it,** X25519MLKEM768
   included. Upstream's filter was a workaround for utls 1.7, whose hybrid
   and plain X25519 keys were one key; metacubex/utls 1.8, which 1.14 uses,
   keeps them apart.
2. **The session id names client version 26.3.27,** the minimum Xray
   26.7.11 and 26.7.28 check for by default (the comparison is `>=`), and
   the Xray whose Chrome, Firefox and Safari hellos the core now sends.
3. **The auth key falls back to the X25519 half of the hybrid share** when a
   fingerprint sends no plain X25519 share. This is the order the server
   reads them in.

In `common/tls/utls_client.go`:

4. **Every `randomized` hello is consistent and carries that share.** The
   core draws that hello once per process, from utls's weights.
   - A draw that listed X25519MLKEM768 without its share failed on any
     Xray whose REALITY offers the group, which 25.12.2's does. The server
     asks for the share in a HelloRetryRequest, and utls gives the
     handshake up (XTLS/Xray-core#6714). That was about a third of core
     starts; against Xray 26.9.8, which requires the share ahead of
     X25519, it would be one in two.
   - Links choose `randomized` on purpose where TSPU is reported to single
     out the uTLS Chrome profile, so the random hello stays.
   - Two weights now always add the share, ahead of X25519, and a P-256
     share behind it, with the group among the supported ones. The draws
     had also sent the share without its group.
   - `utls.HelloRandomizedALPN`, which Xray also uses for `randomized`,
     always offers ALPN; the plain randomized hello left it out now and
     then.

The approach matches the maintained fork Leadaxe/sing-box-lx, which also
keeps a per-server "classical" hello for networks that drop the larger
hybrid one. Tunl has no such switch yet.

## What utls-presets.patch changes

It adds the Firefox 148 and Safari 26.3 hellos to the metacubex/utls version
sing-box requires (v1.8.7 for 1.14.3): three commits of
[refraction-networking/utls](https://github.com/refraction-networking/utls),
the uTLS Xray builds with, cherry-picked in this order, their tests
included.

- `fc716b2d` (2026-02-28) adds Firefox 148. Firefox sends one X25519 key
  twice, as the X25519 half of its hybrid share and as its plain X25519
  share, and the commit makes utls do the same where a hello asks for it.
- `ddebe390` marks such a pair with a byte in the shares' data, in place of
  a new field of `KeyShare`.
- `aa6edf4b` adds Safari 26.3. Xray has built with this commit since 26.3.27.

`HelloFirefox_Auto` and `HelloSafari_Auto` now name the new hellos, so
sing-box's `firefox` and `safari` send them, over plain TLS too, and so does
`random` when it draws Firefox or Safari. Both send the X25519MLKEM768 share
ahead of X25519. The REALITY auth key stays the plain X25519 key, the one the
server reads first; in Firefox's hello it is the hybrid share's half as well.

The one conflict, in the imports of `u_parrots.go`, keeps metacubex's
`internal/mlkem` in place of `crypto/mlkem` and adds `crypto/ecdh`. The
patched tree is that of [Leadaxe/utls-lx](https://github.com/Leadaxe/utls-lx)
`59e89bb`, the same three commits on the same tag, which sing-box-lx builds
with. To reproduce the patch:

```bash
git clone https://github.com/metacubex/utls && cd utls && git checkout v1.8.7
git fetch https://github.com/refraction-networking/utls master
git cherry-pick fc716b2 ddebe39 aa6edf4   # keep internal/mlkem, add crypto/ecdh
git diff v1.8.7
```

refraction-networking/utls cannot stand in for metacubex/utls, which carries
the REALITY server and the `go:linkname` exports sing-box's `common/badtls`
and `common/ktls` rely on; and metacubex/utls has neither hello of its own.

### What Tunl sends a REALITY server

The app hands the core a fingerprint whose hello carries the share, in place
of one a link may name whose hello cannot (`CoreSettings.fingerprintToSend`):

- `random` goes as `randomized`: the core's `random` picks one hello for the
  whole run, among them Edge 85 and iOS 14, which carry no share.
- `ios` goes as `safari`. Every browser on iOS uses Apple's TLS, and the
  core's iOS hello is iOS 14's.
- `edge`, `qq`, `360` and `android` go as `randomized` too. The core's hellos
  for them are Edge 85, QQ Browser 11.1, 360 Browser 7.5 and OkHttp on
  Android 11, the last two TLS 1.2 only, with which REALITY never connected
  (`nil ecdheKey`). The current versions of these browsers send Chrome's
  hello, but the uTLS Chrome profile is the one TSPU is reported to single
  out, and a link that names another browser may do so to stay clear of it.

None of them goes as `chrome`. The server keeps the fingerprint its link
sent, and plain TLS sends it as it is, since an ordinary TLS server takes
any hello.

## How it is built

- `core.yml` builds `darwin-arm64`, `darwin-amd64`, `windows-amd64`,
  `linux-amd64` and `linux-arm64` with `CGO_ENABLED=0`. It uses upstream's
  `-trimpath` and `release/LDFLAGS`, and the version string stays
  upstream's, so the app's version checks hold.
- The Go toolchain is the one upstream's release workflow builds the same
  tag with: `go-version.sh` reads it from the tag's
  `.github/workflows/build.yml` (1.26.8 for 1.14.2 and 1.14.3) and fails the
  run when that file does not name exactly one version. `go.mod` is not
  used: it names only the oldest Go the module accepts (1.25.5), and the
  builds up to `core-v1.14.3-tunl1` were linked with it, without the Go
  security fixes upstream's own archives carry. Each build checks that the
  binary records the chosen version (`go version <binary>`).
- The build tags are what Tunl's configurations use, and nothing more. On
  macOS the core runs as administrator, so tailscale, acme, cloudflared,
  openvpn and the like stay out. The extra tag `tunl` shows in
  `sing-box version`.
- uTLS is the version sing-box's `go.mod` requires, which `go mod download`
  fetches and checks against sing-box's `go.sum`. `utls-presets.patch` is
  applied to a copy of it, uTLS's own tests run on that copy, and
  `go mod edit -replace` builds sing-box against it, so `go version -m` on the
  binary names it: `github.com/metacubex/utls v1.8.7 => ../utls (devel)`.
- `verify.sh` then starts the linux-amd64 build against `verify/`, which
  plays an Xray 26.9 server. It fails the run unless the hello meets four
  conditions:
  - it carries the X25519MLKEM768 share ahead of X25519, in the order the
    server reads the shares;
  - its session id, opened with the server's private key, names 26.3.27;
  - every key share's group is among the supported groups, and
    X25519MLKEM768 is not offered without its share;
  - it offers ALPN h2 and http/1.1.

  It checks every fingerprint Tunl sends a REALITY server: `chrome` and the
  five names the core takes as Chrome's, `firefox` and `safari`, each in one
  core process, and `randomized` in twelve. `CoreSettingsTest` fails when
  that list and the fingerprints `CoreSettings` sends part. Every hello is
  checked and the failing ones are named; a core that sends no hello stops
  the run (exit 2), and upstream's build has to fail on its hellos (exit 1),
  not on that.
- Every pull request that touches the core runs this.
- A run by hand (**Core**, *Run workflow*, from any branch), or one
  **Bump sing-box** starts, also publishes the archives, their `SHA256SUMS`,
  the upstream source and both patches as the pre-release
  `core-v<version>-tunl<N>`. That release is never "latest", which the app's
  updater reads.
- `bundle-singbox.sh`/`.ps1` and the runtime fallback in `SingBoxInstaller`
  download from that release, and `SingBoxRealBinarySmokeTest` fails on every
  system if the bundled core lacks the `tunl` tag while `singbox.release` is
  set. Upstream's archives have the same names, so the build cache keeps them
  apart by release.

To check the patches locally, take the steps of `core.yml`'s *Apply the
patch*, *Patch uTLS* and *Build* with the Go `go-version.sh <tree>` prints,
for the host's own system, and run `verify.sh` on the archive.

## Moving to a new sing-box version

- **Bump sing-box** runs daily. For a new upstream version with no build of
  Tunl's yet, it has **Core** build, check and publish `core-v<version>-tunl1`,
  then pins it (`scripts/bump-singbox.sh <version> 1`) and opens the pull
  request. It pins an existing build of the version instead of making one.
- A patch that no longer applies fails that run at *Apply the patch*, or at
  *Patch uTLS* when the new version requires another uTLS, every day until
  it is rebased. Rebase it onto the new tag (or the new uTLS version) on a
  branch, run **Core** by hand on that branch with the new version and
  revision 1, run `scripts/bump-singbox.sh <version> 1` there, and open one
  pull request with both.
- A change to a patch for the version already pinned needs a new build:
  after merging it, run **Core** on `main` with the next revision, then
  **Bump sing-box** with that version, which pins the newest build.
- When the uTLS sing-box requires has hellos of its own for Firefox and
  Safari that carry the share, `utls-presets.patch` goes, with its step in
  `core.yml`. When upstream also fixes #4520, `reality-xray.patch` goes too:
  `scripts/bump-singbox.sh <version>` with no revision pins upstream's
  release again and empties `singbox.release`, and **Bump sing-box** has to
  go back to pinning upstream's releases.

## Licence

sing-box is GPL-3.0. Each `core-v…` release carries the corresponding source:
upstream's source at the tag and both patches; the uTLS version
`utls-presets.patch` applies to is the one sing-box's `go.mod` requires,
which the Go module proxy serves. `reality-xray.patch` is also in this
directory under sing-box's licence. `utls-presets.patch` is uTLS's code, under
uTLS's BSD 3-Clause licence. Tunl's own code stays Apache-2.0 and runs the
core as a separate program; see `NOTICE`.
