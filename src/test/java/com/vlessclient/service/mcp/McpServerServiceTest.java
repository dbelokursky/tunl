package com.vlessclient.service.mcp;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ConfigStore;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerServiceTest {

    @TempDir
    Path tempDir;

    private McpServerService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void apply_bindFailureRollsBackEnabledSettingAndAllowsRetry() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        AppSettings settings = store.getSettings();

        int blockedPort;
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            blockedPort = blocker.getLocalPort();
            settings.setMcpPort(blockedPort);
            settings.setMcpEnabled(true);
            store.saveSettings(settings);

            service = new McpServerService(store, new FakeAppControlService());
            service.apply();

            assertThat(service.isRunning()).isFalse();
            assertThat(settings.isMcpEnabled()).isFalse();
            assertThat(service.getLastStartFailure())
                    .isEqualTo(new McpServerService.StartFailure.PortInUse(blockedPort));
            assertThat(new ConfigStore(tempDir).getSettings().isMcpEnabled()).isFalse();
        }

        settings.setMcpEnabled(true);
        store.saveSettings(settings);
        service.apply();

        assertThat(service.isRunning()).isTrue();
        assertThat(settings.isMcpEnabled()).isTrue();
        assertThat(service.getLastStartFailure()).isNull();
    }

    /**
     * The rollback above is for a click. At launch nobody clicked: a port
     * taken for one boot, such as one Windows reserves for Hyper-V, turned
     * agent access off for good, though the next launch would have bound.
     */
    @Test
    void applyAtLaunch_bindFailureKeepsTheSettingAndTheNextLaunchStarts() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        AppSettings settings = store.getSettings();

        int blockedPort;
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            blockedPort = blocker.getLocalPort();
            settings.setMcpPort(blockedPort);
            settings.setMcpEnabled(true);
            store.saveSettings(settings);

            service = new McpServerService(store, new FakeAppControlService());
            service.applyAtLaunch();

            assertThat(service.isRunning()).isFalse();
            assertThat(service.getLastStartFailure())
                    .isEqualTo(new McpServerService.StartFailure.PortInUse(blockedPort));
            assertThat(settings.isMcpEnabled()).isTrue();
            assertThat(new ConfigStore(tempDir).getSettings().isMcpEnabled()).isTrue();
        }

        McpServerService nextLaunch = new McpServerService(new ConfigStore(tempDir),
                new FakeAppControlService());
        try {
            nextLaunch.applyAtLaunch();
            assertThat(nextLaunch.isRunning()).isTrue();
        } finally {
            nextLaunch.stop();
        }
    }

    @Test
    void applicationShutdownStopsServerAndReleasesPort() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        AppSettings settings = store.getSettings();
        int port;
        try (ServerSocket available = new ServerSocket()) {
            available.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = available.getLocalPort();
        }
        settings.setMcpPort(port);
        settings.setMcpEnabled(true);
        store.saveSettings(settings);

        service = new McpServerService(store, new FakeAppControlService());
        service.apply();
        ServiceLocator.register(McpServerService.class, service);
        assertThat(service.isRunning()).isTrue();

        ServiceLocator.stopMcpServer();

        assertThat(service.isRunning()).isFalse();
        assertThat(settings.isMcpEnabled()).isTrue();
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            assertThat(rebound.isBound()).isTrue();
        }
    }
}
