package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a bug report is allowed to carry.
 *
 * <p>The bundle exists so a report arrives complete, and the only reason it
 * can be attached to a public issue is that it carries no credential. Both
 * halves are pinned here: the entries a report needs are present, and the
 * two files that quote configuration and traffic — the generated sing-box
 * config and the log tail — come out with their secrets removed.</p>
 */
class DiagnosticsBundleTest {

    private static final String SERVER_SECRET = "top-secret-server-credential";

    @TempDir
    Path tempDir;

    private Path logsDir;
    private ConfigStore store;
    private DiagnosticsBundle bundle;

    @BeforeEach
    void setUp() throws IOException {
        logsDir = Files.createDirectories(tempDir.resolve("logs"));
        store = new ConfigStore(tempDir.resolve("data"));

        ServerConfig active = new ServerConfig();
        active.setName("Netherlands 01");
        active.setProtocol(Protocol.VLESS);
        active.setAddress("198.51.100.7");
        active.setPort(443);
        active.setUuid(SERVER_SECRET);
        store.addServer(active);
        store.saveSettings(store.getSettings());
        Files.writeString(store.getDataDir().resolve("routing.json"),
                "{\"rules\": []}", StandardCharsets.UTF_8);

        bundle = new DiagnosticsBundle(
                store, new SingBoxConfigGenerator(), null, logsDir);
    }

    /** Reads the whole archive into entry name -> UTF-8 content. */
    private static Map<String, String> unzip(Path archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (var in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(
                            in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private Path write() throws IOException {
        Path archive = tempDir.resolve("diagnostics.zip");
        bundle.writeTo(archive);
        return archive;
    }

    @Test
    void theBundleCarriesWhatAReportNeedsAndNothingThatHoldsServers() throws IOException {
        Files.writeString(logsDir.resolve("tunl.log"), "starting up\n", StandardCharsets.UTF_8);

        Map<String, String> entries = unzip(write());

        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "app-info.txt", "tunl.log", "sing-box.json", "settings.json", "routing.json");
        // The credential-bearing files sit in the same directory as the two
        // that are copied; naming them here is what keeps a future "copy the
        // whole data dir" shortcut from shipping them.
        assertThat(entries.keySet())
                .doesNotContain("servers.json", "subscriptions.json", "mcp-token");
    }

    @Test
    void appInfoDescribesTheBuildAndTheMode() throws IOException {
        Map<String, String> entries = unzip(write());

        assertThat(entries.get("app-info.txt"))
                .contains("app.version=")
                .contains("os.name=")
                .contains("java.version=")
                .contains("singbox.pinned.version=" + SingBoxInstaller.PINNED_VERSION)
                .contains("proxy.mode=")
                .contains("server.selection=")
                .contains("active.server.protocol=vless")
                .contains("mcp.enabled=");
        // The protocol is useful; the address and the credential are what the
        // user is asking not to publish.
        assertThat(entries.get("app-info.txt"))
                .doesNotContain(SERVER_SECRET)
                .doesNotContain("198.51.100.7");
    }

    @Test
    void theGeneratedConfigCarriesNoCredential() throws IOException {
        Map<String, String> entries = unzip(write());
        String config = entries.get("sing-box.json");

        // Not an empty stub: the config is only useful if it still describes
        // the outbound the core would build — its protocol and shape, not the
        // address, which is what the user is asking not to publish.
        assertThat(config).contains("outbounds").contains("\"vless\"");
        assertThat(config).doesNotContain("198.51.100.7");
        assertThat(config).doesNotContain(SERVER_SECRET);
        assertThat(config).doesNotContain(store.getSettings().getClashApiSecret());
        assertThat(config).contains(Redact.REDACTED);
    }

    /**
     * A server is named wherever its protocol's schema puts the name, not
     * only in {@code server}: a WireGuard peer's address, key and reserved
     * bytes and the interface address it hands out, a REALITY key and short
     * id, a gRPC service name.
     */
    @Test
    void theGeneratedConfigNamesNoServerWhereverItsProtocolKeepsTheName() throws IOException {
        ServerConfig wireguard = new ServerConfig();
        wireguard.setName("WARP");
        wireguard.setProtocol(Protocol.WIREGUARD);
        wireguard.setAddress("203.0.113.44");
        wireguard.setPort(2408);
        wireguard.setUuid("xunATixZ9R2SMbEghGvNz1fen77h9i5gNCPfxxgxtWk=");
        wireguard.setEncryption("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=");
        wireguard.setFlow("172.16.0.2");
        wireguard.getTls().setServerName("11,22,33");
        store.addServer(wireguard);
        ServerConfig reality = new ServerConfig();
        reality.setName("Reality");
        reality.setProtocol(Protocol.VLESS);
        reality.setAddress("203.0.113.45");
        reality.setPort(443);
        reality.setUuid(SERVER_SECRET);
        reality.getTls().setEnabled(true);
        reality.getTls().setReality(true);
        reality.getTls().setServerName("www.microsoft.com");
        reality.getTls().setRealityPublicKey("WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80");
        reality.getTls().setRealityShortId("6ba85179e30d4fc2");
        reality.getTransport().setType(TransportType.GRPC);
        reality.getTransport().setServiceName("private-grpc-service");
        store.addServer(reality);

        String config = unzip(write()).get("sing-box.json");

        assertThat(config).contains("\"wireguard\"").contains("\"grpc\"");
        assertThat(config)
                .doesNotContain("203.0.113.44")
                .doesNotContain("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
                .doesNotContain("172.16.0.2")
                .doesNotContainPattern("\\[\\s*11\\s*,\\s*22\\s*,\\s*33\\s*]")
                .doesNotContain("WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80")
                .doesNotContain("6ba85179e30d4fc2")
                .doesNotContain("private-grpc-service");
    }

    /**
     * A Shadowsocks plugin's options name the server as much as its address
     * does: v2ray-plugin carries the host and path the server answers on, and
     * obfs-local the host it disguises itself as. They reached the bundle,
     * which gets attached to public issues, as the plugin wrote them.
     */
    @Test
    void aShadowsocksPluginsOptionsNameNoServer() throws IOException {
        ServerConfig shadowsocks = new ServerConfig();
        shadowsocks.setName("SS plugin");
        shadowsocks.setProtocol(Protocol.SHADOWSOCKS);
        shadowsocks.setAddress("203.0.113.46");
        shadowsocks.setPort(8388);
        shadowsocks.setEncryption("aes-256-gcm");
        shadowsocks.setUuid(SERVER_SECRET);
        shadowsocks.setPlugin("v2ray-plugin");
        shadowsocks.setPluginOpts("tls;host=private-cdn.example;path=/private-path");
        store.addServer(shadowsocks);

        String config = unzip(write()).get("sing-box.json");

        assertThat(config).contains("\"v2ray-plugin\"");
        assertThat(config)
                .doesNotContain("private-cdn.example")
                .doesNotContain("/private-path");
    }

    /**
     * A private resolver carries the account it belongs to in its URL, the
     * way NextDNS and AdGuard DNS spell theirs, and in TUN mode the generated
     * configuration quotes that resolver back.
     */
    @Test
    void aPrivateResolverLosesItsAccountInTheGeneratedConfig() throws IOException {
        AppSettings settings = store.getSettings();
        settings.setProxyMode(ProxyMode.TUN);
        settings.setDirectDns("https://dns.adguard-dns.com/dns-query/abc123account");
        store.saveSettings(settings);

        String config = unzip(write()).get("sing-box.json");

        assertThat(config).contains("\"dns\"").doesNotContain("abc123account");
    }

    /** settings.json and routing.json went into the bundle exactly as they were on disk. */
    @Test
    void theCopiedFilesKeepOnlyTheHostOfEachUrl() throws IOException {
        AppSettings settings = store.getSettings();
        settings.setProxyDns("https://dns.nextdns.io/abc123account");
        store.saveSettings(settings);
        Files.writeString(store.getDataDir().resolve("routing.json"),
                "{\"rules\": [], \"bypass_list\": "
                        + "[\"https://intranet.example/login?token=def456account\"]}",
                StandardCharsets.UTF_8);

        Map<String, String> entries = unzip(write());

        assertThat(entries.get("settings.json"))
                .doesNotContain("abc123account")
                .contains("https://dns.nextdns.io/");
        assertThat(entries.get("routing.json"))
                .doesNotContain("def456account")
                .contains("https://intranet.example/");
    }

    @Test
    void theLogTailIsRedactedAndCapped() throws IOException {
        String filler = IntStream.range(0, 2500)
                .mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(logsDir.resolve("tunl.log"),
                filler + "\nRefreshing https://provider.example/sub/abcdef-account-token\n",
                StandardCharsets.UTF_8);

        String tail = unzip(write()).get("tunl.log");

        assertThat(tail).doesNotContain("abcdef-account-token");
        assertThat(tail).contains("https://provider.example/");
        // The tail keeps the end of the file, so the oldest lines go first.
        List<String> lines = tail.lines().toList();
        assertThat(lines).hasSize(2000);
        assertThat(lines.get(0)).isEqualTo("line 501");
        assertThat(lines.get(lines.size() - 1)).startsWith("Refreshing ");
    }

    /**
     * An unexpected exit leaves the core's last lines in the log, and the core
     * names the server there: the address it dialed, the name it looked up and
     * what that name resolved to, which no configuration holds, and the SNI or
     * host a certificate did not match. The tail redacted only URLs.
     */
    @Test
    void theLogTailNamesNoServerTheCoreQuoted() throws IOException {
        ServerConfig named = new ServerConfig();
        named.setName("Frankfurt");
        named.setProtocol(Protocol.VLESS);
        named.setAddress("gateway.example");
        named.setPort(443);
        named.setUuid(SERVER_SECRET);
        named.getTls().setEnabled(true);
        named.getTls().setServerName("www.microsoft.com");
        named.getTransport().setType(TransportType.HTTPUPGRADE);
        named.getTransport().setHost("cdn.example");
        store.addServer(named);
        ServerConfig plugin = new ServerConfig();
        plugin.setName("Tokyo");
        plugin.setProtocol(Protocol.SHADOWSOCKS);
        plugin.setAddress("203.0.113.47");
        plugin.setPort(443);
        plugin.setEncryption("aes-256-gcm");
        plugin.setUuid(SERVER_SECRET);
        plugin.setPlugin("v2ray-plugin");
        plugin.setPluginOpts("tls;host=private-cdn.example;path=/private-path");
        store.addServer(plugin);
        Files.writeString(logsDir.resolve("tunl.log"), String.join("\n",
                "2026-09-27 12:00:00.000 [JavaFX Application Thread] WARN  "
                        + "c.vlessclient.service.SingBoxEngine - "
                        + "The core exited unexpectedly with code 1:",
                "    ERROR[0040] [11 5s] connection: open connection to www.example.com:443 "
                        + "using outbound/vless[srv-a]: dial tcp 198.51.100.7:443: i/o timeout",
                "    ERROR[0041] [12 0ms] outbound/vless[srv-b]: dial tcp: "
                        + "lookup Gateway.Example.: no such host",
                "    ERROR[0042] [13 1s] outbound/vless[srv-b]: "
                        + "dial tcp 203.0.113.46:443: connect: connection refused",
                "    ERROR[0043] [14 1s] outbound/vless[srv-b]: "
                        + "dial tcp [2001:db8::46]:443: connect: network is unreachable",
                "    ERROR[0044] [15 0ms] outbound/vless[srv-b]: tls: failed to verify "
                        + "certificate: x509: certificate is valid for cdn.example, "
                        + "not www.microsoft.com",
                "    ERROR[0045] [16 0ms] outbound/shadowsocks[srv-c]: v2ray-plugin: "
                        + "handshake with private-cdn.example failed",
                "    FATAL[0046] start service: start inbound/http[http-in]: "
                        + "listen tcp 127.0.0.1:1081: bind: address already in use") + "\n",
                StandardCharsets.UTF_8);

        String tail = unzip(write()).get("tunl.log");

        assertThat(tail)
                .doesNotContain("198.51.100.7")
                .doesNotContainIgnoringCase("gateway.example")
                .doesNotContain("203.0.113.46")
                .doesNotContain("2001:db8::46")
                .doesNotContain("www.microsoft.com")
                .doesNotContain("cdn.example");
        // What a report needs stays: the exit, what each line says, the local port.
        assertThat(tail)
                .contains("The core exited unexpectedly with code 1:")
                .contains("dial tcp <redacted>:443: i/o timeout")
                .contains("lookup <redacted>.: no such host")
                .contains("dial tcp [<redacted>]:443: connect: network is unreachable")
                .contains("listen tcp 127.0.0.1:1081: bind: address already in use");
    }

    @Test
    void aMissingLogFileStillProducesABundle() throws IOException {
        Map<String, String> entries = unzip(write());

        assertThat(entries).containsKey("tunl.log");
        assertThat(entries.get("tunl.log")).contains("no log file");
    }
}
