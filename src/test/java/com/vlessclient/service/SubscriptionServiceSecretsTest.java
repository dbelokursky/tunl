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

    /**
     * A URL is sealed only when the file is written, as a server's credential
     * is: one saved while no keychain was there kept the account token in
     * plaintext after a start that had one, until the next refresh.
     */
    @Test
    void urlsLeftInPlaintextAreSealedAtAStartWithAKeychain() throws Exception {
        InMemorySecretSealer offSealer = new InMemorySecretSealer();
        offSealer.setAvailable(false);
        Files.createDirectories(tempDir.resolve("subs"));
        SubscriptionService before = serviceWith(offSealer);
        before.getSubscriptions().add(subscription(TOKEN_URL));
        before.saveSubscriptions();
        assertThat(rawSubscriptionsJson()).contains("secret-token-123");

        InMemorySecretSealer keychain = new InMemorySecretSealer();
        serviceWith(keychain).sealUrlsLeftInPlaintext();

        assertThat(rawSubscriptionsJson()).doesNotContain("secret-token-123")
                .contains(InMemorySecretSealer.FAKE_TAG);
        assertThat(serviceWith(keychain).getSubscriptions().get(0).getUrl())
                .isEqualTo(TOKEN_URL);
    }

    /** No write and no keychain process at a start that has nothing to seal, or may not. */
    @Test
    void aStartWithNothingToSealWritesNothing() throws Exception {
        InMemorySecretSealer keychain = new InMemorySecretSealer();
        Files.createDirectories(tempDir.resolve("subs"));
        SubscriptionService first = serviceWith(keychain);
        first.getSubscriptions().add(subscription(TOKEN_URL));
        first.saveSubscriptions();
        String sealed = rawSubscriptionsJson();
        keychain.resetSealCalls();

        serviceWith(keychain).sealUrlsLeftInPlaintext();
        assertThat(keychain.sealCalls()).isZero();
        assertThat(rawSubscriptionsJson()).isEqualTo(sealed);

        configStore.getSettings().setStoreSecretsSecurely(false);
        SubscriptionService optedOut = serviceWith(keychain);
        optedOut.saveSubscriptions();
        serviceWith(keychain).sealUrlsLeftInPlaintext();
        assertThat(rawSubscriptionsJson()).as("with secure storage off")
                .contains("secret-token-123");
        assertThat(keychain.sealCalls()).isZero();
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

    /**
     * Every save sealed every URL again, and a refresh saves the list each
     * time: on macOS one {@code security} process per subscription, per save.
     */
    @Test
    void aUrlIsSealedAgainOnlyWhenItChanges() throws Exception {
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        SubscriptionService service = serviceWith(sealer);
        Files.createDirectories(tempDir.resolve("subs"));
        service.getSubscriptions().add(subscription(TOKEN_URL));
        service.saveSubscriptions();

        sealer.resetSealCalls();
        service.saveSubscriptions();
        assertThat(sealer.sealCalls()).as("a second save of the same URL").isZero();

        SubscriptionService reloaded = serviceWith(sealer);
        sealer.resetSealCalls();
        reloaded.saveSubscriptions();
        assertThat(sealer.sealCalls()).as("the first save after a reload").isZero();

        reloaded.getSubscriptions().get(0).setUrl(TOKEN_URL + "-rotated");
        reloaded.saveSubscriptions();
        assertThat(sealer.sealCalls()).as("a changed URL is sealed").isEqualTo(1);
        assertThat(rawSubscriptionsJson()).doesNotContain("secret-token-123");
    }
}
