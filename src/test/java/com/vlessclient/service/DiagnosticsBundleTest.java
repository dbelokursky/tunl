package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
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
        // the outbound the core would build.
        assertThat(config).contains("outbounds").contains("198.51.100.7");
        assertThat(config).doesNotContain(SERVER_SECRET);
        assertThat(config).doesNotContain(store.getSettings().getClashApiSecret());
        assertThat(config).contains(Redact.REDACTED);
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

    @Test
    void aMissingLogFileStillProducesABundle() throws IOException {
        Map<String, String> entries = unzip(write());

        assertThat(entries).containsKey("tunl.log");
        assertThat(entries.get("tunl.log")).contains("no log file");
    }
}
