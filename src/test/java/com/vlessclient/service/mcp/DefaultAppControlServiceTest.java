package com.vlessclient.service.mcp;

import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TestSubscriptionServices;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Direct tests of {@link DefaultAppControlService} that don't need a running
 * JavaFX toolkit: the TUN-confirm gate, mode parsing, and server selection all
 * resolve before any FX-thread access.
 */
@ExtendWith(FxToolkitExtension.class)
class DefaultAppControlServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private DefaultAppControlService service;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
        store.addServer(server("srv-1", "Tokyo"));
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        service = new DefaultAppControlService(store, null, null, null, null, null, null, engine);
    }

    private ServerConfig server(String id, String name) {
        return TestServers.server()
                .id(id)
                .name(name)
                .protocol(com.vlessclient.model.Protocol.VLESS)
                .address("example.com")
                .port(443)
                .build();
    }

    private static final class RecordingConnectionService extends ConnectionService {

        private final List<ProxyMode> modes = new ArrayList<>();
        private ConnectAttempt attempt;
        private IOException failure;
        private int disconnects;
        /** The mode a core runs in, as the real service reports it; null when none runs. */
        private ProxyMode running;
        /** The mode a core another request starts in while this connect runs; or null. */
        private ProxyMode startedMeanwhile;

        RecordingConnectionService(ServerConfig server) {
            super(null, null, null, SingBoxEngine.withoutCore());
            attempt = new ConnectAttempt(Outcome.STARTED, server);
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) throws IOException {
            modes.add(modeOverride);
            if (failure != null) {
                throw failure;
            }
            ConnectAttempt result =
                    running != null ? new ConnectAttempt(Outcome.ALREADY_RUNNING, null) : attempt;
            if (startedMeanwhile != null) {
                running = startedMeanwhile;
            }
            return result;
        }

        @Override
        public Optional<ProxyMode> runningMode() {
            return Optional.ofNullable(running);
        }

        @Override
        public void disconnect() {
            disconnects++;
        }
    }

    private DefaultAppControlService serviceWith(RecordingConnectionService connectionService,
                                                  SingBoxEngine engine) {
        return new DefaultAppControlService(store, null, null, null, connectionService,
                null, null, engine);
    }

    @Test
    void connect_tunWithoutConfirm_isRejected() {
        store.getSettings().setProxyMode(ProxyMode.TUN);
        assertThatThrownBy(() -> service.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("confirm");
    }

    @Test
    void connect_tunModeArgWithoutConfirm_isRejected() {
        assertThatThrownBy(() -> service.connect(null, "tun", false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("TUN");
    }

    @Test
    void connect_unknownMode_isRejected() {
        assertThatThrownBy(() -> service.connect(null, "bogus", false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Unknown mode");
    }

    @Test
    void connect_selectsServerPersistsModeAndDelegatesToConnectionOwner() throws Exception {
        ServerConfig osaka = server("srv-2", "Osaka");
        store.addServer(osaka);
        RecordingConnectionService connection = new RecordingConnectionService(osaka);
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService svc = serviceWith(connection, engine);

        StatusInfo status = svc.connect("srv-2", "tun", true);

        assertThat(connection.modes).containsExactly(ProxyMode.TUN);
        assertThat(status.activeServerId()).isEqualTo("srv-2");
        assertThat(status.proxyMode()).isEqualTo("tun");
        assertThat(store.getServerById("srv-2").orElseThrow().isActive()).isTrue();
        assertThat(new ConfigStore(tempDir).getSettings().getProxyMode()).isEqualTo(ProxyMode.TUN);
    }

    @Test
    void connect_mapsRefusedStartToAToolError() {
        ServerConfig active = store.getServerById("srv-1").orElseThrow();
        RecordingConnectionService connection = new RecordingConnectionService(active);
        connection.attempt =
                new ConnectionService.ConnectAttempt(ConnectionService.Outcome.CANCELLED,
                        active);
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("cancelled");
    }

    /**
     * A connect while the tunnel was up saved the server and the mode it was
     * given, then said "sing-box is already running". The Dashboard had
     * meanwhile switched the live tunnel to that server, and a mode alone was
     * saved for a core that kept running in the old one. Nothing is changed
     * now, and the agent is told how to get what it asked for.
     */
    @Test
    void connect_whileATunnelRuns_anotherServerOrModeIsRefusedBeforeAnythingChanges() {
        store.addServer(server("srv-2", "Osaka"));
        store.setActiveServer("srv-1");
        RecordingConnectionService connection = new RecordingConnectionService(null);
        connection.running = ProxyMode.SYSTEM_PROXY;
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect("srv-2", null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("select_server");
        assertThatThrownBy(() -> svc.connect("srv-2", "tun", true))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("disconnect");
        assertThatThrownBy(() -> svc.connect(null, "tun", true))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("disconnect");

        assertThat(connection.modes).as("no connect was tried").isEmpty();
        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServerById("srv-1").orElseThrow().isActive()).isTrue();
        assertThat(store.getServerById("srv-1").orElseThrow().isActive()).isTrue();
        assertThat(reloaded.getSettings().getProxyMode()).isEqualTo(ProxyMode.SYSTEM_PROXY);
    }

    /**
     * Connecting what is already connected is done: an agent asking twice,
     * or asking after the user connected, was told it had failed.
     */
    @Test
    void connect_whileATunnelRuns_withNothingToChange_reportsTheRunningTunnel()
            throws Exception {
        store.setActiveServer("srv-1");
        RecordingConnectionService connection = new RecordingConnectionService(null);
        connection.running = ProxyMode.TUN;
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        StatusInfo plain = svc.connect(null, null, false);
        StatusInfo same = svc.connect("srv-1", "tun", false);

        assertThat(plain.activeServerId()).isEqualTo("srv-1");
        assertThat(plain.runningProxyMode()).isEqualTo("tun");
        assertThat(same.activeServerId()).isEqualTo("srv-1");
        assertThat(connection.modes).as("nothing to start").isEmpty();
    }

    /** A core another request started between the check and the start is the same answer. */
    @Test
    void connect_aCoreStartedMeanwhileIsTheRunningTunnel() throws Exception {
        ServerConfig active = store.getServerById("srv-1").orElseThrow();
        RecordingConnectionService connection = new RecordingConnectionService(active);
        connection.attempt =
                new ConnectionService.ConnectAttempt(ConnectionService.Outcome.ALREADY_RUNNING,
                        active);
        connection.startedMeanwhile = ProxyMode.SYSTEM_PROXY;
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThat(svc.connect(null, null, false).activeServerId()).isEqualTo("srv-1");
    }

    /**
     * A refusal with no tunnel running, as for a core still closing from a
     * stop, was reported to the agent as the tunnel it asked for.
     */
    @Test
    void connect_aRefusalWithNoTunnelRunningIsNotAConnect() {
        ServerConfig active = store.getServerById("srv-1").orElseThrow();
        RecordingConnectionService connection = new RecordingConnectionService(active);
        connection.attempt =
                new ConnectionService.ConnectAttempt(ConnectionService.Outcome.ALREADY_RUNNING,
                        active);
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void connect_anUnknownServerIsRefusedWhileATunnelRuns() {
        RecordingConnectionService connection = new RecordingConnectionService(null);
        connection.running = ProxyMode.SYSTEM_PROXY;
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect("nope", null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("No server with id: nope");
    }

    @Test
    void connect_mapsStartFailureToAToolError() {
        ServerConfig active = store.getServerById("srv-1").orElseThrow();
        RecordingConnectionService connection = new RecordingConnectionService(active);
        connection.failure = new IOException("permission denied");
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void disconnect_delegatesToConnectionOwner() {
        ServerConfig active = store.getServerById("srv-1").orElseThrow();
        RecordingConnectionService connection = new RecordingConnectionService(active);
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        StatusInfo status = svc.disconnect();

        assertThat(connection.disconnects).isEqualTo(1);
        assertThat(status.activeServerId()).isEqualTo("srv-1");
    }

    /**
     * The connect flow itself lives in ConnectionService (and is tested there);
     * what this facade owns is turning an outcome into a tool error. A missing
     * target must be reported, not swallowed into a "connected" status.
     */
    @Test
    void connect_withoutAnActiveServer_isReportedAsAToolError() {
        ConfigStore empty = new ConfigStore(tempDir.resolve("empty"));
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        DefaultAppControlService svc = new DefaultAppControlService(empty, null, null,
                new RoutingService(),
                new ConnectionService(empty, new SingBoxConfigGenerator(),
                        new RoutingService(), engine),
                null, new ShareLinkParser(), engine);

        assertThatThrownBy(() -> svc.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("No active server");
    }

    @Test
    void selectServer_activatesAndPersists() throws Exception {
        ServerSummary summary = service.selectServer("srv-1");
        assertThat(summary.active()).isTrue();
        assertThat(store.getServerById("srv-1").orElseThrow().isActive()).isTrue();

        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServerById("srv-1").orElseThrow().isActive()).isTrue();
    }

    @Test
    void selectServer_unknownId_isRejected() {
        assertThatThrownBy(() -> service.selectServer("nope"))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void measureLatency_unknownServer_isRejected() {
        assertThatThrownBy(() -> service.measureLatency("nope"))
                .isInstanceOf(McpToolException.class);
    }

    /**
     * An agent gets the reason, and nothing is stored: a rule the core refuses
     * stopped every server from connecting.
     */
    @Test
    void addRoutingRule_refusesAValueTheCoreWouldRefuse() {
        RoutingService routing = com.vlessclient.service.TestRoutingServices.at(
                tempDir.resolve("routing"));
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null,
                routing, null, null, new ShareLinkParser(),
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.addRoutingRule("domain_regex", "(", "proxy"))
                .isInstanceOf(McpToolException.class)
                .hasMessage(com.vlessclient.service.RoutingRuleCheck.problem(
                        com.vlessclient.model.RoutingRule.RuleType.DOMAIN_REGEX, "(")
                        .orElseThrow());
        assertThat(routing.getConfig().getRules()).isEmpty();
    }

    @Test
    void addServer_parsesShareLinkAndPersists() throws Exception {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null,
                new RoutingService(), null, null, new ShareLinkParser(),
                new SingBoxEngine(tempDir.resolve("sing-box")));

        ServerSummary added = svc.addServer(
                "vless://test-uuid-1234@example.com:443#MyServer", "Renamed");

        assertThat(added.name()).isEqualTo("Renamed");
        assertThat(added.address()).isEqualTo("example.com");
        assertThat(added.port()).isEqualTo(443);
        assertThat(new ConfigStore(tempDir).getServerById(added.id())).isPresent();
    }

    @Test
    void addServer_invalidLink_isRejected() {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));
        assertThatThrownBy(() -> svc.addServer("not-a-link", null))
                .isInstanceOf(McpToolException.class);
    }

    /**
     * A subscription URL carries the account token, so it is sealed on disk,
     * shown as scheme and host in the app, and scrubbed from get_logs and the
     * event stream. list_subscriptions returned it whole, to any agent holding
     * the MCP token, even with configuration changes turned off.
     */
    @Test
    void listSubscriptions_showsOnlySchemeAndHostOfTheUrl() throws Exception {
        com.vlessclient.service.SubscriptionService subscriptions =
                com.vlessclient.service.TestSubscriptionServices.quiet(tempDir.resolve("subs"));
        subscriptions.addSubscription("Provider",
                "https://sub.example.com/api/v1/client/subscribe?token=SECRET-TOKEN-123");
        DefaultAppControlService svc = new DefaultAppControlService(store, null, subscriptions,
                null, null, null, null, new SingBoxEngine(tempDir.resolve("sing-box")));

        List<SubscriptionSummary> listed = svc.listSubscriptions();

        assertThat(listed).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo("Provider");
            assertThat(summary.url()).startsWith("https://sub.example.com")
                    .doesNotContain("SECRET-TOKEN-123")
                    .doesNotContain("/api/v1");
        });
    }

    /**
     * A link the core would refuse used to be stored and then left out of
     * every connect; an agent adding it is told why instead.
     */
    @Test
    void addServer_linkTheCoreWouldRefuse_isRejectedWithTheReason() {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.addServer("vless://11111111-2222-3333-4444-555555555555"
                + "@example.com:443?security=reality&sni=example.com&fp=chrome"
                + "&pbk=pubkey123&sid=0123abcd#BrokenKey", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("REALITY public key");
        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .doesNotContain("BrokenKey");
    }

    @Test
    void deleteServer_withoutConfirm_isRejected() {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));
        assertThatThrownBy(() -> svc.deleteServer("srv-1", false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("confirm");
    }

    @Test
    void deleteServer_withConfirm_removes() throws Exception {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));
        svc.deleteServer("srv-1", true);
        assertThat(store.getServerById("srv-1")).isEmpty();
    }

    /** A bare JSON string node, without depending on its concrete class. */
    private static JsonNode stringNode(String value) {
        return JsonMapper.builder().build().createObjectNode().put("v", value).get("v");
    }

    @Test
    void setSetting_coreLogLevel_persistsAndIsReportedBack() throws Exception {
        SettingsInfo info = service.setSetting("core_log_level", stringNode("debug"));

        assertThat(info.coreLogLevel()).isEqualTo("debug");
        assertThat(store.getSettings().getCoreLogLevel()).isEqualTo(CoreLogLevel.DEBUG);
    }

    @Test
    void setSetting_unknownCoreLogLevel_isRejected() {
        // The enum forgives an unknown value when loading a settings file; an
        // agent asking for one has to be told, or it would read a log that
        // does not hold what it asked the core to write.
        assertThatThrownBy(() -> service.setSetting("core_log_level",
                stringNode("verbose")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("debug, info, warn, error");
        assertThat(store.getSettings().getCoreLogLevel()).isEqualTo(CoreLogLevel.INFO);
    }

    /**
     * The settings MCP could set but never report back, or neither: an agent
     * that changed one had no way to see it had, or what it was before.
     */
    @Test
    void setSetting_theKeysThatHadDriftedAreSetAndReportedBack() throws Exception {
        JsonMapper json = JsonMapper.builder().build();

        service.setSetting("server_selection", stringNode("auto_best"));
        service.setSetting("system_proxy_auto_config", json.getNodeFactory().booleanNode(false));
        service.setSetting("tun_ipv4_address", stringNode("172.20.0.1/30"));
        SettingsInfo info = service.setSetting("tunIpv6Enabled",
                json.getNodeFactory().booleanNode(true));

        assertThat(info.serverSelection()).isEqualTo("auto_best");
        assertThat(info.systemProxyAutoConfig()).isFalse();
        assertThat(info.tunIpv4Address()).isEqualTo("172.20.0.1/30");
        assertThat(info.tunIpv6Enabled()).isTrue();
        assertThat(store.getSettings().getServerSelection())
                .isEqualTo(com.vlessclient.model.ServerSelection.AUTO_BEST);
    }

    @Test
    void setSetting_refusesAValueTheSettingWouldMisread() {
        // "yes" read as false: the setting went off and the call reported success.
        assertThatThrownBy(() -> service.setSetting("auto_connect", stringNode("yes")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("true or false");
        assertThatThrownBy(() -> service.setSetting("server_selection", stringNode("fastest")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("single, auto_best");
        for (String network : new String[] {"172.20.0.1", "172.20.0.1/31", "fd00::1/126",
                "tun0/30", "172.20.0.1/x"}) {
            assertThatThrownBy(() -> service.setSetting("tun_ipv4_address", stringNode(network)))
                    .as(network)
                    .isInstanceOf(McpToolException.class).hasMessageContaining("IPv4 network");
        }
        // The core is given the name as it is; none of these makes a device.
        String name = com.vlessclient.platform.Platform.current().isMac() ? "tunl0" : " ";
        assertThatThrownBy(() -> service.setSetting("tun_interface_name", stringNode(name)))
                .isInstanceOf(McpToolException.class).hasMessageContaining("TUN interface name");
        assertThat(store.getSettings().isAutoConnect()).isFalse();
        assertThat(store.getSettings().getServerSelection())
                .isEqualTo(com.vlessclient.model.ServerSelection.SINGLE);
    }

    /**
     * Any text was stored as a resolver: two of them reached the core as one
     * server name, and every lookup through it failed, and a port past 65535
     * made the core refuse the whole configuration.
     */
    @Test
    void setSetting_aDnsServerIsOneAddressTheCoreCanUse() throws Exception {
        for (String key : List.of("proxy_dns", "direct_dns")) {
            for (String address : List.of("1.1.1.1, 8.8.8.8", "1.1.1.1 8.8.8.8",
                    "udp://1.1.1.1:99999", "dhcp://auto", "https://", "fakeip")) {
                assertThatThrownBy(() -> service.setSetting(key, stringNode(address)))
                        .as("%s %s", key, address)
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("one DNS server");
            }
        }
        assertThatThrownBy(() -> service.setSetting("proxy_dns", stringNode("system")))
                .isInstanceOf(McpToolException.class);
        assertThat(store.getSettings().getProxyDns()).isEqualTo("https://1.1.1.1/dns-query");
        assertThat(store.getSettings().getDirectDns())
                .isEqualTo(com.vlessclient.model.AppSettings.SYSTEM_DNS);

        // The address alone, without the space an agent sent around it.
        SettingsInfo info = service.setSetting("proxy_dns", stringNode(" tls://9.9.9.9:853 "));
        service.setSetting("direct_dns", stringNode("System"));

        assertThat(info.proxyDns()).startsWith("tls://9.9.9.9:853");
        assertThat(store.getSettings().getProxyDns()).isEqualTo("tls://9.9.9.9:853");
        assertThat(store.getSettings().getDirectDns()).isEqualTo("System");
    }

    @Test
    void setSetting_anUnknownKeyIsToldEveryKeyThereIs() {
        assertThatThrownBy(() -> service.setSetting("colour", stringNode("red")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(String.join(", ", McpSettings.writableKeys()));
    }

    @Test
    void getSettings_reportsTheCoreLogLevel() {
        store.getSettings().setCoreLogLevel(CoreLogLevel.ERROR);

        assertThat(service.getSettings().coreLogLevel()).isEqualTo("error");
    }

    /**
     * The refresh records a failure on the subscription rather than throwing,
     * and the tool said "Refresh triggered" for a dead URL or an expired
     * token alike. It reports the failure, in the app's words, as a tool error.
     */
    @Test
    void refreshSubscription_aFailedFetchIsAToolErrorWithTheReason() throws Exception {
        TestSubscriptionServices.Scripted subscriptions =
                TestSubscriptionServices.scripted(tempDir.resolve("subs"));
        subscriptions.addSubscription("Provider", "https://sub.example.com/s");
        Subscription sub = subscriptions.getSubscriptions().getFirst();
        subscriptions.onRefresh(id -> sub.recordFailure("HTTP 403"));
        DefaultAppControlService svc = new DefaultAppControlService(store, null, subscriptions,
                null, null, null, null, new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.refreshSubscription(sub.getId()))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Provider")
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void refreshSubscription_aRefreshThatWorkedSaysHowManyServers() throws Exception {
        TestSubscriptionServices.Scripted subscriptions =
                TestSubscriptionServices.scripted(tempDir.resolve("subs"));
        subscriptions.addSubscription("Provider", "https://sub.example.com/s");
        Subscription sub = subscriptions.getSubscriptions().getFirst();
        subscriptions.onRefresh(id -> {
            sub.clearLastError();
            sub.setServerIds(List.of("a", "b"));
        });
        DefaultAppControlService svc = new DefaultAppControlService(store, null, subscriptions,
                null, null, null, null, new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThat(svc.refreshSubscription(sub.getId()))
                .isEqualTo("Refreshed subscription 'Provider': 2 servers.");
    }

    /**
     * A server nothing could be measured for, such as one over UDP, came out
     * as -1, the same as one that did not answer.
     */
    @Test
    void measureLatency_tellsANotMeasuredServerFromAnUnreachableOne() throws Exception {
        store.addServer(server("srv-2", "Hysteria"));
        store.addServer(server("srv-3", "Down"));
        Map<String, LatencyTester.Result> results = Map.of(
                "srv-1", new LatencyTester.Result(42, true),
                "srv-2", LatencyTester.Result.notMeasured(),
                "srv-3", new LatencyTester.Result(-1, true));
        LatencyTester tester = new LatencyTester() {
            @Override
            public CompletableFuture<Result> measure(ServerConfig server) {
                return CompletableFuture.completedFuture(results.get(server.getId()));
            }
        };
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, tester, null, new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThat(svc.measureLatency(null))
                .extracting(LatencyResult::serverId, LatencyResult::latencyMs,
                        LatencyResult::outcome)
                .containsExactly(
                        tuple("srv-1", 42L, LatencyResult.MEASURED),
                        tuple("srv-2", -1L, LatencyResult.NOT_MEASURED),
                        tuple("srv-3", -1L, LatencyResult.UNREACHABLE));
    }

    /**
     * A mode changed in Settings applies at the next start. get_status gave
     * the settings' mode, so an agent read the new one as running.
     */
    @Test
    void getStatus_saysTheModeTheRunningCoreUses() {
        ConnectionService running = new ConnectionService(null, null, null,
                SingBoxEngine.withoutCore()) {
            @Override
            public Optional<ProxyMode> runningMode() {
                return Optional.of(ProxyMode.TUN);
            }
        };
        StatusInfo status = new DefaultAppControlService(store, null, null, null, running,
                null, null, new SingBoxEngine(tempDir.resolve("sing-box"))).getStatus();

        assertThat(status.proxyMode()).as("the settings' mode")
                .isEqualTo(ProxyMode.SYSTEM_PROXY.getValue());
        assertThat(status.runningProxyMode()).as("the running core's")
                .isEqualTo(ProxyMode.TUN.getValue());
    }

    @Test
    void getStatus_namesNoRunningModeWithoutACore() {
        StatusInfo status = serviceWith(new RecordingConnectionService(null),
                new SingBoxEngine(tempDir.resolve("sing-box"))).getStatus();

        assertThat(status.runningProxyMode()).isNull();
    }

    /**
     * A theme, language or DNS strategy outside what the app has was stored
     * as it came. The theme and language fell back at the next start without
     * a word, and the core refused a configuration with the strategy in it.
     */
    @Test
    void setSetting_refusesAThemeLanguageOrDnsStrategyTheAppHasNot() {
        assertThatThrownBy(() -> service.setSetting("theme", stringNode("neon")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("auto, light, dark");
        assertThatThrownBy(() -> service.setSetting("language", stringNode("de")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("en, ru");
        assertThatThrownBy(() -> service.setSetting("dns_strategy", stringNode("fastest")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("prefer_ipv4, prefer_ipv6, ipv4_only, ipv6_only");
        assertThat(store.getSettings().getDnsStrategy()).isEqualTo("prefer_ipv4");
    }

    @Test
    void setSetting_takesThemeLanguageAndDnsStrategyInAnyCase() throws Exception {
        service.setSetting("theme", stringNode("Dark"));
        service.setSetting("dns_strategy", stringNode(" IPv4_Only "));
        SettingsInfo info = service.setSetting("theme", stringNode("system"));

        assertThat(info.theme()).as("the legacy name, as startup reads it").isEqualTo("auto");
        assertThat(store.getSettings().getDnsStrategy()).isEqualTo("ipv4_only");
    }

    /**
     * Ten probes run at a time, each of up to 5-7 s, and one fixed budget of
     * 15 s ran out at a few dozen blocked servers: the call failed with
     * "Latency test failed: null" and dropped the servers that had answered.
     */
    @Test
    void measureLatency_aListThatOutlastsTheDeadlineReportsWhatCameIn() throws Exception {
        store.addServer(server("srv-2", "Blocked"));
        CompletableFuture<LatencyTester.Result> never = new CompletableFuture<>();
        LatencyTester tester = new LatencyTester() {
            @Override
            public CompletableFuture<Result> measure(ServerConfig server) {
                return "srv-1".equals(server.getId())
                        ? CompletableFuture.completedFuture(new Result(42, true)) : never;
            }
        };
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, tester, null, new SingBoxEngine(tempDir.resolve("sing-box")));
        svc.setLatencyDeadline(java.time.Duration.ofMillis(200));

        assertThat(svc.measureLatency(null))
                .extracting(LatencyResult::serverId, LatencyResult::latencyMs,
                        LatencyResult::outcome)
                .containsExactly(
                        tuple("srv-1", 42L, LatencyResult.MEASURED),
                        tuple("srv-2", -1L, LatencyResult.TIMED_OUT));
        assertThat(never).as("not left queued on the tester's pool").isCancelled();
    }

    @Test
    void measureLatency_theDeadlineGrowsWithTheList() {
        assertThat(DefaultAppControlService.latencyDeadline(1))
                .isEqualTo(java.time.Duration.ofSeconds(15));
        assertThat(DefaultAppControlService.latencyDeadline(40))
                .isGreaterThanOrEqualTo(java.time.Duration.ofSeconds(4 * 7));
        assertThat(DefaultAppControlService.latencyDeadline(10_000))
                .isEqualTo(java.time.Duration.ofSeconds(60));
    }

    /**
     * A link's own name is cleaned of control characters and capped, and so
     * is a restored backup's; the name an agent gave went in as it came, and
     * every connect wrote it into tunl.log, where a newline forged a line.
     */
    @Test
    void addServerAndUpdateServer_cleanTheNameAsAnImportedLinkWould() throws Exception {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));
        String forged = "Tokyo\n2026-10-11 09:00:00.000 [main] INFO  c.v.service.UpdateManager"
                + " - Update verified";

        ServerSummary added = svc.addServer("vless://test-uuid@example.com:443#One", forged);
        ServerSummary renamed = svc.updateServer("srv-1", "x".repeat(10_000), null);

        assertThat(added.name()).doesNotContain("\n").startsWith("Tokyo 2026-10-11");
        assertThat(new ConfigStore(tempDir).getServerById(added.id()).orElseThrow().getName())
                .doesNotContain("\n");
        assertThat(renamed.name()).hasSize(200);
        assertThatThrownBy(() -> svc.updateServer("srv-1", "\u0000\u0007", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("name");
        assertThat(store.getServerById("srv-1").orElseThrow().getName()).hasSize(200);
    }

    /**
     * Under a Turkish locale "INFO" lowers to "ınfo", with a dotless i, and
     * the filter "info" matched none of the core's INFO lines.
     */
    @Test
    void getLogs_filtersRegardlessOfTheMachinesLocale() {
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        com.vlessclient.service.FxExecutor.run(() -> engine.getLogLines().addAll(
                "+0300 2026-10-11 09:00:00 INFO router: started",
                "+0300 2026-10-11 09:00:01 WARN dns: slow"));
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, null, engine);
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(svc.getLogs(0, "info")).singleElement().asString().contains("router");
            assertThat(svc.getLogs(0, "Started")).hasSize(1);
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    /**
     * The subscription list belongs to the FX thread, which adds to it while
     * the user works; streamed from an HTTP worker it could throw a
     * ConcurrentModificationException, as list_subscriptions already avoided.
     */
    @Test
    void refreshSubscription_readsTheListOnTheFxThread() throws Exception {
        TestSubscriptionServices.Scripted subscriptions =
                TestSubscriptionServices.scripted(tempDir.resolve("subs"));
        subscriptions.addSubscription("Provider", "https://sub.example.com/s");
        Subscription sub = subscriptions.getSubscriptions().getFirst();
        List<Boolean> readOnFx = new java.util.concurrent.CopyOnWriteArrayList<>();
        subscriptions.onListRead(
                () -> readOnFx.add(javafx.application.Platform.isFxApplicationThread()));
        DefaultAppControlService svc = new DefaultAppControlService(store, null, subscriptions,
                null, null, null, null, new SingBoxEngine(tempDir.resolve("sing-box")));

        svc.refreshSubscription(sub.getId());

        assertThat(readOnFx).isNotEmpty().containsOnly(true);
    }

    @Test
    void updateServer_renamesInPlace() throws Exception {
        ServerSummary renamed = service.updateServer("srv-1", "Kyoto", null);

        assertThat(renamed.name()).isEqualTo("Kyoto");
        assertThat(renamed.address()).isEqualTo("example.com");
        assertThat(new ConfigStore(tempDir).getServerById("srv-1").orElseThrow().getName())
                .isEqualTo("Kyoto");
    }

    /**
     * A new link replaced the server with a fresh one, which kept the id and
     * the active flag but not the subscription it came from: the stamp the
     * subscription recovers its members by after a crash between two saves.
     * An edit in the server form keeps it.
     */
    @Test
    void updateServer_aNewLinkKeepsTheIdThePickAndTheSubscription() throws Exception {
        ServerConfig existing = store.getServerById("srv-1").orElseThrow();
        existing.setSubscriptionId("sub-1");
        store.updateServer(existing);
        store.setActiveServer("srv-1");
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));

        ServerSummary relinked = svc.updateServer("srv-1", null,
                "vless://test-uuid@relinked.example.net:8443#FromLink");

        assertThat(relinked.id()).isEqualTo("srv-1");
        assertThat(relinked.active()).isTrue();
        assertThat(relinked.name()).as("the name it had").isEqualTo("Tokyo");
        assertThat(relinked.address()).isEqualTo("relinked.example.net");
        ServerConfig stored = new ConfigStore(tempDir).getServerById("srv-1").orElseThrow();
        assertThat(stored.getPort()).isEqualTo(8443);
        assertThat(stored.getSubscriptionId()).isEqualTo("sub-1");
    }

    @Test
    void updateServer_unknownIdOrBadLink_isRejected() {
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.updateServer("nope", "Kyoto", null))
                .isInstanceOf(McpToolException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> svc.updateServer("srv-1", null, "not-a-link"))
                .isInstanceOf(McpToolException.class);
        assertThat(store.getServerById("srv-1").orElseThrow().getAddress())
                .isEqualTo("example.com");
    }

    @Test
    void removeRoutingRule_removesAPresentRuleAndRefusesAnUnknownOne() throws Exception {
        RoutingService routing = com.vlessclient.service.TestRoutingServices.at(
                tempDir.resolve("routing"));
        DefaultAppControlService svc = new DefaultAppControlService(store, null, null,
                routing, null, null, new ShareLinkParser(),
                new SingBoxEngine(tempDir.resolve("sing-box")));
        String ruleId = svc.addRoutingRule("domain_suffix", "example.org", "direct")
                .rules().getFirst().id();

        assertThatThrownBy(() -> svc.removeRoutingRule("nope"))
                .isInstanceOf(McpToolException.class).hasMessageContaining("nope");
        assertThat(svc.removeRoutingRule(ruleId).rules()).isEmpty();
        assertThat(routing.getConfig().getRules()).isEmpty();
    }

    @Test
    void setProxyMode_persistsTheMode() throws Exception {
        SettingsInfo info = service.setProxyMode("tun");

        assertThat(info.proxyMode()).isEqualTo("tun");
        assertThat(new ConfigStore(tempDir).getSettings().getProxyMode())
                .isEqualTo(ProxyMode.TUN);
        assertThatThrownBy(() -> service.setProxyMode("socks"))
                .isInstanceOf(McpToolException.class);
    }

    /**
     * A private DNS-over-HTTPS resolver keeps the account in its path, which
     * the diagnostics bundle redacts; get_settings, which every agent can call
     * with changes off, returned it whole.
     */
    @Test
    void getSettings_keepsTheAccountInAResolversPathFromAgents() {
        store.getSettings().setProxyDns("https://dns.nextdns.io/ab12cd");
        store.getSettings().setDirectDns("https://d.adguard-dns.com/dns-query/SECRET-TOKEN");

        SettingsInfo info = service.getSettings();

        assertThat(info.proxyDns()).startsWith("https://dns.nextdns.io").doesNotContain("ab12cd");
        assertThat(info.directDns()).startsWith("https://d.adguard-dns.com")
                .doesNotContain("SECRET-TOKEN");
    }

    @Test
    void getSettings_showsAPlainResolverAddressAsItIs() {
        store.getSettings().setProxyDns("8.8.8.8:53");
        store.getSettings().setDirectDns(com.vlessclient.model.AppSettings.SYSTEM_DNS);

        SettingsInfo info = service.getSettings();

        assertThat(info.proxyDns()).isEqualTo("8.8.8.8:53");
        assertThat(info.directDns()).isEqualTo(com.vlessclient.model.AppSettings.SYSTEM_DNS);
    }
}
