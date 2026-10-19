package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.testing.FxToolkitExtension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A servers.json entry without a protocol, from a hand edit or a merge tool,
 * is left out of each connect like any server the core cannot build.
 *
 * <p>A pick made while connected is compared with a configuration generated
 * anew, and for a pick of a server the run had left out, every listed server
 * went into that one, the one without a protocol too, which the generator
 * throws on. So a pick of that server, or of any other left-out one while it
 * was listed, threw instead of being refused by name; the Dashboard, its
 * switch failed with the core still up on the old server, asked for the same
 * switch again at once, for as long as the pick stood, and its check for
 * settings changed since the start threw on the FX thread.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class ServerWithoutProtocolPickTest {

    @TempDir
    Path dir;

    private ConfigStore store;

    /** Starts nothing: running from a start until a stop. */
    private static final class QuietEngine extends SingBoxEngine {

        private volatile boolean running;

        QuietEngine(Path binary) {
            super(binary);
        }

        @Override
        public void start(String config, ProxyMode mode) {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /**
     * A pick is saved by the store's writer thread, and so is one put back on
     * the server the core runs after a refusal: written as the test ended,
     * servers.json landed in the directory JUnit was deleting.
     */
    @AfterEach
    void letTheStoreFinishWriting() {
        if (store != null) {
            assertThat(store.awaitPendingWrites(5_000)).as("the store's writes finished").isTrue();
        }
    }

    @Test
    void aPickOfItWhileConnectedIsRefusedByName() throws Exception {
        ConnectionService service = connectedToTokyo();
        try {
            store.setActiveServer("s-2");

            assertThat(service.runsCurrentSettings())
                    .as("whether a restart would run as the core does").isFalse();
            assertThatThrownBy(service::switchToActiveServer)
                    .isInstanceOf(ConfigRejectedException.class)
                    .hasMessage(I18n.get("engine.config.rejected.server", "No protocol",
                            I18n.get("refusal.protocol")));
        } finally {
            service.disconnect();
            service.getRecoveryService().close();
        }
    }

    @Test
    void anotherRefusedPickIsStillRefusedByNameWhileItIsListed() throws Exception {
        ConnectionService service = connectedToTokyo();
        try {
            store.setActiveServer("s-3");

            assertThat(service.runsCurrentSettings())
                    .as("whether a restart would run as the core does").isFalse();
            assertThatThrownBy(service::switchToActiveServer)
                    .isInstanceOf(ConfigRejectedException.class)
                    .hasMessage(I18n.get("engine.config.rejected.server", "Bad port",
                            I18n.get("refusal.port", "70000")));
        } finally {
            service.disconnect();
            service.getRecoveryService().close();
        }
    }

    /** Connected to Tokyo, with the entry without a protocol and one the core refuses listed. */
    private ConnectionService connectedToTokyo() throws IOException {
        Files.writeString(dir.resolve("servers.json"), """
                {"config_version":1,"servers":[
                  {"id":"s-1","name":"Tokyo","protocol":"vless",
                   "address":"192.0.2.1","port":443,
                   "uuid":"11111111-1111-1111-1111-111111111111"},
                  {"id":"s-2","name":"No protocol",
                   "address":"192.0.2.2","port":443,
                   "uuid":"11111111-1111-1111-1111-111111111112"},
                  {"id":"s-3","name":"Bad port","protocol":"vless",
                   "address":"192.0.2.3","port":70000,
                   "uuid":"11111111-1111-1111-1111-111111111113"}]}""",
                StandardCharsets.UTF_8);
        store = new ConfigStore(dir);
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        store.setActiveServer("s-1");
        ConnectionService service = new ConnectionService(store, new SingBoxConfigGenerator(),
                null, new QuietEngine(dir.resolve("sing-box")));
        ConnectionService.ConnectAttempt attempt = service.connect();
        assertThat(attempt.started()).isTrue();
        assertThat(attempt.skipped()).extracting(ConnectionService.SkippedServer::name)
                .containsExactlyInAnyOrder("No protocol", "Bad port");
        return service;
    }
}
