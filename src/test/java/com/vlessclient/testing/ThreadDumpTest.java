package com.vlessclient.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** The dump a timed-out test prints has the virtual threads in it. */
class ThreadDumpTest {

    @Test
    void aVirtualThreadIsInTheDump() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Thread parked = Thread.ofVirtual().name("thread-dump-probe").start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(ThreadDump.ofAllThreads())
                    .contains("thread-dump-probe")
                    .contains(Thread.currentThread().getName());
        } finally {
            release.countDown();
            parked.join();
        }
    }

    /**
     * The summary a timed-out wait carries names the virtual threads in this
     * app's code, with enough of their stacks to see what they wait on, and
     * how many carriers are taken.
     */
    @Test
    void theSummaryKeepsOurVirtualThreadsAndCountsTheCarriers() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Thread parked = Thread.ofVirtual().name("summary-probe").start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            String summary = ThreadDump.forBackgroundWork();

            assertThat(summary).startsWith("threads: ").contains(" carriers busy")
                    .contains("\"summary-probe\" virtual")
                    .contains("CountDownLatch.await");
            assertThat(summary.lines().count()).isLessThanOrEqualTo(162);
        } finally {
            release.countDown();
            parked.join();
        }
    }
}
