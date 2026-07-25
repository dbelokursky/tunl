# Manual pre-release checklist

CI (`build.yml`, the `-Psmoke` real-binary suite) and the Linux VM QA
(`scripts/linux-vm-qa.sh`) cover a lot, but **nothing exercises a real Windows
desktop** — no CI job, no VM. This checklist is the pragmatic substitute:
walk the Windows items by hand on a Windows 10/11 machine before tagging, plus
a few quick cross-platform confirmations.

Tag only after the `v*`-tagged `release.yml` run is green and all installers
are attached to the release.

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
- [ ] **Downloads** — all three installers (DMG / MSI / DEB) download from the
      Releases page and open.
