# Distribution channels

Primary distribution is **direct download** from the
[Releases](https://github.com/dbelokursky/tunl/releases/latest) page:
`tunl_x.y.z.dmg` (macOS), `tunl_x.y.z.msi` (Windows), and
`tunl_x.y.z_{amd64,arm64}.deb` (Linux), all built by `release.yml` on
each `v*` tag. Everything below is a convenience layer on top of those same
assets — no channel is the source of the binaries.

`packaging/` holds the source-of-truth **Homebrew cask** and **AUR PKGBUILD**.
On release, `scripts/update-packaging.sh` regenerates them (new version + the
released assets' SHA-256) so the published channels track the latest tag.

## Live / prepared

### Homebrew cask (macOS)

Installed via a personal tap:

```bash
brew install --cask dbelokursky/tap/tunl
```

One-time setup of the external tap repo:

1. Create a public GitHub repo named **`dbelokursky/homebrew-tap`** (the
   `homebrew-` prefix is what lets `dbelokursky/tap` resolve to it).
2. Add the cask at `Casks/tunl.rb` — a copy of `packaging/`'s
   source-of-truth cask, pointing `url` at the release DMG with its `sha256`.
3. On each release, `scripts/update-packaging.sh` refreshes the cask and it is
   pushed to the tap.

Until the DMG is signed & notarized (see `SIGNING.md`), the cask still installs
an unsigned app, so first launch needs the Gatekeeper unblock from the README.

### AUR (Arch Linux)

```bash
yay -S tunl-bin
```

`-bin` because the package installs the pre-built `.deb` payload from Releases
rather than compiling from source. One-time setup of the AUR package repo:

1. Create an AUR account and register your SSH public key.
2. Clone the (empty) package repo:
   `git clone ssh://aur@aur.archlinux.org/tunl-bin.git`.
3. Add the `PKGBUILD` (from `packaging/`) and generate `.SRCINFO`
   (`makepkg --printsrcinfo > .SRCINFO`), then commit and push.
4. On each release, `scripts/update-packaging.sh` bumps `pkgver` + the source
   `sha256sums`; commit the refreshed `PKGBUILD`/`.SRCINFO` and push.

## Deferred (with rationale)

- **winget** — submitting to
  [`microsoft/winget-pkgs`](https://github.com/microsoft/winget-pkgs)
  effectively requires a **signed** installer (unsigned MSIs get flagged in
  validation and by SmartScreen on install). Revisit once Windows signing lands
  (`SIGNING.md`).
- **Flatpak** — the app creates a **TUN device**, **elevates** to do it, and
  downloads/manages the **sing-box core** at runtime. The Flatpak sandbox
  fights all three (no raw TUN, no privilege escalation, read-only runtime).
  Making it work is real effort with low near-term payoff, so the `.deb` +
  AUR cover Linux for now.
