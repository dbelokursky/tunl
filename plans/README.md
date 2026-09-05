# plans/

Planning documents, kept for the reasoning behind decisions rather than as a
description of the app. **Read `README.md` and `docs/` for what Tunl does
today**; the files here were written before the work and are not updated when
the code moves on.

| File | What it is | State |
|---|---|---|
| [vless-client.md](vless-client.md) | The original nine-phase plan, from the PRD in issue #1 | Historical. Every phase shipped; the unchecked boxes were never ticked, they are not a to-do list. Phase 8's "JavaFX 28" and macOS-only framing predate the Windows and Linux ports. |
| [windows-support.md](windows-support.md) | Plan for the Windows port | Historical; shipped in #29–#37. |
| [linux-support.md](linux-support.md) | Plan for the Linux port | Historical; shipped. |
| [readme-rewrite.md](readme-rewrite.md) | Plan for rewriting the user-facing README | Historical; shipped. Written in Russian, before the rename to Tunl. |
| [proxy-groups-and-server-ux.md](proxy-groups-and-server-ux.md) | Proxy groups, real latency, WireGuard import, server-list UX | Mostly live. Phases 1, 4, 5 and 6 shipped, and the "Decisions taken during implementation" section at the end records what changed on contact with the real core. Of phases 2 and 3, the `url-test` group shipped as `ServerSelection.AUTO_BEST` (Dashboard → Server → Fastest available) with the dashboard following the member the core picked (`ProxyGroupMonitor`), and the persisted group model was dropped in favour of a selection mode. Still open: switching the selector through clash_api without restarting the core, and per-subscription groups. |
