package com.vlessclient.ui.view;

import static com.vlessclient.testing.FxTestSupport.flushFxEvents;
import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxPulses;
import com.vlessclient.testing.FxToolkitExtension;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@link FxTimer} has to keep the promises of the animations it replaced: the
 * work runs on the FX thread, and a stopped timer stays stopped. What it must
 * not do is what they did while waiting, which is keep JavaFX pulsing.
 */
@ExtendWith(FxToolkitExtension.class)
class FxTimerTest {

    @Test
    void runsTheWorkOnceOnTheFxThreadAfterTheDelay() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean onFxThread = new AtomicBoolean();
        CountDownLatch ran = new CountDownLatch(1);
        long started = System.nanoTime();

        onFx(() -> FxTimer.after(Duration.ofMillis(100), () -> {
            onFxThread.set(Platform.isFxApplicationThread());
            runs.incrementAndGet();
            ran.countDown();
        }));

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(100));
        assertThat(onFxThread).isTrue();
        Thread.sleep(250);
        flushFxEvents();
        assertThat(runs).as("a one-off runs once").hasValue(1);
    }

    @Test
    void waitingPlaysNoAnimation() throws Exception {
        int before = FxPulses.runningAnimations();
        FxTimer.Cancellable pending =
                onFx(() -> FxTimer.after(Duration.ofMinutes(1), () -> { }));
        try {
            assertThat(FxPulses.runningAnimations())
                    .as("a pending wait must leave the pulse timer free to pause")
                    .isEqualTo(before);
        } finally {
            runOnFx(pending::cancel);
        }
    }

    @Test
    void aCancelledWaitNeverRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        FxTimer.Cancellable pending =
                onFx(() -> FxTimer.after(Duration.ofMillis(50), runs::incrementAndGet));

        runOnFx(pending::cancel);
        Thread.sleep(250);
        flushFxEvents();

        assertThat(runs).hasValue(0);
    }

    /**
     * The guarantee a stopped PauseTransition gave for free: its handler ran
     * inside a pulse, so a stop() on the FX thread was the last word. This
     * timer's thread can hand a run to the FX thread just before the cancel,
     * and that run must not happen either.
     */
    @Test
    void cancellingDropsARunAlreadyHandedToTheFxThread() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);

        Platform.runLater(() -> {
            FxTimer.Cancellable pending = FxTimer.after(Duration.ZERO, runs::incrementAndGet);
            // Hold the FX thread while the timer fires, so its run queues up
            // behind this one and only reaches the FX thread after the cancel.
            pause(300);
            pending.cancel();
            cancelled.countDown();
        });
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        flushFxEvents();

        assertThat(runs).as("the queued run met the cancel first").hasValue(0);
    }

    @Test
    void repeatsUntilCancelled() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        FxTimer.Cancellable repeating =
                onFx(() -> FxTimer.every(Duration.ofMillis(20), runs::incrementAndGet));

        Await.until("three runs", () -> runs.get() >= 3, Duration.ofSeconds(5));
        runOnFx(repeating::cancel);
        int atCancel = runs.get();
        Thread.sleep(150);
        flushFxEvents();

        assertThat(runs).as("no run after the cancel").hasValue(atCancel);
    }

    /** A Timeline goes on to its next cycle when a handler throws; so does this. */
    @Test
    void aRunThatThrowsDoesNotEndTheRepeat() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler prior =
                onFx(() -> Thread.currentThread().getUncaughtExceptionHandler());
        runOnFx(() -> Thread.currentThread()
                .setUncaughtExceptionHandler((thread, error) -> reported.add(error)));

        FxTimer.Cancellable repeating = onFx(() -> FxTimer.every(Duration.ofMillis(20), () -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("the first read fails");
            }
        }));
        try {
            Await.until("a run after the one that threw", () -> runs.get() >= 2,
                    Duration.ofSeconds(5));
        } finally {
            runOnFx(repeating::cancel);
            runOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler(prior));
        }

        assertThat(reported).singleElement().isInstanceOf(IllegalStateException.class);
    }

    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> result.complete(work.get()));
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void runOnFx(Runnable work) throws InterruptedException {
        Platform.runLater(work);
        flushFxEvents();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
