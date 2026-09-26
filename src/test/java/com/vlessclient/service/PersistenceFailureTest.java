package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.service.mcp.McpToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceFailureTest {
    @TempDir Path dir;

    private void block(String file) throws Exception {
        Files.createDirectory(dir.resolve(file));
        Files.writeString(dir.resolve(file).resolve("blocker"), "not a writable config file");
    }

    private void unblock(String file) throws Exception {
        Files.delete(dir.resolve(file).resolve("blocker"));
        Files.delete(dir.resolve(file));
    }

    @Test
    void partialRetryKeepsOtherFailuresAndEventuallySavesTheLatestState() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        block("settings.json");
        block("servers.json");
        store.getSettings().setLanguage("ru");
        store.saveSettings(store.getSettings());
        ServerConfig server = new ServerConfig();
        server.setName("Before retry");
        store.addServer(server);
        assertThat(store.getPersistenceState().failedFiles())
                .containsExactlyInAnyOrder("settings.json", "servers.json");

        unblock("settings.json");
        store.getPersistenceState().retry();
        assertThat(store.getPersistenceState().failedFiles()).containsExactly("servers.json");

        server.setName("Latest value");
        unblock("servers.json");
        store.getPersistenceState().retry();
        assertThat(store.getPersistenceState().failedFiles()).isEmpty();
        ConfigStore restored = new ConfigStore(dir);
        assertThat(restored.getSettings().getLanguage()).isEqualTo("ru");
        assertThat(restored.getServers()).singleElement()
                .extracting(ServerConfig::getName).isEqualTo("Latest value");
    }

    @Test
    void routingSharesTheSameFailureState() throws Exception {
        PersistenceState state = new PersistenceState();
        RoutingService routing = new RoutingService(dir, state);
        block("routing.json");
        routing.saveConfig(routing.getConfig());
        assertThat(state.failedFiles()).containsExactly("routing.json");
        unblock("routing.json");
        state.retry();
        assertThat(state.failedFiles()).isEmpty();
        assertThat(Files.isRegularFile(dir.resolve("routing.json"))).isTrue();
    }

    @Test
    void subscriptionsShareTheConfigStoresFailureState() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        SubscriptionService subscriptions = new SubscriptionService(store,
                new ShareLinkParser(), dir, java.net.http.HttpClient.newHttpClient());
        block("subscriptions.json");
        subscriptions.saveSubscriptions();
        assertThat(store.getPersistenceState().failedFiles()).containsExactly("subscriptions.json");
        unblock("subscriptions.json");
        store.getPersistenceState().retry();
        assertThat(store.getPersistenceState().failedFiles()).isEmpty();
        assertThat(Files.isRegularFile(dir.resolve("subscriptions.json"))).isTrue();
    }

    @Test
    void mcpReportsTheFailedSaveAndRetriesWithoutAddingTheServerAgain() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), null);
        block("servers.json");
        assertThatThrownBy(() -> control.addServer("vless://test@example.com:443#One", null))
                .isInstanceOf(McpToolException.class).hasMessageContaining("servers.json");
        assertThat(store.getServers()).hasSize(1);
        assertThatThrownBy(control::retrySaving).isInstanceOf(McpToolException.class);
        unblock("servers.json");
        control.retrySaving();
        assertThat(new ConfigStore(dir).getServers()).hasSize(1);
    }

    /**
     * A file that could not be opened at startup is held: no save writes over
     * it, and a change to it lasts only as long as this run. An agent has to
     * hear that as it hears of a failed save, not be told the change is done.
     */
    @Test
    void mcpReportsThatAHeldFileIsNotSaved() throws Exception {
        block("servers.json");
        ConfigStore store = new ConfigStore(dir);
        DefaultAppControlService control = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), null);

        assertThatThrownBy(() -> control.addServer("vless://test@example.com:443#One", null))
                .isInstanceOf(McpToolException.class)
                .hasMessage(I18n.get("persistence.mcp.held", "servers.json"));
        assertThat(store.getServers()).as("kept for this run").hasSize(1);
        assertThat(store.getPersistenceState().failedFiles())
                .as("not a failed save: retrying cannot help").isEmpty();
        assertThatThrownBy(control::retrySaving).isInstanceOf(McpToolException.class);
        assertThat(dir.resolve("servers.json").resolve("blocker"))
                .as("and the file is left as it was").exists();
    }

    /**
     * The traffic history is not a change a tool makes: a failed write of it
     * told the agent that a server it added, and that was saved, was not.
     */
    @Test
    void mcpDoesNotBlameAToolForTheTrafficHistory() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        store.getPersistenceState().failed(TrafficHistoryStore.HISTORY_FILE, () -> { });
        DefaultAppControlService control = new DefaultAppControlService(store, null, null, null,
                null, null, new ShareLinkParser(), null);

        control.addServer("vless://test@example.com:443#One", null);

        assertThat(new ConfigStore(dir).getServers()).hasSize(1);
    }
}
