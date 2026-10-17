package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.platform.TunLauncher;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import com.vlessclient.ui.view.DashboardViewController;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server picks against a Disconnect, a recovery restart and a refresh, with
 * the real engine driving a fake core whose SIGTERM takes a while, as a real
 * core's close and the system proxy's restore do, and the real Dashboard.
 * Nothing is paused or patched.
 *
 * <p>The engine says Disconnected only at the end of its stop, so the card
 * and the tray read Connected for the whole stop, and a pick then reached
 * the service: its switch asked for the tunnel again and started it after
 * the Disconnect. A pick during a recovery restart's stop called the restart
 * off and started in the saved mode. A refresh that withdrew the picked
 * server under a TUN core whose every launch prompts raised the prompt.</p>
 */
@UiTest
@EnabledOnOs({OS.MAC, OS.LINUX})
public class RealCorePickRaceTest extends ApplicationTest {

    @TempDir
    static Path dirs;

    /** The real engine; it only counts the launches. */
    static final class CountingEngine extends SingBoxEngine {
        final List<ProxyMode> starts = new CopyOnWriteArrayList<>();

        CountingEngine(Path binary) {
            super(binary);
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            starts.add(proxyMode);
            super.start(configJson, proxyMode);
        }
    }

    /**
     * A TUN launcher without elevation: it starts a wrapper at once, as the
     * UAC, osascript and pkexec launchers do, which stops on its stop file.
     * Told to, a launch ends as a dismissed UAC prompt ends the outer
     * PowerShell.
     */
    static final class FakeTunLauncher implements TunLauncher {
        final Path wrapper;
        final Path dir;
        final List<String> launches = new CopyOnWriteArrayList<>();
        volatile boolean promptsEachLaunch;
        volatile boolean declineNext;

        FakeTunLauncher(Path wrapper, Path dir) {
            this.wrapper = wrapper;
            this.dir = dir;
        }

        @Override
        public Launched launch(Path binary, Path configFile, Prompt prompt) throws IOException {
            launches.add(configFile.getFileName().toString());
            Path stop = dir.resolve("stop-" + UUID.randomUUID());
            List<String> command = new ArrayList<>(List.of("/bin/bash", wrapper.toString(),
                    stop.toString()));
            if (declineNext) {
                declineNext = false;
                command.add("--decline");
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new Launched(process, stop, promptsEachLaunch);
        }
    }

    private CountingEngine engine;
    private FakeTunLauncher tunLauncher;
    private ConnectionService service;
    private ConfigStore store;
    private DashboardViewController controller;
    private DefaultAppControlService control;
    private ServerConfig tokyo;
    private ServerConfig osaka;

    @Override
    public void start(Stage stage) throws Exception {
        Path dir = dirs.resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        Path core = dir.resolve("sing-box");
        Files.writeString(core, "#!/bin/bash\n"
                + "[ \"$1\" = check ] && exit 0\n"
                + "trap 'sleep 0.8; exit 0' TERM\n"
                + "echo 'sing-box started'\n"
                + "while true; do sleep 0.1; done\n");
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path wrapper = dir.resolve("tun-wrapper.sh");
        Files.writeString(wrapper, "#!/bin/bash\n"
                + "STOP=\"$1\"\n"
                + "if [ \"$2\" = --decline ]; then\n"
                + "  sleep 0.3\n"
                + "  echo 'FATAL: administrator elevation was declined or failed: "
                + "The operation was canceled by the user.'\n"
                + "  exit 1\n"
                + "fi\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f \"$STOP\" ]; do sleep 0.1; done\n"
                + "sleep 0.5\n"
                + "exit 0\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));

        store = new ConfigStore(dir.resolve("data"));
        AppSettings settings = store.getSettings();
        settings.setHealthCheckEnabled(false);
        settings.setProxyMode(ProxyMode.SYSTEM_PROXY);
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(AppSettings.class, settings);
        engine = new CountingEngine(core);
        engine.setSystemProxyGuard((host, port) -> { });
        tunLauncher = new FakeTunLauncher(wrapper, dir);
        engine.setTunLauncher(tunLauncher);
        ServiceLocator.register(SingBoxEngine.class, engine);
        service = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        ServiceLocator.register(ConnectionService.class, service);
        control = new DefaultAppControlService(store, null, null, new RoutingService(),
                service, null, null, engine);
        tokyo = server("Tokyo", "203.0.113.9");
        osaka = server("Osaka", "203.0.113.10");
        tokyo.setSubscriptionId("sub-1");
        osaka.setSubscriptionId("sub-1");
        store.addServer(tokyo);
        store.addServer(osaka);

        FXMLLoader loader = new FXMLLoader(DashboardViewController.class
                .getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        service.getRecoveryService().close();
        engine.stop();
        WaitForAsyncUtils.waitForFxEvents();
        store.awaitPendingWrites(5_000);
    }

    // ===== a Disconnect stays a Disconnect =====

    /** As the tray does it: its Disconnect, then a pick in its Servers menu. */
    @Test
    void aTrayPickDuringTheStopOfATrayStartedCoreStaysDisconnected() throws Exception {
        assertThat(service.connect().started()).isTrue();
        awaitState(ConnectionState.CONNECTED);
        pickDuringTheStop();
    }

    /** The same under the Dashboard's own Connect, which failed before round one too. */
    @Test
    void aTrayPickDuringTheStopOfADashboardStartedCoreStaysDisconnected() throws Exception {
        interact(controller::toggleConnection);
        awaitState(ConnectionState.CONNECTED);
        pickDuringTheStop();
    }

    private void pickDuringTheStop() throws Exception {
        Thread disconnect = Thread.startVirtualThread(service::disconnect);
        Await.until("the stop under way", engine::isStopping, Duration.ofSeconds(5));
        Thread.sleep(200);
        assertThat(stateOnFx()).as("what the card and the tray read during the stop")
                .isEqualTo(ConnectionState.CONNECTED);
        // TrayIconService's Servers menu: Platform.runLater(() -> selectActiveServer(id)).
        Platform.runLater(() -> store.setActiveServer(osaka.getId()));
        disconnect.join(10_000);

        assertStaysDown(1);
    }

    /**
     * An agent's select_server, then its disconnect at once: two tool calls
     * of one turn. The Dashboard's switch for the pick had begun, and its
     * second look after the cancelled switch started the tunnel again.
     */
    @Test
    void anAgentsSelectServerThenDisconnectStaysDisconnected() throws Exception {
        for (int i = 0; i < 2; i++) {
            if (engine.isRunning()) {
                service.disconnect();
            }
            awaitState(ConnectionState.DISCONNECTED);
            int before = engine.starts.size();
            assertThat(service.connect().started()).isTrue();
            awaitState(ConnectionState.CONNECTED);

            control.selectServer(i % 2 == 0 ? osaka.getId() : tokyo.getId());
            assertThat(control.disconnect().state()).isNotNull();

            assertStaysDown(before + 1);
        }
    }

    private void assertStaysDown(int starts) throws Exception {
        awaitState(ConnectionState.DISCONNECTED);
        // What came after the stop came at once: the switch queued behind it.
        Thread.sleep(1_500);
        assertThat(List.of(engine.starts.size(), engine.isRunning(), service.isTunnelWanted()))
                .as("[launches, a core runs, the tunnel wanted] after the Disconnect")
                .containsExactly(starts, false, false);
        assertThat(control.getStatus().state()).isEqualTo(ConnectionState.DISCONNECTED.name());
    }

    // ===== a pick changes the server, never the mode =====

    /**
     * A pick that reaches the Dashboard while recovery's restart is stopping
     * the core, with the saved mode changed since the start: the restart
     * stays recovery's, in the mode the tunnel ran in.
     */
    @Test
    void aPickDuringARecoveryRestartsStopKeepsTheModeTheCoreRan() throws Exception {
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        interact(controller::toggleConnection);
        awaitState(ConnectionState.CONNECTED);
        // The Dashboard's mode box or an agent's set_proxy_mode: saved, not applied.
        FxExecutor.run(() -> store.getSettings().setProxyMode(ProxyMode.TUN));
        service.getRecoveryService().onHealth(TunnelHealth.BROKEN);
        Await.until("recovery's restart stopping the core", engine::isStopping,
                Duration.ofSeconds(10));
        Thread.sleep(200);
        assertThat(stateOnFx()).as("what the card reads during recovery's stop")
                .isEqualTo(ConnectionState.CONNECTED);

        Platform.runLater(() -> store.setActiveServer(osaka.getId()));
        Await.until("the restart", () -> engine.starts.size() >= 2, Duration.ofSeconds(10));
        awaitState(ConnectionState.CONNECTED);
        Thread.sleep(1_000);

        assertThat(engine.starts).as("the modes the core was started in")
                .containsExactly(ProxyMode.SYSTEM_PROXY, ProxyMode.SYSTEM_PROXY);
        assertThat(tunLauncher.launches).as("TUN launches").isEmpty();
        assertThat(service.appliedServerId()).isEqualTo(osaka.getId());
    }

    // ===== a pick the tunnel does not take =====

    /**
     * A refresh withdraws the picked server under a TUN core whose every
     * launch prompts (UAC). A plain pick first, taken live (a fake control
     * API answers), so the launch the test watches for is the refresh's.
     */
    @Test
    void aRefreshThatWithdrawsThePickUnderAPromptingTunCoreLaunchesNothing() throws Exception {
        tunLauncher.promptsEachLaunch = true;
        FxExecutor.run(() -> store.getSettings().setProxyMode(ProxyMode.TUN));
        interact(controller::toggleConnection);
        awaitState(ConnectionState.CONNECTED);
        HttpServer api = fakeClashApi(store.getSettings().listenClashApiPort());
        try {
            Platform.runLater(() -> store.setActiveServer(osaka.getId()));
            Await.until("the pick taken live", () -> osaka.getId().equals(
                    service.appliedServerId()), Duration.ofSeconds(5));
            assertThat(tunLauncher.launches).as("a plain pick, taken live").hasSize(1);

            // SubscriptionService.diffAndApply, off the FX thread: Osaka withdrawn.
            store.applyServerBatch(List.of(), List.of(osaka.getId()));
            Thread.sleep(2_000);

            assertThat(tunLauncher.launches).as("TUN launches, each one a UAC prompt")
                    .hasSize(1);
            assertThat(List.of(engine.isRunning(), service.isTunnelWanted()))
                    .as("[the working core, the tunnel wanted]").containsExactly(true, true);
        } finally {
            api.stop(0);
        }
    }

    /**
     * The user's pick restarts a TUN core, and its UAC prompt is dismissed:
     * the launcher says so and exits, which the engine reports as a declined
     * start. The working tunnel was gone, the request was withdrawn, and
     * nothing offered the reconnect; the offer is made now.
     */
    @Test
    void aDismissedPromptOfAPicksRestartOffersTheReconnect() throws Exception {
        tunLauncher.promptsEachLaunch = true;
        FxExecutor.run(() -> store.getSettings().setProxyMode(ProxyMode.TUN));
        interact(controller::toggleConnection);
        awaitState(ConnectionState.CONNECTED);
        // Something the core does not run yet, so the pick restarts it.
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);
        tunLauncher.declineNext = true;

        Platform.runLater(() -> store.setActiveServer(osaka.getId()));
        Await.until("the dismissed launch", () -> tunLauncher.launches.size() == 2,
                Duration.ofSeconds(10));
        Await.until("the reconnect offered",
                () -> service.getRecoveryService().isReconnectNeeded(), Duration.ofSeconds(10));

        assertThat(List.of(engine.isRunning(), service.isTunnelWanted()))
                .as("[a core runs, the tunnel wanted] after the dismissal")
                .containsExactly(false, true);
        assertThat(engine.lastExitWasDeclined()).isTrue();
    }

    /** A control API that takes any selector pick, as a running core's does. */
    private static HttpServer fakeClashApi(int port) throws IOException {
        AtomicReference<String> now = new AtomicReference<>("");
        HttpServer api = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        api.createContext("/proxies/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                now.set(JsonMapper.builder().build()
                        .readTree(exchange.getRequestBody().readAllBytes())
                        .path("name").asString());
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] reply = ("{\"now\":\"" + now.get() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, reply.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            }
            exchange.close();
        });
        api.start();
        return api;
    }

    private void awaitState(ConnectionState wanted) {
        Await.until("state " + wanted, () -> stateOnFx() == wanted, Duration.ofSeconds(10));
    }

    private ConnectionState stateOnFx() {
        return FxExecutor.get(() -> engine.connectionStateProperty().get());
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
