package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {

    @TempDir
    Path tempDir;

    private ConfigStore store;

    @BeforeEach
    void setUp() {
        store = new ConfigStore(tempDir);
    }

    @Test
    void addServer_persistsToDiskAndLoadable() {
        ServerConfig server = createTestServer("Test Server");
        store.addServer(server);

        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getName()).isEqualTo("Test Server");
    }

    /**
     * The first-run dead end: `active` defaults to false and no import path
     * sets it, so a user who added their only server had no active server —
     * Connect then failed with "mark it active", a gesture the UI never offers.
     */
    @Test
    void addServer_activatesTheFirstServerSoAFreshInstallCanConnect() {
        store.addServer(createTestServer("First"));

        assertThat(store.getServers().get(0).isActive()).isTrue();
        // Survives a restart — the flag has to reach disk, not just memory.
        assertThat(new ConfigStore(tempDir).getServers().get(0).isActive()).isTrue();
    }

    @Test
    void addServer_doesNotStealActivationFromAnAlreadyActiveServer() {
        store.addServer(createTestServer("First"));
        store.addServer(createTestServer("Second"));

        assertThat(store.getServers().get(0).isActive()).isTrue();
        assertThat(store.getServers().get(1).isActive()).isFalse();
    }

    @Test
    void setActiveServer_persistsTheChoiceAndKeepsExactlyOneActive() {
        ServerConfig first = createTestServer("First");
        ServerConfig second = createTestServer("Second");
        store.addServer(first);
        store.addServer(second);

        store.setActiveServer(second.getId());

        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServers())
                .filteredOn(ServerConfig::isActive)
                .extracting(ServerConfig::getName)
                .containsExactly("Second");
    }

    @Test
    void setActiveServer_firesAListChangeSoTheConnectButtonCanReact() {
        ServerConfig first = createTestServer("First");
        ServerConfig second = createTestServer("Second");
        store.addServer(first);
        store.addServer(second);
        boolean[] notified = {false};
        store.getServers().addListener(
                (javafx.collections.ListChangeListener<ServerConfig>) c -> notified[0] = true);

        store.setActiveServer(second.getId());

        assertThat(notified[0]).isTrue();
    }

    @Test
    void updateServer_modifiesExistingServer() {
        ServerConfig server = createTestServer("Original");
        store.addServer(server);

        server.setName("Updated");
        store.updateServer(server);

        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getName()).isEqualTo("Updated");
    }

    @Test
    void removeServer_deletesServer() {
        ServerConfig server = createTestServer("To Remove");
        store.addServer(server);
        assertThat(store.getServers()).hasSize(1);

        store.removeServer(server.getId());

        assertThat(store.getServers()).isEmpty();

        ConfigStore reloaded = new ConfigStore(tempDir);
        assertThat(reloaded.getServers()).isEmpty();
    }

    @Test
    void duplicateServer_createsCopyWithNewIdAndCopySuffix() {
        ServerConfig server = createTestServer("My Server");
        server.setPort(8443);
        server.setAddress("example.com");
        store.addServer(server);

        store.duplicateServer(server.getId());

        assertThat(store.getServers()).hasSize(2);
        ServerConfig copy = store.getServers().get(1);
        assertThat(copy.getName()).isEqualTo("My Server (copy)");
        assertThat(copy.getId()).isNotEqualTo(server.getId());
        assertThat(copy.getAddress()).isEqualTo("example.com");
        assertThat(copy.getPort()).isEqualTo(8443);
        assertThat(copy.isActive()).isFalse();
    }

    @Test
    void saveAndLoadRoundTrip_preservesAllFields() {
        ServerConfig server = createTestServer("Full Server");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("10.0.0.1");
        server.setPort(443);
        server.setUuid("test-uuid-value");
        server.setEncryption("none");
        server.setFlow("xtls-rprx-vision");
        server.setActive(true);
        server.getTls().setEnabled(true);
        server.getTls().setServerName("sni.example.com");
        server.getTls().setReality(true);
        server.getTls().setRealityPublicKey("pub-key-123");
        server.getTls().setRealityShortId("short-id");
        server.getTransport().setPath("/ws");
        server.getTransport().setHost("ws.example.com");
        store.addServer(server);

        AppSettings settings = new AppSettings();
        settings.setTheme("dark");
        settings.setLanguage("ru");
        settings.setAutoConnect(true);
        settings.setSocksPort(2080);
        settings.setHttpPort(2081);
        settings.setClashApiPort(9091);
        settings.setProxyMode(ProxyMode.TUN);
        store.saveSettings(settings);

        ConfigStore reloaded = new ConfigStore(tempDir);

        assertThat(reloaded.getServers()).hasSize(1);
        ServerConfig loaded = reloaded.getServers().get(0);
        assertThat(loaded.getId()).isEqualTo(server.getId());
        assertThat(loaded.getName()).isEqualTo("Full Server");
        assertThat(loaded.getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(loaded.getAddress()).isEqualTo("10.0.0.1");
        assertThat(loaded.getPort()).isEqualTo(443);
        assertThat(loaded.getUuid()).isEqualTo("test-uuid-value");
        assertThat(loaded.getEncryption()).isEqualTo("none");
        assertThat(loaded.getFlow()).isEqualTo("xtls-rprx-vision");
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getTls().isEnabled()).isTrue();
        assertThat(loaded.getTls().getServerName()).isEqualTo("sni.example.com");
        assertThat(loaded.getTls().isReality()).isTrue();
        assertThat(loaded.getTls().getRealityPublicKey()).isEqualTo("pub-key-123");
        assertThat(loaded.getTls().getRealityShortId()).isEqualTo("short-id");
        assertThat(loaded.getTransport().getPath()).isEqualTo("/ws");
        assertThat(loaded.getTransport().getHost()).isEqualTo("ws.example.com");

        AppSettings loadedSettings = reloaded.getSettings();
        assertThat(loadedSettings.getTheme()).isEqualTo("dark");
        assertThat(loadedSettings.getLanguage()).isEqualTo("ru");
        assertThat(loadedSettings.isAutoConnect()).isTrue();
        assertThat(loadedSettings.getSocksPort()).isEqualTo(2080);
        assertThat(loadedSettings.getHttpPort()).isEqualTo(2081);
        assertThat(loadedSettings.getClashApiPort()).isEqualTo(9091);
        assertThat(loadedSettings.getProxyMode()).isEqualTo(ProxyMode.TUN);
    }

    @Test
    void loadingFromNonExistentDir_createsEmptyList() {
        Path nonExistent = tempDir.resolve("subdir").resolve("nested");
        ConfigStore freshStore = new ConfigStore(nonExistent);

        assertThat(freshStore.getServers()).isEmpty();
        assertThat(freshStore.getSettings()).isNotNull();
    }

    @Test
    void getServerById_returnsCorrectServerOrEmpty() {
        ServerConfig server1 = createTestServer("Server 1");
        ServerConfig server2 = createTestServer("Server 2");
        store.addServer(server1);
        store.addServer(server2);

        Optional<ServerConfig> found = store.getServerById(server1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Server 1");

        Optional<ServerConfig> notFound = store.getServerById("non-existent-id");
        assertThat(notFound).isEmpty();
    }

    private ServerConfig createTestServer(String name) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setProtocol(Protocol.VLESS);
        server.setAddress("127.0.0.1");
        server.setPort(443);
        return server;
    }
}
