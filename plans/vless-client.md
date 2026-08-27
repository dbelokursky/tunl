# Plan: VLESS Client for macOS

> Source PRD: https://github.com/dbelokursky/vless-client/issues/1

## Architectural decisions

Durable decisions that apply across all phases:

- **Core engine**: sing-box binary bundled in `.app` bundle (`Contents/Resources/sing-box`). Java app generates sing-box JSON config and manages it as a child process via `ProcessBuilder`. All protocol handling is delegated to sing-box — no custom protocol implementations in Java.
- **Tech stack**: Java 25, JavaFX 28, Maven. No Spring framework — manual DI or Guice for fast startup. MVVM pattern in UI (FXML Views → ViewModel classes with JavaFX properties → Service layer).
- **Proxy modes**: Two modes — SOCKS/HTTP system proxy (no root) and TUN (requires root via privileged helper). sing-box binds a local SOCKS5 + HTTP proxy; for TUN mode sing-box creates the TUN device itself.
- **Persistence paths** (all under `~/Library/Application Support/VlessClient/`):
  - `servers.json` — server configurations
  - `settings.json` — app preferences (theme, language, auto-connect, etc.)
  - `subscriptions.json` — subscription URLs and metadata
  - `rules/` — custom routing rule files
  - `logs/` — sing-box and app logs
  - `geodata/` — geosite.db, geoip.db caches
- **Config model**: Internal Java model for server configs (protocol-agnostic `ServerConfig` with protocol-specific subtypes). Bidirectional conversion: `ServerConfig ↔ share URI` and `ServerConfig → sing-box JSON`.
- **sing-box Clash API**: sing-box exposes a local REST API (configurable port, e.g., `127.0.0.1:9090`) for real-time traffic stats and connection info. The Traffic Monitor module polls this.
- **Theming**: Two JavaFX CSS stylesheets (`light.css`, `dark.css`), switched at runtime. Follows macOS system appearance by default.
- **i18n**: Java `ResourceBundle` with `messages_en.properties` and `messages_ru.properties`.
- **Packaging**: `jlink` + `jpackage` to create a self-contained `.app` with bundled JRE and sing-box binary. Distributed as `.dmg`.
- **Auto-update**: Check GitHub Releases API on startup (and periodically). Download new `.dmg`, prompt user to install.

---

## Phase 1: Project Skeleton + VLESS Connect

**User stories**: 1, 7, 8

### What to build

The tracer bullet: a working JavaFX app that can connect to a VLESS server through sing-box.

Set up the Maven project with JavaFX 28 dependencies and jpackage plugin. Create the main application window with a sidebar skeleton (Dashboard, Servers sections — other sections can be placeholder). On the Servers view, provide a form to manually enter a VLESS server (address, port, UUID, encryption, flow, transport type = tcp). Store the config in memory (no persistence yet).

Build the SingBox Engine module: take the entered server config, generate a minimal sing-box JSON configuration (one VLESS outbound, a SOCKS5 inbound on localhost), write it to a temp file, and start sing-box as a child process. The Dashboard view shows connected/disconnected status and a connect/disconnect button. When connected, sing-box binds a SOCKS5 proxy on `127.0.0.1:1080`.

No system proxy auto-configuration yet — the user can manually point their browser to the SOCKS5 proxy to verify it works.

### Acceptance criteria

- [ ] Maven project builds with `mvn clean package` and produces a runnable JavaFX app
- [ ] App launches with a sidebar showing Dashboard and Servers sections
- [ ] User can fill in VLESS server details (address, port, UUID, transport=tcp) and save
- [ ] Clicking "Connect" starts sing-box with a generated config; "Disconnect" stops the process
- [ ] Dashboard shows current connection status (connected / disconnected)
- [ ] SOCKS5 proxy is available on localhost when connected (verifiable with `curl --socks5 127.0.0.1:1080`)
- [ ] sing-box process is cleanly terminated when the app exits
- [ ] Unit tests: sing-box JSON config generation produces valid config for VLESS+TCP

---

## Phase 2: Server Management + Share Links

**User stories**: 2, 5, 23, 24, 25, 27

### What to build

Build out the Config Store module with full persistence. Server configs are saved to `servers.json` and loaded on startup. The Servers view becomes a list showing all saved servers (name, address, protocol badge). Users can add, edit, delete, and duplicate servers.

Implement `vless://` share link parsing: pasting a vless URI auto-fills the server form. Implement export: right-click a server → "Copy share link" produces a valid `vless://` URI.

The server list should show which server is currently active (connected). Selecting a different server and clicking connect should disconnect the current one and connect the new one.

### Acceptance criteria

- [ ] Server configs persist across app restarts (saved to `~/Library/Application Support/VlessClient/servers.json`)
- [ ] Server list displays all saved servers with name, address, and protocol type
- [ ] Add, edit, delete, and duplicate operations work on servers
- [ ] Importing a `vless://` share link creates a correctly configured server entry
- [ ] Exporting a server produces a valid `vless://` link that re-imports to the same config
- [ ] Active (connected) server is visually indicated in the list
- [ ] Switching servers disconnects the current and connects the new one
- [ ] Unit tests: URI parsing round-trip (import → export → import yields identical config)
- [ ] Unit tests: Config Store save/load preserves all fields

---

## Phase 3: Multi-Protocol + Transports + System Proxy

**User stories**: 31, 32, 33

### What to build

Extend the config model and sing-box config generator to support all major protocols: VMess, Trojan, Shadowsocks, Hysteria2, WireGuard. For each protocol, support its specific fields (e.g., VMess alterId, Trojan password, SS method/password, Hysteria2 password+obfs, WireGuard keys).

Add all transport types: WebSocket (path, headers), gRPC (serviceName), TCP (http header), HTTP/2 (host, path), QUIC, mKCP. Add TLS configuration panel: SNI, ALPN, fingerprint, allowInsecure. Add Reality configuration: publicKey, shortId, serverName, fingerprint.

Implement share link parsing for all formats: `vmess://` (base64 JSON), `trojan://`, `ss://`, `hysteria2://`, `wg://`.

Implement the Network Manager for system proxy mode: when connecting, automatically set macOS system proxy (SOCKS and HTTP) via `networksetup` commands. Clear on disconnect.

The server editor form should adapt dynamically based on selected protocol, showing only relevant fields.

### Acceptance criteria

- [ ] All protocols connect successfully through sing-box: VMess, Trojan, Shadowsocks, Hysteria2, WireGuard
- [ ] All transport types generate correct sing-box config: WebSocket, gRPC, TCP, HTTP/2, QUIC, mKCP
- [ ] TLS settings (SNI, ALPN, fingerprint, allowInsecure) are configurable and applied
- [ ] Reality settings (publicKey, shortId, serverName) are configurable and applied
- [ ] Share links for all protocols can be imported and exported
- [ ] macOS system proxy (SOCKS + HTTP) is set on connect and cleared on disconnect
- [ ] Server editor form adapts fields based on selected protocol
- [ ] Unit tests: sing-box config generation for each protocol + transport combination
- [ ] Unit tests: share link parsing for all URI formats

---

## Phase 4: Dashboard — Traffic Stats + Latency + Logs

**User stories**: 6, 11, 12, 15

### What to build

Build the Traffic Monitor module: enable sing-box's Clash API (`external_controller` in config), poll it periodically for connection statistics. Display real-time upload/download speed and total traffic on the Dashboard using JavaFX charts or gauges. Show per-session and per-server cumulative stats.

Build the Latency Tester: for each server in the list, perform a TCP handshake latency test (or URL test via sing-box). Run tests concurrently. Display latency values next to each server in the server list. Add a "Test All" button.

Build the Logs view: capture sing-box stdout/stderr in a ring buffer, display in a scrollable text area with auto-scroll. Add log level filtering (debug, info, warn, error) and a search/filter box.

### Acceptance criteria

- [ ] Dashboard shows real-time upload and download speed (updating every 1-2 seconds)
- [ ] Dashboard shows total traffic for current session
- [ ] Latency test runs against all servers concurrently and displays results in the server list
- [ ] "Test All" button triggers latency measurement for every server
- [ ] Logs view displays sing-box output in real time
- [ ] Logs can be filtered by level and searched by text
- [ ] Traffic stats update without UI freezing (background polling)
- [ ] Unit tests: Clash API response parsing, rate calculation (bytes delta → speed)
- [ ] Unit tests: latency tester timeout handling

---

## Phase 5: Subscriptions

**User stories**: 3, 4

### What to build

Build the subscription management feature. Users can add a subscription URL, give it a name, and import all servers from it. The app fetches the URL, parses the response (support both base64-encoded line-separated URIs and JSON array formats), and creates server entries grouped under that subscription.

Add auto-refresh: each subscription has a configurable refresh interval (e.g., every 6/12/24 hours). On refresh, new servers are added, removed servers are deleted, and existing ones are updated. Manual refresh button available.

Subscription-imported servers are visually distinguished from manually added ones. Editing a subscription-imported server creates a local override (won't be overwritten on refresh).

### Acceptance criteria

- [ ] User can add a subscription URL and import servers from it
- [ ] Base64-encoded subscription format is parsed correctly
- [ ] JSON subscription format is parsed correctly
- [ ] Servers from a subscription are grouped and labeled in the server list
- [ ] Auto-refresh runs on the configured interval and updates the server list
- [ ] Manual refresh button updates servers immediately
- [ ] Removed servers (no longer in subscription) are cleaned up on refresh
- [ ] Subscription-imported servers are visually distinguished from manual ones
- [ ] Unit tests: subscription response parsing (base64 and JSON formats)
- [ ] Unit tests: server diff logic (add/update/remove on refresh)

---

## Phase 6: TUN Mode

**User stories**: 9, 10

### What to build

Implement TUN mode for system-wide traffic capture. sing-box has built-in TUN support but requires root privileges to create the TUN device and modify routing tables.

Build a privileged helper mechanism: on first TUN activation, prompt for admin password via `osascript` (or `AuthorizationExecuteWithPrivileges`). Start sing-box with elevated privileges when TUN mode is selected. The sing-box config for TUN mode includes `inbounds` with type `tun`, `auto_route: true`, `stack: system` (or `gvisor`).

Add a proxy mode selector to the Dashboard or Settings: "System Proxy" (default, no root) vs "TUN" (requires admin). The mode choice is persisted in settings. When switching to TUN, the existing SOCKS/HTTP system proxy settings are cleared since TUN handles all traffic.

Ensure clean teardown: if the app crashes or is force-quit, the TUN device and routing tables must not be left in a broken state.

### Acceptance criteria

- [ ] User can switch between System Proxy and TUN mode in settings
- [ ] TUN mode prompts for admin privileges only when first activated
- [ ] In TUN mode, all system traffic is routed through the proxy (verified with traceroute or IP check)
- [ ] System Proxy mode continues to work as before (SOCKS/HTTP, no root)
- [ ] Switching modes disconnects and reconnects with the new mode
- [ ] Clean shutdown removes TUN device and restores routing tables
- [ ] App handles sing-box crash gracefully (restarts or shows error, doesn't leave broken network)
- [ ] Integration test: TUN mode binds successfully (requires macOS + admin, CI-skippable)

---

## Phase 7: Routing Rules + Split Tunneling

**User stories**: 13, 14

### What to build

Build the Routing Rules Engine and its UI. Users can define rules to route traffic either through the proxy or directly:

- Domain rules: exact match, suffix match, keyword, regex, or geosite category (e.g., `geosite:google`)
- IP rules: CIDR blocks or geoip category (e.g., `geoip:ru` for direct routing of domestic traffic)

Download and cache `geosite.db` and `geoip.db` from sing-box releases on first use. Auto-update these databases periodically.

Provide preset rule sets: "Route all" (everything through proxy), "Bypass domestic" (direct for local traffic, proxy for everything else), "Custom". The Routing view in the sidebar lets users manage custom rules with add/remove/reorder.

Generate the `route` section of sing-box config from the rule model.

### Acceptance criteria

- [ ] User can create domain-based routing rules (exact, suffix, keyword, geosite category)
- [ ] User can create IP-based routing rules (CIDR, geoip category)
- [ ] Preset rule sets are available: "Route all", "Bypass domestic", "Custom"
- [ ] geosite.db and geoip.db are downloaded and cached locally
- [ ] Routing rules are correctly reflected in generated sing-box config
- [ ] Rules can be added, removed, and reordered in the UI
- [ ] Changing routing rules takes effect on next connect (or reconnect if already connected)
- [ ] Unit tests: sing-box route config generation from rule model
- [ ] Unit tests: geosite/geoip database loading and category lookup

---

## Phase 8: Theming, i18n, UX Polish

**User stories**: 16, 17, 18, 19, 22, 26, 28, 29, 34

### What to build

Implement visual theming: create `light.css` and `dark.css` stylesheets for JavaFX. By default, follow macOS system appearance (detect via `NSAppearance` or JavaFX's built-in platform detection). Add a manual override in Settings: "System", "Light", "Dark". Theme switches instantly without restart.

Implement i18n: extract all UI strings to `ResourceBundle` property files (`messages_en.properties`, `messages_ru.properties`). Add language selector in Settings. Language switches without restart (reload FXML views or rebind string properties).

Add a macOS menu bar (system tray) icon: shows connection status (colored dot or different icon), click opens the app window or shows a mini menu (connect/disconnect, current server, quit).

Add keyboard shortcuts: Cmd+N (new server), Cmd+Q (quit), Cmd+, (settings), custom shortcut for connect/disconnect (e.g., Cmd+Shift+C).

Add auto-connect on startup: optional setting to reconnect to the last used server when the app launches.

Add server reordering via drag-and-drop in the server list.

Improve error messages: connection failures should show clear, user-friendly messages (e.g., "Connection refused — check server address and port" rather than raw exception text).

### Acceptance criteria

- [ ] Light and dark themes render correctly across all views
- [ ] Theme follows macOS system appearance by default
- [ ] Manual theme override in Settings works and switches instantly
- [ ] All UI text is available in English and Russian
- [ ] Language can be switched in Settings without restarting the app
- [ ] Menu bar icon shows connection status and provides quick connect/disconnect
- [ ] Keyboard shortcuts work: new server, settings, connect/disconnect, quit
- [ ] Auto-connect on startup can be toggled in Settings and works when enabled
- [ ] Servers can be reordered via drag-and-drop
- [ ] Connection errors display clear, actionable messages
- [ ] UI tests (TestFX): theme switching, language switching, sidebar navigation

---

## Phase 9: Packaging + Auto-Update

**User stories**: 20, 21, 30

### What to build

Configure the Maven build to produce a distributable macOS application:

- Use `jlink` to create a minimal custom JRE with only required modules
- Use `jpackage` to bundle the JRE, app JARs, and sing-box binary into a `.app` bundle
- Generate a `.dmg` installer with a drag-to-Applications layout
- Ensure the bundle works on both Intel and Apple Silicon (include universal sing-box binary or detect architecture at build time)

Build the Update Manager: on startup (and periodically, e.g., every 24 hours), check GitHub Releases API for a newer version. Compare semantic versions. If an update is available, show a non-intrusive notification in the app. User can click to download the new `.dmg`. Optionally auto-download in the background.

Also support updating the bundled sing-box binary independently: check sing-box GitHub releases, download the new binary, replace it in the app bundle.

Set up a GitHub Actions CI/CD pipeline: build on push, run tests, create release artifacts (.dmg) on tagged commits.

### Acceptance criteria

- [ ] `mvn clean package` produces a `.app` bundle with bundled JRE and sing-box
- [ ] `.dmg` installer is generated and works on a clean macOS machine
- [ ] App runs on both Intel and Apple Silicon Macs
- [ ] Update check runs on startup and finds new GitHub Releases
- [ ] Update notification is shown in the app when a new version is available
- [ ] User can download the update from within the app
- [ ] sing-box binary can be updated independently of the app
- [ ] GitHub Actions CI builds, tests, and creates release artifacts
- [ ] Unit tests: version comparison logic, GitHub Release API response parsing
