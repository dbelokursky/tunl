package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.mcp.DefaultAppControlService;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whose the pick is that the store makes when a removal takes the picked
 * server, by the path the removal comes from. A running tunnel follows a pick
 * of the user's at once, and leaves the store's own to the user's reconnect
 * where the restart would prompt.
 *
 * <p>Every removal made it the store's own, the user's delete of a
 * subscription with its servers too, so under a TUN core whose every launch
 * prompts the traffic went on through a server the user had removed.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class RemovalRepickTest {

    private static final String BOTH =
            "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls&type=tcp#A\n"
            + "vless://22222222-2222-2222-2222-222222222222@b.example:443?security=tls&type=tcp#B\n";
    private static final String ONLY_B =
            "vless://22222222-2222-2222-2222-222222222222@b.example:443?security=tls&type=tcp#B\n";

    @TempDir
    Path tempDir;

    private ConfigStore store;
    private SubscriptionService subscriptions;
    private volatile String served = BOTH;

    @AfterEach
    void tearDown() {
        assertThat(store.awaitPendingWrites(5_000)).isTrue();
    }

    /** One subscription of A and B, A picked, beside a server of the user's own. */
    private void subscribed() {
        store = new ConfigStore(tempDir);
        subscriptions = new SubscriptionService(store, new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                return served;
            }
        };
        subscriptions.addSubscription("P", "https://provider.example/sub");
        store.addServer(TestServers.server().id("own").name("My own").protocol(Protocol.VLESS)
                .address("mine.example").port(443)
                .uuid("33333333-3333-3333-3333-333333333333").build());
        assertThat(picked()).isEqualTo("[P] A");
    }

    private String picked() {
        return FxExecutor.get(() -> store.getServers().stream().filter(ServerConfig::isActive)
                .map(ServerConfig::getName).findFirst().orElse(null));
    }

    private boolean theStoresOwn() {
        return FxExecutor.get(store::isPickedByTheStore);
    }

    /** The provider withdrew A: nobody asked the tunnel to move. */
    @Test
    void aRefreshThatWithdrawsThePickedServerMakesThePickTheStoresOwn() {
        subscribed();
        served = ONLY_B;

        subscriptions.refreshSubscription(subscriptions.getSubscriptions().getFirst().getId());

        assertThat(picked()).isEqualTo("[P] B");
        assertThat(theStoresOwn()).isTrue();
    }

    /** Removing the subscription, confirmed on its page, is the user leaving its servers. */
    @Test
    void theUsersRemovalOfTheSubscriptionMakesThePickInItsPlaceTheUsers() {
        subscribed();

        subscriptions.removeSubscription(subscriptions.getSubscriptions().getFirst().getId());

        assertThat(picked()).isEqualTo("My own");
        assertThat(theStoresOwn()).isFalse();
    }

    /** An agent's delete_server asks nothing of the running tunnel. */
    @Test
    void anAgentsDeleteOfThePickedServerMakesThePickTheStoresOwn() throws Exception {
        subscribed();
        DefaultAppControlService control = new DefaultAppControlService(store, null, null,
                new RoutingService(), null, null, null,
                new SingBoxEngine(tempDir.resolve("sing-box")));
        String a = FxExecutor.get(() -> store.getServers().stream().filter(ServerConfig::isActive)
                .findFirst().orElseThrow().getId());

        control.deleteServer(a, true);

        assertThat(picked()).isEqualTo("[P] B");
        assertThat(theStoresOwn()).isTrue();
    }

    /** A pick of the user's ends the store's own. */
    @Test
    void aPickOfTheUsersEndsTheStoresOwn() {
        subscribed();
        served = ONLY_B;
        subscriptions.refreshSubscription(subscriptions.getSubscriptions().getFirst().getId());

        FxExecutor.run(() -> store.setActiveServer("own"));

        assertThat(theStoresOwn()).isFalse();
        assertThat(List.of(picked())).containsExactly("My own");
    }

    /**
     * The store's own pick picked again, by select_server or a click on the
     * ticked server in the tray or its row in the list, is the user's pick
     * now, and the list's listeners hear of it: nothing changed in the list,
     * so nothing heard of it, and the tunnel left on the withdrawn server
     * stayed there while the pick was answered as made.
     */
    @Test
    void theStoresOwnPickPickedAgainIsTheUsersAndHeard() {
        subscribed();
        served = ONLY_B;
        subscriptions.refreshSubscription(subscriptions.getSubscriptions().getFirst().getId());
        String b = FxExecutor.get(() -> store.getServers().stream().filter(ServerConfig::isActive)
                .findFirst().orElseThrow().getId());
        AtomicInteger changes = new AtomicInteger();
        FxExecutor.run(() -> store.getServers().addListener(
                (ListChangeListener<ServerConfig>) change -> changes.incrementAndGet()));

        FxExecutor.run(() -> store.setActiveServer(b));

        assertThat(theStoresOwn()).isFalse();
        assertThat(changes).as("changes the list's listeners heard").hasValue(1);
        assertThat(picked()).isEqualTo("[P] B");
        FxExecutor.run(() -> store.setActiveServer(b));
        assertThat(changes).as("a pick of the user's picked again changes nothing").hasValue(1);
    }
}
