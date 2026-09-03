package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunnelProxySelectorTest {

    /** A fallback that records what it was asked and answers with a fixed list. */
    private static final class RecordingSelector extends ProxySelector {
        final List<URI> selected = new ArrayList<>();
        final List<SocketAddress> failed = new ArrayList<>();
        private final List<Proxy> answer;

        RecordingSelector(List<Proxy> answer) {
            this.answer = answer;
        }

        @Override
        public List<Proxy> select(URI uri) {
            selected.add(uri);
            return answer;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            failed.add(sa);
        }
    }

    private static final URI PUBLIC = URI.create("https://api.github.com/repos/x/releases");

    private static TunnelProxySelector selector(OptionalInt port, ProxySelector fallback) {
        return new TunnelProxySelector(() -> port, () -> fallback);
    }

    @Test
    void routesPublicHostsThroughTheLocalInboundWhileTheTunnelIsUp() {
        RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));

        List<Proxy> proxies = selector(OptionalInt.of(2081), fallback).select(PUBLIC);

        assertThat(proxies).hasSize(1);
        Proxy proxy = proxies.get(0);
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(proxy.address())
                .isEqualTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), 2081));
    }

    @Test
    void answersLikeTheFallbackWhileTheTunnelIsDown() {
        Proxy corporate = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy.corp", 3128));
        RecordingSelector fallback = new RecordingSelector(List.of(corporate));

        assertThat(selector(OptionalInt.empty(), fallback).select(PUBLIC))
                .containsExactly(corporate);
    }

    @Test
    void consultsTheFallbackEvenWhenTheTunnelWins() {
        // The test suite's no-network guard lives in the default selector; a
        // request that bypassed it would be invisible to every UI test.
        RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));

        selector(OptionalInt.of(2081), fallback).select(PUBLIC);

        assertThat(fallback.selected).containsExactly(PUBLIC);
    }

    @Test
    void neverProxiesLoopbackTargets() {
        RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));
        TunnelProxySelector selector = selector(OptionalInt.of(2081), fallback);

        for (String local : List.of(
                "http://127.0.0.1:9090/traffic",
                "http://localhost:55555/mcp",
                "http://[::1]:9090/connections",
                "http://mcp.localhost/")) {
            assertThat(selector.select(URI.create(local)))
                    .as(local)
                    .containsExactly(Proxy.NO_PROXY);
        }
        assertThat(TunnelProxySelector.isLoopback("api.github.com")).isFalse();
        assertThat(TunnelProxySelector.isLoopback("10.0.0.1")).isFalse();
    }

    @Test
    void emptyFallbackAnswerMeansDirect() {
        RecordingSelector fallback = new RecordingSelector(List.of());

        assertThat(selector(OptionalInt.empty(), fallback).select(PUBLIC))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(new TunnelProxySelector(OptionalInt::empty, () -> null).select(PUBLIC))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void rejectsANullUriLikeTheContractSays() {
        RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));

        assertThatThrownBy(() -> selector(OptionalInt.empty(), fallback).select(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesOnlyWhenConnectedAndNotDeclaredBroken() {
        for (TunnelHealth health : TunnelHealth.values()) {
            boolean expected = health != TunnelHealth.BROKEN;
            assertThat(TunnelProxySelector.carriesTraffic(ConnectionState.CONNECTED, health))
                    .as("CONNECTED + " + health)
                    .isEqualTo(expected);
        }
        for (ConnectionState state : List.of(ConnectionState.DISCONNECTED,
                ConnectionState.CONNECTING, ConnectionState.ERROR)) {
            assertThat(TunnelProxySelector.carriesTraffic(state, TunnelHealth.HEALTHY))
                    .as(state.name())
                    .isFalse();
        }
    }

    @Test
    void connectFailedIsHandedToTheFallbackForForeignProxies() {
        RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));
        TunnelProxySelector selector = selector(OptionalInt.of(2081), fallback);
        InetSocketAddress corporate = new InetSocketAddress("proxy.corp", 3128);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 2081);

        selector.connectFailed(PUBLIC, corporate, new IOException("refused"));
        selector.connectFailed(PUBLIC, local, new IOException("refused"));

        // Our own inbound going away is our business, not the JVM default's.
        assertThat(fallback.failed).containsExactly(corporate);
        assertThatThrownBy(() -> selector.connectFailed(null, local, new IOException("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRealRequestReachesTheHostThroughTheProxy() throws Exception {
        // A stand-in for sing-box's HTTP inbound: records the absolute request
        // URI a proxied client sends and answers for the "remote" host. The
        // target host is never resolved by the client - with a proxy, name
        // resolution is the proxy's job - which is the whole point for a user
        // whose resolver cannot see the subscription host.
        AtomicReference<URI> seen = new AtomicReference<>();
        HttpServer inbound = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        inbound.createContext("/", exchange -> {
            seen.set(exchange.getRequestURI());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        inbound.start();
        try {
            int port = inbound.getAddress().getPort();
            RecordingSelector fallback = new RecordingSelector(List.of(Proxy.NO_PROXY));
            HttpClient client = HttpClient.newBuilder()
                    .proxy(selector(OptionalInt.of(port), fallback))
                    .build();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://subscription.example/list"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
            assertThat(seen.get()).isEqualTo(URI.create("http://subscription.example/list"));
        } finally {
            inbound.stop(0);
        }
    }
}
