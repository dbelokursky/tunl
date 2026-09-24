package com.vlessclient.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
     * counts the carriers rather than listing them: on a Windows runner there
     * were 256, and listed they crowded out everything else.
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
            // Started is not yet parked: on a Windows runner the dump came
            // before the probe had run at all.
            Await.until("the probe to wait on its latch",
                    () -> parked.getState() == Thread.State.WAITING, Duration.ofSeconds(10));
            String summary = ThreadDump.forBackgroundWork();

            assertThat(summary).startsWith("threads: ").contains("; carriers {")
                    .contains("; HTTP client selectors: ")
                    .contains("\"summary-probe\" virtual")
                    .contains("CountDownLatch.await");
            assertThat(summary.lines().count()).isLessThanOrEqualTo(152);
        } finally {
            release.countDown();
            parked.join();
        }
    }

    /**
     * A Windows runner's dump ended its lines in two carriage returns and a
     * newline; read as blank lines between them, every thread came out as its
     * header alone, with no stack and no carrier counted as busy.
     */
    @Test
    void aDumpWithWindowsLineEndingsKeepsItsStacks() {
        String dump = String.join("\r\r\n",
                "1234", "2026-09-24T21:15:30Z", "27", "",
                "#63 \"JavaFX Application Thread\" WAITING 2026-09-24T21:15:30Z",
                "    at java.base/jdk.internal.misc.Unsafe.park(Native Method)",
                "    at com.sun.glass.ui.monocle.RunnableProcessor.take(RunnableProcessor.java:80)",
                "",
                "#70 \"summary-probe\" virtual WAITING 2026-09-24T21:15:30Z",
                "    at java.base/java.util.concurrent.CountDownLatch.await(CountDownLatch.java:230)",
                "    at com.vlessclient.testing.ThreadDumpTest.lambda$probe$0(ThreadDumpTest.java:1)",
                "",
                "#71 \"HttpClient-7-SelectorManager\" virtual RUNNABLE 2026-09-24T21:15:30Z",
                "    at java.base/sun.nio.ch.WEPoll.wait(Native Method)",
                "",
                "#41 \"ForkJoinPool-1-worker-1\" RUNNABLE 2026-09-24T21:15:30Z",
                "    at java.base/jdk.internal.vm.Continuation.run(Continuation.java:248)",
                "",
                "#42 \"ForkJoinPool-1-worker-2\" WAITING 2026-09-24T21:15:30Z",
                "    at java.base/java.util.concurrent.ForkJoinPool.awaitWork(ForkJoinPool.java:2109)",
                "");

        String summary = ThreadDump.summarize(dump);

        assertThat(summary)
                .startsWith("threads: 2 virtual (1 running); carriers {RUNNABLE=1, WAITING=1};"
                        + " HTTP client selectors: 1 (1 running)")
                .contains("RunnableProcessor.take")
                .contains("\"summary-probe\" virtual WAITING")
                .contains("ThreadDumpTest.lambda$probe$0")
                .contains("WEPoll.wait")
                .doesNotContain("\r");
    }
}
