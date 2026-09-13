package com.vlessclient.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
