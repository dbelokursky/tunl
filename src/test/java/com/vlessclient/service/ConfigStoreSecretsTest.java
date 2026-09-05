package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Protocol;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.testing.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credential sealing in {@link ConfigStore}: plaintext in memory, sealed on
 * disk, legacy plaintext accepted forever, and no credential ever lost when
 * the backend misbehaves.
 */
class ConfigStoreSecretsTest {

    @TempDir
    Path tempDir;

    private static ServerConfig server(String name, String uuid) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress("192.0.2.1");
        config.setPort(443);
        config.setUuid(uuid);
        return config;
    }

    private String rawServersJson() throws Exception {
        return Files.readString(tempDir.resolve("servers.json"));
    }

    @Test
    void savedFileCarriesSealedValueNotPlaintext() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "super-secret-uuid"));

        String raw = rawServersJson();
        assertThat(raw).doesNotContain("super-secret-uuid");
        assertThat(raw).contains(InMemorySecretSealer.FAKE_TAG);
        // In memory the credential stays plaintext.
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("super-secret-uuid");
    }

    @Test
    void reloadRestoresPlaintextFromTheBackend() {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "super-secret-uuid"));

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid()).isEqualTo("super-secret-uuid");
    }

    @Test
    void legacyPlaintextFileLoadsUnchanged() {
        InMemorySecretSealer offSealer = new InMemorySecretSealer();
        offSealer.setAvailable(false);
        ConfigStore legacy = new ConfigStore(tempDir, offSealer);
        legacy.addServer(server("s1", "legacy-plain"));

        // A later run with a working backend must still read the plaintext.
        ConfigStore store = new ConfigStore(tempDir, new InMemorySecretSealer());
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("legacy-plain");
    }

    @Test
    void unavailableBackendKeepsWritingPlaintext() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        sealer.setAvailable(false);
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "plain-uuid"));

        assertThat(rawServersJson()).contains("plain-uuid");
    }

    @Test
    void disabledSettingKeepsWritingPlaintext() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.getSettings().setStoreSecretsSecurely(false);
        store.addServer(server("s1", "plain-uuid"));

        assertThat(rawServersJson()).contains("plain-uuid");
    }

    @Test
    void failedSealKeepsPlaintextInsteadOfLosingTheCredential() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        sealer.setFailSeal(true);
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "must-not-vanish"));

        assertThat(rawServersJson()).contains("must-not-vanish");
    }

    @Test
    void failedUnsealKeepsTheTagSoTheEntryStaysVisible() {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "secret"));
        sealer.entries().clear();   // simulate a deleted backend entry

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid())
                .startsWith(SecretSealer.SEAL_PREFIX);
    }

    @Test
    void removingAServerDeletesItsBackendEntry() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("s1", "secret");
        store.addServer(config);
        assertThat(sealer.entries()).hasSize(1);

        store.removeServer(config.getId());
        // The delete runs on a short-lived background thread.
        Await.until("the sealed secret to be deleted", () -> sealer.entries().isEmpty(),
                Duration.ofSeconds(5));
        assertThat(sealer.entries()).isEmpty();
    }

    @Test
    void optOutAfterSealingWritesPlaintextBack() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("s1", "come-back-plain");
        store.addServer(config);
        assertThat(rawServersJson()).doesNotContain("come-back-plain");

        store.getSettings().setStoreSecretsSecurely(false);
        config.setName("s1 renamed");
        store.updateServer(config);

        assertThat(rawServersJson()).contains("come-back-plain");
    }

    @Test
    void hysteria2ObfsPasswordInFlowIsSealed() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("hy2", "auth-password");
        config.setProtocol(Protocol.HYSTERIA2);
        config.setFlow("obfs-salamander-pass");
        store.addServer(config);

        String raw = rawServersJson();
        assertThat(raw).doesNotContain("obfs-salamander-pass");

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        assertThat(reloaded.getServers().get(0).getFlow()).isEqualTo("obfs-salamander-pass");
    }

    @Test
    void vlessFlowControlModeStaysPlaintext() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        ServerConfig config = server("vless", "some-uuid");
        config.setProtocol(Protocol.VLESS);
        config.setFlow("xtls-rprx-vision");
        store.addServer(config);

        // flow is a public mode selector for VLESS, not a secret.
        assertThat(rawServersJson()).contains("xtls-rprx-vision");
    }
}
