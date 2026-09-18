package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.BundledCore;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final int OUTPUT_TAIL_LINES = 40;

    private static Path binary;
    private static final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();

    @BeforeAll
    static void locateBundledBinary() {
        binary = BundledCore.locate();
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
     * Every transport a provider's share link can carry has to reach the core
     * as fields that transport accepts. sing-box refuses a whole configuration
     * over one unknown field, and every stored server is a member of the proxy
     * group, so a single WebSocket link with a Host header — the usual CDN
     * setup — used to stop every server from connecting. The servers come from
     * the real parser, since that is where the fields are filled in.
     */
    @Test
    void checkAcceptsEveryTransportAsShareLinksCarryIt() throws Exception {
        ShareLinkParser parser = new ShareLinkParser();
        List<ServerConfig> servers = transportLinks().stream().map(parser::parse).toList();
        // Readable tags in a failure: the core names a refused outbound by its tag.
        servers.forEach(server -> server.setId(server.getName()));

        for (ProxyMode mode : ProxyMode.values()) {
            AppSettings settings = new AppSettings();
            settings.setProxyMode(mode);
            for (ServerConfig server : servers) {
                assertCheckPasses(generator.generate(server, settings),
                        server.getName() + "/" + mode);
            }
            // The shape a real server list produces: every server a member of
            // the one group, where a single refused member refuses them all.
            assertCheckPasses(generator.generate(servers, servers.get(0), settings, null),
                    "every-transport-in-one-group/" + mode);
        }
    }

    private static List<String> transportLinks() {
        String tls = "security=tls&sni=example.com";
        return List.of(
                "vless://" + TEST_UUID + "@cdn.example.com:443?type=ws&" + tls
                        + "&host=example.com&path=%2Fws#vless-ws",
                "vless://" + TEST_UUID + "@example.com:443?type=grpc&" + tls
                        + "&serviceName=grpc-svc&host=example.com#vless-grpc",
                "vless://" + TEST_UUID + "@example.com:443?type=http&" + tls
                        + "&host=example.com&path=%2Fh2#vless-h2",
                "vless://" + TEST_UUID + "@example.com:443?type=httpupgrade&" + tls
                        + "&host=example.com&path=%2Fup#vless-httpupgrade",
                "vless://" + TEST_UUID + "@example.com:443?type=quic&" + tls
                        + "&quicSecurity=none&key=&headerType=none#vless-quic",
                "trojan://smoke-password@example.com:443?type=ws&" + tls
                        + "&host=example.com&path=%2Fws#trojan-ws",
                "trojan://smoke-password@example.com:443?type=grpc&" + tls
                        + "&serviceName=grpc-svc#trojan-grpc",
                vmessLink("vmess-ws", "ws", "example.com", "/ws"),
                vmessLink("vmess-grpc", "grpc", "example.com", "grpc-svc"),
                vmessLink("vmess-h2", "h2", "example.com", "/h2"),
                // v2rayN keeps QUIC's header security in host and its key in path.
                vmessLink("vmess-quic", "quic", "none", "key"));
    }

    private static String vmessLink(String name, String net, String host, String path) {
        String json = "{\"v\":\"2\",\"ps\":\"" + name + "\",\"add\":\"example.com\","
                + "\"port\":\"443\",\"id\":\"" + TEST_UUID + "\",\"aid\":\"0\","
                + "\"scy\":\"auto\",\"net\":\"" + net + "\",\"type\":\"none\","
                + "\"host\":\"" + host + "\",\"path\":\"" + path + "\","
                + "\"tls\":\"tls\",\"sni\":\"example.com\"}";
        return "vmess://" + Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Settings a share link can spell in a way the core refuses, although what
     * they mean is not in doubt. The generator corrects them for every stored
     * server, so the core has to accept each one as generated.
     */
    @Test
    void checkAcceptsSettingsLinksSpellInWaysTheCoreRefuses() throws Exception {
        String realityKey = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";
        String realityKeyInStandardAlphabet = "WZaG00XCAiVCF2SP5fmSbKiuTbBB+lMDg/81rC8hR80=";
        List<ServerConfig> servers = List.of(
                realityServer("reality-without-fingerprint", null, realityKey),
                realityServer("fingerprint-in-capitals", "Chrome", realityKey),
                realityServer("key-in-the-standard-alphabet", "chrome",
                        realityKeyInStandardAlphabet),
                wireguardServer("wireguard-v4-without-prefix", "10.0.0.2"),
                wireguardServer("wireguard-v6-without-prefix", "fd00::2"),
                shadowsocksServer("xray-chacha20-poly1305", "chacha20-poly1305"),
                shadowsocksServer("xray-xchacha20-poly1305", "xchacha20-poly1305"),
                shadowsocksServer("xray-plain", "plain"),
                shadowsocksServer("cipher-in-capitals", "AES-256-GCM"));

        for (ProxyMode mode : ProxyMode.values()) {
            AppSettings settings = new AppSettings();
            settings.setProxyMode(mode);
            for (ServerConfig server : servers) {
                assertCheckPasses(generator.generate(server, settings),
                        server.getName() + "/" + mode);
            }
        }
    }

    private static ServerConfig realityServer(String name, String fingerprint, String publicKey) {
        ServerConfig server = new ServerConfig();
        server.setId(name);
        server.setName(name);
        server.setProtocol(Protocol.VLESS);
        server.setAddress("203.0.113.10");
        server.setPort(443);
        server.setUuid(TEST_UUID);
        server.setFlow("xtls-rprx-vision");
        server.getTls().setEnabled(true);
        server.getTls().setReality(true);
        server.getTls().setServerName("www.microsoft.com");
        server.getTls().setFingerprint(fingerprint);
        server.getTls().setRealityPublicKey(publicKey);
        server.getTls().setRealityShortId("0123abcd");
        return server;
    }

    private static ServerConfig wireguardServer(String name, String address) {
        ServerConfig server = new ServerConfig();
        server.setId(name);
        server.setName(name);
        server.setProtocol(Protocol.WIREGUARD);
        server.setAddress("203.0.113.11");
        server.setPort(51820);
        server.setUuid(WG_PRIVATE_KEY);
        server.setEncryption(WG_PEER_PUBLIC_KEY);
        server.setFlow(address);
        return server;
    }

    private static ServerConfig shadowsocksServer(String name, String method) {
        ServerConfig server = new ServerConfig();
        server.setId(name);
        server.setName(name);
        server.setProtocol(Protocol.SHADOWSOCKS);
        server.setAddress("203.0.113.12");
        server.setPort(8388);
        server.setUuid("smoke-password");
        server.setEncryption(method);
        return server;
    }

    /**
     * CoreSettings answers for the core before a server is stored, so its
     * answers have to be the core's: for each server here, "no refusal" and
     * "sing-box check passes" must agree, whichever way the core goes. Port 0
     * is left out on purpose: the check lets it through, but nothing can dial
     * it, so the import refuses it anyway.
     */
    @Test
    void coreSettingsRefusesExactlyWhatTheCoreRefuses() throws Exception {
        String realityKey = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";
        String key16 = "AAAAAAAAAAAAAAAAAAAAAA==";
        String key32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        List<ServerConfig> servers = List.of(
                realityServer("fingerprint-chrome", "chrome", realityKey),
                realityServer("fingerprint-unknown", "randomizednoalpn", realityKey),
                realityServer("key-too-short", "chrome", "pubkey123"),
                with(realityServer("short-id-empty", "chrome", realityKey),
                        server -> server.getTls().setRealityShortId("")),
                with(realityServer("short-id-odd-length", "chrome", realityKey),
                        server -> server.getTls().setRealityShortId("abc")),
                with(realityServer("short-id-too-long", "chrome", realityKey),
                        server -> server.getTls().setRealityShortId("0123456789abcdef01")),
                with(realityServer("flow-udp443", "chrome", realityKey),
                        server -> server.setFlow("xtls-rprx-vision-udp443")),
                with(realityServer("port-70000", "chrome", realityKey),
                        server -> server.setPort(70000)),
                shadowsocksServer("cipher-chacha20", "chacha20"),
                shadowsocksServer("cipher-rc4-md5", "rc4-md5"),
                with(shadowsocksServer("ss2022-aes128-long-key", "2022-blake3-aes-128-gcm"),
                        server -> server.setUuid(key32)),
                with(shadowsocksServer("ss2022-aes128", "2022-blake3-aes-128-gcm"),
                        server -> server.setUuid(key16)),
                with(shadowsocksServer("ss2022-multi-user", "2022-blake3-aes-256-gcm"),
                        server -> server.setUuid(key32 + ":" + key32)));

        for (ServerConfig server : servers) {
            boolean coreAccepts = checkPasses(generator.generate(server, new AppSettings()));
            assertThat(com.vlessclient.service.outbound.CoreSettings.refusal(server).isEmpty())
                    .as("%s: sing-box check %s it", server.getName(),
                            coreAccepts ? "accepts" : "refuses")
                    .isEqualTo(coreAccepts);
        }
    }

    private static ServerConfig with(ServerConfig server,
                                     java.util.function.Consumer<ServerConfig> change) {
        change.accept(server);
        return server;
    }

    private boolean checkPasses(String config) throws Exception {
        Path configFile = Files.createTempFile("smoke-check-", ".json");
        try {
            Files.writeString(configFile, config);
            return run(binary, "check", "-c", configFile.toString()).exitCode() == 0;
        } finally {
            Files.deleteIfExists(configFile);
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

    /**
     * A DNS server can be named by host, and the Settings fields take it with
     * or without a scheme or a port. The core refused a Direct DNS it had no
     * way to resolve, and "8.8.8.8:53" reached it as a name, so TUN mode never
     * started with either.
     */
    @Test
    void checkAcceptsDnsServersHoweverSettingsSpellThem() throws Exception {
        List<String> addresses = List.of(
                "https://dns.google/dns-query",
                "tls://dns.quad9.net",
                "quic://dns.adguard-dns.com",
                "h3://dns.google/dns-query",
                "dns.google",
                "8.8.8.8:53",
                "dns.google:53",
                "[2001:4860:4860::8888]:53",
                "2001:4860:4860::8888",
                "https://[2606:4700:4700::1111]/dns-query");

        for (String address : addresses) {
            AppSettings settings = new AppSettings();
            settings.setProxyMode(ProxyMode.TUN);
            settings.setProxyDns(address);
            settings.setDirectDns(address);

            assertCheckPasses(generator.generate(serverFor(Protocol.VLESS), settings), address);
        }
    }

    /**
     * TUN mode with IPv6 turned off asks the core to resolve names to IPv4
     * only, and the core has to accept that as generated.
     */
    @Test
    void checkAcceptsTunModeWithoutIpv6() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        settings.setTunIpv6Enabled(false);
        String config = generator.generate(serverFor(Protocol.VLESS), settings);

        assertThat(config).contains("\"strategy\" : \"ipv4_only\"");
        assertCheckPasses(config, "tun-without-ipv6");
    }

    /**
     * The production check against the real core: what it lets through, and
     * what it refuses in the core's own words. A subscription can still carry
     * Xray's retired {@code xtls-rprx-direct} flow; the core refuses to build
     * it, and before the check that refusal came after the TUN prompt.
     */
    @Test
    void productionCheckPassesGeneratedConfigsAndQuotesTheCoresRefusal() throws Exception {
        SingBoxConfigCheck check = new SingBoxConfigCheck(Duration.ofSeconds(30));
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        Path accepted = Files.createTempFile("smoke-check-ok-", ".json");
        Path refused = Files.createTempFile("smoke-check-refused-", ".json");
        try {
            Files.writeString(accepted, generator.generate(serverFor(Protocol.VLESS), settings));
            assertThat(check.rejection(binary, accepted)).isEmpty();

            ServerConfig legacy = serverFor(Protocol.VLESS);
            legacy.setFlow("xtls-rprx-direct");
            Files.writeString(refused, generator.generate(legacy, settings));
            assertThat(check.rejection(binary, refused)).hasValueSatisfying(reason ->
                    assertThat(reason)
                            .contains("unsupported flow: xtls-rprx-direct")
                            .doesNotContain("FATAL")
                            .doesNotContain(refused.toString()));
        } finally {
            Files.deleteIfExists(accepted);
            Files.deleteIfExists(refused);
        }
    }

    /**
     * A REALITY short ID longer than 16 hex digits gets no refusal from the
     * core: sing-box 1.14 panics in {@code check}, and the last line of a panic
     * is a frame of its stack trace ("main.go:8 +0x24"), which was reported as
     * the reason. Whether the core panics or, some day, refuses, the reason
     * has to be words, not a frame.
     */
    @Test
    void aCheckThatPanicsIsReportedInWordsNotByAStackFrame() throws Exception {
        SingBoxConfigCheck check = new SingBoxConfigCheck(Duration.ofSeconds(30));
        ServerConfig server = with(realityServer("short-id-too-long", "chrome",
                        "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80"),
                s -> s.getTls().setRealityShortId("0123456789abcdef01"));
        Path config = Files.createTempFile("smoke-check-panic-", ".json");
        try {
            Files.writeString(config, generator.generate(server, new AppSettings()));
            assertThat(check.rejection(binary, config)).hasValueSatisfying(reason ->
                    assertThat(reason)
                            .as("the reason sing-box check gave")
                            .doesNotContain("+0x")
                            .doesNotStartWith("github.com/"));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void checkAcceptsRoutingConfigs() throws Exception {
        // Custom rules + user bypass list.
        RoutingConfig custom = new RoutingConfig();
        custom.setBypassList(List.of("*.local", "192.168.0.0/16", "example.com"));
        RoutingRule rule = new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX,
                "corp.example.com", RoutingRule.RuleAction.DIRECT);
        // An IP rule makes system-proxy mode resolve names first, which brings
        // in a dns block and a default domain resolver the core has to accept.
        RoutingRule ipRule = new RoutingRule(RoutingRule.RuleType.IP_CIDR,
                "203.0.113.0/24", RoutingRule.RuleAction.BLOCK);
        custom.setRules(List.of(rule, ipRule));

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
                assertCheckPasses(new LiveSelector(config).config(),
                        "runtime-selector/" + label + "/" + mode);
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

        RoutingConfig routing = new RoutingConfig();
        routing.setBypassList(List.of("127.0.0.1/32"));

        CoreRun run = startOnFreshPorts(settings,
                s -> generator.generate(serverFor(Protocol.VLESS), s, routing));
        int socksPort = settings.getSocksPort();
        int httpPort = settings.getHttpPort();
        int clashPort = settings.getClashApiPort();
        Process proc = run.process();
        Path configFile = run.configFile();
        Path logFile = run.logFile();
        try {

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
            stopCore(proc);
            target.stop(0);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(logFile);
        }
    }

    /**
     * With a clash_api secret set, the real core rejects an unauthenticated
     * /traffic request (401) and accepts the exact Bearer request
     * TrafficMonitor builds (200) — proving another local process can't read
     * the stream. Covers security-review L7.
     */
    @Test
    void liveSelectorChangesTrafficWithoutRestartAndDoesNotRestoreAStaleCachedPick()
            throws Exception {
        try (SocksResponder a = new SocksResponder("A");
             SocksResponder b = new SocksResponder("B")) {
            AppSettings settings = new AppSettings();
            settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
            settings.setSystemProxyAutoConfig(false);
            settings.setHttpPort(freePort());
            settings.setSocksPort(freePort());
            settings.setClashApiPort(freePort());
            settings.setClashApiSecret("selector-smoke-secret");
            ServerConfig first = serverFor(Protocol.VLESS);
            first.setId("first");
            ServerConfig second = serverFor(Protocol.VLESS);
            second.setId("second");
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var config = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
                    generator.generate(List.of(first, second), first, settings, null));
            for (var outbound : config.path("outbounds")) {
                if ("vless".equals(outbound.path("type").asString())) {
                    String tag = outbound.path("tag").asString();
                    var object = (tools.jackson.databind.node.ObjectNode) outbound;
                    object.removeAll();
                    object.put("tag", tag).put("type", "socks").put("version", "5")
                            .put("server", "127.0.0.1").put("server_port",
                                    tag.equals("srv-first") ? a.port() : b.port());
                }
            }
            Path cache = Files.createTempFile("selector-cache-", ".db");
            Files.delete(cache);
            ((tools.jackson.databind.node.ObjectNode) config.path("experimental")
                    .path("cache_file")).put("path", cache.toString());
            String generated = mapper.writeValueAsString(config);
            // Keep the same cache across runs. The first run stores B; the
            // second must still honor the explicitly selected default A.
            try {
                for (int run = 0; run < 2; run++) {
                    LiveSelector selector = new LiveSelector(generated);
                    Path file = Files.createTempFile("selector-run-", ".json");
                    Path logs = Files.createTempFile("selector-run-", ".log");
                    Files.writeString(file, selector.config());
                    Process process = new ProcessBuilder(binary.toString(), "run", "-c",
                            file.toString()).redirectErrorStream(true)
                            .redirectOutput(logs.toFile()).start();
                    try {
                        awaitPort(settings.getClashApiPort(), process, logs);
                        long pid = process.pid();
                        assertThat(selectorTraffic(settings.getHttpPort())).isEqualTo("A");
                        assertThat(selector.select("srv-second")).isTrue();
                        assertThat(selectorTraffic(settings.getHttpPort())).isEqualTo("B");
                        assertThat(process.isAlive()).isTrue();
                        assertThat(process.pid()).isEqualTo(pid);
                    } catch (Throwable e) {
                        throw new AssertionError(Files.readString(logs), e);
                    } finally {
                        stopCore(process);
                        Files.deleteIfExists(file);
                        Files.deleteIfExists(logs);
                    }
                }
            } finally {
                Files.deleteIfExists(cache);
            }
        }
    }

    private static String selectorTraffic(int port) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port))).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:12345/"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    /** Loopback SOCKS peers answer HTTP themselves; no external destination is contacted. */
    private static final class SocksResponder implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;

        SocksResponder(String label) throws IOException {
            listener = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
            worker = Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        var in = new java.io.DataInputStream(socket.getInputStream());
                        var out = socket.getOutputStream();
                        if (in.readUnsignedByte() != 5) {
                            throw new IOException("Not SOCKS5");
                        }
                        in.readNBytes(in.readUnsignedByte());
                        out.write(new byte[]{5, 0});
                        out.flush();
                        in.readNBytes(3);
                        int addressType = in.readUnsignedByte();
                        int length = addressType == 1 ? 4 : addressType == 4 ? 16
                                : in.readUnsignedByte();
                        in.readNBytes(length + 2);
                        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                        out.flush();
                        var reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(in, StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // Consume the proxied HTTP request before replying.
                        }
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n"
                                + "Connection: close\r\n\r\n" + label)
                                .getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException e) {
                        if (!listener.isClosed()) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        @Override public void close() throws Exception {
            listener.close();
            worker.join(6000);
            assertThat(worker.isAlive()).isFalse();
        }
    }

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
        Path logFile = Files.createTempFile("smoke-clash-", ".log");

        Process proc = new ProcessBuilder(
                binary.toString(), "run", "-c", configFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        try {
            awaitPort(clashPort, proc, logFile);
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

            // What the TUN watchdog waits for before it reports Connected.
            SingBoxEngine.Controller controller = SingBoxEngine.extractController(config);
            try (HttpClient probe = SingBoxEngine.controllerProbeClient()) {
                assertThat(SingBoxEngine.coreAnswers(probe, controller)).isTrue();
                assertThat(SingBoxEngine.coreAnswers(probe,
                        new SingBoxEngine.Controller(controller.version(), "another-secret")))
                        .isFalse();
            }
        } finally {
            stopCore(proc);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(logFile);
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

    /** A core a smoke test started, with the file its output goes to. */
    private record CoreRun(Process process, Path configFile, Path logFile) {
    }

    /** Attempts before a core that will not come up is treated as a real failure. */
    private static final int START_ATTEMPTS = 3;

    /**
     * Starts the core on freshly picked ports and waits for its control port,
     * trying again when it dies before answering.
     *
     * <p>{@link #freePort()} has to close its socket to learn the number, so
     * anything else on the machine can take that port in the moment before the
     * core binds it. That is rare and it cost a whole release job each time,
     * because this is the one live test every release runs. Each attempt picks
     * new numbers; the last failure is rethrown, so a core that is genuinely
     * broken still fails the test with the same message and the same log
     * tail.</p>
     */
    private CoreRun startOnFreshPorts(AppSettings settings,
                                      java.util.function.Function<AppSettings, String> configFor)
            throws Exception {
        // Throwable, not Exception: awaitPort reports a core that died with an
        // AssertionError, which is an Error -- caught as Exception, the retry
        // never ran at all.
        Throwable last = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            settings.setSocksPort(freePort());
            settings.setHttpPort(freePort());
            settings.setClashApiPort(freePort());

            Path configFile = Files.createTempFile("smoke-run-", ".json");
            Files.writeString(configFile, configFor.apply(settings));
            Path logFile = Files.createTempFile("smoke-run-", ".log");
            Process proc = new ProcessBuilder(
                    binary.toString(), "run", "-c", configFile.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            try {
                awaitPort(settings.getClashApiPort(), proc, logFile);
                return new CoreRun(proc, configFile, logFile);
            } catch (AssertionError | Exception notUp) {
                proc.destroyForcibly();
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                // The failure already carries the tail of this log.
                Files.deleteIfExists(configFile);
                Files.deleteIfExists(logFile);
                last = notUp;
                System.err.println("smoke: the core did not come up on attempt " + attempt
                        + " of " + START_ATTEMPTS + "; trying new ports");
            }
        }
        if (last instanceof Error error) {
            // The same failure the last attempt produced, log tail and all.
            throw error;
        }
        throw (Exception) last;
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
            awaitPort(clashPort, proc, logFile);
            assertThat(proc.isAlive()).isTrue();
        } finally {
            stopCore(proc);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(logFile);
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
            awaitPort(clashPort, proc, logFile);
            assertThat(proc.isAlive()).isTrue();
        } finally {
            stopCore(proc);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(logFile);
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
            stopCore(proc);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(logFile);
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

    /**
     * Waits until the port accepts connections; fails fast if the process dies.
     * Either failure ends with the core's own output, which holds the reason:
     * the FATAL line of an early exit, or how far a core that never opened the
     * port got.
     */
    private static void awaitPort(int port, Process proc, Path log) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) {
                throw new AssertionError("sing-box exited early with code " + proc.exitValue()
                        + outputTail(log));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException retry) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("clash_api port " + port + " did not open within 15s"
                + outputTail(log));
    }

    /** The last {@code OUTPUT_TAIL_LINES} lines of a core's output, for a failure message. */
    private static String outputTail(Path log) {
        List<String> lines;
        try {
            // Decoded leniently: a malformed byte must not replace the failure
            // being reported with a decoding exception.
            lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return "\n--- sing-box output could not be read: " + e + " ---";
        }
        if (lines.isEmpty()) {
            return "\n--- sing-box wrote no output ---";
        }
        int from = Math.max(0, lines.size() - OUTPUT_TAIL_LINES);
        String shown = from == 0 ? "" : ", last " + (lines.size() - from) + " of " + lines.size();
        return "\n--- sing-box output" + shown + " ---\n"
                + String.join("\n", lines.subList(from, lines.size()));
    }

    /**
     * Stops a core a test started and waits for it to exit. The wait matters
     * for the cleanup that follows: Windows refuses to delete a log file while
     * the process writing it is still alive.
     */
    private static void stopCore(Process proc) throws InterruptedException {
        proc.destroy();
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void noop() {
        // binary is shared, nothing to clean
    }
}
