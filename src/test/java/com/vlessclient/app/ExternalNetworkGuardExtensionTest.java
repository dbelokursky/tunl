package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class ExternalNetworkGuardExtensionTest {

    @Test
    void delegatesUrisThatDoNotAddressANetworkHost() {
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        ProxySelector selector = new ExternalNetworkGuardExtension.NoNetworkProxySelector(
                directDelegate(), violations);

        assertThat(selector.select(URI.create("file:/tmp/ui-test.css")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsAndRecordsExternalConnections() {
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        ProxySelector selector = new ExternalNetworkGuardExtension.NoNetworkProxySelector(
                null, violations);

        assertThatThrownBy(() -> selector.select(URI.create("https://api.github.com/releases")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Network access is disabled");
        assertThat(violations)
                .singleElement()
                .asString()
                .contains("https://api.github.com/releases")
                .contains("thread");
    }

    @Test
    void rejectsAndRecordsLoopbackConnections() {
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        ProxySelector selector = new ExternalNetworkGuardExtension.NoNetworkProxySelector(
                null, violations);

        assertThatThrownBy(() -> selector.select(URI.create("socket://127.0.0.1:45555")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Network access is disabled");
        assertThat(violations)
                .singleElement()
                .asString()
                .contains("socket://127.0.0.1:45555");
    }

    private static ProxySelector directDelegate() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(
                    URI uri, java.net.SocketAddress address, java.io.IOException failure) {
                // Not used by these tests.
            }
        };
    }
}
