package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.ServerConfig;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the data files hold that this build cannot use survives it: the app
 * starts, says what it left out, and no save writes over what it could not
 * read.
 */
class DataFileSurvivalTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** What a truncated file, or one emptied by a backup tool or a shell, holds. */
    private static final List<String> NO_VALUE = List.of("", "   \n", "null");

    @TempDir
    Path dir;

    private List<Path> setAside(String name) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(name + ".corrupt-"))
                    .toList();
        }
    }

    private void clearDir() throws IOException {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private static ServerConfig server(String name) {
        ServerConfig server = new ServerConfig();
        server.setName(name);
        server.setProtocol(Protocol.VLESS);
        server.setAddress("192.0.2.10");
        server.setPort(443);
        server.setUuid("u-" + name);
        return server;
    }

    private JsonNode read(String name) throws IOException {
        return JSON.readTree(Files.readString(dir.resolve(name)));
    }

    @Test
    @DisplayName("an empty or null settings.json, traffic history or routing.json no longer stops "
            + "the app from starting")
    void filesWithNoValueAreSetAsideAndTheAppStarts() throws IOException {
        for (String content : NO_VALUE) {
            clearDir();
            Files.writeString(dir.resolve("settings.json"), content, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("traffic-history.json"), content,
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("routing.json"), content, StandardCharsets.UTF_8);

            ConfigStore[] store = new ConfigStore[1];
            assertThatCode(() -> store[0] = new ConfigStore(dir))
                    .as("settings.json holding '%s'", content).doesNotThrowAnyException();
            PersistenceState persistence = store[0].getPersistenceState();
            assertThatCode(() -> new TrafficHistoryStore(dir, Clock.systemUTC(), persistence))
                    .as("traffic-history.json holding '%s'", content)
                    .doesNotThrowAnyException();
            assertThatCode(() -> new RoutingService(dir, persistence))
                    .as("routing.json holding '%s'", content).doesNotThrowAnyException();

            assertThat(store[0].getSettings().getProxyMode()).as("defaults").isNotNull();
            assertThat(persistence.setAsideLocations())
                    .as("each is set aside and named to the user, for '%s'", content)
                    .containsOnlyKeys("settings.json", "traffic-history.json", "routing.json");
        }
    }

    @Test
    @DisplayName("an empty servers.json is set aside with a notice, not read as an empty list")
    void anEmptyServersFileIsReported() throws IOException {
        Files.writeString(dir.resolve("servers.json"), "", StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);

        assertThat(store.getServers()).isEmpty();
        assertThat(store.getPersistenceState().setAsideLocations()).containsKey("servers.json");
    }

    @Test
    @DisplayName("a servers.json with no list is set aside before the next save can replace it")
    void aServersFileWithNoListIsSetAside() throws IOException {
        String original = "{\"config_version\":1,\"servers\":{\"tokyo\":{\"name\":\"Tokyo\"}}}";
        Files.writeString(dir.resolve("servers.json"), original, StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);
        store.addServer(server("added"));

        List<Path> aside = setAside("servers.json");
        assertThat(aside).hasSize(1);
        assertThat(aside.get(0)).as("the original bytes survive the save").hasContent(original);
        assertThat(store.getPersistenceState().setAsideLocations()).containsKey("servers.json");
    }

    @Test
    @DisplayName("a servers.json a newer build wrote in another shape stays where it is, unsaved over")
    void aNewerServersFileWithNoListIsHeld() throws IOException {
        String original = "{\"config_version\":7,\"servers\":{\"items\":[{\"name\":\"Tokyo\"}]}}";
        Path file = dir.resolve("servers.json");
        Files.writeString(file, original, StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);
        store.addServer(server("added"));

        assertThat(file).as("there for the newer build after an upgrade").hasContent(original);
        assertThat(setAside("servers.json")).isEmpty();
        assertThat(store.getPersistenceState().heldReasons()).containsKey("servers.json");
    }

    @Test
    @DisplayName("a subscriptions.json with no list is set aside too")
    void aSubscriptionsFileWithNoListIsSetAside() throws IOException {
        String original = "{\"config_version\":1,\"subscriptions\":null}";
        Files.writeString(dir.resolve("subscriptions.json"), original, StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);

        try (HttpClient client = HttpClient.newHttpClient()) {
            new SubscriptionService(store, new ShareLinkParser(), dir, client).saveSubscriptions();
        }

        List<Path> aside = setAside("subscriptions.json");
        assertThat(aside).hasSize(1);
        assertThat(aside.get(0)).hasContent(original);
    }

    @Test
    @DisplayName("a damaged file that cannot be moved aside is held, so no save writes over it")
    void aDamagedFileThatWillNotMoveIsHeld() throws IOException {
        // A name with no room left for ".corrupt-<millis>": every OS refuses
        // the rename, as Windows does for a file an editor still holds.
        String name = "s".repeat(240) + ".json";
        Path file = dir.resolve(name);
        Files.writeString(file, "{\"servers\":[", StandardCharsets.UTF_8);
        PersistenceState persistence = new PersistenceState();

        ConfigStore.setAsideDamaged(file, name, persistence);

        assertThat(file).as("still there, as it was").hasContent("{\"servers\":[");
        assertThat(persistence.isHeld(name)).as("and held from saves").isTrue();
        assertThat(persistence.heldReasons().get(name)).contains("could not be moved");
    }

    @Test
    @DisplayName("server entries a newer build wrote are written back by every save")
    void unreadableServerEntriesSurviveSaves() throws IOException {
        Files.writeString(dir.resolve("servers.json"), """
                { "config_version": 1, "servers": [
                    { "id": "s-1", "name": "known", "protocol": "vless",
                      "address": "192.0.2.1", "port": 443, "uuid": "u-1" },
                    { "id": "s-2", "name": "newer protocol", "protocol": "tuic",
                      "address": "192.0.2.2", "port": 443, "uuid": "u-2",
                      "congestion": "bbr" } ] }""", StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);
        store.addServer(server("added"));
        store.addServer(server("added again"));

        JsonNode servers = read("servers.json").path("servers");
        assertThat(servers.valueStream().map(s -> s.path("name").asString()))
                .containsExactly("known", "added", "added again", "newer protocol");
        JsonNode kept = servers.get(3);
        assertThat(kept.path("protocol").asString()).isEqualTo("tuic");
        assertThat(kept.path("congestion").asString()).as("as it was read").isEqualTo("bbr");
        assertThat(store.getPersistenceState().unreadableEntries())
                .as("the notice stays: the entries are still not shown")
                .containsEntry("servers.json", 1);
    }

    /**
     * A null in a list, from a hand edit or a merge tool, read as no entry at
     * all, and each store's load then dereferenced it: the app did not start
     * until the file was deleted by hand.
     */
    @Test
    @DisplayName("a null entry in servers.json, subscriptions.json or the traffic history is "
            + "skipped, and the app starts with the rest")
    void aNullEntryIsSkippedAndTheRestLoads() throws IOException {
        Files.writeString(dir.resolve("servers.json"), """
                {"config_version":1,"servers":[null,
                  {"id":"s-1","name":"known","protocol":"vless",
                   "address":"192.0.2.1","port":443,"uuid":"u-1"},
                  {"id":"s-2","name":"second","protocol":"vless",
                   "address":"192.0.2.2","port":443,"uuid":"u-2"}]}""", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subscriptions.json"), """
                {"config_version":1,"subscriptions":[null,
                  {"id":"a","name":"Work","url":"https://sub.example/a"},
                  {"id":"b","name":"Home","url":"https://sub.example/b"}]}""",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("traffic-history.json"), """
                {"version":1,"days":[null,{"date":"2026-10-01","servers":[null,
                  {"serverId":"s-1","serverName":"known","upload":1,"download":2}]}]}""",
                StandardCharsets.UTF_8);
        Clock day = Clock.fixed(java.time.Instant.parse("2026-10-01T12:00:00Z"),
                java.time.ZoneOffset.UTC);

        ConfigStore store = new ConfigStore(dir);
        SubscriptionService subscriptions;
        try (HttpClient client = HttpClient.newHttpClient()) {
            subscriptions = new SubscriptionService(store, new ShareLinkParser(), dir, client);
        }
        TrafficHistoryStore history = new TrafficHistoryStore(dir, day,
                store.getPersistenceState());

        assertThat(store.getServers()).extracting(ServerConfig::getName)
                .containsExactly("known", "second");
        assertThat(subscriptions.getSubscriptions())
                .extracting(com.vlessclient.model.Subscription::getName)
                .containsExactly("Work", "Home");
        assertThat(history.totalRecorded()).isEqualTo(3);
        assertThat(history.lastDays(1)).singleElement()
                .extracting(TrafficHistoryStore.DayTotal::total).isEqualTo(3L);
        assertThat(history.serversForDay(java.time.LocalDate.parse("2026-10-01")))
                .extracting(TrafficHistoryStore.ServerTotal::serverName).containsExactly("known");
        // The day that held the null takes a sample: it is today, and record
        // walks that day's rows.
        history.record(store.getServers().getFirst(), 10, 20);
        assertThat(history.totalRecorded()).isEqualTo(33);
        assertThat(store.getPersistenceState().unreadableEntries())
                .as("a null is no entry from a newer build to keep and announce").isEmpty();

        store.addServer(server("added"));
        subscriptions.saveSubscriptions();
        assertThat(read("servers.json").path("servers").valueStream()
                .map(s -> s.path("name").asString()))
                .as("the null holds nothing, so the save does not write it back")
                .containsExactly("known", "second", "added");
        assertThat(read("subscriptions.json").path("subscriptions").valueStream()
                .map(s -> s.path("name").asString())).containsExactly("Work", "Home");
    }

    /**
     * An entry without a protocol loaded, and the check every connect runs on
     * every server threw on it: no connect worked, whichever server was
     * picked. Refused, it is left out like any server the core cannot build.
     */
    @Test
    @DisplayName("a servers.json entry without a protocol is refused, not thrown on")
    void aServerWithoutAProtocolIsRefused() throws IOException {
        Files.writeString(dir.resolve("servers.json"), """
                {"config_version":1,"servers":[
                  {"id":"s-1","name":"known","protocol":"vless",
                   "address":"192.0.2.1","port":443,"uuid":"u-1"},
                  {"id":"s-2","name":"no protocol",
                   "address":"192.0.2.2","port":443,"uuid":"u-2"}]}""",
                StandardCharsets.UTF_8);

        List<ServerConfig> servers = List.copyOf(new ConfigStore(dir).getServers());

        assertThat(servers).hasSize(2);
        assertThat(com.vlessclient.service.outbound.CoreSettings.refusal(servers.get(0)))
                .isEmpty();
        assertThat(com.vlessclient.service.outbound.CoreSettings.refusal(servers.get(1)))
                .get().extracting(com.vlessclient.service.outbound.CoreSettings.Refusal::reason)
                .isEqualTo(com.vlessclient.app.I18n.get("refusal.protocol"));
    }

    /**
     * "proxy_mode": null, from a hand edit or a merge tool, never reached the
     * reading that takes any value it does not know for the system proxy: it
     * loaded as no mode, and every connect threw on it until the file was
     * fixed by hand.
     */
    @Test
    @DisplayName("a settings.json whose proxy_mode is null starts in the system proxy mode")
    void aNullProxyModeReadsAsTheDefault() throws IOException {
        Files.writeString(dir.resolve("settings.json"),
                "{\"config_version\":1,\"proxy_mode\":null,\"socks_port\":7890}",
                StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);
        AppSettings settings = store.getSettings();

        assertThat(settings.getProxyMode()).isEqualTo(ProxyMode.SYSTEM_PROXY);
        assertThat(settings.getSocksPort()).as("the other settings, as read").isEqualTo(7890);
        assertThat(store.getPersistenceState().setAsideLocations()).isEmpty();
        assertThatCode(() -> new SingBoxConfigGenerator().generate(server("known"), settings))
                .as("what each connect generates").doesNotThrowAnyException();
        store.saveSettings(settings);
        assertThat(read("settings.json").path("proxy_mode").asString()).isEqualTo("system_proxy");
    }

    @Test
    @DisplayName("subscription entries a newer build wrote are written back too")
    void unreadableSubscriptionEntriesSurviveSaves() throws IOException {
        Files.writeString(dir.resolve("subscriptions.json"), """
                {"config_version":1,"subscriptions":[
                  {"id":"a","name":"Work","url":"https://sub.example/a"},
                  {"id":"b","name":"Home","url":"https://sub.example/b",
                   "lastRefreshedAt":"yesterday"}
                ]}
                """, StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);

        try (HttpClient client = HttpClient.newHttpClient()) {
            new SubscriptionService(store, new ShareLinkParser(), dir, client).saveSubscriptions();
        }

        assertThat(read("subscriptions.json").path("subscriptions").valueStream()
                .map(s -> s.path("name").asString())).containsExactly("Work", "Home");
    }

    @Test
    @DisplayName("fields a newer build wrote at the top of servers.json and subscriptions.json "
            + "survive a save")
    void envelopeFieldsSurviveSaves() throws IOException {
        Files.writeString(dir.resolve("servers.json"),
                "{\"config_version\":1,\"pinned_order\":[\"s-1\"],\"servers\":[]}",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subscriptions.json"),
                "{\"config_version\":1,\"subscriptions\":[],\"refresh_policy\":{\"hours\":6}}",
                StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);

        store.addServer(server("added"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            new SubscriptionService(store, new ShareLinkParser(), dir, client).saveSubscriptions();
        }

        JsonNode servers = read("servers.json");
        assertThat(servers.path("pinned_order").get(0).asString()).isEqualTo("s-1");
        assertThat(servers.path("servers")).hasSize(1);
        assertThat(servers.path("config_version").asInt()).isEqualTo(1);
        assertThat(read("subscriptions.json").path("refresh_policy").path("hours").asInt())
                .isEqualTo(6);
    }

    @Test
    @DisplayName("a field a newer build wrote on a health-check target survives a settings save")
    void healthCheckTargetFieldsSurviveSaves() throws IOException {
        Files.writeString(dir.resolve("settings.json"), """
                {"config_version":1,"health_check_targets":[
                  {"name":"Mail","url":"https://mail.example","enabled":false}]}
                """, StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);

        AppSettings settings = store.getSettings();
        store.saveSettings(settings);

        JsonNode target = read("settings.json").path("health_check_targets").get(0);
        assertThat(target.path("name").asString()).isEqualTo("Mail");
        assertThat(target.path("enabled").isBoolean()).as("kept, not dropped").isTrue();
        assertThat(target.path("enabled").asBoolean()).isFalse();
    }
}
