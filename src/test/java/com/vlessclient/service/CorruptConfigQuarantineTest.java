package com.vlessclient.service;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What happens to a config file the app cannot read.
 *
 * <p>Falling back to defaults is right — the app has to start. What was wrong
 * is what came next: the fallback left the damaged file in place, and the first
 * save overwrote it. Writes here are atomic ({@code SecureFiles} writes a temp
 * file and {@code ATOMIC_MOVE}s it), so a file that will not parse was damaged
 * from outside — a full disk, a half-restored backup, a sync client — and is
 * very often still repairable by hand. Was, until the app wrote over it.</p>
 *
 * <p>A file that is there and cannot be opened is another matter: nothing
 * says it is damaged. A virus scanner or a sync client holding it, or a
 * restore that left it owned by another user, used to be taken for damage,
 * so the file was moved aside and the app started empty. It is left as it
 * is now, and no save writes over it, since a save would replace data the
 * app never read with the defaults it started from.</p>
 */
class CorruptConfigQuarantineTest {

    @TempDir
    Path dir;

    private List<Path> quarantined(String prefix) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix + ".corrupt-"))
                    .toList();
        }
    }

    @Test
    @DisplayName("an unreadable servers.json is moved aside, not left to be overwritten")
    void corruptServersFileIsQuarantined() throws IOException {
        Path file = dir.resolve("servers.json");
        String damaged = "{\"version\":1,\"servers\":[{\"name\":\"Tokyo\",";
        Files.writeString(file, damaged, StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);

        assertThat(store.getServers()).as("the app still starts").isEmpty();
        List<Path> aside = quarantined("servers.json");
        assertThat(aside).hasSize(1);
        assertThat(Files.readString(aside.get(0)))
                .as("the bytes are preserved exactly, so they can still be repaired")
                .isEqualTo(damaged);
        assertThat(file).as("and the unreadable file is gone from the live path")
                .doesNotExist();
    }

    @Test
    @DisplayName("an unreadable routing.json is moved aside too")
    void corruptRoutingFileIsQuarantined() throws IOException {
        Path file = dir.resolve("routing.json");
        String damaged = "{\"sections\":[ truncated";
        Files.writeString(file, damaged, StandardCharsets.UTF_8);

        new RoutingService(dir);

        List<Path> aside = quarantined("routing.json");
        assertThat(aside).hasSize(1);
        assertThat(Files.readString(aside.get(0))).isEqualTo(damaged);
    }

    @Test
    @DisplayName("a readable file is never moved")
    void aValidFileIsLeftAlone() throws IOException {
        Path file = dir.resolve("servers.json");
        Files.writeString(file, "{\"version\":1,\"servers\":[]}", StandardCharsets.UTF_8);

        new ConfigStore(dir);

        assertThat(quarantined("servers.json"))
                .as("quarantining a healthy file would look exactly like data loss")
                .isEmpty();
        assertThat(file).exists();
    }

    /**
     * A path that is there and cannot be opened as a file. A directory is the
     * one such path every OS agrees on; a locked file (Windows) or one owned by
     * another user fails the same way, with an {@link IOException} at open.
     */
    private Path unopenable(String name) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectory(path);
        Files.writeString(path.resolve("inside"), "kept", StandardCharsets.UTF_8);
        return path;
    }

    private static void assertLeftAsItWas(Path path) {
        assertThat(path).as("left where it was").isDirectory();
        assertThat(path.resolve("inside")).as("and as it was").hasContent("kept");
    }

    @Test
    @DisplayName("a servers.json that cannot be opened stays where it is, and no save writes over it")
    void unopenableServersFileIsHeld() throws IOException {
        Path file = unopenable("servers.json");

        ConfigStore store = new ConfigStore(dir);
        store.saveServers();

        assertThat(quarantined("servers.json")).as("not taken for damage").isEmpty();
        assertLeftAsItWas(file);
        PersistenceState persistence = store.getPersistenceState();
        assertThat(persistence.heldReasons()).containsKey("servers.json");
        assertThat(persistence.failedFiles())
                .as("a held save is not a failed one: retrying cannot help")
                .isEmpty();
    }

    @Test
    @DisplayName("a settings.json that cannot be opened is not replaced by the defaults")
    void unopenableSettingsFileIsHeld() throws IOException {
        Path file = unopenable("settings.json");

        ConfigStore store = new ConfigStore(dir);
        store.saveSettings(new AppSettings());

        assertThat(quarantined("settings.json")).isEmpty();
        assertLeftAsItWas(file);
        assertThat(store.getPersistenceState().heldReasons()).containsKey("settings.json");
    }

    @Test
    @DisplayName("a subscriptions.json that cannot be opened stays where it is")
    void unopenableSubscriptionsFileIsHeld() throws IOException {
        Path file = unopenable("subscriptions.json");
        ConfigStore store = new ConfigStore(dir);

        try (HttpClient client = HttpClient.newHttpClient()) {
            new SubscriptionService(store, new ShareLinkParser(), dir, client)
                    .saveSubscriptions();
        }

        assertThat(quarantined("subscriptions.json")).isEmpty();
        assertLeftAsItWas(file);
        assertThat(store.getPersistenceState().heldReasons()).containsKey("subscriptions.json");
    }

    @Test
    @DisplayName("a routing.json that cannot be opened keeps its rules for the next start")
    void unopenableRoutingFileIsHeld() throws IOException {
        Path file = unopenable("routing.json");
        PersistenceState persistence = new PersistenceState();

        new RoutingService(dir, persistence).saveConfig(new RoutingConfig());

        assertThat(quarantined("routing.json")).isEmpty();
        assertLeftAsItWas(file);
        assertThat(persistence.heldReasons()).containsKey("routing.json");
    }

    @Test
    @DisplayName("a routing.json this user may not read is left as it is (POSIX)")
    void routingFileWithoutReadPermissionIsHeld() throws IOException {
        Path file = dir.resolve("routing.json");
        String rules = "{\"sections\":[]}";
        Files.writeString(file, rules, StandardCharsets.UTF_8);
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX permissions");
        Files.setPosixFilePermissions(file, Set.of());
        PersistenceState persistence = new PersistenceState();
        try {
            assumeFalse(Files.isReadable(file), "root reads the file anyway");

            new RoutingService(dir, persistence).saveConfig(new RoutingConfig());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }

        assertThat(quarantined("routing.json")).isEmpty();
        assertThat(file).as("the rules are there for the next start").hasContent(rules);
        assertThat(persistence.heldReasons().get("routing.json"))
                .as("the reason names the refusal")
                .contains("AccessDenied");
    }

    @Test
    @DisplayName("a damaged file set aside is named to the user with where it is now")
    void setAsideFileIsReported() throws IOException {
        Files.writeString(dir.resolve("servers.json"), "{\"servers\":[", StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);

        List<Path> aside = quarantined("servers.json");
        assertThat(aside).hasSize(1);
        PersistenceState persistence = store.getPersistenceState();
        assertThat(persistence.setAsideLocations())
                .containsExactly(entry("servers.json", aside.get(0).toString()));
        assertThat(persistence.heldReasons()).as("set aside, not held").isEmpty();

        persistence.dismissSetAside();

        assertThat(persistence.setAsideLocations()).isEmpty();
    }

    @Test
    @DisplayName("one subscription this build cannot read does not set the others aside")
    void oneUnreadableSubscriptionKeepsTheOthers() throws IOException {
        Files.writeString(dir.resolve("subscriptions.json"), """
                {"config_version":1,"subscriptions":[
                  {"id":"a","name":"Work","url":"https://sub.example/a"},
                  {"id":"b","name":"Home","url":"https://sub.example/b",
                   "lastRefreshedAt":"yesterday"}
                ]}
                """, StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);

        try (HttpClient client = HttpClient.newHttpClient()) {
            SubscriptionService service =
                    new SubscriptionService(store, new ShareLinkParser(), dir, client);
            assertThat(service.getSubscriptions()).extracting(Subscription::getName)
                    .containsExactly("Work");
        }

        assertThat(quarantined("subscriptions.json")).as("the file stays").isEmpty();
        assertThat(store.getPersistenceState().unreadableEntries())
                .as("and the entry left out is reported, as for servers.json")
                .containsEntry("subscriptions.json", 1);
    }
}
