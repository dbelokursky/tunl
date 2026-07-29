# Plan: MCP Server for VLESS Client

> Goal: expose the running VLESS Client to AI agents via the Model Context
> Protocol — so agents can inspect state (status, traffic, logs), read/modify
> configuration (servers, routing, subscriptions, settings), and drive the
> connection (connect/disconnect/select server). Opt-in, loopback-only,
> token-guarded.

## Why this makes sense

The app already has a clean service layer (`ServiceLocator` → `SingBoxEngine`,
`ConfigStore`, `TrafficMonitor`, `LogReader`, `SubscriptionService`,
`RoutingService`, `LatencyTester`, `ShareLinkParser`). Every capability an agent
would want is already a method on a service — the MCP server is a thin,
well-tested adapter over those, plus a transport. Low structural risk, high
"cool factor": you get an agent-configurable VPN with live-log introspection.

## Architectural decisions (durable across phases)

- **Embedded, in-process server.** The MCP server runs as a thread *inside* the
  JavaFX app, with direct access to `ServiceLocator`. Rationale: the GUI process
  owns the single `sing-box` child process and holds live state (logs, traffic,
  connection). A separate process controlling `sing-box` in parallel would
  conflict. So the agent must talk to the *running instance*.
- **Transport = Streamable HTTP over loopback.** MCP stdio assumes the client
  *spawns* the server; our server is a long-lived GUI, so we host HTTP instead.
  Bind strictly to `127.0.0.1:<port>` (default `8790`, configurable). Agents
  connect with `claude mcp add --transport http vless http://127.0.0.1:8790/mcp`.
  *(SSE transport is the fallback if the SDK version lacks Streamable HTTP.)*
- **Official MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`). Use its
  synchronous server API + an HTTP transport provider. Host it on the JDK
  built-in `com.sun.net.httpserver.HttpServer` (no servlet container / Jetty
  dependency) via a small adapter, keeping startup fast — consistent with the
  project's "no Spring, fast startup" stance.
- **Security is first-class** (this is a VPN control surface):
  - Loopback bind only; never `0.0.0.0`.
  - Bearer token, generated on first enable, stored at
    `~/Library/Application Support/VlessClient/mcp-token` with `0600` perms.
    Every request must present `Authorization: Bearer <token>`.
  - **Off by default.** A `settings.json` flag `mcpEnabled` (+ `mcpPort`) toggled
    from the Settings tab. Server thread starts/stops with the flag.
  - Privileged/destructive ops (TUN connect → sudo prompt, `delete_server`) are
    gated behind an explicit `confirm: true` argument, and there's a master
    `mcpAllowMutations` setting (default **on** for config, but connect/TUN
    always require `confirm`).
  - An append-only audit log (`logs/mcp-audit.log`) records every tool call
    (tool, args-summary, caller, result) — so you can see what an agent did.
- **Thread marshalling.** Service state lives on JavaFX properties and must be
  read/mutated on the FX Application Thread. Introduce `FxExecutor` helper:
  tool handlers submit work via `Platform.runLater` wrapped in a
  `CompletableFuture` and block (with timeout) for the result. Read-only,
  thread-safe services (e.g. `ConfigStore` is `synchronized`) can be called
  directly.
- **Testability via a facade.** Tools call a single `AppControlService`
  interface (not the concrete services directly). Real impl delegates to
  `ServiceLocator`; tests inject a fake — matching the existing
  `service/*Test.java` unit-test style. Each tool = one method on the facade.
- **JSON via Jackson** (already the project's mapper). Tool input/output schemas
  are hand-written `Map`/POJO ↔ JSON; reuse existing model DTOs
  (`ServerConfig`, `RoutingRule`, `AppSettings`) where possible.

## Tool & resource surface (target)

**Read / observability**
- `get_status` → state, active server, proxy mode, uptime, ports, error.
- `get_traffic` → up/down speed + totals (from `TrafficMonitor`).
- `list_servers` → id, name, protocol, endpoint, latency, active flag.
- `get_logs` → tail N lines, optional level/substring filter (from the
  `SingBoxEngine` observable ring buffer + logback file).
- `list_subscriptions`, `get_routing`, `get_settings`.

**Actions**
- `connect { serverId?, mode? }`, `disconnect`.
- `select_server { serverId }`.
- `measure_latency { serverId? }` → triggers `LatencyTester`.
- `refresh_subscription { id }`.

**Config mutation** (guarded by `mcpAllowMutations`)
- `add_server { shareLink }` or `{ fields… }` (reuse `ShareLinkParser`).
- `update_server`, `delete_server { id, confirm }`.
- `set_proxy_mode { mode }`, `set_setting { key, value }`.
- `add_routing_rule`, `remove_routing_rule`, `set_routing`.

**MCP Resources** (browsable, not just callable)
- `vless://status`, `vless://traffic`, `vless://servers`,
  `vless://settings`, `vless://logs/recent`.

**Notifications** (phase 5)
- Resource-update + MCP logging notifications: stream log lines and push
  status/traffic changes to subscribed clients.

---

## Phase 1 — Tracer bullet: HTTP MCP server + one live tool

**Build:** Add MCP Java SDK dependency. Create
`service/mcp/McpServerService` that: on `mcpEnabled`, starts a loopback
`HttpServer` hosting the MCP endpoint with token auth; registers exactly **one**
tool `get_status` wired through a first cut of `AppControlService` →
`SingBoxEngine`/`ConfigStore`. Add `mcpEnabled`, `mcpPort` to `AppSettings`;
generate+persist the token file (0600). Wire start/stop into `ServiceLocator`
lifecycle and app shutdown hook.

**Done when:** `claude mcp add --transport http vless http://127.0.0.1:8790/mcp`
(with token header) lists `get_status`, and calling it returns the real live
connection state of the running app. Unit test: fake facade → `get_status`
serializes correctly; integration test: server boots, rejects missing token,
accepts valid token.

## Phase 2 — Full read/observability surface

`get_traffic`, `list_servers`, `get_logs` (with filters), `list_subscriptions`,
`get_routing`, `get_settings`, plus the read-only MCP **resources**. Flesh out
`AppControlService` read methods + `FxExecutor` for property reads. Tests per
tool with fake facade.

## Phase 3 — Connection control actions

`connect` / `disconnect` / `select_server` / `measure_latency` /
`refresh_subscription`. Introduce the `confirm` gate for TUN-mode connect
(sudo prompt) and the audit log. Careful FX-thread marshalling since these
mutate live `SingBoxEngine` state and trigger the sudo `osascript` path.

## Phase 4 — Config mutation

`add_server` (share-link + fields via `ShareLinkParser`), `update_server`,
`delete_server` (confirm), `set_proxy_mode`, `set_setting`, routing-rule
CRUD. Honour `mcpAllowMutations`. Validate inputs; reuse existing model
validation. Round-trip tests (add via link → list → delete).

## Phase 5 — Live notifications + Settings UI

MCP resource-update + logging notifications: subscribe log lines and
status/traffic to push. Add the **Settings tab** controls: enable toggle,
port, "allow mutations", regenerate-token button, and a copy-ready
`claude mcp add …` snippet + the token. Update `README.md` with an
"Agent control (MCP)" section.

## Phase 6 — Hardening & packaging

Rate-limit / connection cap, tighten audit log, error taxonomy (typed MCP
errors), fuzz the tool inputs, coverage pass. Confirm it survives the
`.app`/jpackage bundle (module-path / `com.sun.net.httpserver` availability).
Document the loopback-only + token security model.

---

## Open decisions (need your call)

1. **Transport:** embedded HTTP-on-loopback (recommended) vs a separate stdio
   bridge process. HTTP is the clean fit for a running GUI; stdio would only
   help if you want to configure the app while it's *closed* (different design).
2. **Default port** `8790` OK? (avoids the 1080/1081/9090 the app already uses.)
3. **Mutation policy default:** allow config edits (add/edit servers, routing)
   out-of-the-box, but always require `confirm:true` for connect-with-TUN and
   deletes? Or start fully read-only and you flip mutations on explicitly?
