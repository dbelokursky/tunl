package com.vlessclient.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link SubscriptionService} doubles for tests that live outside this
 * package and therefore cannot reach the sealing-disabled constructor.
 */
public final class TestSubscriptionServices {

    private TestSubscriptionServices() {
    }

    /**
     * A service that keeps its list under {@code dataDir}, seals nothing and
     * never fetches. Adding and removing subscriptions drives the same
     * observable list a view binds to, without the platform keychain or the
     * network anywhere near the test — the graph's own service saves into
     * the shared test data dir and seals through
     * {@code SecretSealers.forCurrentPlatform()}.
     */
    public static SubscriptionService quiet(Path dataDir) {
        return new Quiet(storeUnder(dataDir), dataDir);
    }

    /**
     * A quiet service a view test scripts after the view has started: it runs
     * what the test sets, on whichever thread asks it to remove or refresh, so
     * the test can tell where a call came from, hold a refresh open, count
     * refreshes or make one throw.
     */
    public static Scripted scripted(Path dataDir) {
        return new Scripted(storeUnder(dataDir), dataDir);
    }

    /**
     * A quiet service whose every add fails, once {@code release} opens. A
     * provider that never answers fails when its timeout runs out, and the
     * view hears about it from another thread whenever that is: the latch
     * lets a test choose the moment.
     */
    public static SubscriptionService failing(Path dataDir, CountDownLatch release) {
        return new Failing(storeUnder(dataDir), dataDir, release);
    }

    private static ConfigStore storeUnder(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return TestConfigStores.at(dataDir.resolve("config"));
    }

    private static class Quiet extends SubscriptionService {

        private Quiet(ConfigStore store, Path dataDir) {
            super(store, new ShareLinkParser(), dataDir, HttpClient.newHttpClient());
        }

        @Override
        public void refreshSubscription(String subscriptionId) {
            // Fetching is what a UI test must never do.
        }

        @Override
        public void refreshAll() {
            // Same.
        }

        @Override
        public void startAutoRefresh() {
            // Never schedule HTTP work from a test.
        }
    }

    /** The service {@link #scripted} returns. */
    public static final class Scripted extends Quiet {

        private volatile Runnable beforeRemoveAction = () -> { };
        private volatile Consumer<String> refreshAction = id -> { };
        private volatile Runnable refreshAllAction = () -> { };

        private Scripted(ConfigStore store, Path dataDir) {
            super(store, dataDir);
        }

        /** Runs {@code action} just before each removal, on the removing thread. */
        public void beforeRemove(Runnable action) {
            beforeRemoveAction = action;
        }

        /** Runs {@code action}, given the subscription's id, instead of each refresh. */
        public void onRefresh(Consumer<String> action) {
            refreshAction = action;
        }

        /** Runs {@code action} instead of each refresh of every subscription. */
        public void onRefreshAll(Runnable action) {
            refreshAllAction = action;
        }

        @Override
        public void removeSubscription(String subscriptionId) {
            beforeRemoveAction.run();
            super.removeSubscription(subscriptionId);
        }

        @Override
        public void refreshSubscription(String subscriptionId) {
            refreshAction.accept(subscriptionId);
        }

        @Override
        public void refreshAll() {
            refreshAllAction.run();
        }
    }

    private static final class Failing extends Quiet {

        private final CountDownLatch release;

        private Failing(ConfigStore store, Path dataDir, CountDownLatch release) {
            super(store, dataDir);
            this.release = release;
        }

        @Override
        public void addSubscription(String name, String url) {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(url + " did not answer");
        }
    }
}
