# Distribution channels

Primary distribution is **direct download** from the
[Releases](https://github.com/dbelokursky/tunl/releases/latest) page:
`tunl_x.y.z.dmg` (macOS), `tunl_x.y.z.msi` (Windows), and
`tunl_x.y.z_{amd64,arm64}.deb` (Linux), all built by `release.yml` on
each `v*` tag. Everything below is a convenience layer on top of those same
assets — no channel is the source of the binaries.

## What exists today

`packaging/` holds two templates: the Homebrew cask
(`packaging/homebrew/tunl.rb`) and the AUR `PKGBUILD` with its `.SRCINFO`
(`packaging/aur/`). They are committed with version `0.0.0` and all-zero
checksums. On every release the `update-packaging` job of `release.yml` runs
`scripts/update-packaging.sh <version> <dmg sha256> <amd64 deb sha256>
<arm64 deb sha256>`, which stamps the version and checksums into the three
files, and attaches the results — `tunl.rb`, `PKGBUILD` and `.SRCINFO`
(GitHub lists it as `default.SRCINFO`) — to the GitHub release. The
`publish-release` job refuses to make the release public without them.

That is all the workflow does. **Nothing is pushed to a tap, to the AUR, or
back into this repository**; the templates in git stay at `0.0.0`.

There is **no Homebrew tap** (`dbelokursky/homebrew-tap` does not exist) and
**no AUR package** (`tunl-bin` is not registered), so
`brew install --cask dbelokursky/tap/tunl` and `yay -S tunl-bin` do not work.
The files are attached so that publishing either channel — by the project or
by anyone else — is a copy rather than a rewrite.

### Homebrew cask (macOS) — not published

Homebrew installs casks only from a tap: `brew install --cask ./tunl.rb`
stops with "Homebrew requires casks to be in a tap" (Homebrew 6), so the
attached file is of no use on its own. To publish it:

1. Create a public GitHub repository named **`dbelokursky/homebrew-tap`**
   (the `homebrew-` prefix is what lets `dbelokursky/tap` resolve to it);
   `brew tap-new dbelokursky/tap` scaffolds one locally.
2. Copy the release's `tunl.rb` to `Casks/tunl.rb`, commit, push.
3. On each release, replace it with the newly attached `tunl.rb` — only the
   `version` and `sha256` lines change.

Users then run `brew install --cask dbelokursky/tap/tunl`. Until the DMG is
signed and notarized (`SIGNING.md`), the cask installs an unsigned app; its
`caveats` block repeats the README's Gatekeeper walkthrough, and the
`livecheck` stanza follows GitHub's latest release.

### AUR (Arch Linux) — not published

The `PKGBUILD` is a `-bin` package: it downloads the release `.deb` for the
build host's architecture (amd64 or arm64) and unpacks it into `$pkgdir`, so
Arch users get exactly the artifact the release workflow built — bundled
runtime and sing-box included. It works locally without any AUR involvement:
download `PKGBUILD` from the release into an empty directory and run
`makepkg -si`; `makepkg` fetches the `.deb` and checks its SHA-256.

To publish it:

1. Create an AUR account and register an SSH public key.
2. `git clone ssh://aur@aur.archlinux.org/tunl-bin.git` (empty until the
   first push).
3. Add the release's `PKGBUILD` and `.SRCINFO` (rename `default.SRCINFO`
   back, or regenerate it with `makepkg --printsrcinfo > .SRCINFO`), commit
   and push.
4. On each release, copy the newly attached pair over the old one and push.

## Deferred (with rationale)

- **winget** — submitting to
  [`microsoft/winget-pkgs`](https://github.com/microsoft/winget-pkgs)
  effectively requires a **signed** installer (unsigned MSIs get flagged in
  validation and by SmartScreen on install). Revisit once Windows signing lands
  (`SIGNING.md`).
- **Flatpak** — the app creates a **TUN device** and **elevates** to do it,
  and it runs the bundled **sing-box** as a separate process. The Flatpak
  sandbox fights the first two (no raw TUN, no privilege escalation). Making
  it work is real effort with low near-term payoff, so the `.deb` covers
  Linux for now, with the `PKGBUILD` for Arch.
