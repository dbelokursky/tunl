package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.CorePlatform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke tests that execute the REAL sing-box binary bundled by
 * scripts/bundle-singbox.sh against the config generator's actual output.
 * This is the gate that catches config-schema drift between the generator
 * and the pinned core (the class of bug where WireGuard configs fatally
 * failed on 1.13) before anything ships.
 *
 * <p>Excluded from the default test run; enabled with {@code -Psmoke}
 * (CI runs it in build.yml on every push and in release.yml before
 * packaging the DMG).</p>
 */
@Tag("smoke")
class SingBoxRealBinarySmokeTest {

    private static final String WG_PRIVATE_KEY = "xunATixZ9R2SMbEghGvNz1fen77h9i5gNCPfxxgxtWk=";
    private static final String WG_PEER_PUBLIC_KEY = "2Gl1nZ7pohiktxNLQq7rb1ZwdPN2BBaHpwA2M6dMJXM=";
    private static final String TEST_UUID = "b1c2d3e4-f5a6-7890-abcd-ef1234567890";

    private static Path binary;
    private static final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();

    @BeforeAll
    static void locateBundledBinary() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = osArch.contains("aarch64") || osArch.contains("arm64")
                ? "arm64" : "amd64";
        CorePlatform core = CorePlatform.current();
        binary = Path.of("target", "classes", "native",
                        core.osKey() + "-" + arch, core.binaryName())
                .toAbsolutePath();
        assumeTrue(Files.isExecutable(binary),
                "bundled sing-box not found at " + binary
                        + " — run the generate-resources phase first");
    }

    @Test
    void bundledBinaryVersionMatchesPin() throws Exception {
        ProcessResult result = run(binary, "version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output().lines().findFirst().orElse(""))
                .isEqualTo("sing-box version " + SingBoxInstaller.PINNED_VERSION);
    }

    @Test
    void checkAcceptsEveryProtocolInBothModes() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (ProxyMode mode : ProxyMode.values()) {
                AppSettings settings = new AppSettings();
                settings.setProxyMode(mode);
                String config = generator.generate(serverFor(protocol), settings);

                assertCheckPasses(config, protocol + "/" + mode);
            }
        }
    }

    /**
     * Every log level the Settings screen offers has to be a string the real
     * core accepts — a rejected one is not a wrong log, it is a core that
     * refuses to start at all, on the very connect the user was trying to
     * diagnose.
     */
    @Test
    void checkAcceptsEveryCoreLogLevel() throws Exception {
        for (CoreLogLevel level : CoreLogLevel.values()) {
            AppSettings settings = new AppSettings();
            settings.setCoreLogLevel(level);
            String config = generator.generate(serverFor(Protocol.VLESS), settings);

            assertThat(config).contains("\"level\" : \"" + level.getValue() + "\"");
            assertCheckPasses(config, "log-level/" + level.getValue());
        }
    }

    @Test
    void checkAcceptsRoutingConfigs() throws Exception {
        // Custom rules + user bypass list.
        RoutingConfig custom = new RoutingConfig();
        custom.setBypassList(List.of("*.local", "192.168.0.0/16", "example.com"));
        RoutingRule rule = new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX,
                "corp.example.com", RoutingRule.RuleAction.DIRECT);
        custom.setRules(List.of(rule));

        // Country bypass — emits remote rule_set references (verified:
        // `sing-box check` does not download them, so this is CI-safe).
        RoutingConfig domestic = new RoutingConfig();
        domestic.setBypassCountries(List.of("ru"));

        for (RoutingConfig routing : List.of(custom, domestic)) {
            for (ProxyMode mode : ProxyMode.values()) {
                AppSettings settings = new AppSettings();
                settings.setProxyMode(mode);
                String config = generator.generate(
                        serverFor(Protocol.VLESS), settings, routing);

                String label = routing.getBypassCountries().isEmpty()
                        ? "custom-rules" : "country-bypass";
                assertCheckPasses(config, label + "/" + mode);
            }
        }
    }

    /**
     * Boots the real binary with a generated SYSTEM_PROXY config on ephemeral
     * ports and exercises the two runtime contracts {@code sing-box check}
     * cannot see: the clash_api /traffic stream (TrafficMonitor) and proxying
     * through the http inbound (ServiceReachabilityChecker). The proxied
     * request goes to a local target server; the bypass rule routes it
     * through the direct outbound, so no external network is needed.
     */
    @Test
    void realRunServesClashApiAndHttpInbound() throws Exception {
        int socksPort = freePort();
        int httpPort = freePort();
        int clashPort = freePort();

        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/ok", exchange -> {
            byte[] body = "smoke".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        target.start();

        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        // The live run must never rewire the host's real proxy settings
        // (developer machines, CI runners). The set_system_proxy shape is
        // still config-checked by checkAcceptsEveryProtocolInBothModes.
        settings.setSystemProxyAutoConfig(false);
        settings.setSocksPort(socksPort);
        settings.setHttpPort(httpPort);
        settings.setClashApiPort(clashPort);

        RoutingConfig routing = new RoutingConfig();
        routing.setBypassList(List.of("127.0.0.1/32"));

        String config = generator.generate(serverFor(Protocol.VLESS), settings, routing);
        Path configFile = Files.createTempFile("smoke-run-", ".json");
        Files.writeString(configFile, config);

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(Files.createTempFile("smoke-run-", ".log").toFile())
                .start();
        try {
            awaitPort(clashPort, proc);

            HttpClient direct = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // clash_api contract: /traffic streams NDJSON with up/down.
            HttpResponse<java.io.InputStream> traffic = direct.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + clashPort + "/traffic"))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(traffic.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(traffic.body()))) {
                String firstLine = reader.readLine();
                assertThat(firstLine).contains("\"up\"").contains("\"down\"");
            }

            // http-inbound contract: proxy a request to the local target.
            HttpClient proxied = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", httpPort)))
                    .build();
            HttpResponse<String> response = proxied.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:"
                                    + target.getAddress().getPort() + "/ok"))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("smoke");
        } finally {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            target.stop(0);
            Files.deleteIfExists(configFile);
        }
    }

    /**
     * With a clash_api secret set, the real core rejects an unauthenticated
     * /traffic request (401) and accepts the exact Bearer request
     * TrafficMonitor builds (200) — proving another local process can't read
     * the stream. Covers security-review L7.
     */
    @Test
    void securedClashApiRejectsUnauthenticatedTraffic() throws Exception {
        int clashPort = freePort();
        String secret = "smoke-clash-secret-token";

        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setSystemProxyAutoConfig(false);
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(clashPort);
        settings.setClashApiSecret(secret);

        RoutingConfig routing = new RoutingConfig();

        String config = generator.generate(serverFor(Protocol.VLESS), settings, routing);
        Path configFile = Files.createTempFile("smoke-clash-", ".json");
        Files.writeString(configFile, config);

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(Files.createTempFile("smoke-clash-", ".log").toFile())
                .start();
        try {
            awaitPort(clashPort, proc);
            HttpClient direct = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // No token → rejected.
            HttpResponse<Void> unauth = direct.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + clashPort + "/traffic"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(unauth.statusCode()).isEqualTo(401);

            // The exact request TrafficMonitor builds → accepted.
            HttpResponse<java.io.InputStream> authed = direct.send(
                    TrafficMonitor.buildTrafficRequest(clashPort, secret),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(authed.statusCode()).isEqualTo(200);
        } finally {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            Files.deleteIfExists(configFile);
        }
    }

    // ===== helpers =====

    private static ServerConfig serverFor(Protocol protocol) {
        ServerConfig server = new ServerConfig();
        server.setProtocol(protocol);
        server.setAddress("203.0.113.10");
        server.setPort(443);
        switch (protocol) {
            case VLESS, VMESS -> {
                server.setUuid(TEST_UUID);
                server.getTls().setEnabled(true);
                server.getTls().setServerName("example.com");
            }
            case TROJAN, HYSTERIA2 -> {
                server.setUuid("smoke-password");
                server.getTls().setEnabled(true);
                server.getTls().setServerName("example.com");
                server.getTls().setAllowInsecure(true);
            }
            case SHADOWSOCKS -> {
                server.setUuid("smoke-password");
                server.setEncryption("aes-256-gcm");
            }
            case WIREGUARD -> {
                server.setPort(51820);
                server.setUuid(WG_PRIVATE_KEY);
                server.setEncryption(WG_PEER_PUBLIC_KEY);
                server.setFlow("10.0.0.2/32");
                server.getTls().setServerName("1,2,3");
            }
        }
        return server;
    }

    private void assertCheckPasses(String config, String label) throws Exception {
        Path configFile = Files.createTempFile("smoke-check-", ".json");
        try {
            Files.writeString(configFile, config);
            ProcessResult result = run(binary, "check", "-c", configFile.toString());
            assertThat(result.exitCode())
                    .withFailMessage("sing-box check failed for %s:%n%s%n--- config ---%n%s",
                            label, result.output(), config)
                    .isZero();
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static ProcessResult run(Path binary, String... args) throws Exception {
        Path out = Files.createTempFile("smoke-out-", ".log");
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = binary.toString();
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            Process proc = pb.start();
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("sing-box " + String.join(" ", args) + " timed out");
            }
            return new ProcessResult(proc.exitValue(), Files.readString(out));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Windows TUN probe: sing-box must be able to create its TUN adapter on
     * a Windows host (answers whether the driver stack — wintun.dll — is
     * available next to the binary). Runs with {@code auto_route} disabled so
     * the probe never touches the host's routing table: rerouting a CI
     * runner's own traffic into the test TUN would sever the job.
     *
     * <p>Requires administrator rights, which GitHub's windows runners have;
     * skipped when unprivileged. The clash_api port only opens after every
     * inbound (including the TUN adapter) started, so reaching it proves the
     * adapter came up.</p>
     */
    /**
     * Gives one test its own wintun adapter, so it cannot collide with another.
     *
     * <p>Both Windows TUN cases used the production name and address, and a
     * killed sing-box does not get to remove its adapter — {@code destroy()} on
     * Windows is a hard kill, and the teardown here has no adapter sweep. So
     * whether the second case could create its adapter depended on how fast
     * the OS tore down the first one: "set ipv4 address: The object already
     * exists", intermittently, on a required check. Distinct identities remove
     * the ordering dependency instead of relying on cleanup being timely.</p>
     *
     * <p>Addresses are in 172.31/16 — well away from the GitHub runner's own
     * 10.x network, and {@code auto_route} is off in both cases anyway.</p>
     */
    private static void withOwnTunIdentity(AppSettings settings, String name, String cidr) {
        settings.setTunInterfaceName(name);
        settings.setTunIpv4Address(cidr);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void tunAdapterComesUpOnWindows() throws Exception {
        int clashPort = freePort();
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        // Its own adapter identity — see withOwnTunIdentity.
        withOwnTunIdentity(settings, "TunlSmokeAdapter", "172.31.240.1/30");
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(clashPort);

        tools.jackson.databind.ObjectMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        tools.jackson.databind.node.ObjectNode config = (tools.jackson.databind.node.ObjectNode)
                mapper.readTree(generator.generate(serverFor(Protocol.VLESS), settings));
        for (tools.jackson.databind.JsonNode inbound : config.get("inbounds")) {
            if ("tun".equals(inbound.path("type").asString())) {
                ((tools.jackson.databind.node.ObjectNode) inbound)
                        .put("auto_route", false);
            }
        }

        Path configFile = Files.createTempFile("smoke-tun-", ".json");
        Files.writeString(configFile, mapper.writeValueAsString(config));
        Path logFile = Files.createTempFile("smoke-tun-", ".log");

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        try {
            try {
                awaitPort(clashPort, proc);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage()
                        + "\n--- sing-box output ---\n" + Files.readString(logFile), e);
            }
            assertThat(proc.isAlive()).isTrue();
        } finally {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            Files.deleteIfExists(configFile);
        }
    }

    /**
     * Full Windows TUN launch cycle through the real
     * {@code WindowsTunLauncher}: outer script, elevated wrapper, real
     * sing-box with a TUN inbound, live log tailing, stop via the signal
     * file. On GitHub's windows runners the shell is already elevated, so
     * {@code Start-Process -Verb RunAs} succeeds without an interactive UAC
     * prompt — making the whole privileged path CI-testable.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsTunLauncherFullCycle() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        withOwnTunIdentity(settings, "TunlSmokeLauncher", "172.31.241.1/30");
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(freePort());

        tools.jackson.databind.ObjectMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        tools.jackson.databind.node.ObjectNode config = (tools.jackson.databind.node.ObjectNode)
                mapper.readTree(generator.generate(serverFor(Protocol.VLESS), settings));
        for (tools.jackson.databind.JsonNode inbound : config.get("inbounds")) {
            if ("tun".equals(inbound.path("type").asString())) {
                // Never reroute the CI runner's own traffic.
                ((tools.jackson.databind.node.ObjectNode) inbound)
                        .put("auto_route", false);
            }
        }
        Path configFile = Files.createTempFile("smoke-tunlauncher-", ".json");
        Files.writeString(configFile, mapper.writeValueAsString(config));

        com.vlessclient.platform.TunLauncher.Launched launched =
                new com.vlessclient.platform.WindowsTunLauncher().launch(binary, configFile);
        Process outer = launched.process();
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread collector = new Thread(() -> {
            try (var reader = outer.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // stream closes when the outer script exits
            }
        }, "tun-log-collector");
        collector.setDaemon(true);
        collector.start();

        try {
            // The outer script tails the elevated core's log files to its
            // stdout; a "started" line proves elevation, core start and
            // tailing all work end to end.
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline
                    && lines.stream().noneMatch(l -> l.contains("sing-box started"))) {
                if (!outer.isAlive()) {
                    throw new AssertionError("TUN launch chain exited early (code "
                            + outer.exitValue() + "):\n" + String.join("\n", lines));
                }
                Thread.sleep(250);
            }
            assertThat(lines)
                    .as("core log lines tailed by the outer script:\n%s",
                            String.join("\n", lines))
                    .anyMatch(l -> l.contains("sing-box started"));

            // Stop contract: creating the signal file shuts the chain down.
            Files.createFile(launched.stopSignalFile());
            assertThat(outer.waitFor(20, TimeUnit.SECONDS))
                    .as("launch chain exits after the stop file appears")
                    .isTrue();
        } finally {
            outer.destroyForcibly();
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(launched.stopSignalFile());
        }
    }

    /**
     * Linux, host without the GNOME proxy schema (the CI runner — same
     * environment as KDE/headless users): the capability gate must keep
     * {@code set_system_proxy} out of the generated config, and the core
     * must start normally with just the local listeners.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void gnomelessHost_generatorOmitsFlagAndCoreStarts() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                com.vlessclient.platform.SystemProxySupport.current().canAutoConfigure(),
                "host has the GNOME proxy schema — gnomeless contract not testable here");

        int clashPort = freePort();
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        // systemProxyAutoConfig stays at its default (true): the host gate,
        // not the user setting, is what must strip the flag here.
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(clashPort);

        String config = generator.generate(serverFor(Protocol.VLESS), settings);
        assertThat(config).doesNotContain("set_system_proxy");

        Path configFile = Files.createTempFile("smoke-gnomeless-", ".json");
        Files.writeString(configFile, config);
        Path logFile = Files.createTempFile("smoke-gnomeless-", ".log");

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        try {
            try {
                awaitPort(clashPort, proc);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage()
                        + "\n--- sing-box output ---\n" + Files.readString(logFile), e);
            }
            assertThat(proc.isAlive()).isTrue();
        } finally {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            Files.deleteIfExists(configFile);
        }
    }

    /**
     * Pins the upstream behavior the capability gate exists for: on a host
     * without the GNOME schema, a config that forces {@code set_system_proxy}
     * makes sing-box exit fatally at startup. If a future core bump makes
     * this degrade gracefully instead, this test fails — signalling the gate
     * (and this pin) can be retired.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void gnomelessHost_forcedFlagStillKillsTheCore() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                com.vlessclient.platform.SystemProxySupport.current().canAutoConfigure(),
                "host has the GNOME proxy schema — gnomeless contract not testable here");
        // The pin covers "gsettings exists but the schema doesn't" (GitHub
        // runners). With no gsettings binary at all — minimal containers —
        // upstream takes a different code path and keeps running.
        org.junit.jupiter.api.Assumptions.assumeTrue(gsettingsOnPath(),
                "no gsettings binary — the pinned upstream contract does not apply");

        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        settings.setSocksPort(freePort());
        settings.setHttpPort(freePort());
        settings.setClashApiPort(freePort());

        SingBoxConfigGenerator forced = new SingBoxConfigGenerator(() -> true);
        String config = forced.generate(serverFor(Protocol.VLESS), settings);
        assertThat(config).contains("set_system_proxy");

        Path configFile = Files.createTempFile("smoke-gnomeless-forced-", ".json");
        Files.writeString(configFile, config);
        Path logFile = Files.createTempFile("smoke-gnomeless-forced-", ".log");

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        try {
            assertThat(proc.waitFor(15, TimeUnit.SECONDS))
                    .as("core should exit fatally, not keep running")
                    .isTrue();
            assertThat(proc.exitValue()).isNotZero();
            assertThat(Files.readString(logFile)).contains("set system proxy");
        } finally {
            proc.destroyForcibly();
            Files.deleteIfExists(configFile);
        }
    }

    private static boolean gsettingsOnPath() {
        try {
            Process p = new ProcessBuilder("gsettings", "--version").start();
            p.waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits until the port accepts connections; fails fast if the process dies. */
    private static void awaitPort(int port, Process proc) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) {
                throw new AssertionError("sing-box exited early with code " + proc.exitValue());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException retry) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("clash_api port " + port + " did not open within 15s");
    }

    @AfterAll
    static void noop() {
        // binary is shared, nothing to clean
    }
}
