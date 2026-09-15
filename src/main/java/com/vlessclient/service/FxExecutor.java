package com.vlessclient.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javafx.application.Platform;

/**
 * Marshals work onto the JavaFX Application Thread and blocks for the result.
 *
 * <p>The services expose their state through JavaFX observable properties and
 * lists, which are only safe to touch on the FX thread. Work reaches them from
 * everywhere else: MCP requests on HTTP worker threads, subscription refreshes
 * on virtual threads and the hourly scheduler, tray actions on the AWT event
 * thread. Reading or mutating from those risks
 * {@link java.util.ConcurrentModificationException} or torn reads, so they
 * funnel through here.</p>
 *
 * <h2>The one rule callers must follow</h2>
 *
 * <p><strong>Never block here while holding a lock the FX thread might want.</strong>
 * These methods wait for the FX thread; if the caller holds a monitor that an
 * FX-thread code path also acquires, the two deadlock — the caller waits for
 * the FX thread, the FX thread waits for the monitor. In {@code ConfigStore}
 * that is why the marshalling happens outside the {@code synchronized} region
 * and the monitor is taken inside the marshalled action, never around it.</p>
 */
public final class FxExecutor {

    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private FxExecutor() {
    }

    /**
     * Runs {@code supplier} on the FX thread and returns its result. If already
     * on the FX thread, runs inline to avoid a deadlock. If the JavaFX toolkit
     * is not running at all — a plain unit test, or a headless tool using these
     * services — runs inline too: there is no FX thread to protect, and the
     * caller is then the only thread touching the list.
     *
     * <p>If the FX thread has not started the task by the time the wait runs
     * out, the task is dropped and never runs: the caller has already been
     * told it failed. A task that has started is left to finish.</p>
     *
     * @throws RuntimeException if the FX task fails or does not complete in time
     */
    public static <T> T get(Supplier<T> supplier) {
        return get(supplier, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /** {@link #get(Supplier)} with the wait bounded by {@code timeout}; a test seam. */
    static <T> T get(Supplier<T> supplier, Duration timeout) {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        // Claimed by whichever comes first: the task starting on the FX thread,
        // or the caller giving up on it. The caller reports a timeout as a
        // failure, so a task it gave up on must not run afterwards: a change it
        // made then would land after whatever the caller did next.
        AtomicBoolean claimed = new AtomicBoolean();
        try {
            Platform.runLater(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (IllegalStateException toolkitNotRunning) {
            // "Toolkit not initialized" — thrown by runLater itself, before the
            // action is ever queued, so nothing has run twice here.
            return supplier.get();
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (claimed.compareAndSet(false, true)) {
                throw new RuntimeException("Timed out waiting for the UI thread; "
                        + "the task had not started and will not run", e);
            }
            // Already running: it may be what the FX thread is stuck on, so
            // waiting on for it could hang both sides for good.
            throw new RuntimeException("Timed out waiting for the UI thread", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for the UI thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    /**
     * Runs {@code action} on the FX thread and blocks until it finishes.
     */
    public static void run(Runnable action) {
        get(() -> {
            action.run();
            return null;
        });
    }
}
