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
}
