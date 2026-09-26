# Code signing policy

**Status:** Tunl's installers are not code-signed yet. They carry the
project's own release signature, which the in-app updater checks
([SIGNING.md](SIGNING.md)). This page is the policy Windows builds will be
signed under once the project's application to the SignPath Foundation is
approved. From then on the line below applies.

> Free code signing provided by [SignPath.io](https://signpath.io),
> certificate by [SignPath Foundation](https://signpath.org).

## What is signed

- Only the Windows installer (`tunl_x.y.z.msi`) that `release.yml` builds
  from this repository's source on a `v*` tag. It is built on GitHub-hosted
  runners from the tagged commit, which must be green on `main`.
- Nothing built on a developer's machine, and nothing from another
  repository.
- The installer bundles a build of sing-box made from its published source
  with one patch ([reality-xray.patch](../packaging/sing-box/reality-xray.patch)),
  and a Java runtime assembled from OpenJDK with `jlink`. Both are open
  source. Their source for a given release is named in
  [`singbox.properties`](../src/main/resources/singbox.properties) and the
  matching `core-v…` release on this repository.
- Every signing request is approved by hand, one release at a time.

## Team roles

- **Committers and reviewers:** Dmitry Belokursky
  ([@dbelokursky](https://github.com/dbelokursky)).
- **Approvers:** Dmitry Belokursky.

Changes reach `main` only through pull requests. `main` requires the four
test jobs (macOS, Windows, Linux amd64 and arm64) to pass, and the Windows
and Linux jobs build and install their installers.

## Privacy

Tunl has no accounts and no telemetry. On its own it connects only to the
following:

- **GitHub** (`api.github.com`, `github.com`):
  - it checks the project's latest release at startup, every six hours, and
    when the tunnel comes up (at most every fifteen minutes);
  - it downloads a newer release's installer by itself, where the
    installation can update itself, and checks its signature; installing
    it waits for the user;
  - it looks up sing-box's latest release to show in Settings;
  - it downloads sing-box when the installed app has none.
- **DB-IP** (`download.db-ip.com`): it downloads the free country database
  that the server list's flags come from, once, when it has none.
- **The servers and subscriptions the user adds**: it connects to them,
  refreshes the subscriptions, and measures their latency.
- **Through the user's server, once connected:**
  - DNS over HTTPS (`1.1.1.1` by default, configurable);
  - the rule lists the routing settings name (sing-box's own, and
    runetfreedom's list of sites blocked in Russia, from
    `raw.githubusercontent.com`);
  - a health check against `www.gstatic.com/generate_204` or
    `www.google.com/generate_204`, or the addresses set in Settings.

It sends none of these parties anything about the user beyond what a
connection itself carries (the address it comes from) and what the user
typed in for them. The local agent interface (MCP) listens on the loopback
address only, and only when turned on in Settings.
