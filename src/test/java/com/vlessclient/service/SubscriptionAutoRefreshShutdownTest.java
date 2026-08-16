package com.vlessclient.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stopAutoRefresh() must not let a stuck in-flight refresh hold shutdown open.
 * The Cmd+Q quit watchdog halts the JVM after 2s, and the engine is now stopped
 * before this step runs, so a slow subscription fetch must never be what keeps
 * shutdown alive — the old 60s wait could never complete under either quit path.
 * This pins the bounded grace plus the interrupt of anything slower.
 */
class SubscriptionAutoRefreshShutdownTest {

    @TempDir
    Path tempDir;

    @Test
    void stopAutoRefreshInterruptsAStuckRefreshWithinTheGrace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        SubscriptionService service = new SubscriptionService(
                new ConfigStore(tempDir), new ShareLinkParser(), tempDir,
                HttpClient.newHttpClient()) {
            @Override
            public void refreshAll() {
                entered.countDown();
                try {
                    // Blocks the way a slow fetch would; shutdownNow's interrupt
                    // is what must free it, exactly as a real HTTP send unwinds.
                    release.await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }
        };

        // Initial delay 0: the scheduler runs one cycle immediately and parks
        // inside refreshAll(), simulating a refresh in flight at quit time.
        service.startAutoRefresh(0, 1, TimeUnit.HOURS);
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("the scheduled refresh started before we stop it")
                .isTrue();

        long start = System.nanoTime();
        service.stopAutoRefresh();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("stop must interrupt within the grace, not block for the old 60s wait")
                .isLessThan(15_000);
        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("the stuck refresh was interrupted, not left running")
                .isTrue();

        release.countDown();
    }
}
