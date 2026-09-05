# Release checklist

Releases are batched: builds are unsigned, so every release makes users repeat
the Gatekeeper / SmartScreen unblock, and `dev-latest` already rebuilds on
every merge. When one is due, `release.yml` does most of the work from the
moment a `v*` tag lands. This page says what it does, so nothing is repeated
by hand, and lists the steps that are genuinely manual.

## What `release.yml` automates

On a pushed `v*` tag:

- **Tag gate** (`prepare-release`): the tag must be annotated with a title, a
  blank line and notes (they become the release's title and body); it must
  match `vMAJOR.MINOR.PATCH`; and it must point at a commit on `main` whose
  four required checks (`test-macos`, `test-windows`, `test-linux`,
  `test-linux-arm`) passed. The release jobs build with `-DskipTests`, so this
  provenance check is what stands in for `mvn verify`.
- **Per OS** — macOS (Apple Silicon), Windows x64, Linux amd64, Linux arm64:
  fat JAR, the real-binary smoke suite (`SingBoxRealBinarySmokeTest`) on the
  bundled core, the installer via the shared `scripts/package-*` script, and
  an upload into a **draft** release.
- **Signing and notarization**, gated on secrets (`SIGNING.md`): macOS
  `codesign` + `notarytool` + `stapler`, Windows `signtool`. A partially
  configured set of secrets fails the job; no secrets means an unsigned
  installer, byte-for-byte as before.
- **MSI install smoke** (`scripts/windows-msi-smoke.ps1`): installs and
  uninstalls the MSI on the runner and checks its version, runtime, embedded
  core and shortcut.
- **Updater signatures** (`sign-release`): an Ed25519 signature over each
  installer's `sha256:<hex>` digest, verified against the public key and
  uploaded as `<asset>.sig`. `RELEASE_SIGNING_KEY` is required — the in-app
  updater refuses assets without a valid signature, so a missing key fails
  the release rather than shipping one the updater cannot install.
- **Cask and PKGBUILD** (`update-packaging`): `scripts/update-packaging.sh`
  stamps the version and asset checksums into `packaging/`'s templates and
  attaches `tunl.rb`, `PKGBUILD` and `.SRCINFO` (listed as `default.SRCINFO`)
  to the release. Nothing is pushed to a tap or to the AUR — neither exists
  (`DISTRIBUTION.md`).
- **Manifest check and publish** (`publish-release`): the asset list must
  equal exactly the four installers, their four `.sig` files and the three
  packaging files; only then is the draft made public. A failed job leaves a
  draft to diagnose, never a partial public release.
- **Pruning** (`prune-old-releases`): real releases older than the newest five
  are deleted (tags kept; `dev-latest` and drafts untouched).

## Before tagging (manual)

- [ ] `main` is green and the commit you are about to tag is on it — the
      workflow refuses anything else.
- [ ] **Screenshots**: if the UI changed since `docs/screenshots/*.png` were
      last generated, regenerate and commit them:
      `mvn test -Dtest=ScreenshotGenerator -Dtunl.screenshots=true -Djacoco.skip=true`.
- [ ] **README.md and README.ru.md**: the feature list, the Settings table and
      the hotkeys still describe the app, in both languages.
- [ ] **Core pin**: `singbox.properties` holds the core you mean to ship — it
      moves only with app releases.
- [ ] **Desktop checks** below, on the `dev-latest` build of the release
      commit, for the paths CI cannot exercise.
- [ ] **Release notes** in English, written into the annotated tag (layout
      below).

## Cutting the release (tagging)

`release.yml` triggers on any `v*` tag push and writes the GitHub Release's
**title and notes from the annotated tag's message** — subject line → title,
message body → notes. The tag *is* the release notes; write them there.

Two things must both hold, or the notes silently degrade:

- **The tag must be annotated** (`git tag -a`). The workflow reads the tag
  object through the GitHub API; a lightweight tag has no annotation, and the
  workflow fails the release rather than publishing a bare `vX.Y.Z` with an
  empty body.
- **Create it with `--cleanup=verbatim`**, or Git strips every `## Section`
  header on the way in (it treats `#` lines as comments), and the headers never
  reach the tag object at all.

```sh
git tag -a --cleanup=verbatim -F notes.txt vX.Y.Z
git push origin vX.Y.Z
```

Verify what the workflow will actually read, before or after pushing:

```sh
gh api "repos/dbelokursky/tunl/git/tags/$(gh api repos/dbelokursky/tunl/git/ref/tags/vX.Y.Z -q .object.sha)" -q .message
```

`notes.txt` layout (repository release notes are written in English). The first
line is the title, then a blank line, then the body:

```text
vX.Y.Z — concise release title
                                  <- blank line
## What's new
- ...
## Fixes
- ...
## Installation
- macOS (Apple Silicon): `tunl_X.Y.Z.dmg`
- Windows 10/11 x64: `tunl_X.Y.Z.msi`
- Debian/Ubuntu amd64: `tunl_X.Y.Z_amd64.deb`
- Debian/Ubuntu arm64: `tunl_X.Y.Z_arm64.deb`
```

The version comes wholly from the tag (`pom.xml` stays `-SNAPSHOT`). To fix notes
after the fact: `gh release edit vX.Y.Z --notes-file notes.txt` — immune to the
`#` stripping, since it doesn't go through Git.

## After the workflow

- [ ] The draft became public with all eleven assets. If it did not, the run
      log names the failed job: fix and re-run it, or delete the draft and the
      tag and tag again.
- [ ] **Version** shows correctly in **Settings → About** of an installed
      build (matches the tag; not "dev").
- [ ] **In-app updater** — on the previous release, "Check for updates" sees
      the new one (it compares `latest` from the GitHub Releases API to the
      running version) and installs it.
- [ ] **Downloads** — all four installers (DMG / MSI / amd64 DEB / arm64 DEB)
      and their `.sig` files download from the Releases page.

## Windows (manual desktop/network coverage)

CI installs and uninstalls the built MSI and checks its runtime, embedded core,
version and shortcut, but it does **not** exercise an interactive Windows
desktop, UAC, TUN, or the live system proxy. Run these on a real Windows 10/11
x64 box, ideally a clean user profile.

- [ ] **Install** the MSI — it installs **per-user** into
      `%LOCALAPPDATA%\Programs`, **no admin prompt** during install.
- [ ] **Launch** from the Start-menu shortcut (group "Tunl").
- [ ] **System-proxy connect** — connect an active server in **SYSTEM_PROXY**
      mode; confirm the Windows proxy is set (Settings → Network & Internet →
      Proxy, or a browser now routes through the server).
- [ ] **System-proxy disconnect** — disconnect; confirm the OS proxy is
      **restored** (proxy toggle off, direct traffic again).
- [ ] **TUN connect** — switch to **TUN** mode and connect; a **UAC prompt**
      appears (per connect); confirm the **`VlessClientTun` wintun adapter**
      comes up (Network Connections / `ipconfig`).
- [ ] **TUN disconnect** — disconnect; confirm the adapter is **removed**.
- [ ] **Quit while connected (TUN)** — connect in TUN, then **Quit the app**
      (tray → Quit) *while still connected*. Confirm **no `sing-box.exe`
      survives** (Task Manager) and the wintun adapter + OS proxy are cleaned
      up. *This is the orphaned-core / stale-proxy class of bug fixed in
      #67 / #70 — Windows relies on `WindowsTunLauncher`'s owner-PID watch and
      `SystemProxyGuard`, neither of which CI can exercise.*
- [ ] **Quit while connected (system proxy)** — repeat the quit-while-connected
      check in SYSTEM_PROXY mode; confirm no `sing-box.exe` survives and the
      proxy is cleared (and, if force-killed, cleared on the **next launch**).
- [ ] **System tray** — minimize to tray, restore, and Quit from the tray menu
      all work.
- [ ] **Autostart toggle** — enable in Settings; confirm the
      `HKCU\...\CurrentVersion\Run` **`VlessClient`** value is written. Disable;
      confirm it is removed.
- [ ] **Uninstall** the MSI — leaves **no orphan** (no running core, no Run-key
      value, no leftover adapter).

## macOS

- [ ] DMG opens; drag-install to Applications; app launches (Gatekeeper unblock
      per README while unsigned).
- [ ] SYSTEM_PROXY connect/disconnect sets and restores the OS proxy.
- [ ] TUN connect prompts for privileges, tunnels traffic; quit-while-connected
      leaves no orphaned `sing-box` and restores the proxy.
- [ ] Tray/menu-bar minimize / restore / quit.

## Linux

- [ ] `.deb` (amd64 and arm64) installs to `/opt/tunl`; app appears in
      the menu (Network) and launches.
- [ ] SYSTEM_PROXY connect/disconnect on GNOME; TUN connect via pkexec/setcap.
- [ ] Quit-while-connected leaves no orphaned core / TUN (covered by
      `scripts/linux-vm-qa.sh`, but confirm on the target DE).
