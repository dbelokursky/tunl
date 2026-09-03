package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ServerConfig;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema versioning of the three config files: legacy pre-envelope files load
 * forever (with a one-time .v0.bak), saves write the current envelope, and a
 * file from a newer build is read best-effort instead of dropping user data.
 */
class ConfigVersioningTest {

    @TempDir
    Path tempDir;

    private static final String LEGACY_SERVERS = """
            [ { "id": "s-1", "name": "legacy", "protocol": "vless",
                "address": "192.0.2.1", "port": 443, "uuid": "legacy-uuid" } ]""";

    private ConfigStore store() {
        return new ConfigStore(tempDir);
    }

    @Test
    void legacyServersArrayLoads_backsUp_andUpgradesOnSave() throws Exception {
        Files.writeString(tempDir.resolve("servers.json"), LEGACY_SERVERS);

        ConfigStore store = store();
        assertThat(store.getServers()).hasSize(1);
        assertThat(store.getServers().get(0).getUuid()).isEqualTo("legacy-uuid");
        assertThat(tempDir.resolve("servers.json.v0.bak")).exists();

        ServerConfig added = new ServerConfig();
        added.setName("new");
        added.setAddress("192.0.2.2");
        added.setPort(443);
        added.setUuid("u2");
        store.addServer(added);

        String raw = Files.readString(tempDir.resolve("servers.json"));
        assertThat(raw).contains("\"config_version\" : 1").contains("\"servers\"");
        // The backup still holds the pre-envelope bytes for downgrades.
        assertThat(Files.readString(tempDir.resolve("servers.json.v0.bak")))
                .isEqualTo(LEGACY_SERVERS);
    }

    @Test
    void envelopeRoundTrips() {
        ConfigStore store = store();
        ServerConfig server = new ServerConfig();
        server.setName("s");
        server.setAddress("192.0.2.1");
        server.setPort(443);
        server.setUuid("u");
        store.addServer(server);

        ConfigStore reloaded = store();
        assertThat(reloaded.getServers()).hasSize(1);
        assertThat(reloaded.getServers().get(0).getUuid()).isEqualTo("u");
    }

    @Test
    void newerServersVersionIsReadBestEffort() throws Exception {
        Files.writeString(tempDir.resolve("servers.json"), """
                { "config_version": 99, "future_field": true,
                  "servers": [ { "id": "s-1", "name": "from-the-future",
                    "address": "192.0.2.1", "port": 443, "uuid": "u" } ] }""");

        ConfigStore store = store();
        assertThat(store.getServers()).hasSize(1);
        assertThat(store.getServers().get(0).getName()).isEqualTo("from-the-future");
    }

    @Test
    void settingsCarryTheCurrentVersionAfterSave() throws Exception {
        ConfigStore store = store();
        store.saveSettings(store.getSettings());

        String raw = Files.readString(tempDir.resolve("settings.json"));
        assertThat(raw).contains("\"config_version\" : " + AppSettings.CURRENT_CONFIG_VERSION);
    }

    @Test
    void legacySettingsWithoutVersionLoadAsCurrent() throws Exception {
        Files.writeString(tempDir.resolve("settings.json"),
                "{ \"theme\": \"dark\", \"language\": \"ru\" }");

        ConfigStore store = store();
        assertThat(store.getSettings().getTheme()).isEqualTo("dark");
        assertThat(store.getSettings().getConfigVersion())
                .isEqualTo(AppSettings.CURRENT_CONFIG_VERSION);
        // core_log_level arrived after v1 without bumping the version, so a
        // file written before it existed has to load at the level the core
        // used to be hardcoded to.
        assertThat(store.getSettings().getCoreLogLevel()).isEqualTo(CoreLogLevel.INFO);
    }

    @Test
    void coreLogLevelRoundTripsThroughTheSettingsFile() {
        ConfigStore store = store();
        AppSettings settings = store.getSettings();
        settings.setCoreLogLevel(CoreLogLevel.DEBUG);
        store.saveSettings(settings);

        assertThat(store().getSettings().getCoreLogLevel()).isEqualTo(CoreLogLevel.DEBUG);
    }

    @Test
    void coreLogLevelIsPersistedAsItsSingBoxString() throws Exception {
        ConfigStore store = store();
        AppSettings settings = store.getSettings();
        settings.setCoreLogLevel(CoreLogLevel.WARN);
        store.saveSettings(settings);

        assertThat(Files.readString(tempDir.resolve("settings.json")))
                .contains("\"core_log_level\" : \"warn\"");
    }

    @Test
    void unknownCoreLogLevelFallsBackToInfo() throws Exception {
        // A level this build does not know — a hand-edited file, or one
        // written by a newer build — must not fail the load or leave the core
        // with a level sing-box would reject at start-up.
        Files.writeString(tempDir.resolve("settings.json"),
                "{ \"config_version\": 1, \"core_log_level\": \"verbose\" }");

        assertThat(store().getSettings().getCoreLogLevel()).isEqualTo(CoreLogLevel.INFO);
    }

    @Test
    void legacySubscriptionsArrayLoads_backsUp_andUpgradesOnSave() throws Exception {
        Files.writeString(tempDir.resolve("subscriptions.json"), """
                [ { "id": "sub-1", "name": "legacy-sub",
                    "url": "https://example.com/sub" } ]""");

        SubscriptionService service = new SubscriptionService(
                store(), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient());
        assertThat(service.getSubscriptions()).hasSize(1);
        assertThat(service.getSubscriptions().get(0).getName()).isEqualTo("legacy-sub");
        assertThat(tempDir.resolve("subscriptions.json.v0.bak")).exists();

        service.saveSubscriptions();
        String raw = Files.readString(tempDir.resolve("subscriptions.json"));
        assertThat(raw).contains("\"config_version\" : 1").contains("\"subscriptions\"");
    }
}
