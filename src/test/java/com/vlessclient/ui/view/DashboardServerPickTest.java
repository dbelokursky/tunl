package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.HostFacts;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * What the Dashboard does with a server pick under a running core: the
 * tray's, the list's or an agent's.
 *
 * <p>Once a switch was done, the card compared the pick again, whatever the
 * switch had come to. One a Disconnect called off came back while the stop
 * still ran and the card still read Connected, so the second look started
 * another switch, which asked for the tunnel again and started it after the
 * stop: the tunnel came back seconds after the user disconnected. So did a
 * pick made in the tray while its Disconnect was stopping the core.</p>
 */
@UiTest
public class DashboardServerPickTest extends ApplicationTest {

    @TempDir
    static Path dataDirs;

    /** Launches nothing; a stop takes a while, as a real core's does. */
    static final class SlowStopEngine extends SingBoxEngine {
        final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        final List<ProxyMode> starts = new CopyOnWriteArrayList<>();
        volatile boolean running;

        SlowStopEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            starts.add(proxyMode);
            running = true;
            Platform.runLater(() -> state.set(ConnectionState.CONNECTED));
        }

        @Override
        public void stop() {
            if (running) {
                try {
                    // SIGTERM, the core closing its inbounds, the system's
                    // proxy put back: the state says Connected meanwhile.
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            running = false;
            Platform.runLater(() -> state.set(ConnectionState.DISCONNECTED));
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

    /** Holds the next off-FX generation until released: where a switch reads the list. */
    static final class PausingGenerator extends SingBoxConfigGenerator {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String generate(List<ServerConfig> candidates, ServerConfig active,
                               AppSettings settings, RoutingConfig routingConfig,
                               HostFacts host) {
            if (!Platform.isFxApplicationThread() && armed.compareAndSet(true, false)) {
                paused.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.generate(candidates, active, settings, routingConfig, host);
        }
    }

    private final ServerConfig tokyo = server("Tokyo", "203.0.113.9");
    private final ServerConfig osaka = server("Osaka", "203.0.113.10");
    private SlowStopEngine engine;
    private PausingGenerator generator;
    private ConnectionService service;
    private ConfigStore store;
    private DashboardViewController controller;

    @Override
    public void start(Stage stage) throws Exception {
        store = new ConfigStore(dataDirs.resolve(UUID.randomUUID().toString()));
        AppSettings settings = store.getSettings();
        settings.setHealthCheckEnabled(false);
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(AppSettings.class, settings);
        engine = new SlowStopEngine();
        ServiceLocator.register(SingBoxEngine.class, engine);
        generator = new PausingGenerator();
        service = new ConnectionService(store, generator, new RoutingService(), engine);
        ServiceLocator.register(ConnectionService.class, service);
        store.addServer(tokyo);
        store.addServer(osaka);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        generator.release.countDown();
        closeDialogs();
        if (engine.running) {
            Thread stop = Thread.startVirtualThread(service::disconnect);
            stop.join(10_000);
        }
        service.getRecoveryService().close();
        WaitForAsyncUtils.waitForFxEvents();
        store.awaitPendingWrites(5_000);
    }

    // ===== a Disconnect stays a Disconnect =====

    @Test
    void aDisconnectDuringASwitchStaysDisconnected() throws Exception {
        // Connected from the tray, on Tokyo.
        assertThat(service.connect().started()).isTrue();
        disconnectDuringASwitch();
    }

    /** The same with the Dashboard's own Connect, the one case the old card switched for. */
    @Test
    void aDisconnectDuringASwitchOfADashboardConnectStaysDisconnected() throws Exception {
        interact(controller::toggleConnection);
        disconnectDuringASwitch();
    }

    private void disconnectDuringASwitch() throws Exception {
        awaitState(ConnectionState.CONNECTED);
        // Something the core does not run yet, so the pick restarts it.
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);

        // Osaka picked: the tray's menu, the server list, or an agent.
        generator.armed.set(true);
        interact(() -> store.setActiveServer(osaka.getId()));
        assertThat(generator.paused.await(5, TimeUnit.SECONDS)).as("the switch is under way")
                .isTrue();

        // The tray's Disconnect, or an agent's, meanwhile.
        Thread disconnect = Thread.startVirtualThread(service::disconnect);
        Await.until("the disconnect withdrew the request", () -> !service.isTunnelWanted(),
                Duration.ofSeconds(5));
        generator.release.countDown();
        disconnect.join(10_000);

        assertStaysDisconnected();
    }

    /**
     * The other order: the tray's Disconnect first, then a server picked in
     * the tray's menu while the core is still stopping, which the card reads
     * as Connected.
     */
    @Test
    void aPickWhileADisconnectStopsTheCoreStaysDisconnected() throws Exception {
        interact(controller::toggleConnection);
        pickWhileTheDisconnectStops();
    }

    /** The same with a core the tray started, which the old card never switched. */
    @Test
    void aPickWhileADisconnectStopsATrayStartedCoreStaysDisconnected() throws Exception {
        assertThat(service.connect().started()).isTrue();
        pickWhileTheDisconnectStops();
    }

    private void pickWhileTheDisconnectStops() throws Exception {
        awaitState(ConnectionState.CONNECTED);
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);

        Thread disconnect = Thread.startVirtualThread(service::disconnect);
        Await.until("the disconnect withdrew the request", () -> !service.isTunnelWanted(),
                Duration.ofSeconds(5));
        Thread.sleep(200);
        assertThat(stateOnFx()).as("what the card reads during the stop")
                .isEqualTo(ConnectionState.CONNECTED);
        interact(() -> store.setActiveServer(osaka.getId()));
        disconnect.join(10_000);

        assertStaysDisconnected();
    }

    /**
     * Gives whatever comes after the stop time to happen: the switch the
     * card started again came right after it.
     */
    private void assertStaysDisconnected() throws Exception {
        awaitState(ConnectionState.DISCONNECTED);
        Thread.sleep(1_500);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(engine.starts).as("starts after the Disconnect")
                .containsExactly(ProxyMode.SYSTEM_PROXY);
        assertThat(engine.isRunning()).as("a core runs after the Disconnect").isFalse();
        assertThat(service.isTunnelWanted()).as("the tunnel wanted again").isFalse();
        assertThat(stateOnFx()).isEqualTo(ConnectionState.DISCONNECTED);
    }

    private void closeDialogs() {
        interact(() -> List.copyOf(Window.getWindows()).forEach(window -> {
            if (window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane) {
                window.hide();
            }
        }));
    }

    private void awaitState(ConnectionState wanted) {
        Await.until("state " + wanted, () -> stateOnFx() == wanted, Duration.ofSeconds(10));
    }

    private ConnectionState stateOnFx() {
        return onFx(() -> engine.connectionStateProperty().get());
    }

    private static <T> T onFx(Supplier<T> read) {
        AtomicReference<T> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                seen.set(read.get());
            } finally {
                done.countDown();
            }
        });
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX thread answered").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return seen.get();
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
