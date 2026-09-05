package com.vlessclient.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.file.Path;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(FxToolkitExtension.class)
class LiveStatusTest {
    @TempDir Path dir;
    private final ReadOnlyObjectWrapper<ConnectionState> state =
            new ReadOnlyObjectWrapper<>(ConnectionState.CONNECTED);
    private final ReadOnlyStringWrapper tag = new ReadOnlyStringWrapper();
    private final TunnelHealthState health = new TunnelHealthState();
    private DefaultAppControlService service;

    @BeforeEach
    void setUp() {
        ConfigStore store = new ConfigStore(dir, SecretSealers.disabled());
        for (String name : new String[]{"selected", "routed"}) {
            ServerConfig server = new ServerConfig();
            server.setId(name);
            server.setName(name);
            store.addServer(server);
        }
        SingBoxEngine engine = new SingBoxEngine(dir.resolve("fake-core")) {
            @Override
            public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
                return state.getReadOnlyProperty();
            }
        };
        ProxyGroupMonitor monitor = new ProxyGroupMonitor() {
            @Override
            public ReadOnlyStringProperty currentMemberTagProperty() {
                return tag.getReadOnlyProperty();
            }
        };
        service = new DefaultAppControlService(store, null, null, null, null,
                null, null, engine, health, monitor);
    }

    @Test
    void reportsRoutedMemberSeparatelyFromSavedSelectionAndExposesBrokenTraffic() {
        FxExecutor.run(() -> {
            tag.set(OutboundTags.server("routed"));
            health.set(TunnelHealth.BROKEN);
        });
        StatusInfo status = service.getStatus();
        assertThat(status.activeServerId()).isEqualTo("selected");
        assertThat(status.currentServerId()).isEqualTo("routed");
        assertThat(status.connected()).isTrue();
        assertThat(status.health()).isEqualTo("BROKEN");
        assertThat(status.tunnelStatus()).isEqualTo("NO_TRAFFIC");
        var json = JsonMapper.builder().build().valueToTree(status);
        assertThat(json.path("currentServer").asString()).isEqualTo("routed");
    }

    @Test
    void unknownCorePickDoesNotClaimThatTheSavedSelectionIsInUse() {
        FxExecutor.run(() -> health.set(TunnelHealth.DEGRADED));
        StatusInfo status = service.getStatus();
        assertThat(status.activeServerId()).isEqualTo("selected");
        assertThat(status.currentServerId()).isNull();
        assertThat(status.tunnelStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void disconnectedStateDropsStaleHealthAndRoutedMember() {
        FxExecutor.run(() -> {
            tag.set(OutboundTags.server("routed"));
            health.set(TunnelHealth.HEALTHY);
            state.set(ConnectionState.DISCONNECTED);
        });
        StatusInfo status = service.getStatus();
        assertThat(status.connected()).isFalse();
        assertThat(status.currentServerId()).isNull();
        assertThat(status.health()).isEqualTo("UNMONITORED");
        assertThat(status.tunnelStatus()).isEqualTo("DISCONNECTED");
    }
}
