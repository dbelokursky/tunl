package com.vlessclient.testing;

import java.time.Duration;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Polls for an asynchronous outcome instead of sleeping for a guessed
 * duration.
 *
 * <p>Ten test classes carried their own {@code deadline + Thread.sleep}
 * loop, each with a different poll interval and a different — sometimes
 * absent — failure message. A wait that gives up says what it waited for
 * and, for a value, what it last saw.</p>
 */
public final class Await {

    private static final long POLL_MILLIS = 20;

    private Await() {
    }

    /** Polls until {@code condition} holds; fails once {@code timeout} has passed. */
    public static void until(BooleanSupplier condition, Duration timeout) {
        until("condition", condition, timeout);
    }

    /**
     * Polls until {@code condition} holds; fails naming {@code what} once
     * {@code timeout} has passed.
     */
    public static void until(String what, BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "timed out after " + timeout.toMillis() + " ms waiting for " + what);
            }
            pause();
        }
    }

    /** Polls {@code source} until {@code accepted} passes its value, and returns it. */
    public static <T> T untilValue(Supplier<T> source, Predicate<? super T> accepted,
                                   Duration timeout) {
        return untilValue("value", source, accepted, timeout);
    }

    /**
     * Polls {@code source} until {@code accepted} passes its value, and
     * returns it; fails naming {@code what} and the last value seen once
     * {@code timeout} has passed.
     */
    public static <T> T untilValue(String what, Supplier<T> source,
                                   Predicate<? super T> accepted, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            T value = source.get();
            if (accepted.test(value)) {
                return value;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out after " + timeout.toMillis()
                        + " ms waiting for " + what + "; last seen: " + value);
            }
            pause();
        }
    }

    /** The live threads whose name starts with {@code namePrefix}. */
    public static Set<Thread> liveThreadsNamed(String namePrefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(namePrefix))
                .collect(Collectors.toSet());
    }

    /**
     * Waits until every live thread named with {@code namePrefix}, other
     * than those in {@code ignoring}, has finished.
     *
     * <p>This is the join point for work the code under test runs on a named
     * daemon thread it never exposes — the sing-box process monitor, the log
     * reader. Snapshot {@link #liveThreadsNamed} before the action, pass the
     * snapshot here, and anything a previous test left behind is ignored
     * rather than waited for.</p>
     */
    public static void untilThreadsFinished(String namePrefix, Set<Thread> ignoring,
                                            Duration timeout) {
        until("threads named " + namePrefix + "* to finish",
                () -> liveThreadsNamed(namePrefix).stream().allMatch(ignoring::contains),
                timeout);
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }
}
