package com.vlessclient.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.platform.ElevationDeclinedException;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TunnelRecoveryService;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent's connect and set_proxy_mode against the real connection flow.
 *
 * <p>A connect with a server or a mode while another start was under way and
 * had no core yet (the user's TUN start at its setup prompt, or in the core's
 * check) saved the pick and the mode, waited for that start, and was then
 * refused as "already running"; the service moved the new tunnel to the
 * agent's server as it came up. A set_proxy_mode without a string mode was
 * answered with a NullPointerException's text.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class McpConnectionControlTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private ConnectionService connection;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
        store.getSettings().setHealthCheckEnabled(false);
        store.getSettings().setMcpAllowMutations(true);
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Osaka"));
        store.setActiveServer("srv-1");
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.disconnect();
            connection.getRecoveryService().close();
        }
    }

    private static ServerConfig server(String id, String name) {
        return TestServers.server().id(id).name(name).protocol(Protocol.VLESS)
                .address(name.toLowerCase(Locale.ROOT) + ".example.com").port(443)
                .uuid("11111111-1111-1111-1111-111111111111").build();
    }

    /** A start that waits, as a TUN launch waits at its setup prompt, before any core runs. */
    static final class PromptingEngine extends SingBoxEngine {
        final CountDownLatch atPrompt = new CountDownLatch(1);
        final CountDownLatch answered = new CountDownLatch(1);
        final List<ProxyMode> starts = new CopyOnWriteArrayList<>();
        final AtomicInteger launches = new AtomicInteger();
        final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        volatile boolean running;
        /** What the next start throws once its prompt is answered, as a dismissal does. */
        volatile IOException failNext;

        PromptingEngine(Path binary) {
            super(binary);
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            launches.incrementAndGet();
            atPrompt.countDown();
            try {
                answered.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            IOException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            starts.add(proxyMode);
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

        @Override
        public boolean isStopping() {
            return false;
        }

        @Override
        public boolean awaitStopped(Duration timeout, BooleanSupplier stillWanted) {
            return !running;
        }

        @Override
        public boolean awaitEarlierTunnel(Duration timeout, BooleanSupplier stillWanted) {
            return true;
        }
    }

    private DefaultAppControlService controlOver(SingBoxEngine engine) {
        connection = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        return new DefaultAppControlService(store, null, null, new RoutingService(),
                connection, null, null, engine);
    }

    /** The user's Connect, from the Dashboard or the tray, on a thread of its own. */
    private Thread usersConnect(AtomicReference<Object> outcome) {
        return Thread.startVirtualThread(() -> {
            try {
                outcome.set(connection.connect().outcome());
            } catch (IOException | RuntimeException e) {
                outcome.set(e);
            }
        });
    }

    private boolean picked(String id) {
        return store.getServerById(id).orElseThrow().isActive();
    }

    @Test
    void aConnectDuringAnotherStartIsRefusedAndChangesNothing() throws Exception {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.saveSettings(store.getSettings());
        PromptingEngine engine = new PromptingEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService control = controlOver(engine);
        AtomicReference<Object> user = new AtomicReference<>();
        Thread userConnect = usersConnect(user);
        assertThat(engine.atPrompt.await(5, TimeUnit.SECONDS)).as("the user's start at its prompt")
                .isTrue();

        assertThatThrownBy(() -> control.connect("srv-2", "system_proxy", false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("starting")
                .hasMessageContaining("nothing was changed");
        assertThat(List.of(picked("srv-1"), store.getSettings().getProxyMode()))
                .as("[Tokyo picked, the saved mode] once the agent was answered, at the prompt")
                .containsExactly(true, ProxyMode.TUN);

        engine.answered.countDown();
        userConnect.join(5_000);
        assertThat(user.get()).isEqualTo(ConnectionService.Outcome.STARTED);
        // The core reports Connected, where a pick made meanwhile is applied.
        FxExecutor.run(() -> engine.state.set(ConnectionState.CONNECTED));
        Thread.sleep(500);
        assertThat(engine.starts).as("TUN launches").containsExactly(ProxyMode.TUN);
        assertThat(connection.appliedServerId()).isEqualTo("srv-1");
        assertThat(new ConfigStore(tempDir).getSettings().getProxyMode())
                .as("the mode saved on disk").isEqualTo(ProxyMode.TUN);
    }

    /** Asking for the tunnel that is starting changes nothing, and is answered once it is up. */
    @Test
    void aConnectDuringAnotherStartAskingForNothingNewIsTheTunnelOnceUp() throws Exception {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PromptingEngine engine = new PromptingEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService control = controlOver(engine);
        AtomicReference<Object> user = new AtomicReference<>();
        Thread userConnect = usersConnect(user);
        assertThat(engine.atPrompt.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Object> agent = new AtomicReference<>();
        Thread agentCall = Thread.startVirtualThread(() -> {
            try {
                agent.set(control.connect("srv-1", "tun", true).activeServerId());
            } catch (McpToolException e) {
                agent.set(e);
            }
        });
        Await.until("the agent's connect to wait for the start",
                () -> agentCall.getState() == Thread.State.WAITING
                        || agentCall.getState() == Thread.State.BLOCKED
                        || agentCall.getState() == Thread.State.TERMINATED,
                Duration.ofSeconds(5));
        engine.answered.countDown();
        agentCall.join(5_000);
        userConnect.join(5_000);

        assertThat(agent.get()).as("the agent's answer").isEqualTo("srv-1");
        assertThat(engine.starts).containsExactly(ProxyMode.TUN);
    }

    /**
     * The user's start ends at its dismissed prompt, while an agent's
     * connect asking for nothing new waited for it. The connect then started
     * a core of its own once the user's had ended, and raised the prompt the
     * user had just dismissed again; it says the tunnel did not come up.
     */
    @Test
    void aConnectWaitingForAStartThatEndsWithoutACoreStartsNone() throws Exception {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PromptingEngine engine = new PromptingEngine(tempDir.resolve("sing-box"));
        engine.failNext = new ElevationDeclinedException("the setup dialog was dismissed");
        DefaultAppControlService control = controlOver(engine);
        AtomicReference<Object> user = new AtomicReference<>();
        Thread userConnect = usersConnect(user);
        assertThat(engine.atPrompt.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Object> agent = new AtomicReference<>();
        Thread agentCall = Thread.startVirtualThread(() -> {
            try {
                agent.set(control.connect(null, null, false));
            } catch (McpToolException e) {
                agent.set(e);
            }
        });
        awaitWaiting(agentCall);
        engine.answered.countDown();
        agentCall.join(5_000);
        userConnect.join(5_000);

        assertThat(user.get()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(agent.get()).isInstanceOf(McpToolException.class);
        assertThat(((McpToolException) agent.get()).getMessage())
                .contains("did not come up").contains("nothing was changed");
        assertThat(engine.launches).as("launches, each one a prompt").hasValue(1);
    }

    // ===== a start that replaces a running core =====

    /** Launches nothing; a stop can be held, as a real core's SIGTERM grace holds it. */
    static final class HeldStopEngine extends SingBoxEngine {
        final List<ProxyMode> starts = new CopyOnWriteArrayList<>();
        final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        volatile boolean running;
        volatile CountDownLatch stopEntered;
        volatile CountDownLatch stopRelease;

        HeldStopEngine(Path binary) {
            super(binary);
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) {
            starts.add(proxyMode);
            running = true;
        }

        @Override
        public void stop() {
            CountDownLatch entered = stopEntered;
            CountDownLatch release = stopRelease;
            if (running && entered != null && release != null) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isStopping() {
            return false;
        }

        @Override
        public boolean awaitStopped(Duration timeout, BooleanSupplier stillWanted) {
            return !running;
        }

        @Override
        public boolean awaitEarlierTunnel(Duration timeout, BooleanSupplier stillWanted) {
            return true;
        }

        void holdTheNextStop() {
            stopEntered = new CountDownLatch(1);
            stopRelease = new CountDownLatch(1);
        }
    }

    /** Waits until {@code thread} waits, for the lock a start holds, or has ended. */
    private static void awaitWaiting(Thread thread) {
        Await.until("the call to wait for the start",
                () -> thread.getState() == Thread.State.WAITING
                        || thread.getState() == Thread.State.BLOCKED
                        || thread.getState() == Thread.State.TERMINATED,
                Duration.ofSeconds(5));
    }

    /**
     * A recovery restart of a TUN tunnel, the saved mode changed to a system
     * proxy since (saved, not applied), and an agent's connect with no
     * arguments during the restart's stop, as get_status read Connecting.
     * The connect took the saved mode for its own, its request called the
     * restart off after the stop, and it started a system proxy: apps that
     * ignore it went around the tunnel. It waits for the restart now, and
     * is answered with it.
     */
    @Test
    void aConnectWithNothingNewDuringARecoveryRestartKeepsTheModeItRestartsIn()
            throws Exception {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        HeldStopEngine engine = new HeldStopEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService control = controlOver(engine);
        assertThat(connection.connect().started()).isTrue();
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        engine.holdTheNextStop();
        TunnelRecoveryService recovery = connection.getRecoveryService();
        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onHealth(TunnelHealth.BROKEN);
        assertThat(engine.stopEntered.await(10, TimeUnit.SECONDS))
                .as("recovery's restart stopping the TUN core").isTrue();

        AtomicReference<Object> agent = new AtomicReference<>();
        Thread agentCall = Thread.startVirtualThread(() -> {
            try {
                agent.set(control.connect(null, null, false));
            } catch (McpToolException e) {
                agent.set(e);
            }
        });
        awaitWaiting(agentCall);
        engine.stopRelease.countDown();
        agentCall.join(10_000);

        assertThat(engine.starts).as("the modes the core was started in")
                .containsExactly(ProxyMode.TUN, ProxyMode.TUN);
        assertThat(agent.get()).isInstanceOf(StatusInfo.class);
        assertThat(((StatusInfo) agent.get()).runningProxyMode()).isEqualTo("tun");
    }

    /**
     * A pick's restart of a system proxy, the saved mode changed to TUN
     * since, and an agent's connect asking for TUN during the restart's
     * stop. Compared with the saved mode it asked for nothing new: it called
     * the restart off and started TUN without the pick's restart being done.
     * Another server is refused as well, through the restart's stop.
     */
    @Test
    void aConnectAskingForAnotherModeOrServerThanAPicksRestartIsRefused() throws Exception {
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        HeldStopEngine engine = new HeldStopEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService control = controlOver(engine);
        assertThat(connection.connect().started()).isTrue();
        // Saved, not applied, and something the core does not run yet, so
        // the pick restarts it.
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);
        engine.holdTheNextStop();
        store.setActiveServer("srv-2");
        AtomicReference<Object> switched = new AtomicReference<>();
        Thread pick = Thread.ofPlatform().name("pick-switch").start(() -> {
            try {
                switched.set(connection.switchToActiveServer().outcome());
            } catch (IOException | RuntimeException e) {
                switched.set(e);
            }
        });
        assertThat(engine.stopEntered.await(5, TimeUnit.SECONDS)).as("the restart's stop")
                .isTrue();

        assertThatThrownBy(() -> control.connect(null, "tun", true))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("starting")
                .hasMessageContaining("nothing was changed");
        assertThatThrownBy(() -> control.connect("srv-1", null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("starting");

        engine.stopRelease.countDown();
        pick.join(10_000);
        assertThat(switched.get()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(engine.starts).as("the modes the core was started in")
                .containsExactly(ProxyMode.SYSTEM_PROXY, ProxyMode.SYSTEM_PROXY);
        assertThat(List.of(picked("srv-2"), store.getSettings().getProxyMode()))
                .as("[Osaka picked, the saved mode]").containsExactly(true, ProxyMode.TUN);
    }

    // ===== set_proxy_mode =====

    private JsonNode setProxyMode(McpServer server, ObjectNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "set_proxy_mode");
        params.set("arguments", arguments);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", "tools/call");
        message.set("params", params);
        return server.handle(message).path("result");
    }

    /**
     * Without a mode, or with one that is no string, the agent was answered
     * "Cannot invoke String.toLowerCase(java.util.Locale) because mode is
     * null", and tunl.log got a warning with the stack trace, as for a
     * failure of the app's own. It is a missing argument.
     */
    @Test
    void setProxyModeWithoutAStringModeIsAMissingArgument() {
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        McpServer server = new McpServerService(store, controlOver(engine)).buildServer();
        Logger mcpLog = (Logger) LoggerFactory.getLogger(McpServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        mcpLog.addAppender(appender);
        try {
            for (ObjectNode arguments : List.of(MAPPER.createObjectNode(),
                    MAPPER.createObjectNode().put("mode", 1),
                    MAPPER.createObjectNode().putNull("mode"),
                    MAPPER.createObjectNode().put("mode", " "))) {
                JsonNode answer = setProxyMode(server, arguments);
                assertThat(answer.path("isError").asBoolean()).as("%s", arguments).isTrue();
                assertThat(answer.path("content").get(0).path("text").asString())
                        .as("%s", arguments)
                        .isEqualTo("Error: 'mode' is required: 'system_proxy' or 'tun'.");
            }
            assertThat(appender.list).as("what tunl.log got")
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
            assertThat(store.getSettings().getProxyMode()).isEqualTo(ProxyMode.SYSTEM_PROXY);
        } finally {
            mcpLog.detachAppender(appender);
        }
    }
}
