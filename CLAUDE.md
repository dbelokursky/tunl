# CLAUDE.md

Guidance for coding agents (Claude Code; Codex reads the same file through the
`AGENTS.md` symlink) and for humans who want the facts the code does not state.
Keep it short: only what cannot be derived from the sources, the README or
`git log`.

## What this is

Tunl: a cross-platform JavaFX desktop client (macOS / Windows / Linux) that
wraps the `sing-box` binary as a VPN/proxy client. Java 27, JavaFX 26, Maven,
no DI framework: `ServiceLocator` is the hand-written registry. The bundled
core is pinned in `src/main/resources/singbox.properties`, the single source of
truth read by `pom.xml`, `scripts/bundle-singbox.*` and `SingBoxInstaller`.

## Build and verify

- `mvn clean verify` is the gate: Checkstyle at `validate`, ~900 tests, JaCoCo
  floors (55 % line, 42 % branch), SpotBugs at `verify`. Run it from a clean
  state before calling anything done, and report failures verbatim.
- `mvn clean verify -Psmoke` also runs `SingBoxRealBinarySmokeTest` against the
  bundled core; CI runs it on macOS, Windows, Linux amd64 and Linux arm64.
- `mvn test -Dtest=SomeTest` for one class, `mvn validate` for Checkstyle only.
- The first build downloads the pinned core into `~/.cache/vless-client-build`;
  only the build host's OS and architecture are bundled.
- Never start the app to look at a UI change: `mvn javafx:run` launches a real
  VPN client, usually while the user's own instance holds the tunnel. Render
  headlessly through a TestFX/Monocle test instead (see
  `src/test/java/com/vlessclient/ui/view/*Test.java` and `ScreenshotGenerator`).
- Workflow edits: run `actionlint` on `.github/workflows/*.yml`; a schema error
  fails the run at startup and `yaml.safe_load` does not catch it.

## Conventions the build enforces

- Google Java Style, vendored in `config/checkstyle/google_checks.xml`: 4-space
  indent, 100-column lines, no star imports, Javadoc on public API. Main
  sources only.
- SpotBugs: `config/spotbugs-exclude.xml` drops whole patterns;
  `config/spotbugs-baseline.xml` pins the remaining findings by method
  signature hash, so changing a listed signature breaks the build until that
  entry's `signature` and `instanceHash` are updated. Never regenerate the
  baseline blindly.
- Every user-visible string goes through `I18n.get(key)` with the key present
  in both `messages_en.properties` and `messages_ru.properties`
  (`I18nBundleConsistencyTest`). FXML `text="..."` literals are placeholders;
  controllers bind the real, locale-aware text (`ButtonLabels.bindStatic`).
- Commit messages: `type(scope): imperative summary`, then a body that says what
  was wrong before, not just what changed.

## Tests

- UI tests are TestFX headless (Monocle). `UiTestServices.initialize()` builds
  the real service graph with network doubles; surefire redirects
  `vless.data.dir`, the log dir and the sing-box install dir into `target/`, so
  a test never reads the developer's profile or touches the OS keychain.
- Network is blocked in UI tests (`ExternalNetworkGuardExtension`). Service
  tests inject seams (`HttpClient`, `Path dataDir`, `SecretSealer`) and talk to
  a `com.sun.net.httpserver` on loopback.
- Layout tests: measure `boundsInParent` inside the real scene including the
  sidebar, never `boundsInLocal`; do not reuse one TestFX scene across
  assertions; and make sure a new test fails before the fix, because each of
  those three mistakes has produced a green test that checked nothing.
- Threading rules are enforced rather than documented: `ConnectionService`
  throws when `connect`/`disconnect` run on the FX thread.

## Identifiers that must not change

The app has been called Tunl since 1.5.0, but these stay `vless-client` /
`VlessClient` / `com.vlessclient` on purpose, because renaming them strands
existing installs: the Java package and Maven `artifactId` (jar name); the data
and log directories (`~/Library/Application Support/VlessClient`,
`%APPDATA%\VlessClient`, `~/.local/share/vless-client`); the Keychain service
name `"VLESS Client"` and the secret-tool label; the autostart identity
(`vless-client.desktop`, Windows Run value `VlessClient`); the MSI
`--win-upgrade-uuid`; the `~/.cache/vless-client-build` cache and CI artifact
names.

## Map

`app/` bootstrap (`Launcher` -> `VlessClientApp` -> `ServiceLocator`);
`model/` Jackson POJOs persisted as JSON in the data dir; `service/` the engine
and everything around it (`SingBoxEngine` owns the process,
`ConnectionService` owns connect/disconnect for the dashboard, tray and MCP,
`SingBoxConfigGenerator` emits the sing-box JSON, `UpdateManager` verifies
sha256 plus an Ed25519 signature); `platform/` per-OS shell-outs (TUN
launchers, system-proxy guards, secret sealers, autostart, update appliers);
`ui/view/` FXML controllers; `service/mcp/` the loopback MCP server.

## Branches and releases

- Base branch is `main` (there is no `develop`). PRs go sequentially off
  `main`, never stacked: `build.yml` only runs for PRs whose base is `main`,
  and `main` requires `test-macos`, `test-windows`, `test-linux` and
  `test-linux-arm` to pass.
- Releases are batched, not cut per PR: builds are unsigned, and every release
  makes users repeat the Gatekeeper / SmartScreen unblock. `dev-latest`
  already rebuilds on every merge. A release is an annotated tag
  (`git tag -a --cleanup=verbatim -F notes.txt vX.Y.Z`), notes in English; see
  `docs/RELEASE-CHECKLIST.md`.
