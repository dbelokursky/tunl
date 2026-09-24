package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which stored servers belong to a subscription, across a quit that came
 * between a refresh saving the servers and saving the subscription list.
 *
 * <p>A refresh writes {@code servers.json} first and {@code subscriptions.json}
 * after it. When only the first write made it to disk, the list still named
 * the servers from before the refresh: the new ones belonged to nothing, the
 * next refresh added them again, and deleting the subscription left them
 * behind. Each test here plays that quit out by putting the list back as it
 * was before the refresh and starting the services again from disk.</p>
 */
class SubscriptionMembershipTest {

    private static final String ONE = "vless://uuid1@one.example:443?security=tls&type=tcp#One\n";
    private static final String TWO = "vless://uuid2@two.example:443?security=tls&type=tcp#Two\n";

    @TempDir
    Path dataDir;

    @Test
    void aRefreshWhoseListWasNeverSavedAddsNoDuplicatesNextTime() throws Exception {
        String subscriptionId = refreshThenLoseTheListSave();

        Services restarted = start();
        restarted.subscriptions.setFetchedContent(ONE + TWO);
        restarted.subscriptions.refreshSubscription(subscriptionId);

        assertThat(restarted.config.getServers()).extracting(ServerConfig::getAddress)
                .containsExactlyInAnyOrder("one.example", "two.example");
        assertThat(restarted.subscription().getServerIds()).hasSize(2);
    }

    @Test
    void removingTheSubscriptionRemovesTheServersItsListMissed() throws Exception {
        String subscriptionId = refreshThenLoseTheListSave();

        Services restarted = start();
        restarted.subscriptions.removeSubscription(subscriptionId);

        assertThat(restarted.config.getServers()).isEmpty();
    }

    @Test
    void theListIsWholeAgainAsSoonAsTheServicesStart() throws Exception {
        refreshThenLoseTheListSave();

        Services restarted = start();

        assertThat(restarted.subscription().getServerIds())
                .containsExactlyInAnyOrderElementsOf(
                        restarted.config.getServers().stream().map(ServerConfig::getId).toList());
    }

    /**
     * Servers saved by a build that did not stamp them still match what the
     * provider sends: the stamp is not part of what makes two servers the same.
     */
    @Test
    void anUnstampedServerFromAnOlderBuildIsMatchedAndStamped() {
        Services services = start();
        services.subscriptions.setFetchedContent(ONE);
        services.subscriptions.addSubscription("Provider", "https://example.com/sub");
        ServerConfig stored = services.config.getServers().getFirst();
        stored.setSubscriptionId(null);

        services.subscriptions.refreshSubscription(services.subscription().getId());

        assertThat(services.config.getServers()).hasSize(1);
        ServerConfig refreshed = services.config.getServers().getFirst();
        assertThat(refreshed.getId()).isEqualTo(stored.getId());
        assertThat(refreshed.getSubscriptionId()).isEqualTo(services.subscription().getId());
    }

    @Test
    void aDuplicateIsTheUsersOwnServer() {
        Services services = start();
        services.subscriptions.setFetchedContent(ONE);
        services.subscriptions.addSubscription("Provider", "https://example.com/sub");
        ServerConfig original = services.config.getServers().getFirst();
        assertThat(original.getSubscriptionId()).isEqualTo(services.subscription().getId());

        services.config.duplicateServer(original.getId());
        services.subscriptions.setFetchedContent(TWO);
        services.subscriptions.refreshSubscription(services.subscription().getId());

        // The provider withdrew One: its server goes, the user's copy of it stays.
        assertThat(services.config.getServers()).extracting(ServerConfig::getAddress)
                .containsExactlyInAnyOrder("one.example", "two.example");
        ServerConfig copy = services.config.getServers().stream()
                .filter(s -> s.getAddress().equals("one.example")).findFirst().orElseThrow();
        assertThat(copy.getSubscriptionId()).isNull();
    }

    /**
     * Adds a subscription serving One, refreshes it to serve One and Two,
     * then puts {@code subscriptions.json} back as it was before the refresh,
     * the way a quit between the refresh's two saves leaves it.
     */
    private String refreshThenLoseTheListSave() throws Exception {
        Services services = start();
        services.subscriptions.setFetchedContent(ONE);
        services.subscriptions.addSubscription("Provider", "https://example.com/sub");
        String id = services.subscription().getId();
        byte[] listBeforeTheRefresh = Files.readAllBytes(dataDir.resolve("subscriptions.json"));

        services.subscriptions.setFetchedContent(ONE + TWO);
        services.subscriptions.refreshSubscription(id);
        assertThat(services.config.getServers()).hasSize(2);

        Files.write(dataDir.resolve("subscriptions.json"), listBeforeTheRefresh);
        return id;
    }

    private Services start() {
        ConfigStore config = new ConfigStore(dataDir);
        return new Services(config, new FetchingNothing(config, dataDir));
    }

    private record Services(ConfigStore config, FetchingNothing subscriptions) {
        Subscription subscription() {
            return subscriptions.getSubscriptions().getFirst();
        }
    }

    /** Serves whatever the test set instead of fetching. */
    private static final class FetchingNothing extends SubscriptionService {

        private String content = "";

        FetchingNothing(ConfigStore config, Path dataDir) {
            super(config, new ShareLinkParser(), dataDir, java.net.http.HttpClient.newHttpClient());
        }

        void setFetchedContent(String links) {
            this.content = links;
        }

        @Override
        String fetchContent(String url) {
            return content;
        }
    }
}
