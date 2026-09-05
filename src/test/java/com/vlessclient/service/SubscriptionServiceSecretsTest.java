package com.vlessclient.service;

import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.testing.Await;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL sealing in {@link SubscriptionService}: subscription URLs carry the
 * account token, so they follow the same plaintext-in-memory / sealed-on-disk
 * rules as server credentials.
 */
class SubscriptionServiceSecretsTest {

    private static final String TOKEN_URL = "https://provider.example/sub/secret-token-123";

    @TempDir
    Path tempDir;

    private ConfigStore configStore;
    private ShareLinkParser parser;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore(tempDir.resolve("config"));
        parser = new ShareLinkParser();
        httpClient = HttpClient.newHttpClient();
    }

    private SubscriptionService serviceWith(SecretSealer sealer) {
        return new SubscriptionService(
                configStore, parser, tempDir.resolve("subs"), httpClient, sealer);
    }

    private static Subscription subscription(String url) {
        Subscription sub = new Subscription();
        sub.setName("provider");
        sub.setUrl(url);
        return sub;
    }

    private String rawSubscriptionsJson() throws Exception {
        return Files.readString(tempDir.resolve("subs").resolve("subscriptions.json"));
    }

    @Test
    void savedFileCarriesSealedUrlNotTheToken() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        SubscriptionService service = serviceWith(sealer);
        Files.createDirectories(tempDir.resolve("subs"));
        service.getSubscriptions().add(subscription(TOKEN_URL));
        service.saveSubscriptions();

        String raw = rawSubscriptionsJson();
        assertThat(raw).doesNotContain("secret-token-123");
        assertThat(raw).contains(InMemorySecretSealer.FAKE_TAG);
        assertThat(service.getSubscriptions().get(0).getUrl()).isEqualTo(TOKEN_URL);
    }

    @Test
    void reloadRestoresPlaintextUrl() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        SubscriptionService service = serviceWith(sealer);
        Files.createDirectories(tempDir.resolve("subs"));
        service.getSubscriptions().add(subscription(TOKEN_URL));
        service.saveSubscriptions();

        SubscriptionService reloaded = serviceWith(sealer);
        assertThat(reloaded.getSubscriptions()).hasSize(1);
        assertThat(reloaded.getSubscriptions().get(0).getUrl()).isEqualTo(TOKEN_URL);
    }

    @Test
    void legacyPlaintextFileLoadsUnchanged() throws Exception {
        InMemorySecretSealer off = new InMemorySecretSealer();
        off.setAvailable(false);
        SubscriptionService legacy = serviceWith(off);
        Files.createDirectories(tempDir.resolve("subs"));
        legacy.getSubscriptions().add(subscription(TOKEN_URL));
        legacy.saveSubscriptions();
        assertThat(rawSubscriptionsJson()).contains("secret-token-123");

        SubscriptionService reloaded = serviceWith(new InMemorySecretSealer());
        assertThat(reloaded.getSubscriptions().get(0).getUrl()).isEqualTo(TOKEN_URL);
    }

    @Test
    void disabledSettingKeepsWritingPlaintext() throws Exception {
        configStore.getSettings().setStoreSecretsSecurely(false);
        SubscriptionService service = serviceWith(new InMemorySecretSealer());
        Files.createDirectories(tempDir.resolve("subs"));
        service.getSubscriptions().add(subscription(TOKEN_URL));
        service.saveSubscriptions();

        assertThat(rawSubscriptionsJson()).contains("secret-token-123");
    }

    @Test
    void failedUnsealKeepsTheTagSoTheEntryStaysVisible() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        SubscriptionService service = serviceWith(sealer);
        Files.createDirectories(tempDir.resolve("subs"));
        service.getSubscriptions().add(subscription(TOKEN_URL));
        service.saveSubscriptions();
        sealer.entries().clear();

        SubscriptionService reloaded = serviceWith(sealer);
        assertThat(reloaded.getSubscriptions()).hasSize(1);
        assertThat(reloaded.getSubscriptions().get(0).getUrl())
                .startsWith(SecretSealer.SEAL_PREFIX);
    }

    @Test
    void removingASubscriptionDeletesItsBackendEntry() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        SubscriptionService service = serviceWith(sealer);
        Files.createDirectories(tempDir.resolve("subs"));
        Subscription sub = subscription(TOKEN_URL);
        service.getSubscriptions().add(sub);
        service.saveSubscriptions();
        assertThat(sealer.entries()).hasSize(1);

        service.removeSubscription(sub.getId());
        Await.until("the sealed URL to be deleted", () -> sealer.entries().isEmpty(),
                Duration.ofSeconds(5));
        assertThat(sealer.entries()).isEmpty();
    }
}
