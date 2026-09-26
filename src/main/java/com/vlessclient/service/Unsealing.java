package com.vlessclient.service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Reads the sealed values a loaded file holds, a few at a time.
 *
 * <p>Each sealed value is a process of the keychain's own ({@code security}
 * on macOS, {@code secret-tool} on Linux), about 16 ms apiece, and a load read
 * them one after another before the window first appeared: 1.6 s for a
 * hundred servers. The backends take concurrent callers, and the callers
 * guard what they share.</p>
 */
final class Unsealing {

    /** Enough to hide the processes' start-up, few enough not to flood the keychain. */
    static final int AT_ONCE = 8;

    private Unsealing() {
    }

    /**
     * Runs {@code unseal} for every item, up to {@link #AT_ONCE} at a time, and
     * returns once all have run.
     *
     * @param items  the loaded entries
     * @param unseal restores one entry's sealed values in place
     * @param <T>    the entry type
     */
    static <T> void each(List<T> items, Consumer<T> unseal) {
        if (items.size() < 2) {
            items.forEach(unseal);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(AT_ONCE, items.size()), DaemonThreads.factory("unseal"));
        try {
            List<Future<?>> pending = items.stream()
                    .<Future<?>>map(item -> pool.submit(() -> unseal.accept(item)))
                    .toList();
            for (Future<?> future : pending) {
                future.get();
            }
        } catch (ExecutionException e) {
            // What a sequential load would have thrown, from the item that threw it.
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            // A load abandoned at quit: what is still sealed stays sealed, as
            // it does when the keychain does not answer.
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }
}
