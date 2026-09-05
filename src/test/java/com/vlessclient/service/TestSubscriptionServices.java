package com.vlessclient.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

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
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Quiet(TestConfigStores.at(dataDir.resolve("config")), dataDir);
    }

    private static final class Quiet extends SubscriptionService {

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
}
