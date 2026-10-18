package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.ServerSelection;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpToolException;
import com.vlessclient.service.outbound.CoreSettings;
import com.vlessclient.testing.BundledCore;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pick of a server the bundled core's own check refuses, which
 * {@link CoreSettings#refusal} lets through: VLESS with REALITY over QUIC,
 * "uTLS is required by reality client". The start leaves it out, and the
 * pick is refused before anything stops, also after a refresh hands the
 * server out with another name, REALITY short ID and server name, as 3x-ui
 * and Marzban do at each hand-out. The refusal was kept for the server as it
 * was whole, and after such a refresh select_server reported the pick made,
 * and the pick stopped the working tunnel for a start the core refused.
 *
 * <p>The core's {@code check} is the bundled binary's; its run is a loop,
 * which no test here lets touch a route or a port it does not own.</p>
 */
@ExtendWith(FxToolkitExtension.class)
@EnabledOnOs({OS.MAC, OS.LINUX})
class RealCoreCheckRefusalTest {

    private static final String UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String PBK = "EXF012wqzEM3EpSiDItZC2K5kSEPEXyOL41gpHTnQ6I";

    @TempDir
    Path dir;

    private ConfigStore store;
    private SingBoxEngine engine;
    private ConnectionService service;
    private DefaultAppControlService control;

    /** Frankfurt as its panel hands it out, name, short ID and server name included. */
    private static ServerConfig frankfurt(String name, String shortId, String serverName) {
        ServerConfig server = new ShareLinkParser().parse("vless://" + UUID
                + "@203.0.113.11:443?type=quic&security=reality&sni=" + serverName
                + "&fp=chrome&pbk=" + PBK + "&sid=" + shortId + "#" + name);
        server.setId("srv-frankfurt");
        server.setSubscriptionId("sub-1");
        return server;
    }

    @BeforeEach
    void setUp() throws Exception {
        Path real = BundledCore.locate();
        Path core = dir.resolve("sing-box");
        Files.writeString(core, "#!/bin/bash\n"
                + "if [ \"$1\" = check ]; then exec '" + real + "' \"$@\"; fi\n"
                + "trap 'exit 0' TERM\n"
                + "echo 'sing-box started'\n"
                + "while true; do sleep 0.1; done\n");
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwxr-xr-x"));
        store = new ConfigStore(dir.resolve("data"));
        store.getSettings().setHealthCheckEnabled(false);
        store.getSettings().setProxyMode(ProxyMode.SYSTEM_PROXY);
        store.getSettings().setServerSelection(ServerSelection.SINGLE);
        engine = new SingBoxEngine(core);
        engine.setSystemProxyGuard((host, port) -> { });
        service = new ConnectionService(store, new SingBoxConfigGenerator(),
                new RoutingService(), engine);
        control = new DefaultAppControlService(store, null, null, new RoutingService(),
                service, null, null, engine);
        ServerConfig tokyo = TestServers.server().id("srv-tokyo").name("Tokyo")
                .protocol(Protocol.VLESS).address("203.0.113.9").port(443).uuid(UUID).build();
        tokyo.setSubscriptionId("sub-1");
        store.addServer(tokyo);
        store.addServer(frankfurt("Frankfurt", "ab", "a.example"));
        store.setActiveServer(tokyo.getId());
        assertThat(CoreSettings.refusal(frankfurt("Frankfurt", "ab", "a.example")))
                .as("what the app refuses of Frankfurt itself").isEmpty();
        assertThat(service.connect().skipped()).extracting(ConnectionService.SkippedServer::id)
                .as("the servers the start left out").containsExactly("srv-frankfurt");
    }

    @AfterEach
    void tearDown() {
        service.getRecoveryService().close();
        engine.stop();
        store.awaitPendingWrites(5_000);
    }

    @Test
    void selectServerOfTheServerTheCoresCheckRefusedIsRefused() {
        assertThatThrownBy(() -> control.selectServer("srv-frankfurt"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Nothing was changed");
        assertThat(engine.isRunning()).isTrue();
    }

    /** A remark that counts the traffic left, renamed at a refresh. */
    @Test
    void aRenameAtARefreshKeepsThePickRefused() {
        store.applyServerBatch(List.of(frankfurt("Frankfurt%20%7C%2011%20GB", "ab", "a.example")),
                List.of(), ConfigStore.Removal.UNASKED);

        assertThatThrownBy(() -> control.selectServer("srv-frankfurt"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Frankfurt | 11 GB");
        assertThat(engine.isRunning()).isTrue();
    }

    /** Another short ID and server name at a refresh: the pick, made in the list, stops nothing. */
    @Test
    void anotherShortIdAndServerNameAtARefreshKeepThePickRefusedBeforeAnyStop() {
        ServerConfig rotated = frankfurt("Frankfurt", "cd", "b.example");
        assertThat(CoreSettings.refusal(rotated)).isEmpty();
        store.applyServerBatch(List.of(rotated), List.of(), ConfigStore.Removal.UNASKED);

        assertThatThrownBy(() -> control.selectServer("srv-frankfurt"))
                .isInstanceOf(McpToolException.class);
        FxExecutor.run(() -> store.setActiveServer("srv-frankfurt"));
        assertThatThrownBy(service::switchToActiveServer)
                .isInstanceOf(ConfigRejectedException.class)
                .hasMessageContaining("uTLS is required by reality client");

        assertThat(engine.isRunning()).as("the working tunnel after the pick").isTrue();
        assertThat(store.getServerById("srv-tokyo").orElseThrow().isActive())
                .as("the pick put back on the server the core runs").isTrue();
    }
}
