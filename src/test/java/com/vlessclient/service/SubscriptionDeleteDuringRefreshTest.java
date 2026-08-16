package com.vlessclient.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a subscription while one of its refreshes is mid-fetch must not
 * bring its servers back. Without the apply-stage re-check the racing refresh
 * finds none of the (deleted) ids, treats every fetched server as new, and
 * re-inserts the whole list as untracked orphans — the mirror image of PR #128.
 */
class SubscriptionDeleteDuringRefreshTest {

    @TempDir
    Path tempDir;

    private static final String CONTENT =
            "vless://11111111-1111-1111-1111-111111111111@server1.com:443?security=tls&type=tcp#S1\n"
            + "vless://22222222-2222-2222-2222-222222222222@server2.com:443?security=tls&type=tcp#S2\n";

    @Test
    void deletingASubscriptionMidRefreshDoesNotResurrectItsServers() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        SubscriptionService service = new SubscriptionService(
                store, new ShareLinkParser(), tempDir, HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) {
                // The first fetch (from addSubscription) returns at once; the
                // second (the racing refresh) parks so a delete can land while
                // it is "in flight", exactly like a slow provider would.
                if (fetches.incrementAndGet() >= 2) {
                    fetchEntered.countDown();
                    try {
                        releaseFetch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return CONTENT;
            }
        };

        service.addSubscription("Sub", "https://example.test/sub");
        String subId = service.getSubscriptions().get(0).getId();
        assertThat(store.getServers()).hasSize(2);

        Thread refresher = new Thread(() -> service.refreshSubscription(subId), "racing-refresh");
        refresher.start();
        assertThat(fetchEntered.await(5, TimeUnit.SECONDS))
                .as("the racing refresh reached the fetch before we delete")
                .isTrue();

        // Delete while the refresh is parked in fetch.
        service.removeSubscription(subId);
        assertThat(store.getServers()).isEmpty();
        assertThat(service.getSubscriptions()).isEmpty();

        // Let the refresh finish: it must discard its result, not resurrect.
        releaseFetch.countDown();
        refresher.join(10_000);
        assertThat(refresher.isAlive()).isFalse();

        assertThat(store.getServers())
                .as("a delete during refresh must not bring the servers back")
                .isEmpty();
        assertThat(service.getSubscriptions()).isEmpty();
    }
}
