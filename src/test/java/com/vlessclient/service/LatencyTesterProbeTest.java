package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which measurement the tester uses, and how it says so.
 *
 * <p>The distinction matters to the user: a TCP connect to {@code host:port}
 * reports a healthy 40 ms for a server that is reachable but unusable —
 * blocked, throttled, or refusing the proxy handshake. The through-proxy probe
 * is the number that predicts browsing, so it is preferred whenever the core
 * is running, and the result records which one produced it.</p>
 */
class LatencyTesterProbeTest {

    /** Stands in for the Clash API without a running core. */
    private static class StubProbe extends ClashApiDelayProbe {
        private final ClashApiDelayProbe.Answer answer;
        final AtomicReference<String> lastTag = new AtomicReference<>();

        StubProbe(ClashApiDelayProbe.Answer answer) {
            super(HttpClient.newHttpClient());
            this.answer = answer;
        }

        @Override
        public ClashApiDelayProbe.Answer measure(int port, String secret, String tag) {
            lastTag.set(tag);
            return answer;
        }
    }

    private static ServerConfig server() throws IOException {
        ServerConfig server = new ServerConfig();
        server.setName("Test");
        server.setProtocol(Protocol.VLESS);
        // A loopback port nothing listens on, so the TCP fallback is refused at
        // once. A reserved address such as 192.0.2.1 left each fallback test
        // waiting out the tester's 5 s connect timeout on CI runners.
        server.setAddress("127.0.0.1");
        server.setPort(closedPort());
        return server;
    }

    /** A loopback port that was free a moment ago, so a connect to it is refused. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void usesTheProxyProbeWhenTheCoreIsRunning() throws Exception {
        StubProbe probe = new StubProbe(new ClashApiDelayProbe.Answer.Delay(137));
        LatencyTester tester = new LatencyTester(probe);
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9090, "token"));
        ServerConfig server = server();

        LatencyTester.Result result = tester.measure(server).get();

        assertThat(result.millis()).isEqualTo(137);
        assertThat(result.throughProxy()).isTrue();
        // Addressed by the server's own tag, the same one the group references.
        assertThat(probe.lastTag.get())
                .isEqualTo(com.vlessclient.service.outbound.OutboundTags.server(server));
    }

    /**
     * The core may be up but not know this server — in a pinned selection the
     * config carries only the active one. That must fall back rather than
     * report the server as unreachable.
     */
    @Test
    void fallsBackToTcpWhenTheProxyIsNotKnownToTheCore() throws Exception {
        LatencyTester tester = new LatencyTester(new StubProbe(new ClashApiDelayProbe.Answer.NoAnswer()));
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9090, ""));

        LatencyTester.Result result = tester.measure(server()).get();

        // Only the fallback itself is asserted, not the number it produces:
        // the connect is refused, so there is no latency to compare.
        assertThat(result.throughProxy()).isFalse();
    }

    /**
     * The core answers 503 when the proxy fails the test and 504 when it
     * times out (sing-box 1.14, experimental/clashapi/proxies.go): the proxy
     * was tried and does not work. Both used to send the tester to a TCP
     * connect, which a server with expired credentials still passes, so it
     * showed as a fast one and sorted to the top of the list.
     */
    @Test
    void aProxyTheCoreTriedAndFoundBrokenIsUnreachable() throws Exception {
        for (int status : new int[] {503, 504}) {
            HttpServer core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            core.createContext("/", exchange -> {
                byte[] body = "{\"message\":\"An error occurred in the delay test\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            core.start();
            // The server's own port accepts a connection, as a server whose
            // credentials expired still does.
            try (ServerSocket open = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
                ServerConfig server = server();
                server.setPort(open.getLocalPort());
                LatencyTester tester = new LatencyTester(
                        new ClashApiDelayProbe(HttpClient.newHttpClient()));
                tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(
                        core.getAddress().getPort(), ""));

                LatencyTester.Result result = tester.measure(server).get();

                assertThat(result.reachable()).as("reachable after HTTP %d", status).isFalse();
                assertThat(result.throughProxy()).as("through the proxy after HTTP %d", status)
                        .isTrue();
            } finally {
                core.stop(0);
            }
        }
    }

    @Test
    void staysOnTcpWhileDisconnected() throws Exception {
        StubProbe probe = new StubProbe(new ClashApiDelayProbe.Answer.Delay(50));
        LatencyTester tester = new LatencyTester(probe);
        tester.setApiEndpointSupplier(() -> null);

        LatencyTester.Result result = tester.measure(server()).get();

        assertThat(result.throughProxy()).isFalse();
        assertThat(probe.lastTag.get())
                .as("the probe must not be called with no core running")
                .isNull();
    }

    /**
     * The regression guard for how the probe first shipped: it was reachable
     * only through {@link LatencyTester#measure}, while the app measures with
     * {@link LatencyTester#testAll}, which still ran the TCP path. Every
     * measurement the user could actually trigger was the old one, and no test
     * noticed because they all called {@code measure} directly.
     */
    @Test
    void theBulkPathAlsoGoesThroughTheProxy() throws Exception {
        StubProbe probe = new StubProbe(new ClashApiDelayProbe.Answer.Delay(212));
        LatencyTester tester = new LatencyTester(probe);
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9090, "token"));
        ServerConfig server = server();

        var results = tester.testAll(java.util.List.of(server)).get();

        assertThat(results.get(server.getId()).millis()).isEqualTo(212);
        assertThat(results.get(server.getId()).throughProxy()).isTrue();
    }

    @Test
    void aNullServerIsUnreachableRatherThanAnError() throws Exception {
        LatencyTester tester = new LatencyTester(new StubProbe(new ClashApiDelayProbe.Answer.Delay(10)));

        LatencyTester.Result result = tester.measure(null).get();

        assertThat(result.reachable()).isFalse();
    }
}
