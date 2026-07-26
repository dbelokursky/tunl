# Proxy groups, real latency, WireGuard import, server-list UX — implementation plan

Status: proposed (2026-07).

Four features that together close the gap with v2rayN / Nekoray / Hiddify for
the case this client is weakest at today: **a user with a 30-server
subscription where half the servers are dead on any given day.** Right now that
user picks servers by hand, guided by a latency number that cannot tell a
working server from a blocked one.

## Goal & scope

- **Proxy groups** — `url-test` (auto-pick lowest latency) and `fallback`
  (auto-failover), plus explicit manual selection, replacing today's single
  "active server".
- **Real latency** — measure the proxy handshake, not the TCP path to the host.
- **WireGuard import** — the protocol is supported by the config generator but
  can only be entered by hand.
- **Server list at scale** — search, sort, and grouping by subscription.

Out of scope: per-app/split-tunnel routing, a connections view, QR import,
Clash-YAML subscriptions. Each is a separate piece of work; none is a
prerequisite for these four.

## The discovery that makes groups cheap

Everything downstream of the proxy already points at **one tag**: routing's
`final`, the DNS `detour`, and every rule-set `download_detour` all reference
`"proxy"` (`SingBoxConfigGenerator` lines 132, 156, 599, 656). Today that tag
belongs to the single proxy outbound built from the active server.

sing-box lets a **group** carry that tag. So the change is:

```
outbounds: [ {tag: "proxy", type: "selector"|"urltest", outbounds: [...]},
             {tag: "srv-<id>", ...},   // one per server, as today's builders emit
             {tag: "direct", type: "direct"} ]
```

Route, DNS and rule-sets stay **untouched** — they keep resolving `"proxy"`,
which now resolves to a group instead of a server. That is the whole
architectural cost: `buildOutbounds` goes from emitting one proxy outbound to
emitting N plus a group node, and every builder in `service/outbound/` keeps
its current contract apart from taking a tag.

The second lever is already installed too: **clash_api** is enabled and
authenticated (`ConfigStore.newClashApiSecret`, consumed by `TrafficMonitor`).
It exposes selector switching and per-proxy delay — which is what Feature 2
needs, and what lets the UI show a group's current pick without restarting the
core.

## Guiding principles

- **The tunnel keeps working at every phase.** Each phase ships behind a
  default that reproduces today's behaviour: a single-server group is
  byte-for-byte equivalent to what the generator emits now.
- **No new config schema until it earns itself.** Phase 1 needs no persisted
  model change at all.
- **`sing-box check` is the gate.** The smoke suite already validates every
  protocol × mode; group configs join it rather than getting a parallel path.
- **Honest numbers.** A latency figure that cannot distinguish "blocked" from
  "slow" is worse than no figure; label what was actually measured.

---

## Phase 1 — One server, expressed as a group (no user-visible change)

The refactor, isolated from any behaviour change.

- `buildOutbounds` emits `srv-<serverId>` for the active server plus a
  `selector` tagged `"proxy"` whose `outbounds` is that single tag.
- Every `service/outbound/*Builder` takes the tag to emit instead of
  hardcoding `"proxy"`.
- WireGuard keeps its endpoint special case (it is an `endpoints` entry, not an
  outbound) — the group references its tag the same way.

**Done when:** the generated config differs from today only by the extra
indirection, `sing-box check` passes for every protocol × mode in the smoke
suite, and connecting works unchanged.

**Risk:** the WireGuard endpoint/outbound asymmetry. Mitigation: assert the
generated JSON for a WireGuard server in `SingBoxConfigGeneratorTunTest`
before touching the shape.

---

## Phase 2 — Group model and manual selection

- New `ProxyGroup` model: id, name, mode (`MANUAL` | `URL_TEST` | `FALLBACK`),
  member server ids, test URL, interval, tolerance.
- Persisted as `groups.json` through `SecureFiles` (owner-only, atomic) with
  the `config_version` envelope `servers.json` already uses.
- **Migration:** today's single active server becomes a one-member `MANUAL`
  group, so an upgraded install behaves identically before the user touches
  anything.
- `ConfigStore.setActiveServer` gains a group-aware sibling; the Dashboard's
  "active server" readout becomes "active group → current member".

**Done when:** a user can create a group, add servers, and switch member from
the UI with the same durability guarantees as the active-server fix (persisted,
list-change fired, tray and list cannot diverge).

---

## Phase 3 — `url-test` and `fallback`

- Generator emits `type: "urltest"` (with `url`, `interval`, `tolerance`) or
  `type: "fallback"` for those modes.
- The Dashboard shows which member the group currently uses, read from
  clash_api rather than guessed.
- Switching a member while connected goes through clash_api's selector PUT —
  **no tunnel restart**, unlike today's server switch (which restarts, by
  design, since the whole config changes).

**Done when:** with a dead server in the group, connecting still works and the
UI names the member actually carrying traffic.

**Risk:** clash_api group names must match the tags the generator emits.
Mitigation: one source of truth for tag naming, asserted in a test that
compares generator output with the names the client requests.

---

## Phase 4 — Real latency

Today's `LatencyTester` opens a raw `Socket` to `address:port`
(`LatencyTester.java:88`). That measures the ISP path to the host: a server
that is reachable but blocked, throttled, or wrong-credentialed reports a
healthy 40 ms.

Two honest measurements, clearly labelled:

| When | How | What it means |
|---|---|---|
| Connected | clash_api `GET /proxies/<tag>/delay?url=…` | real proxy handshake + HTTP round trip |
| Disconnected | today's TCP connect | "host reachable", nothing more |

- Results become **actionable**: clicking a row selects that server/member
  (the current list is read-only), and rows sort by delay.
- The distinction is shown, not hidden — a TCP figure is never presented as if
  it were a proxy measurement.

**Done when:** a blocked-but-pingable server is visibly distinguishable from a
working one while connected.

**Risk:** delay probes through a live tunnel add traffic and can disturb a
metered connection. Mitigation: on demand plus a bounded interval, never a
tight loop; reuse the health-check settings rather than inventing new ones.

---

## Phase 5 — WireGuard import

`ShareLinkParser.parse` dispatches on scheme (`:36-40`); WireGuard is the one
supported protocol with no entry, so it is manual-entry-only — a private key
typed by hand.

**There is no standard WireGuard share link.** What users actually have is a
`.conf` file (INI: `[Interface]` / `[Peer]`). So:

- Parse `.conf` text — pasted or opened from a file — into `ServerConfig`,
  reusing `WireguardEndpointBuilder`'s existing field expectations.
- Keep it out of `ShareLinkParser` (that class is URI-shaped); add a sibling
  `WireguardConfigParser` and wire it into the import dialog as a second input
  mode.
- Reject partial configs loudly, using the validation surface Phase 0 of the
  UI work already added, rather than silently producing a server that cannot
  connect.

**Done when:** a stock `wg0.conf` imports and connects, and a malformed one
explains what is missing.

---

## Phase 6 — Server list at scale

Pure UI on top of an `ObservableList` that already fires proper change events
after the active-server fix.

- Search box filtering by name, address, protocol (`FilteredList`).
- Sort by name / latency / protocol (`SortedList`), latency reusing Phase 4.
- Group rows by their originating subscription, so a 40-server import is
  navigable.
- Multi-select + bulk delete, with the confirmation pattern used elsewhere.

**Done when:** a 40-server subscription is usable without scrolling hunting.

---

## Sequencing and cost

Phases 1–3 are one dependency chain and carry the real risk; 4–6 are
independent of each other and only need Phase 1's tag scheme.

| Phase | Depends on | Rough size |
|---|---|---|
| 1 — group indirection | — | small, mechanical, well tested |
| 2 — model + manual | 1 | medium (new persisted model + migration) |
| 3 — url-test/fallback | 2 | medium (clash_api integration) |
| 4 — real latency | 1 (tags) | medium |
| 5 — WireGuard import | — | small |
| 6 — list UX | — | small |

Suggested order: **1 → 5 → 6 → 2 → 3 → 4**. Phases 5 and 6 are small, visible
wins that ship while the group work is still in progress, and neither can be
destabilised by it.

## Risks & open questions

- **Does the user still have an "active server"?** Once groups exist, the
  concept splits into "active group" and "the member it chose". The tray menu,
  the Dashboard readout and the share-link import path all assume the old
  concept today. Decide the vocabulary in Phase 2 and apply it everywhere at
  once, or the two models will drift the way the list and tray did.
- **Group membership vs. subscription refresh.** A refresh deletes and recreates
  servers; a group holding their ids would silently empty. Either groups hold a
  subscription reference rather than ids, or refresh must remap. Needs deciding
  before Phase 2 persists anything.
- **clash_api availability.** Everything in Phase 3 and half of Phase 4 needs
  the core running with clash_api reachable. The UI must degrade to manual
  selection and TCP latency when it is not, rather than showing blanks.
- **Not verifiable here:** none of this can be validated against a real
  censored network from a developer machine. The smoke suite proves the config
  is accepted by sing-box; it cannot prove `url-test` picks a *usable* server.
  A manual pass with a deliberately dead member belongs in
  `docs/RELEASE-CHECKLIST.md`.


---

## Decisions taken during implementation

**Membership is a mode, not a stored list (option A).** The two questions this
plan flagged as blocking — what "the active server" means once groups exist,
and what a subscription refresh does to a stored membership — both came from
one choice: persisting the member list. Replacing it with a rule evaluated at
connect time removes both. `ServerSelection` is therefore a setting
(`single` / `auto_best`), not an entity, and membership is derived in
`SingBoxConfigGenerator.groupMembers`.

**There is no fallback mode.** The plan assumed sing-box offers `url-test` and
`fallback` group types. It does not: the real core answers
`unknown outbound type: fallback` — that is a Clash concept. Only `selector`
and `urltest` exist, confirmed by probing the shipped binary. `urltest` already
covers the fallback case, since it only picks among members that answered the
probe, so a dead server is excluded rather than ranked last.

**A selector may reference a WireGuard endpoint.** Checked against the real
core before the shape was adopted, because endpoints and outbounds are separate
namespaces and this was not obvious. It works, so WireGuard participates in
groups like any other protocol.

**Activation is a click, not a selection change (phase 6).** The server list
bound "make this active" to the selection model, which only worked because the
list was never rebuilt underneath it. A `FilteredList` rebuilds it on every
keystroke, and a rebuilt list moves the selection on its own — so searching
would have silently switched the server the app connects through. Two existing
faults surfaced with it: arrow-key navigation was writing the config and
restarting a live tunnel once per keypress, and a multi-select was impossible
because every row touched while building one would activate. Activation now
comes from a plain primary click or Enter; modifier-clicks build a selection.

**Grouping by subscription was dropped, not deferred.** The plan asked for
group rows per originating subscription. `SubscriptionService.applyNamePrefix`
already prefixes every imported server with `[SubName]`, so the grouping is
present in the row and, more usefully, reachable through the same search box
as everything else — typing the subscription name narrows to it. Real group
headers would also contradict sorting: a list cannot be both grouped by source
and ordered globally by latency, and latency is the ordering users came for.

**Latency lives in `LatencyTester`, in memory only.** The list sorts by the
last measurement, which meant it had to be readable outside the view that took
it. Putting it on the tester rather than in a new service keeps it with the
thing that produces it; keeping it out of `ServerConfig` keeps it out of
`servers.json`, where a latency from a previous session on a different network
would be presented as current fact.
