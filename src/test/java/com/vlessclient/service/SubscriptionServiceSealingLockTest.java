package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A subscriptions.json save that seals a URL does not hold the service while
 * the keychain works.
 *
 * <p>The whole save ran inside the service's monitor, its keychain process
 * and its fsync included, and the FX thread takes that monitor for the list
 * change of every add, edit and delete. One made while another save sealed
 * froze the window until the keychain answered: for a locked keychain, its
 * unlock prompt, up to two minutes. ConfigStore was fixed the same way for
 * servers.json.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class SubscriptionServiceSealingLockTest {

    /** Well under FxExecutor's own ten seconds, and far over a free monitor. */
    private static final Duration PROMPT = Duration.ofSeconds(2);

    @TempDir
    Path tempDir;

    /** A keychain that, once told to, stops in the middle of sealing until it is let go. */
    private static final class StalledKeychain implements SecretSealer {
        private final InMemorySecretSealer backend = new InMemorySecretSealer();
        final AtomicBoolean stalling = new AtomicBoolean();
        final CountDownLatch sealing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String seal(String key, String plaintext) {
            if (stalling.get()) {
                sealing.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("the keychain was never let go");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return backend.seal(key, plaintext);
        }

        @Override
        public Optional<String> unseal(String key, String stored) {
            return backend.unseal(key, stored);
        }

        @Override
        public void delete(String key) {
            backend.delete(key);
        }
    }

    private SubscriptionService service(SecretSealer keychain) {
        return new SubscriptionService(new ConfigStore(tempDir.resolve("config")),
                new ShareLinkParser(), tempDir, HttpClient.newHttpClient(), keychain) {
            @Override
            String fetchContent(String url) throws IOException {
                throw new IOException("not fetched in this test");
            }
        };
    }

    private static Subscription named(SubscriptionService service, String name) {
        return FxExecutor.get(() -> service.getSubscriptions().stream()
                .filter(sub -> name.equals(sub.getName())).findFirst().orElse(null), PROMPT);
    }

    @Test
    void theFxStepOfAnAddRunsWhileAnEditsSaveIsSealing() throws Exception {
        StalledKeychain keychain = new StalledKeychain();
        SubscriptionService service = service(keychain);
        service.addSubscription("A", "https://a.example/sub?token=1");
        String a = named(service, "A").getId();
        keychain.stalling.set(true);
        // An edit gives A a new link, and its save seals it.
        Thread edit = Thread.startVirtualThread(
                () -> service.updateSubscription(a, "A", "https://a.example/sub?token=new"));
        assertThat(keychain.sealing.await(10, TimeUnit.SECONDS)).as("the edit is sealing")
                .isTrue();
        // Meanwhile the user adds C, off the FX thread as the page does.
        Thread add = Thread.startVirtualThread(
                () -> service.addSubscription("C", "https://c.example/sub?token=3"));
        try {
            // Each read is a task on the FX thread; once the add's list change
            // is queued there, a read runs only after it has.
            Await.untilValue("the add's list change on the FX thread",
                    () -> FxExecutor.get(() -> service.getSubscriptions().size(), PROMPT),
                    size -> size == 2, Duration.ofSeconds(10));
        } finally {
            keychain.release.countDown();
            edit.join(10_000);
            add.join(10_000);
        }
    }

    /**
     * The write is outside the monitor, so two saves can overlap: the later
     * snapshot is the one the file keeps, sealed, whichever save finished
     * its keychain work first.
     */
    @Test
    void aSaveMadeWhileAnotherSealsLandsAfterIt() throws Exception {
        StalledKeychain keychain = new StalledKeychain();
        SubscriptionService service = service(keychain);
        service.addSubscription("A", "https://a.example/sub?token=1");
        String a = named(service, "A").getId();
        keychain.stalling.set(true);
        Thread edit = Thread.startVirtualThread(
                () -> service.updateSubscription(a, "A", "https://a.example/sub?token=new"));
        assertThat(keychain.sealing.await(10, TimeUnit.SECONDS)).isTrue();
        Thread rename = Thread.startVirtualThread(() -> service.updateSubscription(
                a, "A renamed", "https://a.example/sub?token=new"));
        try {
            Await.until("the rename in the list", () -> named(service, "A renamed") != null,
                    Duration.ofSeconds(10));
        } finally {
            keychain.release.countDown();
            edit.join(10_000);
            rename.join(10_000);
        }

        JsonNode saved = JsonMapper.builder().build()
                .readTree(Files.readString(tempDir.resolve("subscriptions.json")))
                .path("subscriptions");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).path("name").asString()).isEqualTo("A renamed");
        assertThat(saved.get(0).path("url").asString())
                .as("sealed, not the plaintext").isEqualTo(InMemorySecretSealer.FAKE_TAG);
        assertThat(keychain.backend.entries()).containsEntry(a + ".url",
                "https://a.example/sub?token=new");
    }
}
