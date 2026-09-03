package com.vlessclient.service.mcp;

import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct tests of {@link DefaultAppControlService} that don't need a running
 * JavaFX toolkit: the TUN-confirm gate, mode parsing, and server selection all
 * resolve before any FX-thread access.
 */
class DefaultAppControlServiceTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private DefaultAppControlService service;

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
        store.addServer(server("srv-1", "Tokyo"));
        SingBoxEngine engine = new SingBoxEngine(tempDir.resolve("sing-box"));
        service = new DefaultAppControlService(store, null, null, null, null, null, null, engine);
    }

    private ServerConfig server(String id, String name) {
        ServerConfig s = new ServerConfig();
        s.setId(id);
        s.setName(name);
        s.setProtocol(com.vlessclient.model.Protocol.VLESS);
        s.setAddress("example.com");
        s.setPort(443);
        return s;
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

    @Test
    void getSettings_reportsTheCoreLogLevel() {
        store.getSettings().setCoreLogLevel(CoreLogLevel.ERROR);

        assertThat(service.getSettings().coreLogLevel()).isEqualTo("error");
    }
}
