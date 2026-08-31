package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The connect flow used to exist in three independent copies (dashboard, tray,
 * MCP) and had drifted apart; these tests pin the single owner's behaviour so
 * the divergence cannot come back through any one caller.
 */
class ConnectionServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Already started by another test class.
        }
    }

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
    }

    private ServerConfig server(String id, String name) {
        ServerConfig s = new ServerConfig();
        s.setId(id);
        s.setName(name);
        s.setProtocol(Protocol.VLESS);
        s.setAddress(name.toLowerCase(java.util.Locale.ROOT) + ".example.com");
        s.setPort(443);
        s.setUuid("11111111-1111-1111-1111-111111111111");
        return s;
    }

    /**
     * Stands in for the real engine: records what it was asked to do, in order,
     * without launching anything.
     */
    private static final class RecordingEngine extends SingBoxEngine {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> configs = new CopyOnWriteArrayList<>();
        final List<ProxyMode> modes = new CopyOnWriteArrayList<>();
        volatile boolean running;
        volatile IOException failStartWith;
        volatile boolean refuseAsAlreadyRunning;

        RecordingEngine(Path binary) {
            super(binary);
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            calls.add("start");
            if (failStartWith != null) {
                throw failStartWith;
            }
            if (refuseAsAlreadyRunning) {
                throw new IllegalStateException("sing-box is already running");
            }
            configs.add(configJson);
            modes.add(proxyMode);
            running = true;
        }

        @Override
        public void stop() {
            calls.add("stop");
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean awaitStopped(Duration timeout) {
            calls.add("await");
            return !running;
        }
    }

    private ConnectionService service(SingBoxEngine engine) {
        return new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
    }

    private RecordingEngine engine() {
        return new RecordingEngine(tempDir.resolve("sing-box"));
    }

    // ===== what the generator is given =====

    /**
     * The AUTO_BEST regression, now pinned at the single owner: every configured
     * server must reach the generator as a candidate. The MCP path used to pass
     * the active server alone, which silently collapsed automatic selection to a
     * one-member group.
     */
    @Test
    void connectPassesEveryCandidateToTheGenerator() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Osaka"));
        store.getSettings().setServerSelection(ServerSelection.AUTO_BEST);

        List<List<ServerConfig>> captured = new ArrayList<>();
        SingBoxConfigGenerator capturing = new SingBoxConfigGenerator() {
            @Override
            public String generate(List<ServerConfig> candidates, ServerConfig active,
                                   AppSettings settings, RoutingConfig routing) {
                captured.add(List.copyOf(candidates));
                return "{}";
            }
        };
        ConnectionService service =
                new ConnectionService(store, capturing, new RoutingService(), engine());

        assertThat(service.connect().started()).isTrue();

        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst()).extracting(ServerConfig::getId)
                .containsExactlyInAnyOrder("srv-1", "srv-2");
    }

    @Test
    void connectUsesTheProxyModeFromSettings() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        RecordingEngine engine = engine();

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.started()).isTrue();
        assertThat(attempt.server().getId()).isEqualTo("srv-1");
        assertThat(engine.modes).containsExactly(ProxyMode.SYSTEM_PROXY);
        assertThat(engine.configs.getFirst()).contains("\"outbounds\"");
    }

    /** The MCP path can override the mode per call without touching settings. */
    @Test
    void connectHonoursAnExplicitModeOverride() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        RecordingEngine engine = engine();

        service(engine).connect(ProxyMode.TUN);

        assertThat(engine.modes).containsExactly(ProxyMode.TUN);
        assertThat(store.getSettings().getProxyMode())
                .as("an override is per-call and must not rewrite the stored mode")
                .isEqualTo(ProxyMode.SYSTEM_PROXY);
    }

    @Test
    void connectFallsBackToDefaultRoutingWhenServiceIsUnavailable() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        List<RoutingConfig> captured = new ArrayList<>();
        SingBoxConfigGenerator capturing = new SingBoxConfigGenerator() {
            @Override
            public String generate(List<ServerConfig> candidates, ServerConfig active,
                                   AppSettings settings, RoutingConfig routing) {
                captured.add(routing);
                return "{}";
            }
        };
        ConnectionService service = new ConnectionService(store, capturing, null, engine());

        assertThat(service.connect().started()).isTrue();

        assertThat(captured).containsExactly((RoutingConfig) null);
    }

    @Test
    void connectFallsBackToDefaultRoutingWhenRulesCannotBeRead() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        List<RoutingConfig> captured = new ArrayList<>();
        SingBoxConfigGenerator capturing = new SingBoxConfigGenerator() {
            @Override
            public String generate(List<ServerConfig> candidates, ServerConfig active,
                                   AppSettings settings, RoutingConfig routing) {
                captured.add(routing);
                return "{}";
            }
        };
        RoutingService failingRouting = new RoutingService() {
            @Override
            public RoutingConfig getConfig() {
                throw new IllegalStateException("unreadable routing file");
            }
        };
        ConnectionService service =
                new ConnectionService(store, capturing, failingRouting, engine());

        assertThat(service.connect().started()).isTrue();

        assertThat(captured).containsExactly((RoutingConfig) null);
    }

    // ===== outcomes callers map to their own UX =====

    @Test
    void connectWithoutAnEngineReportsNoEngine() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));

        ConnectionService.ConnectAttempt attempt = service(null).connect();

        assertThat(attempt.outcome()).isEqualTo(ConnectionService.Outcome.NO_ENGINE);
        assertThat(attempt.started()).isFalse();
        assertThat(attempt.server()).isNull();
    }

    @Test
    void connectWithoutAnActiveServerReportsNoActiveServer() throws Exception {
        RecordingEngine engine = engine();

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.outcome()).isEqualTo(ConnectionService.Outcome.NO_ACTIVE_SERVER);
        assertThat(engine.calls).as("nothing may be launched without a target").isEmpty();
    }

    @Test
    void anEngineThatRefusesIsReportedNotThrown() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.refuseAsAlreadyRunning = true;

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.outcome()).isEqualTo(ConnectionService.Outcome.ALREADY_RUNNING);
        assertThat(attempt.server().getId())
                .as("the caller still learns which server was intended")
                .isEqualTo("srv-1");
    }

    @Test
    void aFailedStartPropagatesItsIoException() {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.failStartWith = new IOException("no such binary");

        assertThatThrownBy(() -> service(engine).connect())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no such binary");
    }

    // ===== ordering: a start never races a previous stop =====

    @Test
    void connectWaitsForAPreviousCoreToExitBeforeStarting() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();

        service(engine).connect();

        assertThat(engine.calls).containsExactly("await", "start");
    }

    @Test
    void reconnectStopsThenWaitsThenStarts() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.running = true;

        ConnectionService.ConnectAttempt attempt = service(engine).reconnect(null);

        assertThat(attempt.started()).isTrue();
        assertThat(engine.calls).containsExactly("stop", "await", "start");
    }

    @Test
    void disconnectStopsTheEngineAndIsSafeWithoutOne() {
        RecordingEngine engine = engine();
        engine.running = true;

        service(engine).disconnect();
        assertThat(engine.calls).containsExactly("stop");

        service(null).disconnect();   // no engine yet: must not throw
    }

    // ===== the engine is replaced once the binary is installed =====

    /**
     * The app can start with no sing-box binary and register an engine later.
     * A connect after that must drive the new engine — missing this would leave
     * every caller pointed at the engine that never had a binary.
     */
    @Test
    void setEngineRedirectsLaterConnects() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        ConnectionService service = service(null);
        assertThat(service.connect().outcome())
                .isEqualTo(ConnectionService.Outcome.NO_ENGINE);

        RecordingEngine installed = engine();
        service.setEngine(installed);

        assertThat(service.getEngine()).isSameAs(installed);
        assertThat(service.connect().started()).isTrue();
        assertThat(installed.calls).contains("start");
    }

    @Test
    void isRunningFollowsTheEngineAndIsFalseWithoutOne() throws Exception {
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);

        assertThat(service.isRunning()).isFalse();
        store.addServer(server("srv-1", "Tokyo"));
        service.connect();
        assertThat(service.isRunning()).isTrue();

        assertThat(service(null).isRunning()).isFalse();
    }

    // ===== the threading contract =====

    /**
     * The tray shipped a version of this flow that ran on the JavaFX thread and
     * froze the window for up to a minute on a TUN connect. The contract is
     * enforced rather than documented so that mistake fails a test instead of
     * reaching a user.
     */
    @Test
    void connectOnTheJavaFxThreadIsRefused() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                service.connect();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(thrown.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run on the JavaFX thread");
        assertThat(engine.calls).as("nothing ran on the FX thread").isEmpty();
    }

    @Test
    void disconnectOnTheJavaFxThreadIsRefused() throws Exception {
        ConnectionService service = service(engine());

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                service.disconnect();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
    }
}
