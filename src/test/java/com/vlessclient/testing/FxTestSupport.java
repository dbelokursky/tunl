package com.vlessclient.testing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things every non-TestFX test that touches JavaFX needs: a running
 * toolkit, and a way to wait for the FX thread to drain.
 *
 * <p>Nine classes used to carry their own {@code Platform.startup} wrapped in
 * a catch of the "already started" exception, and three defined the same
 * {@code flushFxEvents}. Surefire already runs the whole suite on Monocle, so
 * there is nothing per-class left to configure.</p>
 */
public final class FxTestSupport {

    private static final long STARTUP_TIMEOUT_SECONDS = 10;
    private static final long FLUSH_TIMEOUT_SECONDS = 5;

    private FxTestSupport() {
    }

    /**
     * Starts the JavaFX toolkit unless another class in this JVM already has,
     * and returns once the FX thread is accepting work.
     */
    public static void startToolkit() {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            return;
        }
        try {
            assertThat(ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the JavaFX toolkit started within %ds", STARTUP_TIMEOUT_SECONDS)
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while starting the JavaFX toolkit", e);
        }
    }

    /**
     * Runs a no-op on the JavaFX Application Thread and blocks until it has
     * run, which guarantees that everything queued with
     * {@link Platform#runLater} before this call has finished.
     */
    public static void flushFxEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the JavaFX thread drained its queue within %ds", FLUSH_TIMEOUT_SECONDS)
                .isTrue();
    }
}
