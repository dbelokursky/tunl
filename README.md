# Tunl

<p align="center">
  <img src="src/main/resources/icons/app-icon-256.png" width="128" alt="Tunl icon"/>
</p>

<p align="center">
  <a href="https://github.com/dbelokursky/tunl/actions/workflows/build.yml"><img src="https://github.com/dbelokursky/tunl/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases/latest"><img src="https://img.shields.io/github/v/release/dbelokursky/tunl" alt="Latest release"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases"><img src="https://img.shields.io/github/downloads/dbelokursky/tunl/total" alt="Downloads"/></a>
  <a href="https://github.com/dbelokursky/tunl/releases/latest"><img src="https://img.shields.io/github/downloads/dbelokursky/tunl/latest/total?label=downloads@latest" alt="Latest release downloads"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/dbelokursky/tunl" alt="License"/></a>
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

There is no Homebrew tap yet, so `brew install` cannot fetch Tunl today. Every
release does attach a ready-made cask file (`tunl.rb`, version and SHA-256
filled in) for anyone who wants to publish it in a tap of their own — Homebrew
refuses to install a cask from a bare file. See
[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

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

There is no AUR package yet. Every release attaches a `PKGBUILD` (and its
`.SRCINFO`, listed as `default.SRCINFO` on the release page) that repackages
the `.deb` for Arch: download the `PKGBUILD` into an empty directory and run
`makepkg -si`, or use it to publish the package yourself — see
[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

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
   `vless://…` / `vmess://…` / `trojan://…` / `ss://…` / `hysteria2://…`
   link from your provider, or a whole WireGuard `.conf` — the form fills
   itself. (Or **Add Server** and fill the form manually; a third way is the
   **Subscriptions** tab with a subscription URL — the server list will keep
   itself in sync.) On a fresh install the Dashboard card links straight to
   the Servers view.
2. **Check the active server** — clicking a server row gives it the
   **ACTIVE** badge; that's the server used when you connect.
3. **Hit Connect** on the **Dashboard** tab (or `⌘⇧C` / `Ctrl+Shift+C`).
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

Next to it, **Server** decides who carries the traffic: **Selected server** —
the one with the ACTIVE badge — or **Fastest available**, where the core
measures every server, moves traffic to the quickest one on its own, and the
status line names the server it actually picked.

Either way, Tunl's own requests — subscription refreshes, update checks and
downloads, the country database — go **through the tunnel** while it is up, so
they work on a network that blocks the sites they talk to. (While the tunnel
is up but failing its reachability checks, a subscription refresh is postponed
rather than sent around it.)

In TUN mode the tunnel captures IPv6 as well (**Settings → Advanced → Route
IPv6 through the tunnel**, on by default); turn it off for a server without
IPv6 egress.

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
**Duplicate**, **Copy Share Link** (greyed out for WireGuard, which has no
share-link format), **Delete**.

A server whose TLS certificate is never verified (`allowInsecure` in the link
or the subscription) carries an amber **NO CERT CHECK** badge: anyone on the
network path could impersonate it.

**Backup** exports every server to a JSON file — credentials in plain text, so
keep it somewhere private — and imports such a file, or a plain list of share
links, back.

### Subscriptions

**Subscriptions** tab: add a URL — the server list is fetched and re-synced
every hour. **Edit** changes the name or the URL in place (the servers follow
the new list). The row shows only the scheme and host of the URL, since the
account token lives in the path, and the provider's quota when the response
carries a `subscription-userinfo` header ("12.3 GB of 100 GB used · expires
…"). Links of protocols Tunl does not support (TUIC, AnyTLS, …) are left out
without marking the subscription failed.

### Routing

<p align="center">
  <img src="docs/screenshots/routing.png" width="900" alt="Routing tab: bypass countries, bypass list and custom rules"/>
</p>

**Routing** tab, top to bottom: **Bypass countries** (their traffic goes
direct; countries match by IP ranges, and ru, cn and ir by domain as well), a
**Bypass list** (one host, wildcard, CIDR or IP per line) and **Custom rules**
that match by domain, domain suffix, domain keyword, domain regex, GeoSite,
IP CIDR or GeoIP and send the match to **Proxy**, **Direct** or **Block**. A
Block rule rejects the connection outright (TCP reset, ICMP unreachable)
rather than silently dropping it. In TUN mode the names of direct-routed
traffic — the bypass list, Direct rules, bypassed countries — are resolved
through the **Direct DNS** server from Settings instead of through the tunnel,
so resolution follows the same split as the traffic.

### Monitoring

The **Dashboard** shows live stats: upload/download speed and totals.
**Logs** streams sing-box logs with a level filter (how much the core writes
is **Settings → Core log level**); the download button saves the core log,
and **Save diagnostics** writes a zip for bug reports with credentials, URLs,
server addresses and SNIs redacted — read it before sharing anyway.

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

The icon's colour answers "is my traffic getting out?", not "did the core
start?" — it stays amber until the service checks come back:

| Colour | Meaning |
|---|---|
| ⚪️ Grey | Disconnected |
| 🟠 Amber | Connecting, verifying, or only some services reachable |
| 🟢 Green | Connected and every checked service is reachable |
| 🔴 Red | Failed to start, or connected with nothing getting through |

With the service checks switched off there is nothing to verify, so a
connected tunnel is simply green.

When the core exits unexpectedly the tray also posts a system notification
("Tunnel stopped") — a tunnel that dies while the window is hidden should not
be silent.

### Autostart

| OS | Mechanism |
|---|---|
| macOS | LaunchAgent |
| Windows | Run registry key (native exe) |
| Linux | XDG autostart (`~/.config/autostart`) |

### Updating the sing-box core

The core ships pinned with the app and moves only when the app is updated —
there is no separate core update. On startup a cache left by an earlier pin is
discarded, so the bundled binary always wins.

### Hotkeys

`⌘` on macOS = `Ctrl` on Windows/Linux.

| Hotkey | Action                                                        |
|--------|---------------------------------------------------------------|
| `⌘⇧C`  | Connect / Disconnect                                          |
| `⌘N`   | Add server                                                    |
| `⌘1`   | Dashboard                                                     |
| `⌘2`   | Servers                                                       |
| `⌘3`   | Subscriptions                                                 |
| `⌘4`   | Routing                                                       |
| `⌘5`   | Logs                                                          |
| `⌘,`   | Settings                                                      |
| `⌘W`   | Hide window (keeps running in the tray)                       |
| `⌘Q`   | Quit (macOS; on Windows/Linux use **Quit** in the tray menu)  |

### Settings

<p align="center">
  <img src="docs/screenshots/settings.png" width="900" alt="Settings: appearance, connection and health check"/>
</p>

The view is a column of cards. Text fields save when you press Enter or leave
the field (an empty or out-of-range number keeps the stored value); everything
else saves on click.

| Card | Setting | Description |
|---|---|---|
| Appearance | Theme | Auto (follows the OS) / Light / Dark |
| | Language | English / Russian |
| Connection | Auto-connect on startup | Connect to the active server on launch |
| | Launch at login | Start Tunl with the OS (see [Autostart](#autostart)) |
| | SOCKS Port / HTTP Port | The local proxy listeners, `1080` and `1081` by default; both bind to `127.0.0.1` only |
| | Core log level | How much sing-box writes to the Logs tab: Debug / Info / Warning / Error; takes effect on the next connect |
| | Proxy Mode | System Proxy / TUN — the same switch as **Mode** on the Dashboard |
| | Set system proxy automatically | In System Proxy mode, point the OS at the local ports on connect and restore it on disconnect |
| Health Check | Enable health check | After connecting, verify that traffic actually reaches a few services (on by default) |
| | Auto-reconnect when unreachable | Reconnect after the reconnect delay when every check fails |
| | Check interval / Reconnect delay | Seconds between checks (`5`) and before a reconnect (`10`) |
| Advanced | Proxy DNS | Resolver for tunnelled names, queried through the tunnel (`https://1.1.1.1/dns-query`) |
| | Direct DNS | Resolver for direct-routed names — the bypass list, Direct rules, bypassed countries (`https://223.5.5.5/dns-query`) |
| | TUN Interface Name | `utun99` on macOS and Linux, `VlessClientTun` on Windows |
| | TUN IPv4 Address | `172.19.0.1/30` |
| | Route IPv6 through the tunnel | On by default; off, IPv6 traffic bypasses the VPN on dual-stack networks |
| | Store credentials in the system keychain | Seal server credentials and subscription URLs with Keychain / DPAPI / Secret Service instead of writing them into the JSON files |
| Agent Control (MCP) | Enable MCP server, Port, Allow configuration changes, Copy command, Regenerate token | See [Agent control](#agent-control-mcp) |
| About | Tunl version, sing-box version, Check for updates | The version rows double as update status; **Restart now** appears once an update is staged. The DB-IP attribution for the country flags lives here too |

The DNS and TUN settings only apply in TUN mode: in System Proxy mode the
core has no DNS section and the OS resolver is used as before.

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

## Agent control (MCP)

Tunl can run a local **MCP server** (Model Context Protocol) so AI agents such
as Claude Code can inspect state and drive the client: status, traffic, logs,
server list, connect/disconnect, add servers from share links, routing rules,
and more.

### Security

- Listens on **`127.0.0.1`** (loopback) only — never reachable from the
  network — and refuses any request whose `Host` or `Origin` header is not
  loopback (HTTP 403), which shuts the door on DNS-rebinding pages that would
  otherwise reach the port through the browser.
- Every request needs an `Authorization: Bearer <token>` header; the token lives
  in `mcp-token` (in the data dir) with `0600` permissions.
- **Off by default** — enabled from Settings.
- Mutating operations are gated by the "Allow configuration changes" toggle
  (on by default). The most dangerous ones (connecting in TUN mode, which
  prompts for the macOS admin password; deleting a server) additionally
  require an explicit `confirm: true`.
- Every mutating call is appended to `logs/mcp-audit.log`.
- Log lines handed to agents (`get_logs`, the SSE stream) have URLs reduced to
  scheme and host, so a subscription's account token never leaves the app
  through this door.

### Enable it

**Settings → Agent Control (MCP):**
1. Tick **Enable MCP server** (default port `55555`).
2. Optionally toggle **Allow configuration changes** (on by default).
3. Click **Copy command** and run it:

```bash
claude mcp add --transport http tunl http://127.0.0.1:55555/mcp \
  --header "Authorization: Bearer <your-token>"
```

**Regenerate token** issues a new token and restarts the server.

### Tools

| Category | Tools |
|----------|-------|
| Read     | `get_status`, `get_traffic`, `list_servers`, `get_logs`, `list_subscriptions`, `get_routing`, `get_settings` |
| Actions  | `connect`, `disconnect`, `select_server`, `measure_latency`, `refresh_subscription` |
| Config   | `add_server`, `update_server`, `delete_server`, `set_proxy_mode`, `set_setting`, `add_routing_rule`, `remove_routing_rule` |

Browsable resources: `vless://status`, `vless://traffic`, `vless://servers`,
`vless://routing`, `vless://settings`, `vless://logs/recent`. Live log streaming
is available over SSE (`GET /mcp`, `notifications/message`).

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
**ACTIVE** badge (on a fresh install the Dashboard card links straight there).

**"Process exited unexpectedly (code N)"**
sing-box crashed; the tray posts a "Tunnel stopped" notification. The reason
is in the **Logs** tab. Common ones: wrong UUID, wrong transport, unreachable
server, port conflict.

**TUN mode asks for a password every time**
Creating a TUN interface requires root/admin: macOS shows an osascript prompt
(or set up sudo-NOPASSWD — then the password is asked once), Windows — UAC,
Linux — pkexec (or a one-time `setcap`).

**Port 1080 or 1081 is busy**
The core fails to start and the **Logs** tab shows the bind error. Change
**SOCKS Port** / **HTTP Port** in **Settings → Connection** to free ones.

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
pinned in [singbox.properties](src/main/resources/singbox.properties)) for the
build host's OS and architecture into `target/classes/native/{os}-{arch}/`
with SHA-256 verification. The binary is bundled into the jar and extracted on
first launch. Only the host's architecture is bundled: jpackage produces a
single-architecture app, so a DMG built on Apple Silicon could never run an
Intel core anyway.

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

- JDK 25
- Maven 3.9+
- bash + curl + tar (standard on macOS) — needed by `generate-resources`
  to download sing-box

### Commands

```bash
mvn clean verify            # the gate: checkstyle, tests, coverage, SpotBugs
mvn clean javafx:run        # run in dev mode
mvn clean package           # build the shaded jar (with the sing-box bundle)
mvn test                    # tests only
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

- [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md) — what `release.yml`
  automates on a `v*` tag, and the checks that stay manual (Windows-heavy,
  since no CI or VM covers the UAC/tray/proxy paths).
- [docs/SIGNING.md](docs/SIGNING.md) — activate the dormant macOS notarization
  and Windows signing by adding the documented secrets; the workflow steps are
  already in place.
- [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) — the Homebrew cask and AUR
  PKGBUILD templates in [packaging/](packaging/), filled in by
  [scripts/update-packaging.sh](scripts/update-packaging.sh) and attached to
  every release; no tap or AUR package is published yet. winget/Flatpak
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
├── css/            # base.css (rules) + light.css / dark.css (tokens)
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
- **Transports:** TCP, WebSocket, gRPC, HTTP/2, HTTP Upgrade, QUIC
- **TLS / Reality / XTLS-Vision**
- **Modes:** System Proxy, TUN (IPv4 and IPv6)
- **Server selection** — the pinned server, or the fastest available (sing-box `urltest`)
- **Subscriptions** — hourly-refreshing server lists from a URL, with the provider's quota
- **Routing** — bypass countries, a bypass list, custom rules by domain / suffix / keyword / regex / GeoSite / IP CIDR / GeoIP → Proxy, Direct or Block
- **Share links** — import of `vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://` (`hy2://`) and WireGuard `.conf`; export of everything but WireGuard
- **Backup** — export/import of the server list
- **Latency** — one-click ping of every server, through the proxy while connected
- **Traffic** — live stats via the Clash API
- **Diagnostics** — a redacted bundle for bug reports
- **Tray/menu bar**, **hotkeys**, Auto/light/dark **themes**, **Russian/English**
- **Agent control** — a loopback MCP server for AI agents
- **Updates** — in-app updater verifying SHA-256 and an Ed25519 signature

---

## License

[Apache-2.0](LICENSE). Copyright and attribution live in [NOTICE](NOTICE).

sing-box is licensed under
[GPL-3.0](https://github.com/SagerNet/sing-box/blob/main/LICENSE); the
installers and dev builds bundle its binary unmodified, as a separate process
invoked over a documented interface. The bundled version is pinned in
[`singbox.properties`](src/main/resources/singbox.properties) and its source is
available upstream.
