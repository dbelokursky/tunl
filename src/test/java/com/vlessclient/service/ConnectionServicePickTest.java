package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.platform.ElevationDeclinedException;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpToolException;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a server pick does to the tunnel: the list's, the tray's, an agent's
 * select_server, a subscription refresh's re-pick, and one made while the
 * core was starting, which the service follows once it is up.
 *
 * <p>A pick asked for a tunnel of its own (a new connection request), so a
 * Disconnect that met a pick was undone: the pick's switch, run after the
 * stop or queued behind it, found no core and started one in the saved mode,
 * or switched the core the Disconnect then stopped and left the tunnel
 * wanted with nothing running. A pick of a server the core refuses stopped
 * the working tunnel before the refusal was found, and a refresh's re-pick
 * under a TUN core whose every launch prompts raised the prompt unasked.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class ConnectionServicePickTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        store.getSettings().setServerSelection(ServerSelection.SINGLE);
        store.getSettings().setHealthCheckEnabled(false);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.getRecoveryService().close();
        }
        // A change of the list made on the FX thread saves through the store's
        // writer, which would otherwise still be writing as the folder is removed.
        assertThat(store.awaitPendingWrites(5_000)).as("the store's writes finished").isTrue();
    }

    /**
     * Launches nothing. Records the mode of each start and the starts and
     * stops asked of it in order, can take as long to stop as a test says,
     * and can be told what the next start throws, as a dismissed elevation
     * prompt or the core's check does.
     */
    static final class PickEngine extends SingBoxEngine {

        final List<ProxyMode> modes = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final ReadOnlyObjectWrapper<ConnectionState> state =
                new ReadOnlyObjectWrapper<>(ConnectionState.DISCONNECTED);
        volatile boolean running;
        /** Every launch raises an elevation prompt, as UAC does for each TUN start. */
        volatile boolean prompts;
        /** The last launch ended at a dismissed prompt, as the process monitor says. */
        volatile boolean declined;
        /** What the next start throws instead of starting. */
        volatile IOException failNextStart;
        /** A refusal of the configuration, as the core's check gives one; null for none. */
        volatile Function<String, String> refuseWith;
        /** Counted down as a stop begins, when set. */
        volatile CountDownLatch stopEntered;
        /** Waited for before a stop ends, when set. */
        volatile CountDownLatch stopRelease;

        PickEngine(Path binary) {
            super(binary);
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state.getReadOnlyProperty();
        }

        /** Publishes {@code next} as the engine does, on the FX thread. */
        void report(ConnectionState next) {
            FxExecutor.run(() -> state.set(next));
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            calls.add("start");
            IOException failure = failNextStart;
            if (failure != null) {
                failNextStart = null;
                throw failure;
            }
            Function<String, String> refusal = refuseWith;
            String reason = refusal != null ? refusal.apply(configJson) : null;
            if (reason != null) {
                throw new ConfigRejectedException(reason);
            }
            modes.add(proxyMode);
            running = true;
        }

        @Override
        public void stop() {
            calls.add("stop");
            CountDownLatch entered = stopEntered;
            CountDownLatch release = stopRelease;
            if (running && entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("the stop was not released");
                    }
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
        public boolean lastExitWasDeclined() {
            return declined;
        }

        @Override
        public boolean restartNeedsElevationPrompt() {
            return prompts;
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

    private static ServerConfig server(String id, String name) {
        return TestServers.server().id(id).name(name).protocol(Protocol.VLESS)
                .address(name.toLowerCase(Locale.ROOT) + ".example.com").port(443)
                .uuid("11111111-1111-1111-1111-111111111111").build();
    }

    private PickEngine engine() {
        return new PickEngine(tempDir.resolve("sing-box"));
    }

    private ConnectionService serviceOver(SingBoxEngine engine) {
        service = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        return service;
    }

    /** Tokyo picked, Osaka beside it. */
    private void tokyoAndOsaka() {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Osaka"));
        store.setActiveServer("srv-1");
    }

    private boolean picked(String id) {
        return store.getServerById(id).orElseThrow().isActive();
    }

    /**
     * Waits until {@code thread} waits for a monitor, as one queued on the
     * service's lock does, or has already ended without needing it.
     */
    private static void awaitBlocked(Thread thread) {
        Await.until(thread.getName() + " to wait for the lock",
                () -> thread.getState() == Thread.State.BLOCKED
                        || thread.getState() == Thread.State.TERMINATED,
                Duration.ofSeconds(5));
    }

    /** The lock the service's operations take, to hold it as another operation would. */
    private static Object operationsOf(ConnectionService service) throws Exception {
        Field field = ConnectionService.class.getDeclaredField("operations");
        field.setAccessible(true);
        return field.get(service);
    }

    // ===== a Disconnect is final =====

    /**
     * A pick is no request for a tunnel. Once a Disconnect has withdrawn the
     * user's, a pick's switch found no core and started one in the saved
     * mode: the tunnel the user had just stopped came back.
     */
    @Test
    void aPickAfterADisconnectStartsNoCore() throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        service.disconnect();
        store.setActiveServer("srv-2");

        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(engine.modes).as("starts").hasSize(1);
        assertThat(engine.isRunning()).as("a core runs after the Disconnect").isFalse();
        assertThat(service.isTunnelWanted()).as("the tunnel wanted again").isFalse();
    }

    /**
     * The tray's Disconnect, then a pick in its Servers menu while the core
     * is still stopping, which the card and the tray read as Connected for
     * the whole stop. The pick's switch queued behind the stop and started
     * the tunnel again once it was done.
     */
    @Test
    void aPickWhileADisconnectStopsTheCoreDoesNotStartItAgain() throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.stopEntered = new CountDownLatch(1);
        engine.stopRelease = new CountDownLatch(1);
        Thread disconnect = Thread.ofPlatform().name("tray-disconnect").start(service::disconnect);
        assertThat(engine.stopEntered.await(5, TimeUnit.SECONDS)).as("the stop under way")
                .isTrue();

        store.setActiveServer("srv-2");
        AtomicReference<Object> switched = new AtomicReference<>();
        Thread pick = Thread.ofPlatform().name("pick-switch").start(() -> {
            try {
                switched.set(service.switchToActiveServer().outcome());
            } catch (IOException | RuntimeException e) {
                switched.set(e);
            }
        });
        awaitBlocked(pick);
        engine.stopRelease.countDown();
        disconnect.join(10_000);
        pick.join(10_000);

        assertThat(switched.get()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(engine.modes).as("starts after the Disconnect").hasSize(1);
        assertThat(engine.isRunning()).as("a core runs after the Disconnect").isFalse();
        assertThat(service.isTunnelWanted()).as("the tunnel wanted again").isFalse();
    }

    /**
     * The other order: the Disconnect has withdrawn the request and waits for
     * the lock, and the pick's switch takes it first (here on the thread that
     * holds it, which is what taking it first means). The switch took a
     * request of its own and moved the core, the Disconnect then stopped it,
     * and the tunnel was left wanted with nothing running: the tray said
     * traffic went outside the VPN, and every subscription refresh was
     * refused until the next connect.
     */
    @Test
    void aSwitchThatTakesTheLockAheadOfAQueuedDisconnectLeavesNothingWanted()
            throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        store.setActiveServer("srv-2");
        Object operations = operationsOf(service);

        Thread disconnect;
        synchronized (operations) {
            disconnect = Thread.ofPlatform().name("tray-disconnect").start(service::disconnect);
            awaitBlocked(disconnect);
            service.switchToActiveServer();
        }
        disconnect.join(10_000);

        assertThat(engine.isRunning()).as("a core runs after the Disconnect").isFalse();
        assertThat(service.isTunnelWanted()).as("the tunnel wanted after the Disconnect")
                .isFalse();
    }

    /**
     * A server picked while the tunnel was connecting is applied once the
     * core is up, on a thread of its own. Held there past a Disconnect, as a
     * busy scheduler holds it for a few milliseconds, it asked for the tunnel
     * again and started it after the Disconnect had returned.
     */
    @Test
    void aPickTheServiceFollowsAfterADisconnectStartsNoCore() throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        CountDownLatch followEntered = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Object> followed = new AtomicReference<>();
        CountDownLatch followDone = new CountDownLatch(1);
        service = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine) {
            @Override
            public ConnectAttempt switchToActiveServer() throws IOException {
                if (!Thread.currentThread().isVirtual()) {
                    return super.switchToActiveServer();
                }
                // The follow's own thread, before it gets anywhere.
                followEntered.countDown();
                try {
                    disconnected.await(10, TimeUnit.SECONDS);
                    ConnectAttempt attempt = super.switchToActiveServer();
                    followed.set(attempt.outcome());
                    return attempt;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                } finally {
                    followDone.countDown();
                }
            }
        };
        assertThat(service.connect().started()).isTrue();
        store.setActiveServer("srv-2");

        engine.report(ConnectionState.CONNECTED);
        assertThat(followEntered.await(5, TimeUnit.SECONDS))
                .as("the service follows the pick made while connecting").isTrue();
        service.disconnect();
        disconnected.countDown();
        assertThat(followDone.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(followed.get()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(engine.modes).as("starts after the Disconnect").hasSize(1);
        assertThat(engine.isRunning()).as("a core runs after the Disconnect").isFalse();
        assertThat(service.isTunnelWanted()).as("the tunnel wanted again").isFalse();
    }

    // ===== a pick changes the server, never the mode =====

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

    /**
     * A pick under a running core is compared in the running core's mode, so
     * a mode changed since it started (saved, not applied) does not stop a
     * live switch, either way round.
     */
    @Test
    void aPickWithAPendingModeChangeIsStillTakenLive() throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        HttpServer api = fakeClashApi(store.getSettings().listenClashApiPort());
        try {
            store.setActiveServer("srv-2");
            assertThat(service.switchToActiveServer().outcome()).as("a pick, nothing else changed")
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);

            store.getSettings().setProxyMode(ProxyMode.TUN);
            store.setActiveServer("srv-1");
            assertThat(service.switchToActiveServer().outcome())
                    .as("a pick with the saved mode changed since the start")
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);
            assertThat(engine.modes).containsExactly(ProxyMode.SYSTEM_PROXY);
            assertThat(service.appliedServerId()).isEqualTo("srv-1");
        } finally {
            api.stop(0);
        }
    }

    /** The same the other way round: a TUN core, the saved mode changed to a system proxy. */
    @Test
    void aPickUnderTunWithAPendingSystemProxyIsStillTakenLive() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        HttpServer api = fakeClashApi(store.getSettings().listenClashApiPort());
        try {
            store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
            store.setActiveServer("srv-2");

            assertThat(service.switchToActiveServer().outcome())
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);
            assertThat(engine.modes).containsExactly(ProxyMode.TUN);
        } finally {
            api.stop(0);
        }
    }

    /**
     * The health card counts down to a restart for failed checks, and the
     * user presses its Cancel; the core runs on. Then the user picks another
     * server, in the list, the tray or through an agent. The Cancel had
     * withdrawn the request for the tunnel, which a pick moves only while
     * the user wants it: the pick was silently called off, and the traffic
     * stayed on the server the checks had just found unreachable while the
     * list, the tray and get_status named the new one.
     */
    @Test
    void aPickAfterTheCountdownsCancelMovesTheRunningTunnel() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(60);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.report(ConnectionState.CONNECTED);
        TunnelRecoveryService recovery = service.getRecoveryService();
        recovery.onHealth(TunnelHealth.BROKEN);
        Await.until("the countdown", () -> FxExecutor.get(
                () -> recovery.retryProperty().get()) != null, Duration.ofSeconds(5));
        // The banner's Cancel.
        recovery.cancelCountdown();
        HttpServer api = fakeClashApi(store.getSettings().listenClashApiPort());
        try {
            store.setActiveServer("srv-2");

            ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

            assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.SWITCHED);
            assertThat(service.appliedServerId()).as("the server the core runs")
                    .isEqualTo("srv-2");
            assertThat(engine.modes).as("starts").hasSize(1);
            assertThat(service.isTunnelWanted()).isTrue();
        } finally {
            api.stop(0);
        }
    }

    // ===== a pick that moves the tunnel starts recovery over =====

    /**
     * Tokyo keeps failing its checks: recovery restarted it once, and the
     * next countdown is its second, at twice the delay. The user picks
     * Osaka, taken live. Once the pick took no request of its own, the
     * countdown and the backoff stayed Tokyo's: their restart hit Osaka,
     * and a failing Osaka waited out a backoff grown on Tokyo, up to five
     * minutes.
     */
    @Test
    void aLivePickStartsTheCountdownOverForTheNewExit() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.report(ConnectionState.CONNECTED);
        TunnelRecoveryService recovery = service.getRecoveryService();
        recovery.onHealth(TunnelHealth.BROKEN);
        Await.until("recovery's first restart", () -> engine.modes.size() == 2,
                Duration.ofSeconds(10));
        Await.until("its end", () -> !recovery.isRecovering(), Duration.ofSeconds(5));
        recovery.onHealth(TunnelHealth.BROKEN);
        Await.untilValue("the second countdown",
                () -> FxExecutor.get(() -> recovery.retryProperty().get()),
                retry -> retry != null && retry.attempt() == 2, Duration.ofSeconds(5));
        HttpServer api = fakeClashApi(store.getSettings().listenClashApiPort());
        try {
            store.setActiveServer("srv-2");

            assertThat(service.switchToActiveServer().outcome())
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);

            TunnelRecoveryService.Retry after = FxExecutor.get(
                    () -> recovery.retryProperty().get());
            assertThat(after).as("the countdown, for the new exit's broken verdict").isNotNull();
            assertThat(List.of(after.attempt(), after.delaySeconds()))
                    .as("[attempt, delay] of the countdown after the pick")
                    .containsExactly(1, 1);
        } finally {
            api.stop(0);
        }
    }

    /**
     * The same where the pick restarts the core: health checks on, so the
     * restarted core's Connected does not call the countdown off, and the
     * old server's restart ran on the new core before its first verdict.
     */
    @Test
    void aPickThatRestartsTheCoreCallsOffTheOldServersCountdown() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.report(ConnectionState.CONNECTED);
        TunnelRecoveryService recovery = service.getRecoveryService();
        recovery.onHealth(TunnelHealth.BROKEN);
        // Something the core does not run yet, so the pick restarts it.
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);
        store.setActiveServer("srv-2");

        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.STARTED);

        assertThat(FxExecutor.get(() -> recovery.retryProperty().get()))
                .as("the countdown of the server the tunnel left").isNull();
        Thread.sleep(1_500);
        assertThat(engine.modes).as("starts: the connect and the pick's restart").hasSize(2);
    }

    /**
     * The old server's restart already ran when the pick came, waiting for
     * the lock the pick's switch holds: once the switch was done it
     * restarted the core the pick had just brought up.
     */
    @Test
    void aRestartThatWaitsForThePicksSwitchIsCalledOff() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.report(ConnectionState.CONNECTED);
        TunnelRecoveryService recovery = service.getRecoveryService();
        store.getSettings().setCoreLogLevel(CoreLogLevel.DEBUG);
        store.setActiveServer("srv-2");

        synchronized (operationsOf(service)) {
            recovery.onHealth(TunnelHealth.BROKEN);
            Await.until("recovery's restart waiting for the lock", recovery::isRecovering,
                    Duration.ofSeconds(5));
            assertThat(service.switchToActiveServer().outcome())
                    .isEqualTo(ConnectionService.Outcome.STARTED);
        }
        Await.until("recovery done", () -> !recovery.isRecovering(), Duration.ofSeconds(5));

        assertThat(engine.modes).as("starts: the connect and the pick's restart").hasSize(2);
        assertThat(service.appliedServerId()).isEqualTo("srv-2");
    }

    /**
     * A pick that reaches the service while a recovery restart is stopping
     * the core: the card still reads Connected, so the Dashboard asks for a
     * switch. The pick's own request called the restart off after its stop,
     * and the pick, finding no core, started one in the saved mode: a system
     * proxy came back as TUN, through an elevation prompt nobody asked for.
     * The restart is recovery's to finish; it reads the pick as it starts.
     */
    @Test
    void aPickDuringARecoveryRestartLeavesTheRestartToRecoveryInTheModeItRan()
            throws Exception {
        tokyoAndOsaka();
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        // Saved, not applied: the Dashboard's mode box, or an agent's set_proxy_mode.
        store.getSettings().setProxyMode(ProxyMode.TUN);
        engine.stopEntered = new CountDownLatch(1);
        engine.stopRelease = new CountDownLatch(1);
        TunnelRecoveryService recovery = service.getRecoveryService();
        recovery.onConnectionState(ConnectionState.CONNECTED);
        recovery.onHealth(TunnelHealth.BROKEN);
        assertThat(engine.stopEntered.await(10, TimeUnit.SECONDS))
                .as("recovery stops the core to restart it").isTrue();

        store.setActiveServer("srv-2");
        AtomicReference<Object> switched = new AtomicReference<>();
        Thread pick = Thread.ofPlatform().name("pick-switch").start(() -> {
            try {
                switched.set(service.switchToActiveServer().outcome());
            } catch (IOException | RuntimeException e) {
                switched.set(e);
            }
        });
        awaitBlocked(pick);
        engine.stopRelease.countDown();
        pick.join(10_000);
        Await.until("the restart", () -> engine.modes.size() >= 2, Duration.ofSeconds(10));

        assertThat(switched.get()).as("the pick, once the restart had read it")
                .isEqualTo(ConnectionService.Outcome.SWITCHED);
        assertThat(engine.modes).as("the modes the core was started in")
                .containsExactly(ProxyMode.SYSTEM_PROXY, ProxyMode.SYSTEM_PROXY);
        assertThat(service.appliedServerId()).as("the restart took the pick").isEqualTo("srv-2");
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * A pick while the tunnel is wanted and its core is gone, as between a
     * crash and the restart recovery makes, starts nothing itself: the next
     * start reads it.
     */
    @Test
    void aPickWhileTheCoreIsDownIsLeftToTheNextStart() throws Exception {
        tokyoAndOsaka();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.running = false;
        store.setActiveServer("srv-2");

        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.NOT_RUNNING);
        assertThat(engine.modes).as("starts").hasSize(1);
        assertThat(service.isTunnelWanted()).as("the request stands").isTrue();

        assertThat(service.connect().started()).isTrue();
        assertThat(service.appliedServerId()).as("the next start's server").isEqualTo("srv-2");
    }

    // ===== a re-pick nobody made =====

    /** Tokyo and Osaka from one subscription, Tokyo picked, in TUN. */
    private void subscribedPairInTun() {
        ServerConfig tokyo = server("srv-1", "Tokyo");
        tokyo.setSubscriptionId("sub-1");
        ServerConfig osaka = server("srv-2", "Osaka");
        osaka.setSubscriptionId("sub-1");
        store.addServer(tokyo);
        store.addServer(osaka);
        store.setActiveServer("srv-1");
        store.getSettings().setProxyMode(ProxyMode.TUN);
    }

    /**
     * An hourly refresh withdraws the picked server and the store picks
     * another, under a TUN core whose every launch prompts (UAC on Windows).
     * The candidates changed, so the pick cannot be taken live, and the
     * working core was stopped for a launch that raised the prompt with
     * nobody asking. Recovery, in the same spot, leaves the restart to the
     * user, and so does a re-pick now: the core keeps running.
     */
    @Test
    void aRepickARefreshMadeUnderAPromptingTunCoreKeepsTheCore() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        assertThat(service.restartNeedsTheUser()).as("a restart would prompt").isTrue();
        // What the prompt nobody asked for met: the user dismissed it, and the
        // VPN was off with the request withdrawn.
        engine.failNextStart = new ElevationDeclinedException("the UAC prompt was dismissed");

        // The provider no longer lists Tokyo; the store picks Osaka.
        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        assertThat(picked("srv-2")).isTrue();
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(engine.modes).as("TUN launches, each one a prompt")
                .containsExactly(ProxyMode.TUN);
        assertThat(engine.failNextStart).as("a launch, and its prompt").isNotNull();
        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.DEFERRED);
        assertThat(engine.isRunning()).as("the working core").isTrue();
        assertThat(service.isTunnelWanted()).isTrue();
    }

    /** The same for an agent's delete_server of the picked server. */
    @Test
    void aRepickAnAgentsDeleteMadeUnderAPromptingTunCoreKeepsTheCore() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();

        new DefaultAppControlService(store, null, null, new RoutingService(), service, null,
                null, engine).deleteServer("srv-1", true);
        assertThat(picked("srv-2")).isTrue();

        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.DEFERRED);
        assertThat(engine.modes).containsExactly(ProxyMode.TUN);
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * The user's own delete of the server the tunnel runs on, confirmed on
     * the Servers page, under a TUN core whose every launch prompts. The
     * user asked the tunnel to leave that server, and it moves to the pick
     * made in its place, prompt and all. It was held as a re-pick nobody
     * asked for, and the traffic went on through the deleted server while
     * the list, the tray and get_status named the new pick.
     */
    @Test
    void theUsersDeleteOfTheServerTheTunnelRunsOnMovesTheTunnelOffIt() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();

        // ServersViewController.deleteServer, once the user confirmed it.
        FxExecutor.run(() -> store.removeServer("srv-1"));
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(service.appliedServerId()).isEqualTo("srv-2");
        assertThat(engine.modes).containsExactly(ProxyMode.TUN, ProxyMode.TUN);
    }

    /** The same for several servers deleted at once, or a subscription removed with them. */
    @Test
    void theUsersDeleteOfSeveralServersMovesTheTunnelOffThem() throws Exception {
        subscribedPairInTun();
        store.addServer(server("srv-3", "Nagoya"));
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();

        // ServersViewController.deleteSelected, and removeSubscription, call this.
        store.applyServerBatch(List.of(), List.of("srv-1", "srv-2"));
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(service.appliedServerId()).isEqualTo("srv-3");
    }

    /**
     * Without a prompt a re-pick still moves the tunnel off the server the
     * provider withdrew, as before: only a prompt nobody asked for is held.
     */
    @Test
    void aRepickWithoutAPromptStillMovesTheTunnel() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();

        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(engine.modes).containsExactly(ProxyMode.TUN, ProxyMode.TUN);
        assertThat(service.appliedServerId()).isEqualTo("srv-2");
    }

    /**
     * The store's re-pick landing on a server the core refuses: nobody picked
     * it, so nothing is put up about it, and the core is not stopped for a
     * start that would fail. The tunnel stays as it is until a reconnect.
     */
    @Test
    void aRepickOfAServerTheCoreRefusesKeepsTheCoreQuietly() throws Exception {
        ServerConfig tokyo = server("srv-1", "Tokyo");
        tokyo.setSubscriptionId("sub-1");
        ServerConfig frankfurt = withUnbuildableShortId(server("srv-2", "Frankfurt"));
        frankfurt.setSubscriptionId("sub-1");
        store.addServer(tokyo);
        store.addServer(frankfurt);
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().skipped()).hasSize(1);

        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        assertThat(picked("srv-2")).as("the store's pick").isTrue();

        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.DEFERRED);
        assertThat(engine.calls).as("no stop for a start the core would refuse")
                .containsExactly("start");
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * A refresh's re-pick left the tunnel on the withdrawn server; then an
     * agent's select_server of the server the store picked, which says a
     * connected tunnel switches to it at once. It was answered active:true
     * and moved nothing, as the pick changed nothing in the list: the user's
     * pick now, it restarts the tunnel there.
     */
    @Test
    void anAgentsSelectServerOfTheStoresPickMovesTheTunnelThere() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.DEFERRED);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), service, null, null, engine);

        assertThat(control.selectServer("srv-2").active()).isTrue();
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(service.appliedServerId()).isEqualTo("srv-2");
        assertThat(engine.modes).containsExactly(ProxyMode.TUN, ProxyMode.TUN);
    }

    /**
     * get_status while the tunnel stays on a server a refresh withdrew: the
     * server the core routes through was looked up in the list alone, and
     * read as none, so an agent could not tell the tunnel ran anything but
     * the active server.
     */
    @Test
    void getStatusNamesTheWithdrawnServerTheTunnelStaysOn() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        engine.report(ConnectionState.CONNECTED);
        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        assertThat(service.switchToActiveServer().outcome())
                .isEqualTo(ConnectionService.Outcome.DEFERRED);
        ReadOnlyStringWrapper routedTag = new ReadOnlyStringWrapper(OutboundTags.server("srv-1"));
        ProxyGroupMonitor monitor = new ProxyGroupMonitor() {
            @Override
            public ReadOnlyStringProperty currentMemberTagProperty() {
                return routedTag.getReadOnlyProperty();
            }
        };
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), service, null, null, engine, null, monitor);

        com.vlessclient.service.mcp.StatusInfo status = control.getStatus();

        assertThat(List.of(String.valueOf(status.activeServerId()),
                        String.valueOf(status.currentServerId()),
                        String.valueOf(status.currentServer())))
                .as("[the active server, the server routed through, its name]")
                .containsExactly("srv-2", "srv-1", "Tokyo");
    }

    /** A pick the user makes is asked for, prompt or not, and restarts as before. */
    @Test
    void aPickOfTheUsersUnderAPromptingTunCoreStillRestartsIt() throws Exception {
        subscribedPairInTun();
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        store.applyServerBatch(List.of(), List.of("srv-1"), ConfigStore.Removal.UNASKED);
        service.switchToActiveServer();

        store.addServer(server("srv-3", "Nagoya"));
        store.setActiveServer("srv-3");
        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(engine.modes).containsExactly(ProxyMode.TUN, ProxyMode.TUN);
        assertThat(service.appliedServerId()).isEqualTo("srv-3");
    }

    // ===== a dismissed prompt of a pick's restart =====

    /**
     * The user's pick restarted a TUN core, and the user dismissed the
     * prompt the launch raised (the macOS setup dialog, the Linux grant).
     * The working tunnel was stopped before the prompt, and the dismissal
     * withdrew the request: no core, no retry, no offer, the VPN off with
     * nothing saying so. A dropped tunnel whose restart prompts offers the
     * reconnect instead, and so does this one.
     */
    @Test
    void dismissingThePromptOfAPicksRestartOffersTheReconnect() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        store.setActiveServer("srv-2");
        engine.failNextStart = new ElevationDeclinedException("the setup dialog was dismissed");

        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(engine.isRunning()).isFalse();
        assertThat(List.of(service.isTunnelWanted(),
                        service.getRecoveryService().isReconnectNeeded()))
                .as("[the tunnel wanted, the reconnect offered] after the dismissed prompt")
                .containsExactly(true, true);
    }

    /**
     * The same where the prompt is raised by the launch's own process (UAC,
     * the osascript dialog, pkexec), which ends when it is dismissed and is
     * reported as a declined exit.
     */
    @Test
    void aDeclinedExitOfAPicksRestartOffersTheReconnect() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();
        store.setActiveServer("srv-2");
        assertThat(service.switchToActiveServer().started()).as("the launcher started").isTrue();

        engine.report(ConnectionState.CONNECTING);
        engine.running = false;
        engine.declined = true;
        engine.report(ConnectionState.DISCONNECTED);

        assertThat(List.of(service.isTunnelWanted(),
                        service.getRecoveryService().isReconnectNeeded()))
                .as("[the tunnel wanted, the reconnect offered] after the declined exit")
                .containsExactly(true, true);
    }

    /** A Connect whose prompt is dismissed is still the user cancelling it. */
    @Test
    void aDeclinedExitOfAConnectStillWithdrawsTheRequest() throws Exception {
        tokyoAndOsaka();
        store.getSettings().setProxyMode(ProxyMode.TUN);
        PickEngine engine = engine();
        engine.prompts = true;
        serviceOver(engine);
        assertThat(service.connect().started()).isTrue();

        engine.report(ConnectionState.CONNECTING);
        engine.running = false;
        engine.declined = true;
        engine.report(ConnectionState.DISCONNECTED);

        assertThat(service.isTunnelWanted()).isFalse();
        assertThat(service.getRecoveryService().isReconnectNeeded()).isFalse();
    }

    // ===== a server the core refuses =====

    /** A REALITY server with a short ID the core cannot build, longer than 16 hex digits. */
    private static ServerConfig withUnbuildableShortId(ServerConfig server) {
        TlsConfig tls = new TlsConfig();
        tls.setEnabled(true);
        tls.setServerName("www.example.com");
        tls.setReality(true);
        tls.setRealityPublicKey("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0");
        tls.setRealityShortId("0123456789abcdef01");
        server.setTls(tls);
        return server;
    }

    /**
     * A pick of a server the running core was started without, because it
     * cannot build it: the working core was stopped first, and only the
     * start after it found the refusal. The refusal was known before the
     * stop.
     */
    @Test
    void aPickOfAServerTheCoreCannotBuildKeepsTheRunningTunnel() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(withUnbuildableShortId(server("srv-2", "Frankfurt")));
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        serviceOver(engine);
        assertThat(service.connect().skipped()).extracting(ConnectionService.SkippedServer::id)
                .containsExactly("srv-2");
        store.setActiveServer("srv-2");

        assertThatThrownBy(service::switchToActiveServer)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessageStartingWith("The VPN core rejected the settings of server "
                        + "\"Frankfurt\": ");

        assertThat(engine.isRunning()).as("the working tunnel").isTrue();
        assertThat(engine.calls).as("what the engine was asked to do")
                .containsExactly("start");
        assertThat(service.isTunnelWanted()).isTrue();
        assertThat(picked("srv-1")).as("the pick put back on the server the core runs")
                .isTrue();
    }

    /** The position the core would quote for this server's outbound. */
    private static int outboundIndex(String configJson, String serverId) {
        var outbounds = JsonMapper.builder().build().readTree(configJson).path("outbounds");
        String tag = OutboundTags.server(serverId);
        for (int i = 0; i < outbounds.size(); i++) {
            if (tag.equals(outbounds.path(i).path("tag").asString(""))) {
                return i;
            }
        }
        throw new AssertionError("no outbound tagged " + tag);
    }

    /** Frankfurt refused at the check, as a subscription's flow the core does not know is. */
    private static String refusingFrankfurt(String config) {
        return config.contains(OutboundTags.server("srv-2"))
                ? "initialize outbound[" + outboundIndex(config, "srv-2")
                        + "]: unsupported flow: xtls-rprx-direct"
                : null;
    }

    /** The same for a server the core's own check refused when the run started. */
    @Test
    void aPickOfAServerTheCoreRefusedAtItsCheckKeepsTheRunningTunnel() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Frankfurt"));
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        engine.refuseWith = ConnectionServicePickTest::refusingFrankfurt;
        serviceOver(engine);
        assertThat(service.connect().skipped()).extracting(ConnectionService.SkippedServer::id)
                .containsExactly("srv-2");
        store.setActiveServer("srv-2");

        assertThatThrownBy(service::switchToActiveServer)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessage("The VPN core rejected the settings of server \"Frankfurt\": "
                        + "unsupported flow: xtls-rprx-direct");

        assertThat(engine.isRunning()).as("the working tunnel").isTrue();
        assertThat(engine.calls).as("no stop, and no start the core would refuse")
                .doesNotContain("stop");
        assertThat(picked("srv-1")).as("the pick put back on the server the core runs")
                .isTrue();
    }

    /** Frankfurt as a REALITY server, with the short ID and server name its panel handed out. */
    private static ServerConfig realityFrankfurt(String name, String shortId, String serverName) {
        ServerConfig frankfurt = server("srv-2", name);
        // Where the server is stays as it was; only what the panel picks anew changes.
        frankfurt.setAddress("frankfurt.example.com");
        TlsConfig tls = new TlsConfig();
        tls.setEnabled(true);
        tls.setServerName(serverName);
        tls.setReality(true);
        tls.setRealityPublicKey("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0");
        tls.setRealityShortId(shortId);
        frankfurt.setTls(tls);
        frankfurt.setSubscriptionId("sub-1");
        return frankfurt;
    }

    /**
     * A server the core's check refused at the start, handed out again by a
     * refresh with another name, as a remark that counts the traffic left
     * changes, and another REALITY short ID and server name, which 3x-ui and
     * Marzban pick anew at each hand-out. The refusal was kept for the
     * server as it was whole, so after such a refresh select_server reported
     * the pick made, and the pick stopped the working tunnel for a start the
     * core refused again.
     */
    @Test
    void aCheckRefusedServerARefreshRenamedAndReKeyedIsStillRefusedBeforeAnyStop()
            throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(realityFrankfurt("Frankfurt", "ab", "a.example"));
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        engine.refuseWith = ConnectionServicePickTest::refusingFrankfurt;
        serviceOver(engine);
        assertThat(service.connect().skipped()).extracting(ConnectionService.SkippedServer::id)
                .containsExactly("srv-2");
        store.applyServerBatch(List.of(realityFrankfurt("Frankfurt | 11 GB", "cd", "b.example")),
                List.of(), ConfigStore.Removal.UNASKED);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), service, null, null, engine);

        assertThatThrownBy(() -> control.selectServer("srv-2"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("unsupported flow: xtls-rprx-direct");
        store.setActiveServer("srv-2");
        assertThatThrownBy(service::switchToActiveServer)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessageContaining("Frankfurt | 11 GB");

        assertThat(engine.calls).as("no stop for a start the core would refuse")
                .doesNotContain("stop");
        assertThat(engine.isRunning()).as("the working tunnel").isTrue();
        assertThat(picked("srv-1")).as("the pick put back on the server the core runs")
                .isTrue();
    }

    /**
     * A server the check refused and that changed since, as a refresh that
     * fixed it changes it, is the core's to judge again: the pick restarts.
     */
    @Test
    void aPickOfALeftOutServerThatChangedSinceIsTried() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Frankfurt"));
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        engine.refuseWith = ConnectionServicePickTest::refusingFrankfurt;
        serviceOver(engine);
        assertThat(service.connect().skipped()).hasSize(1);
        engine.refuseWith = null;
        ServerConfig fixed = server("srv-2", "Frankfurt");
        fixed.setAddress("frankfurt-2.example.com");
        store.updateServer(fixed);
        store.setActiveServer("srv-2");

        ConnectionService.ConnectAttempt pick = service.switchToActiveServer();

        assertThat(pick.outcome()).isEqualTo(ConnectionService.Outcome.STARTED);
        assertThat(service.appliedServerId()).isEqualTo("srv-2");
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * select_server of a server the running core refuses said
     * {"active": true} and that the tunnel "switches at once"; the switch
     * then stopped the tunnel, and the agent never heard why. It is a tool
     * error now, and the pick stays where it was.
     */
    @Test
    void selectServerReportsAServerTheRunningCoreRefuses() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(withUnbuildableShortId(server("srv-2", "Frankfurt")));
        store.setActiveServer("srv-1");
        PickEngine engine = engine();
        serviceOver(engine);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), service, null, null, engine);
        assertThat(service.connect().started()).isTrue();

        assertThatThrownBy(() -> control.selectServer("srv-2"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Frankfurt");

        assertThat(picked("srv-1")).as("Tokyo still picked").isTrue();
        assertThat(engine.isRunning()).isTrue();
    }
}
