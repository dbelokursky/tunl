package com.vlessclient.service.mcp;

import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
