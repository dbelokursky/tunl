package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.TestServers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server list is a live {@code ObservableList} wrapped by a
 * {@code FilteredList}/{@code SortedList}/{@code ListView}, so it may only be
 * mutated on the JavaFX Application Thread — but callers arrive from virtual
 * threads, the refresh scheduler, MCP workers and the AWT tray thread.
 *
 * <p>The marshalling that fixes this is also the easiest way to introduce a
 * deadlock, so these tests pin both halves: mutations land on the FX thread,
 * and no path waits for the FX thread while holding the store's monitor.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class ConfigStoreFxThreadTest {

    @TempDir
    Path tempDir;

    private static ServerConfig server(String name) {
        return TestServers.server()
                .name(name)
                .address(name + ".example")
                .port(443)
                .uuid("uuid-of-" + name)
                .build();
    }

    @Test
    @Timeout(20)
    @DisplayName("a mutation from a background thread runs on the FX thread")
    void backgroundMutationIsMarshalled() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        List<Boolean> sawFxThread = new ArrayList<>();
        store.getServers().addListener((javafx.collections.ListChangeListener<ServerConfig>) c ->
                sawFxThread.add(Platform.isFxApplicationThread()));

        Thread worker = new Thread(() -> store.addServer(server("s1")), "worker");
        worker.start();
        worker.join(10_000);

        assertThat(store.getServers()).hasSize(1);
        assertThat(sawFxThread)
                .as("the change event must be delivered on the FX thread, "
                        + "because a listener on it touches live controls")
                .containsExactly(true);
    }

    @Test
    @Timeout(30)
    @DisplayName("the FX thread and a background thread cannot deadlock each other")
    void concurrentMutationsDoNotDeadlock() throws Exception {
        ConfigStore store = new ConfigStore(tempDir);
        store.addServer(server("seed"));

        int rounds = 40;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Background thread: the shape that used to hold the monitor while
        // waiting for the FX thread.
        Thread background = new Thread(() -> {
            try {
                go.await();
                for (int i = 0; i < rounds; i++) {
                    store.addServer(server("bg" + i));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        }, "background-mutator");

        // FX thread: hammering a synchronized method from the other side, which
        // is the half that closes the cycle.
        Runnable fxWork = () -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    store.setActiveServer("no-such-id-" + i);
                    store.getServerById("no-such-id-" + i);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        };

        background.start();
        Platform.runLater(() -> {
            go.countDown();
            fxWork.run();
        });

        assertThat(done.await(20, TimeUnit.SECONDS))
                .as("both sides finished — a deadlock would time out here")
                .isTrue();
        assertThat(failure.get()).isNull();
        assertThat(store.getServers()).hasSize(1 + rounds);
    }

    @Test
    @Timeout(20)
    @DisplayName("the disk write stays off the FX thread")
    void savingDoesNotRunOnTheFxThread() throws Exception {
        AtomicReference<Boolean> savedOnFx = new AtomicReference<>();
        ConfigStore store = new ConfigStore(tempDir) {
            @Override
            synchronized void saveServers() {
                savedOnFx.compareAndSet(null, Platform.isFxApplicationThread());
                super.saveServers();
            }
        };

        Thread worker = new Thread(() -> store.addServer(server("s1")), "worker");
        worker.start();
        worker.join(10_000);

        assertThat(savedOnFx.get())
                .as("serializing and sealing the whole list must not run on the "
                        + "FX thread — that is the jank this split exists to avoid")
                .isFalse();
    }

    /**
     * The FX thread wrote the list itself whenever no other write was under
     * way, and sealing a credential starts a keychain process: pasting,
     * restoring or importing servers froze the window, a process per new
     * credential. A save asked for there is written off it, and a quit waits
     * for it.
     */
    @Test
    @Timeout(20)
    @DisplayName("a save asked for on the FX thread is written off it")
    void aSaveAskedForOnTheFxThreadIsWrittenOffIt() throws Exception {
        AtomicReference<Boolean> sealedOnFx = new AtomicReference<>();
        com.vlessclient.platform.InMemorySecretSealer keychain =
                new com.vlessclient.platform.InMemorySecretSealer();
        com.vlessclient.platform.SecretSealer recording =
                new com.vlessclient.platform.SecretSealer() {
                    @Override
                    public boolean isAvailable() {
                        return keychain.isAvailable();
                    }

                    @Override
                    public String seal(String key, String plaintext) {
                        sealedOnFx.compareAndSet(null, Platform.isFxApplicationThread());
                        return keychain.seal(key, plaintext);
                    }

                    @Override
                    public java.util.Optional<String> unseal(String key, String stored) {
                        return keychain.unseal(key, stored);
                    }

                    @Override
                    public void delete(String key) {
                        keychain.delete(key);
                    }
                };
        ConfigStore store = new ConfigStore(tempDir, recording);
        store.getSettings().setStoreSecretsSecurely(true);
        CountDownLatch added = new CountDownLatch(1);

        Platform.runLater(() -> {
            store.addServer(server("s1"));
            added.countDown();
        });

        assertThat(added.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.awaitPendingWrites(5000)).as("the write finished").isTrue();
        assertThat(sealedOnFx.get()).as("sealed, and not on the FX thread").isFalse();
    }
}
