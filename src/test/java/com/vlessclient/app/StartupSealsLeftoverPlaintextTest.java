package com.vlessclient.app;

import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.testing.Await;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A start with a keychain seals what an earlier run saved in plaintext.
 *
 * <p>A server's credential and a subscription's URL are sealed only when
 * their file is written, so one saved while no keychain was there stayed in
 * plaintext at every later start that had one, until some change wrote the
 * file again. The stores seal such a file when asked
 * ({@code ConfigStoreSecretsTest}, {@code SubscriptionServiceSecretsTest});
 * this is the start that asks them. Nothing covered it: without that step
 * every test stayed green, and every start left the files as they were.</p>
 */
class StartupSealsLeftoverPlaintextTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final String URL = "https://sub.example.com/api/sub/Xq7TokenZ9w2";

    @TempDir
    Path dir;

    /** The data dir the UI tests' graphs share, as surefire set it. */
    private String sharedDataDir;

    /** The graph keeps its files in this test's directory, not in the shared one. */
    @BeforeEach
    void buildTheGraphInThisTestsDirectory() {
        sharedDataDir = System.getProperty(PlatformPaths.DATA_DIR_PROPERTY);
        System.setProperty(PlatformPaths.DATA_DIR_PROPERTY, dir.toString());
    }

    /** Leaves the graph the UI tests build, on their data dir, which a later test may expect. */
    @AfterEach
    void restoreTheTestGraph() {
        ServiceLocator.shutdown();
        if (sharedDataDir != null) {
            System.setProperty(PlatformPaths.DATA_DIR_PROPERTY, sharedDataDir);
        } else {
            System.clearProperty(PlatformPaths.DATA_DIR_PROPERTY);
        }
        UiTestServices.initialize();
    }

    @Test
    void aStartWithAKeychainSealsTheCredentialsAndUrlsLeftInPlaintext() throws Exception {
        Files.writeString(dir.resolve("servers.json"), """
                {"config_version":1,"servers":[
                  {"id":"s-1","name":"Tokyo","protocol":"vless",
                   "address":"192.0.2.1","port":443,"uuid":"%s"}]}""".formatted(UUID),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subscriptions.json"), """
                {"config_version":1,"subscriptions":[
                  {"id":"a","name":"Provider","url":"%s","serverIds":[]}]}""".formatted(URL),
                StandardCharsets.UTF_8);
        InMemorySecretSealer keychain = new InMemorySecretSealer();

        ServiceLocator.shutdown();
        ServiceLocator.initialize(ServiceLocator.StartupMode.TEST, keychain);

        Await.until("servers.json and subscriptions.json written sealed",
                () -> writtenWithout("servers.json", UUID)
                        && writtenWithout("subscriptions.json", URL),
                Duration.ofSeconds(10));
        assertThat(read("servers.json")).contains(InMemorySecretSealer.FAKE_TAG);
        assertThat(read("subscriptions.json")).contains(InMemorySecretSealer.FAKE_TAG);
        assertThat(keychain.entries()).as("what the keychain holds now")
                .containsValues(UUID, URL);
    }

    /** Whether the file no longer holds {@code plaintext}; not while it cannot be read. */
    private boolean writtenWithout(String name, String plaintext) {
        try {
            return !Files.readString(dir.resolve(name), StandardCharsets.UTF_8)
                    .contains(plaintext);
        } catch (IOException e) {
            return false;
        }
    }

    private String read(String name) {
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
