package com.vlessclient.service.mcp;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Marshals work onto the JavaFX Application Thread and blocks for the result.
 *
 * <p>MCP requests are served on HTTP worker threads, but the services expose
 * their state through JavaFX observable properties and lists that are only
 * mutated on the FX thread. Reading or mutating them from another thread risks
 * {@link java.util.ConcurrentModificationException} or torn reads, so tool
 * handlers funnel those touches through here.</p>
 */
public final class FxExecutor {

    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private FxExecutor() {
    }

    /**
     * Runs {@code supplier} on the FX thread and returns its result. If already
     * on the FX thread, runs inline to avoid a deadlock.
     *
     * @throws RuntimeException if the FX task fails or does not complete in time
     */
    public static <T> T get(Supplier<T> supplier) {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
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
