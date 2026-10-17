package com.vlessclient.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's connect against the real connection flow.
 *
 * <p>A connect with a server or a mode while another start was under way and
 * had no core yet (the user's TUN start at its setup prompt, or in the core's
 * check) saved the pick and the mode, waited for that start, and was then
 * refused as "already running"; the service moved the new tunnel to the
 * agent's server as it came up.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class McpConnectionControlTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private ConnectionService connection;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
        store.getSettings().setHealthCheckEnabled(false);
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
        final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        volatile boolean running;

        PromptingEngine(Path binary) {
            super(binary);
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) {
            atPrompt.countDown();
            try {
                answered.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
}
