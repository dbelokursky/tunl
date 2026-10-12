package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server backup carries each subscription server with the id of the
 * subscription it came from, but not the subscription. After a reinstall,
 * restoring the backup and adding the subscription again gave every server
 * twice: the new subscription's id matched none of the restored ones, so its
 * first refresh added a fresh copy of each, and the restored copies stayed
 * tied to a subscription that no longer existed, which no refresh updated
 * and no delete reached.
 */
class SubscriptionRestoredBackupTest {

    private static final String URL = "https://provider.example/sub?token=1";
    private static final String LIST =
            "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls&type=tcp#A\n"
            + "vless://22222222-2222-2222-2222-222222222222@b.example:443?security=tls&type=tcp#B\n";

    @TempDir
    Path oldInstall;

    @TempDir
    Path newInstall;

    private Path backup;

    @BeforeEach
    void exportFromTheOldInstall() throws Exception {
        ConfigStore store = new ConfigStore(oldInstall);
        serving(store, oldInstall, LIST).addSubscription("P", URL);
        assertThat(store.getServers()).hasSize(2);
        backup = Files.createTempFile(oldInstall, "servers", ".json");
        new ServerBackupService(store, new ShareLinkParser()).exportAll(backup);
    }

    @Test
    void addingTheSubscriptionAgainTakesTheRestoredServersBack() throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        new ServerBackupService(store, new ShareLinkParser()).importFile(backup);
        List<String> restored = ids(store.getServers());
        String picked = store.getServers().stream().filter(ServerConfig::isActive)
                .findFirst().orElseThrow().getId();

        SubscriptionService service = serving(store, newInstall, LIST);
        service.addSubscription("P", URL);
        Subscription sub = service.getSubscriptions().getFirst();

        assertThat(names(store)).as("one of each, not two").containsExactly("[P] A", "[P] B");
        assertThat(ids(store.getServers())).as("the restored servers, kept")
                .containsExactlyElementsOf(restored);
        assertThat(store.getServers()).allMatch(s -> sub.getId().equals(s.getSubscriptionId()));
        assertThat(sub.getServerIds()).containsExactlyInAnyOrderElementsOf(restored);
        assertThat(store.getServers()).filteredOn(ServerConfig::isActive)
                .extracting(ServerConfig::getId).as("the restored pick is kept")
                .containsExactly(picked);

        service.removeSubscription(sub.getId());
        assertThat(store.getServers()).as("deleting the subscription reaches them").isEmpty();
    }

    /**
     * A provider that rotated a credential since the backup still hands out
     * the same server, under the same name and address.
     */
    @Test
    void aRestoredServerWhoseCredentialChangedIsTakenBackToo() throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        new ServerBackupService(store, new ShareLinkParser()).importFile(backup);
        List<String> restored = ids(store.getServers());

        serving(store, newInstall, LIST.replace("11111111-1111", "33333333-3333"))
                .addSubscription("P", URL);

        assertThat(names(store)).containsExactly("[P] A", "[P] B");
        assertThat(ids(store.getServers())).containsExactlyElementsOf(restored);
        assertThat(store.getServers().getFirst().getUuid())
                .isEqualTo("33333333-3333-1111-1111-111111111111");
    }

    /**
     * Only what the new subscription's list names is taken back. A restored
     * server it no longer lists is left as it is, and so is the user's own
     * server, which no subscription ever owned, even one that matches a
     * subscription server field for field.
     */
    @Test
    void whatTheListDoesNotNameAndTheUsersOwnServersAreLeftAlone() throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        new ServerBackupService(store, new ShareLinkParser()).importFile(backup);
        // B as the provider hands it out, saved by the user as a server of their own.
        ServerConfig own = new ShareLinkParser().parseForImport(LIST.lines().toList().get(1));
        own.setName("Mine");
        store.addServer(own);
        String onlyA = LIST.lines().findFirst().orElseThrow() + "\n";

        SubscriptionService service = serving(store, newInstall, onlyA);
        service.addSubscription("P", URL);
        Subscription sub = service.getSubscriptions().getFirst();

        assertThat(names(store)).containsExactlyInAnyOrder("[P] A", "[P] B", "Mine");
        assertThat(sub.getServerIds()).hasSize(1);
        assertThat(store.getServerById(own.getId()).orElseThrow().getSubscriptionId()).isNull();
        ServerConfig b = store.getServers().stream().filter(s -> s.getName().equals("[P] B"))
                .findFirst().orElseThrow();
        assertThat(b.getSubscriptionId()).as("not taken by a list that does not name it")
                .isNotEqualTo(sub.getId())
                .isNotNull();
    }

    /**
     * A server another subscription owns is that subscription's, however
     * alike: only one whose subscription is gone is taken back.
     */
    @Test
    void anotherSubscriptionsServersAreLeftToIt() throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        SubscriptionService other = serving(store, newInstall,
                LIST.lines().findFirst().orElseThrow() + "\n");
        other.addSubscription("Other", "https://other.example/sub");
        String otherId = other.getSubscriptions().getFirst().getId();

        SubscriptionService service = serving(store, newInstall, LIST);
        service.addSubscription("P", URL);

        assertThat(names(store)).containsExactlyInAnyOrder("[Other] A", "[P] A", "[P] B");
        assertThat(store.getServers()).filteredOn(s -> s.getName().equals("[Other] A"))
                .extracting(ServerConfig::getSubscriptionId).containsExactly(otherId);
    }

    private static SubscriptionService serving(ConfigStore store, Path dir, String list) {
        return new SubscriptionService(store, new ShareLinkParser(), dir,
                HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                return list;
            }
        };
    }

    private static List<String> ids(List<ServerConfig> servers) {
        return servers.stream().map(ServerConfig::getId).toList();
    }

    private static List<String> names(ConfigStore store) {
        return store.getServers().stream().map(ServerConfig::getName).toList();
    }
}
