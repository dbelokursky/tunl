package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.model.TransportType;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

    /**
     * A restored server is taken back only when the provider sends the same
     * server, or one under its name on its endpoint. On the weaker keys a
     * subscription's own servers are matched on, any provider's server took
     * it over: another provider fronting the same CDN address, or another
     * account on the same panel. The restored server, picked, was overwritten
     * in place with the other's name and account, and the pick went with it.
     */
    @Test
    void anotherProvidersServerOnTheSameAddressLeavesARestoredOneAlone() throws Exception {
        assertLeftAlone(
                "vless://11111111-1111-1111-1111-111111111111@cdn.example:443?security=tls"
                        + "&type=ws&host=p1.example&sni=p1.example&path=%2Fp1#Fast",
                "[P1] Fast",
                "vless://22222222-2222-2222-2222-222222222222@cdn.example:443?security=tls"
                        + "&type=ws&host=p2.example&sni=p2.example&path=%2Fp2#Fast",
                "P2");
    }

    @Test
    void anotherAccountOnTheSamePanelLeavesARestoredServerAlone() throws Exception {
        String a = LIST.lines().findFirst().orElseThrow();
        assertLeftAlone(a, "[Old] A",
                a.replace("11111111-1111", "44444444-4444"), "New");
    }

    private void assertLeftAlone(String restoredLink, String restoredName,
                                 String fetchedLink, String subscriptionName) throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        ServerConfig restored = new ShareLinkParser().parseForImport(restoredLink);
        restored.setName(restoredName);
        restored.setSubscriptionId("a-deleted-subscription");
        store.addServer(restored);
        String uuid = restored.getUuid();

        serving(store, newInstall, fetchedLink + "\n")
                .addSubscription(subscriptionName, "https://other.example/sub");

        assertThat(store.getServers()).as("the restored server and the new one").hasSize(2);
        ServerConfig kept = store.getServerById(restored.getId()).orElseThrow();
        assertThat(kept.getName()).isEqualTo(restoredName);
        assertThat(kept.getUuid()).isEqualTo(uuid);
        assertThat(kept.getSubscriptionId()).isEqualTo("a-deleted-subscription");
        assertThat(store.getServers()).filteredOn(ServerConfig::isActive)
                .extracting(ServerConfig::getId).as("the pick, where the user left it")
                .containsExactly(restored.getId());
    }

    /**
     * A server the subscription's list names is the subscription's, whatever
     * its stamp says: a backup imported over servers the subscription had
     * taken back puts their old stamps back. It was matched twice, as one of
     * the subscription's own and as a stray, and a second server the provider
     * sends under its name on its address took its id: the server was
     * overwritten and its id listed twice.
     */
    @Test
    void aServerTheListNamesIsNotTakenASecondTimeAsAStray() throws Exception {
        ConfigStore store = new ConfigStore(newInstall);
        new ServerBackupService(store, new ShareLinkParser()).importFile(backup);
        AtomicReference<String> list = new AtomicReference<>(LIST);
        SubscriptionService service = serving(store, newInstall, list::get);
        service.addSubscription("P", URL);
        Subscription sub = service.getSubscriptions().getFirst();
        List<String> takenBack = ids(store.getServers());
        // Imported again: the servers get the stamp of the old subscription back.
        new ServerBackupService(store, new ShareLinkParser()).importFile(backup);
        assertThat(store.getServers()).noneMatch(s -> sub.getId().equals(s.getSubscriptionId()));

        // A as before, B, and A over WebSocket on the same address, under the same name.
        list.set(LIST + "vless://11111111-1111-1111-1111-111111111111@a.example:443"
                + "?security=tls&type=ws&path=%2Fws#A\n");
        service.refreshSubscription(sub.getId());

        assertThat(store.getServers()).as("A, B and the new one").hasSize(3);
        assertThat(ids(store.getServers())).doesNotHaveDuplicates()
                .containsAll(takenBack);
        assertThat(store.getServerById(takenBack.getFirst()).orElseThrow()
                .getTransport().getType()).as("A, still A").isEqualTo(TransportType.TCP);
        assertThat(sub.getServerIds()).hasSize(3).doesNotHaveDuplicates();
        assertThat(store.getServers()).allMatch(s -> sub.getId().equals(s.getSubscriptionId()));
    }

    private static SubscriptionService serving(ConfigStore store, Path dir, String list) {
        return serving(store, dir, () -> list);
    }

    private static SubscriptionService serving(ConfigStore store, Path dir,
                                               Supplier<String> list) {
        return new SubscriptionService(store, new ShareLinkParser(), dir,
                HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                return list.get();
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
