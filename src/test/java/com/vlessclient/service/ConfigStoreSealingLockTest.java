package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A save that seals credentials does not hold the store while the keychain
 * works.
 *
 * <p>Sealing takes a keychain process per new credential, and the save ran
 * them all inside the store's monitor. The FX thread takes that monitor to
 * read the store, to change the list and to save the settings, so a
 * subscription of 300 servers froze the window for seconds, and the
 * connect, recovery and MCP paths that wait for the FX thread gave up after
 * ten.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class ConfigStoreSealingLockTest {

    /** Well under FxExecutor's own ten seconds, and far over a free monitor. */
    private static final Duration PROMPT = Duration.ofSeconds(2);

    @TempDir
    Path tempDir;

    /** A keychain that stops in the middle of sealing until it is let go. */
    private static final class StalledKeychain implements SecretSealer {
        private final InMemorySecretSealer backend = new InMemorySecretSealer();
        final CountDownLatch sealing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        /** Holds a delete back, so it lands after a seal that is under way. */
        final CountDownLatch allowDelete = new CountDownLatch(1);
        final CountDownLatch deleted = new CountDownLatch(1);

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String seal(String key, String plaintext) {
            sealing.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("the keychain was never let go");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return backend.seal(key, plaintext);
        }

        @Override
        public Optional<String> unseal(String key, String stored) {
            return backend.unseal(key, stored);
        }

        @Override
        public void delete(String key) {
            try {
                if (!allowDelete.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("the delete was never let through");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backend.delete(key);
            deleted.countDown();
        }

        Map<String, String> entries() {
            return backend.entries();
        }
    }

    private static ServerConfig server(String id, String uuid) {
        ServerConfig server = new ServerConfig();
        server.setId(id);
        server.setName(id);
        server.setProtocol(Protocol.VLESS);
        server.setAddress(id + ".example");
        server.setPort(443);
        server.setUuid(uuid);
        return server;
    }

    /**
     * A seal that finishes after its server was removed is not kept for
     * reuse. The server's keychain entry is deleted as it goes, and the same
     * server added again, which a subscription refresh does, would take the
     * kept tag and name an entry that is gone: its credential lost at the
     * next start.
     */
    @Test
    void aSealThatOutlivedItsServerIsNotReused() throws Exception {
        StalledKeychain keychain = new StalledKeychain();
        ConfigStore store = new ConfigStore(tempDir, keychain);
        Thread importer = Thread.startVirtualThread(
                () -> store.addServer(server("x", "secret-of-x")));
        assertThat(keychain.sealing.await(10, TimeUnit.SECONDS)).as("the add is sealing")
                .isTrue();

        FxExecutor.get(() -> {
            store.removeServer("x");
            return null;
        }, PROMPT);
        keychain.release.countDown();
        importer.join(10_000);
        keychain.allowDelete.countDown();
        assertThat(keychain.deleted.await(10, TimeUnit.SECONDS)).as("the entry deleted").isTrue();

        store.addServer(server("x", "secret-of-x"));

        assertThat(keychain.entries()).as("the entry the file names now").containsKey("x.uuid");
    }

    @Test
    void theStoreAnswersTheFxThreadWhileAnImportIsSealed() throws Exception {
        StalledKeychain keychain = new StalledKeychain();
        ConfigStore store = new ConfigStore(tempDir, keychain);
        Thread importer = Thread.startVirtualThread(() -> store.applyServerBatch(
                List.of(server("a", "secret-of-a"), server("b", "secret-of-b")), List.of()));
        assertThat(keychain.sealing.await(10, TimeUnit.SECONDS)).as("the import is sealing")
                .isTrue();

        try {
            assertThat(FxExecutor.get(() -> store.getServerById("a").isPresent(), PROMPT))
                    .as("a read on the FX thread").isTrue();
            FxExecutor.get(() -> {
                store.saveSettings(store.getSettings());
                store.setActiveServer("b");
                return null;
            }, PROMPT);
        } finally {
            keychain.release.countDown();
            importer.join(10_000);
        }

        String written = Files.readString(tempDir.resolve("servers.json"));
        assertThat(written).as("the import's credentials, sealed")
                .doesNotContain("secret-of-a", "secret-of-b")
                .contains(InMemorySecretSealer.FAKE_TAG);
        ConfigStore reloaded = new ConfigStore(tempDir, keychain);
        assertThat(reloaded.getServers()).filteredOn(ServerConfig::isActive)
                .extracting(ServerConfig::getId)
                .as("the pick made on the FX thread while the import sealed")
                .containsExactly("b");
    }
}
