# Manual pre-release checklist

CI (`build.yml`, the `-Psmoke` real-binary suite) and the Linux VM QA
(`scripts/linux-vm-qa.sh`) cover a lot, but **nothing exercises a real Windows
desktop** — no CI job, no VM. This checklist is the pragmatic substitute:
walk the Windows items by hand on a Windows 10/11 machine before tagging, plus
a few quick cross-platform confirmations.

The workflow keeps a tagged release as a draft until every job is green, all
four installers and updater signatures are attached, and the exact asset
manifest has been verified. A failed job therefore leaves a draft to diagnose,
not a partial public release.

GitHub exposes the uploaded AUR `.SRCINFO` as `default.SRCINFO`; the final
manifest check deliberately uses that release-asset name.

## Cutting the release (tagging)

`release.yml` triggers on any `v*` tag push and writes the GitHub Release's
**title and notes from the annotated tag's message** — subject line → title,
message body → notes. The tag *is* the release notes; write them there.

Two things must both hold, or the notes silently degrade:

- **The tag must be annotated** (`git tag -a`). The workflow reads the tag
  object through the GitHub API; a lightweight tag has no annotation, and the
  release then falls back to a bare `vX.Y.Z` title with an empty body.
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

## Windows (manual — no CI/VM covers this)

Run on a real Windows 10/11 x64 box, ideally a clean user profile.

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

## Cross-platform quick checks

- [ ] **Version** shows correctly in **Settings → About** (matches the tag; not
      "dev").
- [ ] **In-app updater** — with the new release published, "Check for updates"
      sees it (compares `latest` from the GitHub Releases API to the running
      version).
- [ ] **Downloads** — all four installers (DMG / MSI / amd64 DEB / arm64 DEB)
      and their `.sig` files download from the Releases page.
