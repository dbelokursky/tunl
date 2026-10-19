package com.vlessclient.service;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start whose session configuration cannot be written: a full disk, a run
 * folder that cannot be made, or, as here, a configuration the file's UTF-8
 * cannot hold. The start published CONNECTING and then threw from the write,
 * which came before the steps that take CONNECTING back once nothing was
 * launched. The status, the tray and an agent's get_status said Connecting
 * with nothing running, the Connect button acted as a Disconnect, and every
 * recovery retry left it so again.
 */
@ExtendWith(FxToolkitExtension.class)
class SingBoxEngineConfigWriteFailureTest {

    /** A lone half of a surrogate pair, which no UTF-8 file can hold. */
    private static final String UNWRITABLE_CONFIG = "{\"log\":{\"level\":\"info\"},"
            + "\"outbounds\":[{\"type\":\"vless\",\"tag\":\"ws-\uD83C\"}]}";

    @TempDir
    Path tempDir;

    /** The session configurations already there, so the test removes only its own. */
    private final Set<Path> before = new HashSet<>();

    @BeforeEach
    void noteTheSessionFolder() throws IOException {
        before.addAll(sessionConfigs());
    }

    @AfterEach
    void removeWhatTheFailedWriteLeft() throws IOException {
        for (Path left : sessionConfigs()) {
            if (!before.contains(left)) {
                Files.deleteIfExists(left);
            }
        }
    }

    private static Set<Path> sessionConfigs() throws IOException {
        Set<Path> found = new HashSet<>();
        Path dir = SingBoxEngine.withoutCore().sessionConfigDir();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> configs = Files.newDirectoryStream(dir,
                    SingBoxEngine.SESSION_CONFIG_PREFIX + "*.json")) {
                configs.forEach(found::add);
            }
        }
        return found;
    }

    @Test
    void aStartWhoseConfigCannotBeWrittenDoesNotStayConnecting() throws Exception {
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));

        assertThatThrownBy(() -> engine.start(UNWRITABLE_CONFIG, ProxyMode.SYSTEM_PROXY))
                .isInstanceOf(IOException.class);
        flushFxEvents();

        assertThat(engine.isRunning()).as("nothing was launched").isFalse();
        assertThat(FxExecutor.get(() -> engine.connectionStateProperty().get()))
                .as("a start that launched nothing")
                .isEqualTo(ConnectionState.DISCONNECTED);
    }

    /**
     * Through the connect every caller makes: the Dashboard, the tray, an
     * agent, recovery. It failed, and a second one failed the same way, each
     * leaving Connecting behind.
     */
    @Test
    void aConnectWhoseConfigCannotBeWrittenFailsWithoutStayingConnecting() throws Exception {
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box")) {
            @Override
            public void start(String configJson, ProxyMode proxyMode) throws IOException {
                super.start(UNWRITABLE_CONFIG, proxyMode);
            }
        };
        ConfigStore store = new ConfigStore(tempDir.resolve("data"));
        store.getSettings().setHealthCheckEnabled(false);
        store.addServer(TestServers.vless("Tokyo").id("srv-1").build());
        ConnectionService service = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        try {
            for (int attempt = 1; attempt <= 2; attempt++) {
                assertThatThrownBy(service::connect).as("connect #%d", attempt)
                        .isInstanceOf(IOException.class);
                flushFxEvents();
                assertThat(FxExecutor.get(() -> engine.connectionStateProperty().get()))
                        .as("after connect #%d", attempt)
                        .isEqualTo(ConnectionState.DISCONNECTED);
            }
        } finally {
            service.getRecoveryService().close();
        }
    }
}
