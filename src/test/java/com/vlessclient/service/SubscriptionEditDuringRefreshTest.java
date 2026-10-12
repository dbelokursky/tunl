package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A refresh of a subscription's old URL that finishes after the user pointed
 * it at a new one must not be applied. Only a delete was checked for: the old
 * answer replaced the servers the new URL had just brought, with the old
 * provider's quota and interval, and lastRefreshedAt set to now kept it that
 * way for a whole interval; an old URL that failed left its error on a
 * subscription whose new URL had just worked.
 */
class SubscriptionEditDuringRefreshTest {

    private static final String OLD_URL = "https://old-provider.example/sub?token=1";
    private static final String NEW_URL = "https://new-provider.example/sub?token=2";
    private static final String OLD_LIST =
            "vless://11111111-1111-1111-1111-111111111111@old-provider.example:443"
            + "?security=tls&type=tcp#Old\n";
    private static final String NEW_LIST =
            "vless://22222222-2222-2222-2222-222222222222@new-provider.example:443"
            + "?security=tls&type=tcp#New\n";

    @TempDir
    Path tempDir;

    /** Answers each URL with its list; the old one can be held, then answer or fail. */
    private static final class TwoProviders extends SubscriptionService {
        final CountDownLatch oldRequested = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        volatile boolean holdOld;
        volatile int oldStatus = 200;

        TwoProviders(ConfigStore store, Path dir) {
            super(store, new ShareLinkParser(), dir, HttpClient.newHttpClient());
        }

        @Override
        String fetchContent(String url) throws IOException, InterruptedException {
            if (!url.equals(OLD_URL)) {
                return NEW_LIST;
            }
            if (holdOld) {
                oldRequested.countDown();
                releaseOld.await(10, TimeUnit.SECONDS);
                if (oldStatus != 200) {
                    throw new HttpStatusException(oldStatus, "HTTP " + oldStatus);
                }
            }
            return OLD_LIST;
        }
    }

    @Test
    void anOldListArrivingAfterTheEditDoesNotReplaceTheNewOne() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        TwoProviders service = new TwoProviders(store, tempDir);
        Subscription sub = editWhileTheOldRefreshIsHeld(store, service);

        assertThat(addresses(store)).containsExactly("new-provider.example");
        assertThat(sub.getUrl()).isEqualTo(NEW_URL);
        assertThat(sub.hasLastError()).isFalse();
    }

    @Test
    void anOldUrlFailingAfterTheEditLeavesNoErrorOnTheNewOne() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        TwoProviders service = new TwoProviders(store, tempDir);
        service.oldStatus = 404;
        Subscription sub = editWhileTheOldRefreshIsHeld(store, service);

        assertThat(addresses(store)).containsExactly("new-provider.example");
        assertThat(sub.hasLastError())
                .as("the error of a URL the subscription no longer has: %s",
                        sub.getLastErrorKey())
                .isFalse();
    }

    private Subscription editWhileTheOldRefreshIsHeld(ConfigStore store, TwoProviders service)
            throws InterruptedException {
        service.addSubscription("P", OLD_URL);
        Subscription sub = service.getSubscriptions().getFirst();
        assertThat(addresses(store)).containsExactly("old-provider.example");

        // A refresh of the old URL is in flight (the auto-refresh, or the
        // user's own click: Edit stays enabled while a row refreshes)...
        service.holdOld = true;
        Thread inFlight = Thread.ofPlatform().start(
                () -> service.refreshSubscription(sub.getId()));
        assertThat(service.oldRequested.await(5, TimeUnit.SECONDS)).isTrue();

        // ...when the user points the subscription at the provider's new link.
        service.updateSubscription(sub.getId(), "P", NEW_URL);
        assertThat(addresses(store)).containsExactly("new-provider.example");

        service.releaseOld.countDown();
        inFlight.join(10_000);
        assertThat(inFlight.isAlive()).isFalse();
        return sub;
    }

    private static java.util.List<String> addresses(ConfigStore store) {
        return store.getServers().stream().map(ServerConfig::getAddress).toList();
    }
}
