package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
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
@ExtendWith(FxToolkitExtension.class)
class ConnectionServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;

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
        /** Refuses a start, as the real check does, whenever it returns a reason. */
        volatile java.util.function.Function<String, String> refuseWith;
        CountDownLatch awaiting;
        CountDownLatch releaseAwait;
        /** A stop is under way, so a connect has to wait for it. */
        volatile boolean stopping;
        /** The stop under way finishes while a connect waits for it. */
        volatile boolean stopFinishesWhileAwaited;
        /** The last launch asked for elevation, so starting again would ask again. */
        volatile boolean prompts;

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
            String refusal = refuseWith != null ? refuseWith.apply(configJson) : null;
            if (refusal != null) {
                throw new ConfigRejectedException(refusal);
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
        public boolean isStopping() {
            return stopping;
        }

        @Override
        public boolean restartNeedsElevationPrompt() {
            return prompts;
        }

        @Override
        public boolean awaitStopped(Duration timeout) {
            calls.add("await");
            if (stopFinishesWhileAwaited) {
                running = false;
                stopping = false;
            }
            if (awaiting != null) {
                awaiting.countDown();
                try {
                    if (!releaseAwait.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("awaitStopped was not released");
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
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

    @Test
    void recoveryLeavesTheReconnectToTheUserOnlyWhereARestartWouldPrompt() {
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);
        try {
            store.getSettings().setProxyMode(ProxyMode.TUN);
            engine.prompts = true;
            assertThat(service.restartNeedsTheUser()).as("TUN, launched through a prompt").isTrue();

            store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
            assertThat(service.restartNeedsTheUser())
                    .as("the system proxy asks for no elevation")
                    .isFalse();

            store.getSettings().setProxyMode(ProxyMode.TUN);
            engine.prompts = false;
            assertThat(service.restartNeedsTheUser())
                    .as("TUN through the sudoers rule or cap_net_admin asks for nothing")
                    .isFalse();
        } finally {
            service.getRecoveryService().close();
        }
    }

    /**
     * A server picked while connected is switched through the running core,
     * which publishes no state change. The switch still counted as a new
     * request, and a new request clears "the tunnel was up since the request",
     * so a tunnel that dropped afterwards, in a mode whose restart needs an
     * elevation prompt, neither retried nor offered the reconnect.
     */
    @Test
    void aTunnelSwitchedLiveStillOffersTheReconnectWhenItDrops() throws Exception {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Frankfurt"));
        RecordingEngine engine = engine();
        engine.prompts = true;
        ConnectionService service = service(engine);
        com.sun.net.httpserver.HttpServer api = null;
        try {
            assertThat(service.connect().started()).isTrue();
            service.getRecoveryService().onConnectionState(ConnectionState.CONNECTED);
            api = fakeClashApi(store.getSettings().getClashApiPort());
            store.setActiveServer("srv-2");

            assertThat(service.switchToActiveServer().outcome())
                    .isEqualTo(ConnectionService.Outcome.SWITCHED);
            service.getRecoveryService().onHealth(com.vlessclient.model.TunnelHealth.BROKEN);

            assertThat(service.getRecoveryService().isReconnectNeeded())
                    .as("the reconnect offered once the switched tunnel dropped")
                    .isTrue();
        } finally {
            service.getRecoveryService().close();
            if (api != null) {
                api.stop(0);
            }
        }
    }

    /**
     * A connect to a core that is already running changes nothing, but it
     * counted as a new request, which cancelled a retry waiting for a broken
     * tunnel; the verdict stayed broken with no new event, so nothing
     * re-armed it. An agent's connect over MCP did exactly that.
     */
    @Test
    void aConnectToARunningCoreKeepsTheRetryOfABrokenTunnel() throws Exception {
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);
        try {
            assertThat(service.connect().started()).isTrue();
            service.getRecoveryService().onConnectionState(ConnectionState.CONNECTED);
            service.getRecoveryService().onHealth(com.vlessclient.model.TunnelHealth.BROKEN);

            assertThat(service.connect().outcome())
                    .isEqualTo(ConnectionService.Outcome.ALREADY_RUNNING);

            Await.until("the retry to restart the broken tunnel",
                    () -> starts(engine) >= 2, Duration.ofSeconds(10));
        } finally {
            service.getRecoveryService().close();
        }
    }

    /** Stands in for the core's control endpoint: a selector switch always works. */
    private static com.sun.net.httpserver.HttpServer fakeClashApi(int port) throws IOException {
        com.sun.net.httpserver.HttpServer api = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", port), 0);
        AtomicReference<String> selected = new AtomicReference<>("");
        api.createContext("/proxies/", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                selected.set(tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(exchange.getRequestBody().readAllBytes())
                        .path("name").asString());
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] body = ("{\"now\":\"" + selected.get() + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
        return api;
    }

    /**
     * A crash is recovered by stopping what is left of the core and starting it
     * again, through the service. {@code recover()} had no test of its own: the
     * recovery loop was tested with a stand-in restart only.
     */
    @Test
    void aCrashIsRecoveredByRestartingTheCore() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);
        try {
            assertThat(service.connect().started()).as("the first start").isTrue();
            engine.running = false;

            service.getRecoveryService().onConnectionState(ConnectionState.ERROR);

            Await.until("recovery to start the core again",
                    () -> starts(engine) >= 2, Duration.ofSeconds(10));
            assertThat(engine.calls)
                    .as("what the restart asked of the engine")
                    .containsSubsequence("start", "stop", "await", "start");
        } finally {
            service.getRecoveryService().close();
        }
    }

    /** A disconnect during the wait before a restart keeps the core stopped. */
    @Test
    void aDisconnectDuringTheWaitBeforeARestartKeepsTheCoreStopped() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.getSettings().setHealthCheckAutoReconnect(true);
        store.getSettings().setHealthCheckDelaySeconds(1);
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);
        try {
            assertThat(service.connect().started()).as("the first start").isTrue();
            engine.running = false;
            service.getRecoveryService().onConnectionState(ConnectionState.ERROR);

            service.disconnect();
            // Absence has no event to wait for: sleep past the one-second
            // wait, twice over, and count.
            Thread.sleep(2_000);

            assertThat(starts(engine))
                    .as("starts, after a disconnect during the wait before a restart")
                    .isEqualTo(1);
        } finally {
            service.getRecoveryService().close();
        }
    }

    private static long starts(RecordingEngine engine) {
        return engine.calls.stream().filter("start"::equals).count();
    }

    /** Refuses every start the way the real check does, with a reason built from the config. */
    private static final class RefusingEngine extends SingBoxEngine {

        private final java.util.function.Function<String, String> reasonFor;

        RefusingEngine(Path binary, java.util.function.Function<String, String> reasonFor) {
            super(binary);
            this.reasonFor = reasonFor;
        }

        @Override
        public void start(String configJson, ProxyMode proxyMode) throws IOException {
            throw new ConfigRejectedException(reasonFor.apply(configJson));
        }
    }

    /** The position the core would quote for this server's outbound. */
    private static int outboundIndex(String configJson, String serverId) {
        var outbounds = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(configJson).path("outbounds");
        String tag = com.vlessclient.service.outbound.OutboundTags.server(serverId);
        for (int i = 0; i < outbounds.size(); i++) {
            if (tag.equals(outbounds.path(i).path("tag").asString(""))) {
                return i;
            }
        }
        throw new AssertionError("no outbound tagged " + tag + " in " + configJson);
    }

    /**
     * Every server is a member of the configuration, so Frankfurt's broken
     * entry used to stop the connect to Tokyo, the server the user picked.
     */
    @Test
    void aServerTheCoreRefusesIsLeftOutSoTheOthersStillConnect() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Frankfurt"));
        RecordingEngine engine = engine();
        engine.refuseWith = config -> config.contains(tag("srv-2"))
                ? "initialize outbound[" + outboundIndex(config, "srv-2")
                        + "]: unsupported flow: xtls-rprx-direct"
                : null;
        ConnectionService service = service(engine);

        ConnectionService.ConnectAttempt attempt = service.connect();

        assertThat(attempt.started()).isTrue();
        assertThat(attempt.server().getId()).isEqualTo("srv-1");
        assertThat(attempt.skipped()).containsExactly(new ConnectionService.SkippedServer(
                "srv-2", "Frankfurt", "unsupported flow: xtls-rprx-direct"));
        assertThat(engine.configs).singleElement().asString()
                .contains(tag("srv-1"))
                .doesNotContain(tag("srv-2"));
        assertThat(skippedAsTheUiSeesThem(service))
                .extracting(ConnectionService.SkippedServer::name)
                .containsExactly("Frankfurt");
    }

    @Test
    void aRefusedWireGuardEndpointIsLeftOutToo() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(wireguard("srv-wg", "Warp"));
        RecordingEngine engine = engine();
        engine.refuseWith = config -> config.contains(tag("srv-wg"))
                ? "endpoints[" + endpointIndex(config, "srv-wg")
                        + "].address: netip.ParsePrefix(\"10.0.0.2\"): no '/'"
                : null;

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.started()).isTrue();
        assertThat(attempt.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.name()).isEqualTo("Warp");
            assertThat(skipped.reason()).startsWith("address: ");
        });
    }

    /** Connecting through a server the user did not pick is not a fallback. */
    @Test
    void aRefusalOfTheActiveServerStillFailsAndNamesIt() {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(server("srv-2", "Frankfurt"));
        ConnectionService service = service(new RefusingEngine(tempDir.resolve("sing-box"),
                config -> "initialize outbound[" + outboundIndex(config, "srv-1")
                        + "]: unsupported flow: xtls-rprx-direct"));

        assertThatThrownBy(service::connect)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessage("The VPN core rejected the settings of server \"Tokyo\": "
                        + "unsupported flow: xtls-rprx-direct");
    }

    /**
     * A REALITY short ID of more than 16 hex digits does not get a refusal
     * from the core: {@code sing-box check} panics, and a panic quotes no
     * outbound, so the refused member could not be found and every connect
     * failed, to whichever server, with a line from the stack trace. The
     * import rules already knew such a server; it only has to be left out
     * before the core sees it, as it can be stored by an older build, the
     * server form or a restored backup.
     */
    @Test
    void aStoredServerTheCoreWouldRefuseIsLeftOutBeforeTheCheck() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        store.addServer(withShortId(server("srv-2", "Frankfurt"), "0123456789abcdef01"));
        RecordingEngine engine = engine();
        engine.refuseWith = config -> config.contains("0123456789abcdef01")
                ? "github.com/sagernet/sing-box/cmd/sing-box/main.go:8 +0x24"
                : null;
        ConnectionService service = service(engine);

        ConnectionService.ConnectAttempt attempt = service.connect();

        assertThat(attempt.started()).isTrue();
        assertThat(attempt.server().getId()).isEqualTo("srv-1");
        assertThat(attempt.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.id()).isEqualTo("srv-2");
            assertThat(skipped.name()).isEqualTo("Frankfurt");
            assertThat(skipped.reason()).contains("short ID");
        });
        assertThat(starts(engine)).as("starts, with nothing for the core to refuse").isEqualTo(1);
        assertThat(engine.configs).singleElement().asString()
                .contains(tag("srv-1"))
                .doesNotContain(tag("srv-2"));
    }

    @Test
    void anActiveServerTheCoreWouldRefuseFailsBeforeTheCheckAndIsNamed() {
        store.addServer(withShortId(server("srv-1", "Tokyo"), "0123456789abcdef01"));
        store.addServer(server("srv-2", "Frankfurt"));
        RecordingEngine engine = engine();
        engine.refuseWith = config -> config.contains("0123456789abcdef01")
                ? "github.com/sagernet/sing-box/cmd/sing-box/main.go:8 +0x24"
                : null;
        ConnectionService service = service(engine);

        assertThatThrownBy(service::connect)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessageStartingWith("The VPN core rejected the settings of server \"Tokyo\": ")
                .hasMessageContaining("short ID");
        assertThat(engine.calls).as("what the engine was asked to do").doesNotContain("start");
    }

    /** A REALITY server with the given short ID and an otherwise valid key. */
    private static ServerConfig withShortId(ServerConfig server, String shortId) {
        com.vlessclient.model.TlsConfig tls = new com.vlessclient.model.TlsConfig();
        tls.setEnabled(true);
        tls.setServerName("www.example.com");
        tls.setReality(true);
        tls.setRealityPublicKey("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0");
        tls.setRealityShortId(shortId);
        server.setTls(tls);
        return server;
    }

    private static String tag(String serverId) {
        return com.vlessclient.service.outbound.OutboundTags.server(serverId);
    }

    private ServerConfig wireguard(String id, String name) {
        ServerConfig s = server(id, name);
        s.setProtocol(Protocol.WIREGUARD);
        s.setPort(51820);
        s.setUuid("xunATixZ9R2SMbEghGvNz1fen77h9i5gNCPfxxgxtWk=");
        s.setEncryption("2Gl1nZ7pohiktxNLQq7rb1ZwdPN2BBaHpwA2M6dMJXM=");
        s.setFlow("10.0.0.2");
        return s;
    }

    /** The position the core would quote for this server's WireGuard endpoint. */
    private static int endpointIndex(String configJson, String serverId) {
        var endpoints = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(configJson).path("endpoints");
        for (int i = 0; i < endpoints.size(); i++) {
            if (tag(serverId).equals(endpoints.path(i).path("tag").asString(""))) {
                return i;
            }
        }
        throw new AssertionError("no endpoint tagged " + tag(serverId) + " in " + configJson);
    }

    /** The list as a UI listener reads it, once the queued update has landed. */
    private static List<ConnectionService.SkippedServer> skippedAsTheUiSeesThem(
            ConnectionService service) throws InterruptedException {
        AtomicReference<List<ConnectionService.SkippedServer>> seen = new AtomicReference<>();
        CountDownLatch read = new CountDownLatch(1);
        Platform.runLater(() -> {
            seen.set(service.skippedServersProperty().get());
            read.countDown();
        });
        assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
        return seen.get();
    }

    @Test
    void aDecodeErrorQuotingAnOutboundPositionIsNamedToo() {
        store.addServer(server("srv-1", "Tokyo"));
        ConnectionService service = service(new RefusingEngine(tempDir.resolve("sing-box"),
                config -> "outbounds[" + outboundIndex(config, "srv-1")
                        + "].transport: unknown transport type: xhttp"));

        assertThatThrownBy(service::connect)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessage("The VPN core rejected the settings of server \"Tokyo\": "
                        + "transport: unknown transport type: xhttp");
    }

    @Test
    void aRefusalThatQuotesNoServerKeepsTheCoresWords() {
        store.addServer(server("srv-1", "Tokyo"));
        ConnectionService service = service(new RefusingEngine(tempDir.resolve("sing-box"),
                config -> "missing route.default_domain_resolver"));

        assertThatThrownBy(service::connect)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessage("The VPN core rejected the configuration: "
                        + "missing route.default_domain_resolver");
    }

    @Test
    void cancellationWhileWaitingForTheOldCorePreventsANewStart() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.awaiting = new CountDownLatch(1);
        engine.releaseAwait = new CountDownLatch(1);
        ConnectionService service = service(engine);
        AtomicReference<ConnectionService.ConnectAttempt> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread connect = Thread.startVirtualThread(() -> {
            try {
                result.set(service.connect());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        try {
            assertThat(engine.awaiting.await(5, TimeUnit.SECONDS)).isTrue();
            service.getRecoveryService().cancel();
        } finally {
            engine.releaseAwait.countDown();
            connect.join(5000);
            service.disconnect();
            service.getRecoveryService().close();
        }
        assertThat(connect.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get().outcome()).isEqualTo(ConnectionService.Outcome.CANCELLED);
        assertThat(engine.calls).doesNotContain("start");
    }

    // ===== what the generator is given =====

    /**
     * The AUTO_BEST regression, now pinned at the single owner: every configured
     * server must reach the generator as a candidate. The MCP path used to pass
     * the active server alone, which silently collapsed automatic selection to a
     * one-member group.
     */
    /**
     * A local port another program holds failed every start with "address
     * already in use", and recovery repeated that failure for as long as the
     * other program lived, notifying each time. The run moves to a free port
     * instead; the file keeps what the user chose, so the next start tries
     * their port again.
     */
    @Test
    void aTakenLocalPortMovesToAFreeOneForTheRun() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = taken.getLocalPort();
            store.getSettings().setSocksPort(busy);
            store.saveSettings(store.getSettings());

            RecordingEngine engine = engine();
            assertThat(service(engine).connect().started()).isTrue();

            assertThat(store.getSettings().listenSocksPort())
                    .as("the run listens somewhere it can actually bind")
                    .isNotEqualTo(busy);
            assertThat(new ConfigStore(tempDir).getSettings().getSocksPort())
                    .as("the file keeps the user's choice: this is a fallback, not a preference")
                    .isEqualTo(busy);
        }
    }

    /**
     * Two inbounds must not be moved onto the same port: the second bind would
     * fail for the same reason the first one moved.
     */
    @Test
    void portsMovedOutOfTheWayDoNotLandOnEachOther() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = taken.getLocalPort();
            store.getSettings().setSocksPort(busy);
            store.getSettings().setHttpPort(busy + 1);

            assertThat(service(engine()).connect().started()).isTrue();

            assertThat(store.getSettings().listenSocksPort())
                    .isNotEqualTo(store.getSettings().listenHttpPort());
        }
    }

    /**
     * The move was made on the settings object every save writes out, so it
     * stayed in memory only until the first save: expanding the traffic
     * history, any Settings change or an MCP edit made the moved port the
     * user's choice for good.
     */
    @Test
    void aMovedPortStaysOutOfTheFileWhenTheSettingsAreSavedLater() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = taken.getLocalPort();
            store.getSettings().setSocksPort(busy);
            store.saveSettings(store.getSettings());
            assertThat(service(engine()).connect().started()).isTrue();

            // What the traffic-history toggle does, among others.
            store.saveSettings(store.getSettings());

            assertThat(new ConfigStore(tempDir).getSettings().getSocksPort())
                    .as("the file after a later save")
                    .isEqualTo(busy);
            assertThat(store.getSettings().getSocksPort())
                    .as("the port the user chose, as Settings shows it")
                    .isEqualTo(busy);
        }
    }

    /**
     * A taken port moved to the next free one without regard for the ports
     * the other inbounds were about to take: with the defaults, a taken 1080
     * moved SOCKS onto 1081, the HTTP port, and HTTP to 1082, so a browser set
     * to the HTTP proxy on 1081 reached a SOCKS inbound.
     */
    @Test
    void aTakenPortDoesNotMoveOntoAnotherInboundsChosenPort() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        int base = freeBlockOf(3);
        try (ServerSocket taken = new ServerSocket(base, 1, InetAddress.getLoopbackAddress())) {
            store.getSettings().setSocksPort(base);
            store.getSettings().setHttpPort(base + 1);

            assertThat(service(engine()).connect().started()).isTrue();

            assertThat(store.getSettings().listenHttpPort())
                    .as("HTTP keeps the port the user chose, which is free")
                    .isEqualTo(base + 1);
            assertThat(store.getSettings().listenSocksPort())
                    .as("SOCKS moves past it")
                    .isNotIn(base, base + 1);
        }
    }

    /**
     * A moved port is named on the Dashboard, since a program set to the
     * chosen one reaches nothing this session; and a moved HTTP port is
     * recorded, since a run that dies leaves the system's proxy on it and the
     * next start looked for such a proxy on the chosen port only.
     */
    @Test
    void aMovedPortIsReportedAndItsHttpPortKeptUntilTheDisconnect() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        // All three ports chosen off the defaults: a machine running a proxy
        // of its own holds 1080, and SOCKS moved too.
        int base = freeBlockOf(3);
        try (ServerSocket taken = new ServerSocket(base + 1, 1, InetAddress.getLoopbackAddress())) {
            int busy = taken.getLocalPort();
            store.getSettings().setSocksPort(base);
            store.getSettings().setHttpPort(busy);
            store.getSettings().setClashApiPort(base + 2);
            ConnectionService service = service(engine());

            assertThat(service.connect().started()).isTrue();

            int used = store.getSettings().listenHttpPort();
            assertThat(movedAsTheUiSeesThem(service))
                    .containsExactly(new ConnectionService.MovedPort("HTTP", busy, used));
            assertThat(SessionPorts.recorded(tempDir)).hasValue(used);

            service.disconnect();
            assertThat(SessionPorts.recorded(tempDir))
                    .as("a disconnect restores the system's proxy")
                    .isEmpty();
        }
    }

    private static List<ConnectionService.MovedPort> movedAsTheUiSeesThem(
            ConnectionService service) throws InterruptedException {
        AtomicReference<List<ConnectionService.MovedPort>> seen = new AtomicReference<>();
        CountDownLatch read = new CountDownLatch(1);
        Platform.runLater(() -> {
            seen.set(service.movedPortsProperty().get());
            read.countDown();
        });
        assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
        return seen.get();
    }

    /** The first of {@code count} consecutive loopback ports that are all free. */
    private static int freeBlockOf(int count) throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base;
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                base = probe.getLocalPort();
            }
            if (base + count > 65535) {
                continue;
            }
            boolean free = true;
            for (int port = base; port < base + count && free; port++) {
                try (ServerSocket probe = new ServerSocket(port, 1,
                        InetAddress.getLoopbackAddress())) {
                    free = probe.getLocalPort() == port;
                } catch (IOException taken) {
                    free = false;
                }
            }
            if (free) {
                return base;
            }
        }
        throw new AssertionError("no free block of " + count + " loopback ports");
    }

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

    /**
     * The control API's secret lasted the whole app run. While a TUN start
     * waits for the admin prompt, the watchdog sends it to whatever answers on
     * the control port, so a program squatting there kept a token that stayed
     * good for every later core of the run. Each core gets one of its own.
     */
    @Test
    void eachCoreGetsAControlSecretOfItsOwn() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        ConnectionService service = service(engine);

        assertThat(service.connect().started()).isTrue();
        service.disconnect();
        assertThat(service.connect().started()).isTrue();

        List<String> secrets = engine.configs.stream()
                .map(ConnectionServiceTest::controlSecret)
                .toList();
        assertThat(secrets).hasSize(2).doesNotHaveDuplicates()
                .allSatisfy(secret -> assertThat(secret).matches("[0-9a-f]{48}"));
        assertThat(store.getSettings().getClashApiSecret())
                .as("what the monitors and the latency probe send to the running core")
                .isEqualTo(secrets.getLast());
    }

    private static String controlSecret(String configJson) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(configJson)
                .path("experimental").path("clash_api").path("secret").asString("");
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

    /**
     * A connect while the core is already up waited out the whole stop timeout
     * for a stop nobody had asked for, holding the lock a Disconnect needs, and
     * then reported the core as already running anyway.
     */
    @Test
    void connectWhileTheCoreRunsReportsItAtOnce() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.running = true;
        engine.refuseAsAlreadyRunning = true;

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.outcome()).isEqualTo(ConnectionService.Outcome.ALREADY_RUNNING);
        assertThat(engine.calls)
                .as("no wait for a stop nobody asked for, and no second start")
                .isEmpty();
    }

    @Test
    void connectWhileTheCoreIsStoppingWaitsForItToExit() throws Exception {
        store.addServer(server("srv-1", "Tokyo"));
        RecordingEngine engine = engine();
        engine.running = true;
        engine.stopping = true;
        engine.stopFinishesWhileAwaited = true;

        ConnectionService.ConnectAttempt attempt = service(engine).connect();

        assertThat(attempt.started()).isTrue();
        assertThat(engine.calls).containsExactly("await", "start");
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
