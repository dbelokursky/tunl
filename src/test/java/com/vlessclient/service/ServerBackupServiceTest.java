package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Export and import of the whole server list.
 *
 * <p>The properties that matter are the ones a user only discovers when the
 * backup is the only copy left: credentials survive the round trip, ids
 * survive it (so a restore updates rather than duplicates), WireGuard — which
 * has no share link at all — is in the file, and a file that cannot be read
 * leaves the configured list untouched.</p>
 */
class ServerBackupServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private ServerBackupService backup;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir.resolve("data"));
        backup = new ServerBackupService(store, new ShareLinkParser());
    }

    private static ServerConfig server(String name, Protocol protocol, String credential) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setProtocol(protocol);
        config.setAddress("198.51.100.7");
        config.setPort(443);
        config.setUuid(credential);
        return config;
    }

    @Test
    void roundTripKeepsCredentialsIdsAndWireguard() throws IOException {
        store.addServer(server("Netherlands 01", Protocol.VLESS, "vless-secret-uuid"));
        ServerConfig wireguard = server("Finland WG", Protocol.WIREGUARD, "wg-private-key");
        wireguard.setEncryption("wg-peer-public-key");
        store.addServer(wireguard);
        List<String> originalIds = store.getServers().stream().map(ServerConfig::getId).toList();

        Path file = tempDir.resolve("backup.json");
        assertThat(backup.exportAll(file)).isEqualTo(2);

        ConfigStore elsewhere = new ConfigStore(tempDir.resolve("other"));
        ServerBackupService restore = new ServerBackupService(elsewhere, new ShareLinkParser());
        ServerBackupService.ImportResult result = restore.importFile(file);

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isEmpty();
        assertThat(elsewhere.getServers())
                .extracting(ServerConfig::getId).containsExactlyElementsOf(originalIds);
        assertThat(elsewhere.getServers())
                .extracting(ServerConfig::getUuid)
                .containsExactly("vless-secret-uuid", "wg-private-key");
        assertThat(elsewhere.getServers())
                .extracting(ServerConfig::getProtocol)
                .contains(Protocol.WIREGUARD);
    }

    @Test
    void theFileHoldsPlaintextCredentialsAndIsOwnerOnly() throws IOException {
        store.addServer(server("Netherlands 01", Protocol.VLESS, "vless-secret-uuid"));
        Path file = tempDir.resolve("backup.json");
        backup.exportAll(file);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        // A sealed tag would restore to nothing on another machine, which is
        // the whole reason this file is written unsealed.
        assertThat(json).contains("vless-secret-uuid");
        assertThat(json).contains("config_version");

        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not a thing on this filesystem");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void restoringOverTheSameIdsUpdatesInPlace() throws IOException {
        store.addServer(server("Netherlands 01", Protocol.VLESS, "vless-secret-uuid"));
        Path file = tempDir.resolve("backup.json");
        backup.exportAll(file);

        ServerConfig renamed = store.getServers().get(0);
        renamed.setName("Renamed by mistake");
        store.updateServer(renamed);

        ServerBackupService.ImportResult result = backup.importFile(file);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.added()).isZero();
        assertThat(store.getServers()).hasSize(1);
        assertThat(store.getServers().get(0).getName()).isEqualTo("Netherlands 01");
    }

    @Test
    void aRestoredServerDoesNotStealTheActiveFlag() throws IOException {
        store.addServer(server("Netherlands 01", Protocol.VLESS, "one"));
        store.addServer(server("Germany 02", Protocol.VLESS, "two"));
        // Export while the first is active, then activate the second: the
        // file still says the first is active, and importing it must not
        // leave the store with two active servers.
        Path file = tempDir.resolve("backup.json");
        backup.exportAll(file);
        store.setActiveServer(store.getServers().get(1).getId());

        backup.importFile(file);

        assertThat(store.getServers().stream().filter(ServerConfig::isActive).count()).isOne();
        assertThat(store.getServers().get(1).isActive()).isTrue();
    }

    @Test
    void aShareLinkListIsImportedLineByLine() throws IOException {
        Path file = tempDir.resolve("links.txt");
        Files.writeString(file, """
                # my servers
                vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443?type=tcp#NL

                trojan://trojan-password@198.51.100.8:8443#DE
                not-a-link-at-all
                """, StandardCharsets.UTF_8);

        ServerBackupService.ImportResult result = backup.importFile(file);

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(store.getServers())
                .extracting(ServerConfig::getName).containsExactly("NL", "DE");
        assertThat(store.getServers())
                .extracting(ServerConfig::getProtocol)
                .containsExactly(Protocol.VLESS, Protocol.TROJAN);
    }

    @Test
    void aSkippedLinkIsReportedWithoutItsCredential() throws IOException {
        Path file = tempDir.resolve("links.txt");
        Files.writeString(file,
                "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443#ok\n"
                        + "quux://leaked-credential@bad.example.com:443#broken\n",
                StandardCharsets.UTF_8);

        ServerBackupService.ImportResult result = backup.importFile(file);

        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).entry()).doesNotContain("leaked-credential");
    }

    @Test
    void aCorruptFileFailsLoudlyAndChangesNothing() throws IOException {
        store.addServer(server("Netherlands 01", Protocol.VLESS, "vless-secret-uuid"));
        String before = Files.readString(
                store.getDataDir().resolve("servers.json"), StandardCharsets.UTF_8);

        Path file = tempDir.resolve("broken.json");
        Files.writeString(file, "{\"config_version\": 1, \"servers\": [ {", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> backup.importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");

        assertThat(store.getServers()).hasSize(1);
        assertThat(Files.readString(store.getDataDir().resolve("servers.json"),
                StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    void jsonWithoutAServerListIsRefused() throws IOException {
        Path file = tempDir.resolve("settings-by-mistake.json");
        Files.writeString(file, "{\"theme\": \"dark\"}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> backup.importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a server backup");
        assertThat(store.getServers()).isEmpty();
    }

    @Test
    void aCompactFileReadsBackJustTheSame() throws IOException {
        ServerBackupService compact = new ServerBackupService(
                store, new ShareLinkParser(), JsonMapper.builder().build());
        store.addServer(server("Netherlands 01", Protocol.VLESS, "vless-secret-uuid"));

        Path file = tempDir.resolve("compact.json");
        compact.exportAll(file);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("\n");

        ConfigStore elsewhere = new ConfigStore(tempDir.resolve("other"));
        ServerBackupService.ImportResult result =
                new ServerBackupService(elsewhere, new ShareLinkParser()).importFile(file);

        assertThat(result.added()).isEqualTo(1);
        assertThat(elsewhere.getServers().get(0).getUuid()).isEqualTo("vless-secret-uuid");
    }

    @Test
    void aBareArrayOfServersIsAcceptedToo() throws IOException {
        Path file = tempDir.resolve("bare.json");
        Files.writeString(file,
                "[{\"name\":\"Legacy\",\"protocol\":\"vless\",\"address\":\"198.51.100.9\","
                        + "\"port\":443,\"uuid\":\"legacy-uuid\"}]",
                StandardCharsets.UTF_8);

        ServerBackupService.ImportResult result = backup.importFile(file);

        assertThat(result.added()).isEqualTo(1);
        // No id in the file, so one is minted rather than the entry being
        // dropped or every such server sharing a blank id.
        assertThat(store.getServers().get(0).getId()).isNotBlank();
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("legacy-uuid");
    }
}
