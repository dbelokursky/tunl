package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.net.http.HttpClient;
import java.util.Optional;
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
        private final Optional<Long> answer;
        final AtomicReference<String> lastTag = new AtomicReference<>();

        StubProbe(Optional<Long> answer) {
            super(HttpClient.newHttpClient());
            this.answer = answer;
        }

        @Override
        public Optional<Long> measure(int port, String secret, String tag) {
            lastTag.set(tag);
            return answer;
        }
    }

    private static ServerConfig server() {
        ServerConfig server = new ServerConfig();
        server.setName("Test");
        server.setProtocol(Protocol.VLESS);
        // Reserved-for-documentation address, so nothing real is contacted.
        server.setAddress("192.0.2.1");
        server.setPort(443);
        return server;
    }

    @Test
    void usesTheProxyProbeWhenTheCoreIsRunning() throws Exception {
        StubProbe probe = new StubProbe(Optional.of(137L));
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
        LatencyTester tester = new LatencyTester(new StubProbe(Optional.empty()));
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9090, ""));

        LatencyTester.Result result = tester.measure(server()).get();

        // Only the fallback itself is asserted, not the number it produces:
        // what a TCP connect to a reserved address does is environment
        // dependent — this machine's network answers it in 0 ms rather than
        // timing out, which would make an assertion on the value flaky.
        assertThat(result.throughProxy()).isFalse();
    }

    @Test
    void staysOnTcpWhileDisconnected() throws Exception {
        StubProbe probe = new StubProbe(Optional.of(50L));
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
        StubProbe probe = new StubProbe(Optional.of(212L));
        LatencyTester tester = new LatencyTester(probe);
        tester.setApiEndpointSupplier(() -> new LatencyTester.ApiEndpoint(9090, "token"));
        ServerConfig server = server();

        var results = tester.testAll(java.util.List.of(server)).get();

        assertThat(results.get(server.getId()).millis()).isEqualTo(212);
        assertThat(results.get(server.getId()).throughProxy()).isTrue();
    }

    @Test
    void aNullServerIsUnreachableRatherThanAnError() throws Exception {
        LatencyTester tester = new LatencyTester(new StubProbe(Optional.of(10L)));

        LatencyTester.Result result = tester.measure(null).get();

        assertThat(result.reachable()).isFalse();
    }
}
