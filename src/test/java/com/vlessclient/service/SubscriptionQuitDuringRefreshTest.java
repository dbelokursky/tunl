package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Subscription;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quitting while the auto-refresh waits on a slow provider interrupts it.
 * The interrupt was caught as a provider failure: the subscription was saved
 * with "java.lang.InterruptedException" as its last error, the loop went on
 * to the next due subscription, which failed on the HTTP client the quit had
 * just closed and was saved with "closed". Both rows said so at the next
 * launch until a refresh succeeded, an hour or more later.
 */
class SubscriptionQuitDuringRefreshTest {

    private static final String LIST =
            "vless://11111111-1111-1111-1111-111111111111@one.example:443?security=tls#S\n";

    @TempDir
    Path tempDir;

    @Test
    void aQuitDuringARefreshRecordsNoFailureAndEndsTheCycle() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch cycleEnded = new CountDownLatch(1);
        AtomicBoolean interruptKept = new AtomicBoolean();
        AtomicBoolean slow = new AtomicBoolean();
        List<String> fetchedAfterTheQuit = new CopyOnWriteArrayList<>();
        SubscriptionService service = new SubscriptionService(new ConfigStore(tempDir),
                new ShareLinkParser(), tempDir, HttpClient.newHttpClient()) {
            @Override
            String fetchContent(String url) throws IOException, InterruptedException {
                if (!slow.get()) {
                    return LIST;
                }
                if (url.contains("one")) {
                    stalled.countDown();
                    // A provider that does not answer; the quit's interrupt
                    // ends the wait the way it ends a real HTTP send.
                    new CountDownLatch(1).await();
                }
                fetchedAfterTheQuit.add(url);
                throw new IOException("closed");
            }

            @Override
            void refreshDue() {
                try {
                    super.refreshDue();
                } finally {
                    interruptKept.set(Thread.currentThread().isInterrupted());
                    cycleEnded.countDown();
                }
            }
        };
        service.addSubscription("One", "https://one.example/sub");
        service.addSubscription("Two", "https://two.example/sub");
        for (Subscription sub : service.getSubscriptions()) {
            sub.setLastRefreshedAt(System.currentTimeMillis() - Duration.ofHours(2).toMillis());
        }
        slow.set(true);

        service.startAutoRefresh(0, 1, TimeUnit.HOURS);
        assertThat(stalled.await(5, TimeUnit.SECONDS)).as("the refresh is under way").isTrue();
        service.shutdown();
        assertThat(cycleEnded.await(10, TimeUnit.SECONDS)).as("the cycle ended").isTrue();

        assertThat(service.getSubscriptions())
                .as("an interrupted or closed fetch is not the provider's failure")
                .noneMatch(Subscription::hasLastError);
        String saved = Files.readString(tempDir.resolve("subscriptions.json"),
                StandardCharsets.UTF_8);
        assertThat(saved).doesNotContain("InterruptedException").doesNotContain("closed");
        assertThat(fetchedAfterTheQuit).as("nothing more was fetched once the quit began")
                .isEmpty();
        assertThat(interruptKept).as("the interrupt was kept for the thread's owner").isTrue();
    }
}
