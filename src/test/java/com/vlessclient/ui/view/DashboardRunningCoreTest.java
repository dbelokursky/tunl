package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Dashboard acts on the core that runs, whoever started it: the tray, an
 * agent over MCP, a recovery restart, or its own Connect.
 *
 * <p>It compared each server pick with a copy of its own, which only its own
 * Connect wrote. A core started from the tray left that copy empty, so a
 * server picked afterwards changed the list and the tray's checkmark while
 * the traffic stayed on the first one; and a core the service had moved on
 * at Connected was taken to be on the server the Connect began with.</p>
 *
 * <p>Each test gets an engine, a store, a checker and a service of its own:
 * a controller from an earlier test is still listening to whatever it was
 * built with.</p>
 */
@UiTest
public class DashboardRunningCoreTest extends ApplicationTest {

    @TempDir
    static Path dataDirs;

    /** Engine whose connection state the test drives directly. */
    private static final class FakeEngine extends SingBoxEngine {
        private final SimpleObjectProperty<ConnectionState> state =
                new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

        FakeEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state;
        }
    }

    /** Finds every target reachable at once, and counts the probe batches. */
    private static final class CountingChecker extends ServiceReachabilityChecker {
        private final AtomicInteger batches = new AtomicInteger();

        @Override
        public CompletableFuture<List<ProbeResult>> checkAll(
                List<HealthCheckTarget> targets, int probePort) {
            batches.incrementAndGet();
            List<ProbeResult> results = new ArrayList<>();
            for (HealthCheckTarget target : targets) {
                results.add(new ProbeResult(
                        target.getName(), target.getUrl(), true, 12, "HTTP 204"));
            }
            return CompletableFuture.completedFuture(results);
        }
    }

    /**
     * Stands in for the connection flow: says which server the running core
     * was last pointed at, as the real one keeps it for every caller, and
     * records the live switches the Dashboard asks for.
     */
    private static final class StubConnectionService extends ConnectionService {
        private volatile String applied;
        private final List<String> switches = new CopyOnWriteArrayList<>();
        private final CountDownLatch connected = new CountDownLatch(1);

        StubConnectionService() {
            super(null, null, null, SingBoxEngine.withoutCore());
        }

        @Override
        public String appliedServerId() {
            return applied;
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) {
            ServerConfig active = activeServer();
            applied = active.getId();
            connected.countDown();
            return new ConnectAttempt(Outcome.STARTED, active);
        }

        @Override
        public ConnectAttempt switchToActiveServer() {
            ServerConfig active = activeServer();
            switches.add(active.getId());
            applied = active.getId();
            return new ConnectAttempt(Outcome.SWITCHED, active);
        }

        private static ServerConfig activeServer() {
            return FxExecutor.get(() -> ServiceLocator.get(ConfigStore.class).getServers()
                    .stream().filter(ServerConfig::isActive).findFirst().orElseThrow());
        }
    }

    private final ServerConfig tokyo = server("Tokyo", "203.0.113.9");
    private final ServerConfig osaka = server("Osaka", "203.0.113.10");
    private FakeEngine engine;
    private CountingChecker checker;
    private StubConnectionService service;
    private ConfigStore store;
    private AppSettings settings;
    private DashboardViewController controller;

    @Override
    public void start(Stage stage) throws Exception {
        store = new ConfigStore(dataDirs.resolve(UUID.randomUUID().toString()));
        settings = store.getSettings();
        settings.setHealthCheckEnabled(true);
        settings.setHealthCheckAutoReconnect(false);
        // Long enough that no periodic probe lands while a test runs.
        settings.setHealthCheckIntervalSeconds(3600);
        settings.setHealthCheckTargets(new ArrayList<>(
                List.of(new HealthCheckTarget("a", "https://a.example"))));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(AppSettings.class, settings);
        engine = new FakeEngine();
        ServiceLocator.register(SingBoxEngine.class, engine);
        checker = new CountingChecker();
        ServiceLocator.register(ServiceReachabilityChecker.class, checker);
        service = new StubConnectionService();
        ServiceLocator.register(ConnectionService.class, service);
        // What a launch finds: two servers, the first of them picked.
        store.addServer(tokyo);
        store.addServer(osaka);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @AfterEach
    void disconnect() {
        interact(() -> engine.state.set(ConnectionState.DISCONNECTED));
        checker.shutdown();
    }

    /**
     * Connected from the tray, the Dashboard's Connect never ran, and a server
     * picked afterwards in the tray's menu was not applied at all: no switch,
     * and no banner either, since a pick switches live and does not count as
     * a change waiting for a reconnect.
     */
    @Test
    void aServerPickedUnderACoreTheTrayStartedIsApplied() {
        service.applied = tokyo.getId();
        interact(() -> engine.state.set(ConnectionState.CONNECTED));

        interact(() -> store.setActiveServer(osaka.getId()));

        Await.until("the switch to Osaka", () -> service.switches.contains(osaka.getId()),
                Duration.ofSeconds(5));
    }

    /**
     * Connected from here to Tokyo, Osaka picked while connecting, and the
     * service moving the core to Osaka at Connected: picking Tokyo again was
     * taken for no change, since the card still thought the core ran there.
     */
    @Test
    void pickingBackTheServerTheConnectBeganWithMovesACoreThatMovedOn() throws Exception {
        interact(controller::toggleConnection);
        assertThat(service.connected.await(5, TimeUnit.SECONDS)).as("connected").isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> engine.state.set(ConnectionState.CONNECTING));
        interact(() -> store.setActiveServer(osaka.getId()));
        // What ConnectionService.followActiveServer does as the core comes up.
        service.applied = osaka.getId();
        interact(() -> engine.state.set(ConnectionState.CONNECTED));

        interact(() -> store.setActiveServer(tokyo.getId()));

        Await.until("the switch back to Tokyo", () -> service.switches.contains(tokyo.getId()),
                Duration.ofSeconds(5));
    }

    /**
     * Deleting the only subscription, to add it back with a new link, empties
     * the list under a running core. Connect reads Disconnect then, and it
     * was greyed out with "No servers configured": the one control on the
     * card that stops the tunnel, until something else repainted it.
     */
    @Test
    void emptyingTheListUnderARunningCoreKeepsDisconnect() {
        service.applied = tokyo.getId();
        interact(() -> engine.state.set(ConnectionState.CONNECTED));

        interact(() -> store.removeServer(osaka.getId()));
        interact(() -> store.removeServer(tokyo.getId()));

        Button connect = lookup("#connectButton").queryButton();
        assertThat(connect.getText()).isEqualTo(I18n.get("button.disconnect"));
        assertThat(connect.isDisabled()).as("Disconnect, with no server left in the list")
                .isFalse();
    }

    /**
     * A server picked while connected switches the core through its selector,
     * which changes no connection state. The probes went on at the cadence the
     * old server had earned, up to a minute apart, and a dead new server read
     * as healthy until the next one.
     */
    @Test
    void aLiveSwitchChecksTheNewExitAtOnce() {
        service.applied = tokyo.getId();
        interact(() -> engine.state.set(ConnectionState.CONNECTED));
        Await.until("the connect's probe", () -> checker.batches.get() == 1,
                Duration.ofSeconds(5));

        interact(() -> store.setActiveServer(osaka.getId()));

        Await.until("the switch to Osaka", () -> service.switches.contains(osaka.getId()),
                Duration.ofSeconds(5));
        Await.until("a probe through Osaka", () -> checker.batches.get() == 2,
                Duration.ofSeconds(5));
    }

    private static ServerConfig server(String name, String address) {
        return TestServers.server()
                .id(UUID.randomUUID().toString())
                .name(name)
                .protocol(Protocol.VLESS)
                .address(address)
                .port(443)
                .uuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                .build();
    }
}
