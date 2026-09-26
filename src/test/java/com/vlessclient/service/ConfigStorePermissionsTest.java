package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The config files hold credentials, so on POSIX the data dir must be 0700
 * and each file 0600 — not the umask default (0644) that would let other
 * local users read a stored UUID/password when the OS keychain is unused.
 */
class ConfigStorePermissionsTest {

    private static final boolean POSIX = FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");

    @TempDir
    Path tempDir;

    private static ServerConfig server() {
        return TestServers.server()
                .name("s1")
                .address("192.0.2.1")
                .port(443)
                .uuid("secret-uuid")
                .build();
    }

    @Test
    void savedFilesAndDirAreOwnerOnly() throws Exception {
        assumeTrue(POSIX, "POSIX permissions only");
        Path dataDir = tempDir.resolve("data");
        ConfigStore store = new ConfigStore(dataDir);
        store.addServer(server());
        store.saveSettings(store.getSettings());

        assertThat(Files.getPosixFilePermissions(dataDir))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(dataDir.resolve("servers.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dataDir.resolve("settings.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void aLegacyWorldReadableFileIsTightenedOnLoad() throws Exception {
        assumeTrue(POSIX, "POSIX permissions only");
        Path dataDir = tempDir.resolve("legacy");
        Files.createDirectories(dataDir);
        Path servers = dataDir.resolve("servers.json");
        Files.writeString(servers, "[]");
        Files.setPosixFilePermissions(servers,
                PosixFilePermissions.fromString("rw-r--r--"));   // 0644, as an old build wrote

        new ConfigStore(dataDir);   // loading tightens it

        assertThat(Files.getPosixFilePermissions(servers))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void writesStayAtomicAcrossReloads() {
        // The private write goes via a temp + atomic move; the round-trip
        // must still produce a readable config (guards against a botched move).
        Path dataDir = tempDir.resolve("atomic");
        ConfigStore store = new ConfigStore(dataDir);
        store.addServer(server());

        ConfigStore reloaded = new ConfigStore(dataDir);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid()).isEqualTo("secret-uuid");
        assertThat(Files.exists(dataDir.resolve("servers.json"))).isTrue();
        assertThat(leftoverTempFiles(dataDir)).isEmpty();
    }

    private static Set<String> leftoverTempFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp"))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }
}
