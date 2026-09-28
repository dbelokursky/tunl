package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A client that a service rarely needs is built when it is first needed, once. */
class LazyHttpClientTest {

    @Test
    void theClientIsBuiltOnFirstUseAndOnlyOnce() throws IOException {
        AtomicInteger builds = new AtomicInteger();
        LazyHttpClient lazy = LazyHttpClient.of(() -> {
            builds.incrementAndGet();
            return HttpClient.newHttpClient();
        });
        assertThat(lazy.isBuilt()).isFalse();
        assertThat(builds.get()).isZero();

        HttpClient first = lazy.get();
        assertThat(lazy.get()).isSameAs(first);
        assertThat(builds.get()).isEqualTo(1);
        lazy.shutdownNow();
        assertThat(first.isTerminated() || awaitTermination(first)).isTrue();
    }

    @Test
    void shuttingDownAClientThatWasNeverBuiltBuildsNothingAndLaterUseFails() {
        AtomicInteger builds = new AtomicInteger();
        LazyHttpClient lazy = LazyHttpClient.of(() -> {
            builds.incrementAndGet();
            return HttpClient.newHttpClient();
        });

        lazy.shutdownNow();

        assertThat(builds.get()).isZero();
        assertThatThrownBy(lazy::get).isInstanceOf(IOException.class);
    }

    @Test
    void anInstallerThatFindsItsCoreNeverBuildsAClient(@org.junit.jupiter.api.io.TempDir
                                                       java.nio.file.Path dir) {
        SingBoxInstaller installer = new SingBoxInstaller(dir);
        installer.findExisting();
        assertThat(installer.hasBuiltHttpClient()).isFalse();
        installer.shutdown();
    }

    private static boolean awaitTermination(HttpClient client) {
        try {
            return client.awaitTermination(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
