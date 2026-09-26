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

        RecordingConnectionService(ServerConfig server) {
            super(null, null, null, null);
            attempt = new ConnectAttempt(Outcome.STARTED, server);
        }

        @Override
        public ConnectAttempt connect(ProxyMode modeOverride) throws IOException {
            modes.add(modeOverride);
            if (failure != null) {
                throw failure;
            }
            return attempt;
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
                new ConnectionService.ConnectAttempt(ConnectionService.Outcome.ALREADY_RUNNING,
                        active);
        DefaultAppControlService svc = serviceWith(connection,
                new SingBoxEngine(tempDir.resolve("sing-box")));

        assertThatThrownBy(() -> svc.connect(null, null, false))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("already running");
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
        assertThat(store.getSettings().isAutoConnect()).isFalse();
        assertThat(store.getSettings().getServerSelection())
                .isEqualTo(com.vlessclient.model.ServerSelection.SINGLE);
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
        LatencyTester tester = new LatencyTester() {
            @Override
            public CompletableFuture<Map<String, Result>> testAll(List<ServerConfig> servers) {
                return CompletableFuture.completedFuture(Map.of(
                        "srv-1", new Result(42, true),
                        "srv-2", Result.notMeasured(),
                        "srv-3", new Result(-1, true)));
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
        ConnectionService running = new ConnectionService(null, null, null, null) {
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
}
