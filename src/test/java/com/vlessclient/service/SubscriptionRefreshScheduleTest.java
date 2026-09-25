package com.vlessclient.service;

import com.vlessclient.model.Subscription;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the auto-refresh refreshes a subscription.
 *
 * <p>It refreshed every subscription hourly, the first time an hour after
 * launch, so a list last refreshed yesterday stayed stale for the first hour
 * of a session, and a provider's {@code profile-update-interval} was ignored
 * (item 2.3 of the 2026-09-25 review). A check now runs a minute after start
 * and every five minutes after, and refreshes the subscriptions due.</p>
 */
class SubscriptionRefreshScheduleTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();

    @TempDir
    Path tempDir;

    private Recording service;

    /** Records the refreshes a check asks for, and fails them as told, fetching nothing. */
    private static final class Recording extends SubscriptionService {
        final List<String> refreshed = new ArrayList<>();
        String failWith;

        Recording(Path dir) {
            super(new ConfigStore(dir), new ShareLinkParser(), dir, HttpClient.newHttpClient());
        }

        @Override
        public void refreshSubscription(String subscriptionId) {
            refreshed.add(subscriptionId);
            if (failWith != null) {
                getSubscriptions().stream().filter(sub -> sub.getId().equals(subscriptionId))
                        .findFirst().ifPresent(sub -> sub.recordFailure(failWith, List.of()));
            }
        }
    }

    @BeforeEach
    void setUp() {
        service = new Recording(tempDir);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private Subscription subscription(long refreshedAgoMillis) {
        service.addSubscription("Provider", "https://provider.example/sub");
        Subscription sub = service.getSubscriptions().getLast();
        sub.setLastRefreshedAt(System.currentTimeMillis() - refreshedAgoMillis);
        service.refreshed.clear();
        return sub;
    }

    @Test
    void aListRefreshedYesterdayIsRefreshedAtTheFirstCheck() {
        Subscription stale = subscription(24 * HOUR);

        service.refreshDue();

        assertThat(service.refreshed).containsExactly(stale.getId());
    }

    @Test
    void aListRefreshedWithinItsIntervalIsLeftAlone() {
        subscription(10 * 60 * 1000L);

        service.refreshDue();

        assertThat(service.refreshed).isEmpty();
    }

    @Test
    void theProvidersIntervalDecides() {
        Subscription twoHoursAgo = subscription(2 * HOUR);
        twoHoursAgo.setUpdateIntervalHours(12);

        service.refreshDue();
        assertThat(service.refreshed).as("2 h into a 12 h interval").isEmpty();

        twoHoursAgo.setLastRefreshedAt(System.currentTimeMillis() - 13 * HOUR);
        service.refreshDue();
        assertThat(service.refreshed).containsExactly(twoHoursAgo.getId());
    }

    @Test
    void aFailedRefreshWaitsAnIntervalButOneTheTunnelDeclinedDoesNot() {
        Subscription failing = subscription(24 * HOUR);
        service.failWith = "subscriptions.error.timeout";

        service.refreshDue();
        service.refreshDue();
        assertThat(service.refreshed).as("tried, and failed").containsExactly(failing.getId());

        Subscription declined = subscription(24 * HOUR);
        service.failWith = "subscriptions.error.tunnel";
        service.refreshDue();
        service.refreshDue();
        assertThat(service.refreshed)
                .as("declined while the tunnel the user wants was not up: tried again")
                .containsExactly(declined.getId(), declined.getId());
    }

    @Test
    void theProvidersIntervalHeaderIsReadInHoursAndBounded() {
        assertThat(SubscriptionService.updateIntervalHours(headers("12"))).isEqualTo(12);
        assertThat(SubscriptionService.updateIntervalHours(headers("0"))).isZero();
        assertThat(SubscriptionService.updateIntervalHours(headers("soon"))).isZero();
        assertThat(SubscriptionService.updateIntervalHours(null)).isZero();

        Subscription sub = new Subscription();
        assertThat(SubscriptionService.intervalOf(sub)).isEqualTo(Duration.ofHours(1));
        sub.setUpdateIntervalHours(10_000);
        assertThat(SubscriptionService.intervalOf(sub)).isEqualTo(Duration.ofDays(7));
    }

    private static HttpHeaders headers(String interval) {
        return HttpHeaders.of(Map.of("profile-update-interval", List.of(interval)),
                (name, value) -> true);
    }
}
