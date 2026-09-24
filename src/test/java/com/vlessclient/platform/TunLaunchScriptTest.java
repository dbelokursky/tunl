package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RouteMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.model.TransportType;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The macOS TUN launcher run for real — {@code /bin/sh}, the system's
 * {@code jq} — under a temporary base directory, with a stand-in core that
 * records what it was run with and the config it read on stdin.
 *
 * <p>Two properties matter. A config the app generates must reach the core
 * as generated, but for the cache file the launcher moves into its root-only
 * directory: anything else dropped would change what TUN does. And a config
 * written by anything else running as the user must not get root to write,
 * run or read a file, however it spells its keys and whatever the caller's
 * {@code HOME} holds.</p>
 *
 * <p>Runs wherever {@code /usr/bin/jq} is: macOS 15 and later, and the Linux
 * CI runners.</p>
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class TunLaunchScriptTest {

    private static final Path JQ = Path.of("/usr/bin/jq");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path base;

    private Path launcher;

    @BeforeEach
    void install() throws IOException {
        assumeTrue(Files.isExecutable(JQ), "the launcher filters with /usr/bin/jq");
        launcher = base.resolve("tun-launch");
        Files.writeString(launcher, PrivilegeHelper.launcherScript(base));
        Path core = base.resolve("sing-box");
        Files.writeString(core, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + base.resolve("args") + "'\n"
                + "cat > '" + base.resolve("received.json") + "'\n");
        for (Path script : List.of(launcher, core)) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generatedConfigs")
    void aGeneratedConfigReachesTheCoreAsGeneratedButForItsCacheFile(
            String shape, String config) throws Exception {
        Run run = launch(config, Map.of());

        assertThat(run.exit()).as(run.output()).isZero();
        ObjectNode expected = (ObjectNode) mapper.readTree(config);
        ((ObjectNode) expected.get("experimental").get("cache_file"))
                .put("path", base.resolve("state").resolve("cache.db").toString());
        assertThat(mapper.readTree(received())).isEqualTo(expected);
    }

    static Stream<Arguments> generatedConfigs() {
        ShareLinkParser links = new ShareLinkParser();
        ServerConfig reality = links.parse("vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                + "@reality.example.com:443?security=reality&pbk=pubkey123&sid=ab12"
                + "&fp=chrome&flow=xtls-rprx-vision&sni=www.example.com#Reality");
        ServerConfig websocket = links.parse("vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                + "@ws.example.com:443?type=ws&security=tls&sni=cdn.example.com&fp=chrome"
                + "&alpn=h2,http/1.1&path=%2Fws&host=cdn.example.com#WS");
        ServerConfig trojan = links.parse("trojan://secret@trojan.example.com:443"
                + "?type=grpc&serviceName=svc&sni=trojan.example.com#Trojan");
        ServerConfig hysteria = links.parse("hysteria2://pass@hy2.example.com:443"
                + "?sni=hy2.example.com&obfs=salamander&obfs-password=salt#HY2");
        ServerConfig shadowsocks = links.parse(
                "ss://2022-blake3-aes-128-gcm:L3zK%2FQ%3D%3D@ss.example.com:8388#SS");
        shadowsocks.setPlugin("obfs-local");
        shadowsocks.setPluginOpts("obfs=http;obfs-host=www.example.com");

        ServerConfig vmess = new ServerConfig();
        vmess.setName("VMess");
        vmess.setProtocol(Protocol.VMESS);
        vmess.setAddress("vmess.example.com");
        vmess.setPort(443);
        vmess.setUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        vmess.getTransport().setType(TransportType.HTTPUPGRADE);
        vmess.getTransport().setPath("/up");
        vmess.getTransport().setHost("vmess.example.com");
        vmess.getTls().setEnabled(true);
        vmess.getTls().setServerName("vmess.example.com");
        vmess.getTls().setAllowInsecure(true);

        ServerConfig wireguard = new ServerConfig();
        wireguard.setName("WG");
        wireguard.setProtocol(Protocol.WIREGUARD);
        wireguard.setAddress("wg.example.com");
        wireguard.setPort(51820);
        wireguard.setUuid("wg-private-key-base64");
        wireguard.setEncryption("wg-peer-public-key-base64");
        wireguard.setFlow("10.0.0.2/32");
        wireguard.getTls().setServerName("1,2,3");

        AppSettings plain = tun();
        AppSettings doh = tun();
        doh.setProxyDns("https://dns.google/dns-query");
        doh.setDirectDns("udp://77.88.8.8:53");
        doh.setClashApiSecret("s3cret");
        doh.setCoreLogLevel(CoreLogLevel.DEBUG);
        AppSettings auto = tun();
        auto.setServerSelection(ServerSelection.AUTO_BEST);
        auto.setTunIpv6Enabled(true);
        auto.setDirectDns("tls://dns.quad9.net");

        RoutingConfig all = new RoutingConfig();
        RoutingConfig blockedOnly = new RoutingConfig();
        blockedOnly.setMode(RouteMode.BLOCKED_IN_RUSSIA);
        RoutingConfig custom = new RoutingConfig();
        custom.setBypassCountries(List.of("ru"));
        custom.setBypassList(List.of("*.example.org", "*vk.com*", "mail.*.com", "10.1.0.0/16"));
        custom.setRules(new ArrayList<>(List.of(
                new RoutingRule(RoutingRule.RuleType.DOMAIN_SUFFIX, "google.com",
                        RoutingRule.RuleAction.PROXY),
                new RoutingRule(RoutingRule.RuleType.IP_CIDR, "192.0.2.0/24",
                        RoutingRule.RuleAction.DIRECT),
                new RoutingRule(RoutingRule.RuleType.DOMAIN_KEYWORD, "ads",
                        RoutingRule.RuleAction.BLOCK))));

        SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
        List<ServerConfig> every = List.of(
                reality, websocket, trojan, hysteria, shadowsocks, vmess, wireguard);
        return Stream.of(
                Arguments.of("VLESS Reality, everything proxied",
                        generator.generate(List.of(reality), reality, plain, all)),
                Arguments.of("VLESS WS over TLS, blocked sites only, DoH",
                        generator.generate(List.of(websocket), websocket, doh, blockedOnly)),
                Arguments.of("Trojan gRPC, country bypass and custom rules",
                        generator.generate(List.of(trojan), trojan, plain, custom)),
                Arguments.of("Hysteria2 with obfs",
                        generator.generate(List.of(hysteria), hysteria, doh, all)),
                Arguments.of("Shadowsocks with a plugin",
                        generator.generate(List.of(shadowsocks), shadowsocks, plain, custom)),
                Arguments.of("VMess HTTPUpgrade",
                        generator.generate(List.of(vmess), vmess, doh, custom)),
                Arguments.of("WireGuard endpoint",
                        generator.generate(List.of(wireguard), wireguard, plain, all)),
                Arguments.of("every protocol, fastest picked, IPv6, DNS over TLS",
                        generator.generate(every, reality, auto, custom)));
    }

    @Test
    void whatWouldMakeRootWriteRunOrReadAFileNeverReachesTheCore() throws Exception {
        Run run = launch(HOSTILE, Map.of());

        assertThat(run.exit()).as(run.output()).isZero();
        String text = received();
        JsonNode config = mapper.readTree(text);
        assertThat(config.propertyNames()).containsExactlyInAnyOrder(
                "log", "dns", "inbounds", "outbounds", "endpoints", "route", "experimental");
        assertThat(config.get("log").has("output")).isFalse();
        assertThat(types(config.get("dns").get("servers"))).containsExactly("https", "local");
        assertThat(config.get("dns").get("servers").get(0).has("tls")).isFalse();
        assertThat(types(config.get("inbounds"))).containsExactly("tun", "mixed", "http");
        assertThat(config.get("inbounds").get(0).has("platform")).isFalse();
        for (int i = 1; i <= 2; i++) {
            JsonNode local = config.get("inbounds").get(i);
            assertThat(local.get("listen").asString()).isEqualTo("127.0.0.1");
            assertThat(local.has("tls")).isFalse();
            assertThat(local.has("set_system_proxy")).isFalse();
        }
        assertThat(types(config.get("outbounds"))).containsExactly("vless", "direct");
        assertThat(types(config.get("endpoints"))).containsExactly("wireguard");
        assertThat(types(config.get("route").get("rule_set"))).containsExactly("remote");
        assertThat(config.get("route").get("rule_set").get(0).has("path")).isFalse();
        assertThat(config.get("experimental")).isEqualTo(mapper.readTree("""
                {"clash_api": {"external_controller": "127.0.0.1:9090", "secret": "s"},
                 "cache_file": {"enabled": true, "path": "%s"}}"""
                .formatted(base.resolve("state").resolve("cache.db"))));
        // Whatever the shape, no path the hostile config named survives.
        assertThat(text).doesNotContain("/etc", "/var/root", "/tmp/evil", "0.0.0.0", "\"::\"");
    }

    /**
     * The core reads keys the way Go's JSON does: ignoring case, with Unicode
     * folding (U+212A, the Kelvin sign, is a {@code k}). jq compares
     * them exactly, so a key spelled another way would pass the filter by and
     * still set the field; the launcher refuses the config instead.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("keysSpelledAnotherWay")
    void aKeySpelledAnotherWayGetsTheConfigRefused(String key) throws Exception {
        String config = """
                {"outbounds": [{"type": "vless", "tag": "v", "server": "x", "server_port": 443,
                  "uuid": "u", "tls": {"enabled": true, "%s": "/var/root/key.pem"}}]}"""
                .formatted(key);

        Run run = launch(config, Map.of());

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("keys outside a-z, 0-9 and _");
        assertThat(base.resolve("received.json")).doesNotExist();
    }

    static Stream<String> keysSpelledAnotherWay() {
        return Stream.of("Certificate_Path", "CLIENT_KEY_PATH", "client_\\u212Aey_path",
                "certificate_path ");
    }

    /**
     * sudo keeps the caller's HOME, and jq loads {@code ~/.jq} into every
     * program it runs: one that redefines the builtins the filter calls would
     * wave everything through. The launcher runs jq with a HOME of its own.
     */
    @Test
    void aJqFileInTheCallersHomeChangesNothing() throws Exception {
        Path home = Files.createDirectory(base.resolve("home"));
        // Leaves the key check working and turns the type allowlist and the
        // key deletions into no-ops.
        Files.writeString(home.resolve(".jq"), "def map(f): .; def del(f): .; def walk(f): .;\n");

        Run run = launch("""
                {"outbounds": [{"type": "tor", "tag": "t", "executable_path": "/tmp/evil"},
                  {"type": "direct", "tag": "direct"}]}""", Map.of("HOME", home.toString()));

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(types(mapper.readTree(received()).get("outbounds"))).containsExactly("direct");
    }

    @Test
    void theCoreRunsFromTheRootOnlyStateDirWithTheConfigOnStdin() throws Exception {
        Run run = launch("{\"log\": {\"level\": \"info\"}}", Map.of());

        assertThat(run.exit()).as(run.output()).isZero();
        Path state = base.resolve("state");
        assertThat(Files.readAllLines(base.resolve("args")))
                .containsExactly("run", "-D", state.toString(), "-c", "stdin");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(state)))
                .isEqualTo("rwx------");
        // The filtered config went to the core as an unlinked file: nothing
        // with the server's credentials is left on disk, even for root.
        try (Stream<Path> left = Files.list(state)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void anEmptyOrBrokenConfigStartsNothing() throws Exception {
        for (String config : List.of("", "{\"log\": ", "not json")) {
            Run run = launch(config, Map.of());

            assertThat(run.exit()).as("config %s", config).isNotZero();
            assertThat(base.resolve("args")).doesNotExist();
        }
    }

    private static AppSettings tun() {
        AppSettings settings = new AppSettings();
        settings.setProxyMode(ProxyMode.TUN);
        return settings;
    }

    private static List<String> types(JsonNode entries) {
        List<String> types = new ArrayList<>();
        entries.forEach(entry -> types.add(entry.get("type").asString()));
        return types;
    }

    private String received() throws IOException {
        return Files.readString(base.resolve("received.json"));
    }

    private Run launch(String config, Map<String, String> env) throws Exception {
        Path input = Files.writeString(base.resolve("input.json"), config);
        ProcessBuilder pb = new ProcessBuilder(launcher.toString())
                .redirectInput(input.toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(env);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the launcher exits").isTrue();
        return new Run(process.exitValue(), output);
    }

    private record Run(int exit, String output) {
    }

    /** Everything a sing-box config could make root do beyond a TUN connection. */
    private static final String HOSTILE = """
            {
              "log": {"level": "info", "output": "/etc/sudoers.d/owned", "timestamp": true},
              "ntp": {"enabled": true, "server": "time.example.com", "write_to_system": true},
              "certificate": {"store": "system", "certificate_path": ["/var/root/a.pem"]},
              "services": [{"type": "ssm-api", "listen": "0.0.0.0", "listen_port": 1}],
              "dns": {"servers": [
                {"type": "hosts", "tag": "hosts", "path": ["/etc/master.passwd"]},
                {"type": "https", "tag": "proxy-dns", "server": "1.1.1.1", "path": "/dns-query",
                 "tls": {"enabled": true, "client_certificate_path": "/var/root/c.pem",
                         "client_key_path": "/var/root/k.pem"}},
                {"type": "local", "tag": "local-dns"}]},
              "inbounds": [
                {"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"],
                 "auto_route": true,
                 "platform": {"http_proxy": {"enabled": true, "server": "0.0.0.0",
                                             "server_port": 1}}},
                {"type": "mixed", "tag": "mixed-in", "listen": "0.0.0.0", "listen_port": 7890,
                 "set_system_proxy": true},
                {"type": "http", "tag": "http-in", "listen": "::", "listen_port": 8080,
                 "tls": {"enabled": true, "certificate_path": "/etc/c.pem",
                         "key_path": "/var/root/k.pem",
                         "acme": {"domain": ["x.example"], "data_directory": "/etc/cron.d"}}},
                {"type": "redirect", "tag": "redirect-in", "listen_port": 1},
                {"type": "shadowsocks", "tag": "ss-in", "listen": "0.0.0.0", "listen_port": 8388,
                 "method": "none", "password": ""}],
              "outbounds": [
                {"type": "tor", "tag": "tor", "executable_path": "/tmp/evil",
                 "data_directory": "/etc"},
                {"type": "ssh", "tag": "ssh", "server": "x", "private_key_path": "/var/root/id"},
                {"type": "vless", "tag": "proxy", "server": "x", "server_port": 443, "uuid": "u",
                 "tls": {"enabled": true, "certificate_path": "/var/root/secret",
                         "ech": {"enabled": true, "config_path": "/var/root/ech"}}},
                {"type": "direct", "tag": "direct"}],
              "endpoints": [
                {"type": "tailscale", "tag": "ts", "state_directory": "/etc"},
                {"type": "wireguard", "tag": "wg", "address": ["10.0.0.2/32"],
                 "private_key": "k", "peers": []}],
              "route": {
                "rule_set": [
                  {"type": "local", "tag": "local-set", "format": "source",
                   "path": "/etc/master.passwd"},
                  {"type": "remote", "tag": "remote-set", "format": "binary",
                   "url": "https://example.com/x.srs", "path": "/etc/cron.d/x"}],
                "final": "direct"},
              "experimental": {
                "clash_api": {"external_controller": "0.0.0.0:9090", "secret": "s",
                              "external_ui": "/etc",
                              "external_ui_download_url": "https://example.com/ui.zip"},
                "cache_file": {"enabled": true, "path": "/etc/sudoers", "cache_id": "x"},
                "v2ray_api": {"listen": "0.0.0.0:1"},
                "debug": {"listen": "0.0.0.0:6060"}}
            }""";
}
