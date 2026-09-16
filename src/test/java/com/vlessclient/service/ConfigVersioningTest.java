package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ProxyMode;
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
        // The backup covered the window between reading the old file and
        // writing the new one. It holds credentials in the clear, from the
        // builds before sealing, so it goes as soon as the new file is there.
        assertThat(tempDir.resolve("servers.json.v0.bak")).doesNotExist();
    }

    /**
     * The backup is the only copy of the old file until the new one lands, so
     * a save that failed must not take it: disk full, a locked file, a
     * read-only directory all end here.
     */
    @Test
    void aFailedSaveKeepsTheLegacyBackup() throws Exception {
        Files.writeString(tempDir.resolve("servers.json"), LEGACY_SERVERS);
        ConfigStore store = store();
        assertThat(tempDir.resolve("servers.json.v0.bak")).exists();

        // A directory where the file belongs: the write cannot land.
        Files.delete(tempDir.resolve("servers.json"));
        Files.createDirectory(tempDir.resolve("servers.json"));
        Files.writeString(tempDir.resolve("servers.json").resolve("blocker"), "not a file");

        ServerConfig added = new ServerConfig();
        added.setName("new");
        added.setAddress("192.0.2.2");
        added.setPort(443);
        added.setUuid("u2");
        store.addServer(added);

        assertThat(store.getPersistenceState().failedFiles()).contains("servers.json");
        assertThat(tempDir.resolve("servers.json.v0.bak"))
                .as("the only copy of the old file, while the new one is not there")
                .exists();
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

    /**
     * A new install starts with MCP configuration changes off. A settings file
     * that already allows them keeps them: that may be the old default rather
     * than a choice, but an agent set up on it must not stop working on update.
     */
    @Test
    void mcpChangesAreOffUnlessTheSettingsFileAllowsThem() throws Exception {
        assertThat(store().getSettings().isMcpAllowMutations()).isFalse();

        Files.writeString(tempDir.resolve("settings.json"), "{ \"mcp_allow_mutations\": true }");

        assertThat(store().getSettings().isMcpAllowMutations()).isTrue();
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

    /**
     * A servers.json from a newer build can name a protocol, or a transport,
     * this one has never heard of. Jackson failed the whole list on that one
     * entry, the file went to quarantine and the user was left with no servers
     * at all. The path is real today: run dev-latest, roll back to a release.
     */
    @Test
    void anEntryFromANewerBuildIsSkippedAndTheOthersStillLoad() throws Exception {
        Files.writeString(tempDir.resolve("servers.json"), """
                { "config_version": 1, "servers": [
                    { "id": "s-1", "name": "known", "protocol": "vless",
                      "address": "192.0.2.1", "port": 443, "uuid": "u-1" },
                    { "id": "s-2", "name": "newer protocol", "protocol": "tuic",
                      "address": "192.0.2.2", "port": 443, "uuid": "u-2" },
                    { "id": "s-3", "name": "newer transport", "protocol": "vless",
                      "transport": "xhttp", "address": "192.0.2.3", "port": 443,
                      "uuid": "u-3" },
                    { "id": "s-4", "name": "also known", "protocol": "trojan",
                      "address": "192.0.2.4", "port": 443, "password": "p" } ] }""");

        ConfigStore store = store();

        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("known", "also known");
        assertThat(tempDir.resolve("servers.json"))
                .as("the file still reads, so nothing is quarantined").exists();
    }

    /**
     * The same for a scalar: a proxy mode this build does not know must not
     * cost the user every other setting in the file. The system proxy is the
     * safe reading -- it is the default and needs no elevation.
     */
    @Test
    void anUnknownProxyModeFallsBackToTheSystemProxyAndKeepsTheFile() throws Exception {
        Files.writeString(tempDir.resolve("settings.json"),
                "{ \"config_version\": 1, \"proxy_mode\": \"split_tunnel\","
                        + " \"language\": \"ru\" }");

        ConfigStore store = store();

        assertThat(store.getSettings().getProxyMode()).isEqualTo(ProxyMode.SYSTEM_PROXY);
        assertThat(store.getSettings().getLanguage())
                .as("the rest of the file survives the unknown value").isEqualTo("ru");
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
        // Subscription URLs carry the account token, so the same applies here.
        assertThat(tempDir.resolve("subscriptions.json.v0.bak")).doesNotExist();
    }
}
