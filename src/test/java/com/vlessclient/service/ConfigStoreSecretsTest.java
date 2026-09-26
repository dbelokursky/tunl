package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Protocol;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        return TestServers.server()
                .name(name)
                .address("192.0.2.1")
                .port(443)
                .uuid(uuid)
                .build();
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

    /**
     * Every sealed credential is a keychain process of its own, and a load
     * read them one after another before the window first appeared: 1.6 s
     * for a hundred servers on macOS. They are read a few at a time now, and
     * each still lands on its own server.
     */
    @Test
    void sealedCredentialsAreReadAFewAtATime() {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        List<String> uuids = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "uuid-" + i).toList();
        for (int i = 0; i < uuids.size(); i++) {
            store.addServer(server("s" + i, uuids.get(i)));
        }
        java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger most =
                new java.util.concurrent.atomic.AtomicInteger();
        SecretSealer slow = new SecretSealer() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String seal(String key, String plaintext) {
                return sealer.seal(key, plaintext);
            }

            @Override
            public java.util.Optional<String> unseal(String key, String stored) {
                most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                    synchronized (sealer) {
                        return sealer.unseal(key, stored);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return java.util.Optional.empty();
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public void delete(String key) {
                sealer.delete(key);
            }
        };

        ConfigStore reloaded = new ConfigStore(tempDir, slow);

        assertThat(reloaded.getServers()).extracting(ServerConfig::getUuid)
                .containsExactlyElementsOf(uuids);
        assertThat(most.get()).as("credentials read at once").isGreaterThan(1);
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

    /**
     * The seal cache only knew values sealed in this session, so a restarted
     * app sealed every credential again on its first save: on macOS one
     * {@code security} process per server, on the FX thread when the save
     * came from a click in the server list.
     */
    @Test
    void aReloadedStoreDoesNotSealItsCredentialsAgainOnTheFirstSave() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(tempDir, sealer);
        store.addServer(server("s1", "uuid-one"));
        store.addServer(server("s2", "uuid-two"));

        ConfigStore reloaded = new ConfigStore(tempDir, sealer);
        sealer.resetSealCalls();
        reloaded.setActiveServer(reloaded.getServers().get(1).getId());

        assertThat(sealer.sealCalls())
                .as("the credentials unsealed on load are sealed on disk already")
                .isZero();
        assertThat(rawServersJson()).doesNotContain("uuid-one").doesNotContain("uuid-two");
    }
}
