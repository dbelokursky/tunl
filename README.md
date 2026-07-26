# Tunl

<p align="center">
  <img src="src/main/resources/icons/app-icon-256.png" width="128" alt="Tunl icon"/>
</p>

<p align="center">
  <a href="https://github.com/dbelokursky/tunl/actions/workflows/build.yml"><img src="https://github.com/dbelokursky/tunl/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases/latest"><img src="https://img.shields.io/github/v/release/dbelokursky/tunl" alt="Latest release"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases"><img src="https://img.shields.io/github/downloads/dbelokursky/tunl/total" alt="Downloads"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases/latest"><img src="https://img.shields.io/github/downloads/dbelokursky/tunl/latest/total?label=downloads@latest" alt="Latest release downloads"/></a>
</p>

<p align="center">🇬🇧 <b>English</b> · 🇷🇺 <a href="README.ru.md">Русский</a></p>

**Tunl** — cross-platform desktop client (macOS / Windows / Linux) for
VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and WireGuard, built on JavaFX —
wraps [sing-box](https://github.com/SagerNet/sing-box) in a friendly GUI with
live traffic stats, share-link import, subscriptions, routing rules and a
tray/menu-bar icon.

<p align="center">
  <img src="docs/screenshots/dashboard.png" width="900" alt="Tunl dashboard: connected through Amsterdam, live traffic graph"/>
</p>

---

## Download

Ready-made installers are on the
[**Releases**](https://github.com/dbelokursky/tunl/releases/latest) page:

| OS | File | Note |
|---|---|---|
| macOS (Apple Silicon) | `tunl_x.y.z.dmg` | |
| Windows 10/11 (x64) | `tunl_x.y.z.msi` | installs per-user, no admin rights needed |
| Debian/Ubuntu (amd64) | `tunl_x.y.z_amd64.deb` | |
| Debian/Ubuntu (arm64) | `tunl_x.y.z_arm64.deb` | Raspberry Pi 5 and other ARM machines |

A build of the newest merge to `main` (possibly rough) lives in the
[**dev-latest**](https://github.com/dbelokursky/tunl/releases/tag/dev-latest)
prerelease.

---

## Install

### macOS

Builds are **not signed** with an Apple Developer certificate yet, so
Gatekeeper blocks the app on first launch. Unblocking is a one-time trip
through System Settings:

1. Open the DMG and drag **Tunl** into **Applications**.
2. Launch the app. macOS will say it cannot verify the app is free of
   malware — click **Done** (*not* "Move to Trash").
3. Open **System Settings → Privacy & Security**.
4. Scroll down to the **Security** section — you'll see
   '"Tunl" was blocked to protect your Mac'.
5. Click **Open Anyway** and confirm with your password or Touch ID.
6. In the dialog that follows, click **Open**. Done — from now on the app
   launches normally.

<!-- TODO(screenshots): Gatekeeper dialog and the Privacy & Security pane
     with the Open Anyway button — Phase 3 of the plan -->

Notes:

- You'll have to repeat this after **every app update** — until builds are
  signed, macOS re-blocks each new binary.
- On macOS 13–14 there is a shortcut: right-click the app in Applications →
  **Open** → Open. On macOS 15+ this trick no longer works for unsigned
  apps — System Settings is the only way.
- Terminal equivalent (strips the quarantine attribute):

  ```bash
  xattr -d com.apple.quarantine "/Applications/Tunl.app"
  ```

Or install via [Homebrew](https://brew.sh) once the tap is set up (see
[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)):

```bash
brew install --cask dbelokursky/tap/tunl
```

### Windows

1. Run the MSI. SmartScreen will show "Windows protected your PC" — click
   **More info** → **Run anyway**.
2. Then it's a regular install wizard. The app installs per-user — no admin
   rights needed.

The only place Windows asks for elevation is the UAC prompt when connecting
in TUN mode.

### Linux (Debian/Ubuntu)

```bash
sudo apt install ./tunl_*.deb
```

The app installs into `/opt/tunl` and shows up in the application
menu (Network category).

On Arch-based distros an [AUR](https://aur.archlinux.org) package is available
(see [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)):

```bash
yay -S tunl-bin
```

### Upgrading from VLESS Client (1.4.x and older)

The app was renamed to Tunl in 1.5.0. Your servers, subscriptions, routing
rules and saved passwords carry over untouched — only the app's name changed,
not where it stores things.

| OS | What happens | What you do |
|---|---|---|
| Windows | the MSI upgrades the existing install in place | nothing |
| macOS | `Tunl.app` installs **next to** the old `VLESS Client.app` | delete the old app once Tunl works |
| Linux | apt sees `tunl` as a new package, not an upgrade | `sudo apt remove vless-client` after installing |

---

## First connection

1. **Add a server.** **Servers** tab → **Import Link** → paste the
   `vless://…` / `vmess://…` / `trojan://…` / `ss://…` link from your
   provider — the form fills itself. (Or **Add Server** and fill the form
   manually; a third way is the **Subscriptions** tab with a subscription
   URL — the server list will keep itself in sync.)
2. **Check the active server** — clicking a server row gives it the
   **ACTIVE** badge; that's the server used when you connect.
3. **Hit Connect** on the **Dashboard** tab (or `⌘K` / `Ctrl+K`).
   Green indicator — connected, orange — connecting, red — error (check the
   **Logs** tab).
4. **Verify your IP**: open [ifconfig.me](https://ifconfig.me) or run
   `curl ifconfig.me` — the address should change to the server's.

### Which mode to pick

The **Mode** dropdown on the Dashboard:

| | **System Proxy** (default) | **TUN** |
|---|---|---|
| What goes through the tunnel | apps that honor the system proxy: browsers, most CLI tools | **all** system traffic, including apps that ignore the proxy |
| Privileges | none needed | required (see below) |
| When to pick | everyday browsing | messengers, games, system services |

What TUN asks for on each OS:

| OS | TUN privileges |
|---|---|
| macOS | sudo-NOPASSWD rule (password once) or an osascript prompt on every Connect |
| Windows | UAC prompt on every Connect |
| Linux | one-time `setcap` via PolicyKit (no prompts afterwards) or a pkexec prompt on Connect |

---

## Usage

### Servers

<p align="center">
  <img src="docs/screenshots/servers.png" width="900" alt="Server list with search, sort and per-server latency"/>
</p>

Search filters on name, address, port and protocol at once. Sort by your own
order, name, protocol, or fastest first — **Measure** fills in the latency for
whatever the search currently shows. While the tunnel is up the measurement
goes *through* each proxy rather than to its address, so a server that answers
but does not work ranks where it belongs.

Click a server to make it active; Cmd/Shift-click builds a selection, and
deleting one asks once for the whole batch. Right-click for **Edit**,
**Duplicate**, **Copy Share Link**, **Delete**.

### Subscriptions

**Subscriptions** tab: add a URL — the server list is fetched and
periodically re-synced.

### Routing

<p align="center">
  <img src="docs/screenshots/routing.png" width="900" alt="Routing tab: bypass countries, bypass list and custom rules"/>
</p>

**Routing** tab — routing rules: geoip, geosite, domain, ruleset.

### Monitoring

The **Dashboard** shows live stats: upload/download speed and totals.
**Logs** streams sing-box logs with a level filter.

### Tray / menu bar

The icon offers quick actions without opening the window: Show window,
Connect/Disconnect, server selection, Quit. Closing the main window (`⌘W`,
the red button) does **not** quit the app — it keeps running in the tray;
quit via **Quit** in the tray menu or `⌘Q`.

| OS | Where |
|---|---|
| macOS | menu bar |
| Windows | system tray |
| Linux | wherever a tray exists (KDE/XFCE/…); stock GNOME has no tray — closing the window quits the app |

### Autostart

| OS | Mechanism |
|---|---|
| macOS | LaunchAgent |
| Windows | Run registry key (native exe) |
| Linux | XDG autostart (`~/.config/autostart`) |

### Updating the sing-box core

**Settings → About → "Check for updates"** — core patches arrive without an
app release (within the minor branch, validated with `sing-box check`, with
rollback on failure).

### Hotkeys

`⌘` on macOS = `Ctrl` on Windows/Linux.

| Hotkey | Action                                  |
|--------|-----------------------------------------|
| `⌘K`   | Connect / Disconnect                    |
| `⌘N`   | Add server                              |
| `⌘1`   | Dashboard                               |
| `⌘2`   | Servers                                 |
| `⌘3`   | Subscriptions                           |
| `⌘4`   | Routing                                 |
| `⌘5`   | Logs                                    |
| `⌘,`   | Settings                                |
| `⌘W`   | Hide window (keeps running in the tray) |
| `⌘Q`   | Quit                                    |

### Settings

<p align="center">
  <img src="docs/screenshots/settings.png" width="900" alt="Settings: appearance, connection and health check"/>
</p>

| Field                    | Description                                           |
|--------------------------|-------------------------------------------------------|
| Theme                    | Light / Dark                                          |
| Language                 | Russian / English                                     |
| Mixed port               | SOCKS/HTTP proxy port (default `2080`)                |
| Clash API port           | Traffic stats port (default `9090`)                   |
| Auto-connect on start    | Connect to the active server on launch                |
| Check for updates        | Periodic app update checks                            |
| Allow LAN                | Allow proxy connections from the local network        |

Where the data lives (`settings.json`, `servers.json`, `subscriptions.json`,
`routing.json`, binary cache `bin/`):

| OS | Path |
|---|---|
| macOS | `~/Library/Application Support/VlessClient` |
| Windows | `%APPDATA%\VlessClient` |
| Linux | `~/.local/share/vless-client` |

These paths still carry the app's old name (it was "VLESS Client" before the
Tunl rename). That is deliberate: keeping them means an update finds your
servers, subscriptions and saved passwords exactly where they already are,
with nothing to migrate by hand.

### System Proxy per OS

| OS | Behavior |
|---|---|
| macOS | system proxy settings switch automatically |
| Windows | automatic (WinINET) |
| Linux | automatic on GNOME; on other DEs — set the proxy manually (the local ports work everywhere) or use TUN |

---

## Troubleshooting

**macOS: "app was blocked" / "Apple could not verify"**
That's Gatekeeper and an unsigned build — walk through the
[install steps](#macos): System Settings → Privacy & Security → Open Anyway.

**macOS: the app is blocked again after an update**
Expected until builds are signed — every new binary goes through Gatekeeper
afresh. Same procedure.

**Connect button is disabled**
No active server — on the Servers tab click a server so it gets the
**ACTIVE** badge.

**"Process exited unexpectedly (code N)"**
sing-box crashed. The reason is in the **Logs** tab. Common ones: wrong UUID,
wrong transport, unreachable server, port conflict.

**TUN mode asks for a password every time**
Creating a TUN interface requires root/admin: macOS shows an osascript prompt
(or set up sudo-NOPASSWD — then the password is asked once), Windows — UAC,
Linux — pkexec (or a one-time `setcap`).

**Port 2080 is busy**
Change `Mixed port` in Settings to a free one.

**Linux: no tray icon**
Stock GNOME has no tray (needs an extension like AppIndicator), closing the
window quits the app. KDE/XFCE trays work out of the box.

**"sing-box binary not found" on startup**
Applies to running from sources without bundling — see
[Development](#if-the-bundle-is-unavailable). The installers (DMG/MSI/DEB)
ship sing-box inside.

---

## Development

### Quick start

```bash
git clone https://github.com/dbelokursky/tunl.git
cd tunl
mvn clean javafx:run
```

On the first build Maven automatically downloads `sing-box` (the version is
pinned in [singbox.properties](src/main/resources/singbox.properties)) for
both architectures (arm64 + amd64) into `target/classes/native/darwin-{arch}/`
with SHA-256 verification. The binaries are bundled into the jar and extracted
on first launch.

### If the bundle is unavailable

If the app runs without build-time bundling (e.g. a bare jar built without
`generate-resources`), a modal dialog appears on startup that downloads
`sing-box` from GitHub Releases and caches it in
`~/Library/Application Support/VlessClient/bin/sing-box`. The download is
SHA-256 verified.

If there's no network — the installer dialog suggests:

```bash
brew install sing-box
```

After installing manually, restart the app or hit **Retry download** in the
orange banner on the Dashboard — it will pick up the binary from the standard
Homebrew paths (`/opt/homebrew/bin`, `/usr/local/bin`) or `$PATH`.

### Requirements

- JDK 25 (the project uses preview features)
- Maven 3.9+
- bash + curl + tar (standard on macOS) — needed by `generate-resources`
  to download sing-box

### Commands

```bash
mvn clean javafx:run        # run in dev mode
mvn clean package           # build the shaded jar (with the sing-box bundle)
mvn test                    # all tests
mvn test -Dtest=SingBoxInstallerTest   # a single test class
mvn validate                # checkstyle

# regenerate docs/screenshots/*.png after a UI change
mvn test -Dtest=ScreenshotGenerator -Dtunl.screenshots=true -Djacoco.skip=true
```

### Regenerating the icon

```bash
java --source 25 scripts/GenerateAppIcon.java
```

Generates PNGs 16/32/64/128/256/512/1024 into `src/main/resources/icons/`.
Edit the design in [GenerateAppIcon.java](scripts/GenerateAppIcon.java).

### Updating sing-box

The version and SHA-256 live in a single file —
[singbox.properties](src/main/resources/singbox.properties). It is read by
pom.xml (properties-maven-plugin), [scripts/bundle-singbox.sh](scripts/bundle-singbox.sh)
and SingBoxInstaller, so they can never drift. Bumping is one command:

```bash
scripts/bump-singbox.sh 1.13.14   # downloads tarballs, checks SHA-256 against the GitHub API digest, updates the properties
mvn clean verify -Psmoke          # full tests + smoke on the real binary
```

The smoke profile (`-Psmoke`,
[SingBoxRealBinarySmokeTest](src/test/java/com/vlessclient/service/SingBoxRealBinarySmokeTest.java))
exercises the real binary: exact version match against the pin,
`sing-box check` across all protocols × modes × routing presets, and a live
`run` verifying clash_api and the http inbound. CI runs it on every PR and
before packaging the installers.

Minor sing-box updates (1.13 → 1.14) break the config schema — first migrate
[SingBoxConfigGenerator.java](src/main/java/com/vlessclient/service/SingBoxConfigGenerator.java)
per the [migration guide](https://sing-box.sagernet.org/migration/), then bump.

### Releasing, signing & distribution

- [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md) — manual pre-release
  checks (Windows-heavy, since no CI or VM covers the UAC/tray/proxy paths).
- [docs/SIGNING.md](docs/SIGNING.md) — activate the dormant macOS notarization
  and Windows signing by adding the documented secrets; the workflow steps are
  already in place.
- [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) — Homebrew cask and AUR (source
  of truth in [packaging/](packaging/), regenerated on release by
  [scripts/update-packaging.sh](scripts/update-packaging.sh)); winget/Flatpak
  deferred with rationale.

### Layout

```
src/main/java/com/vlessclient/
├── app/            # Launcher, VlessClientApp, ServiceLocator, I18n, AppVersion
├── model/          # POJOs: ServerConfig, AppSettings, Subscription, Routing...
├── service/        # SingBoxEngine, SingBoxInstaller, ConfigStore,
│                   # SubscriptionService, RoutingService, LatencyTester,
│                   # TrafficMonitor, TrayIconService, UpdateManager, ...
└── ui/view/        # JavaFX controllers for each tab

src/main/resources/
├── fxml/           # FXML markup
├── css/            # light.css, dark.css
├── i18n/           # messages_en.properties, messages_ru.properties
└── icons/          # app-icon-{16..1024}.png

scripts/
├── bundle-singbox.sh       # downloads sing-box during mvn generate-resources
├── package-dmg.sh          # DMG (macOS), shared by build.yml and release.yml
├── package-windows.ps1     # MSI (Windows)
├── package-linux.sh        # DEB (Linux)
├── linux-qa.sh             # one-command Linux QA in Docker (build+tests+UI screenshot)
├── linux-vm-qa.sh          # desktop-VM QA: TUN teardown, tray, GNOME proxy
└── GenerateAppIcon.java    # app icon generator
```

### Features (full list)

- **Protocols:** VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard (via sing-box)
- **Transports:** TCP, WebSocket, gRPC, HTTP/2, QUIC
- **TLS / Reality / XTLS-Vision**
- **Modes:** System Proxy, TUN
- **Subscriptions** — auto-refreshing server lists from a URL
- **Routing rules** — geoip, geosite, domain, ruleset
- **Share links** — import/export of `vless://`, `vmess://`, `trojan://`, `ss://`
- **Latency** — one-click ping of every server
- **Traffic** — live stats via the Clash API
- **Tray/menu bar**, **hotkeys**, light/dark **themes**, **Russian/English**

---

## License

TBD. sing-box is licensed under
[GPL-3.0](https://github.com/SagerNet/sing-box/blob/main/LICENSE); the
installers and dev builds bundle its binary unmodified.
