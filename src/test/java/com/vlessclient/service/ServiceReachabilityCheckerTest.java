package com.vlessclient.service;

import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.service.ServiceReachabilityChecker.HostPort;
import com.vlessclient.service.ServiceReachabilityChecker.ProbeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceReachabilityCheckerTest {

    private ServiceReachabilityChecker checker;

    @AfterEach
    void tearDown() {
        if (checker != null) {
            checker.shutdown();
        }
    }

    @Test
    void checkAll_emptyList_returnsEmpty() throws Exception {
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<ProbeResult> results = checker.checkAll(List.of(), 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_nullList_returnsEmpty() throws Exception {
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<ProbeResult> results = checker.checkAll(null, 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_returnsOneResultPerTarget_inOrder() throws Exception {
        // Stub: a target whose url is "ok" is reachable, anything else is not.
        checker = new ServiceReachabilityChecker((t, p) -> reachable(t));

        List<HealthCheckTarget> targets = List.of(
                new HealthCheckTarget("Google", "ok"),
                new HealthCheckTarget("X", "bad"),
                new HealthCheckTarget("Third", "ok"));

        List<ProbeResult> results = checker.checkAll(targets, 1081).get(5, TimeUnit.SECONDS);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).name()).isEqualTo("Google");
        assertThat(results.get(0).reachable()).isTrue();
        assertThat(results.get(1).name()).isEqualTo("X");
        assertThat(results.get(1).reachable()).isFalse();
        assertThat(results.get(2).name()).isEqualTo("Third");
        assertThat(results.get(2).reachable()).isTrue();
    }

    @Test
    void checkAll_passesProxyPortToProbe() throws Exception {
        checker = new ServiceReachabilityChecker((t, port) ->
                new ProbeResult(t.getName(), t.getUrl(), true, port, "port=" + port));

        List<ProbeResult> results = checker
                .checkAll(List.of(new HealthCheckTarget("Google", "ok")), 9999)
                .get(5, TimeUnit.SECONDS);

        assertThat(results.get(0).latencyMs()).isEqualTo(9999);
    }

    @Test
    void allUnreachable_allFail_isTrue() {
        List<ProbeResult> results = List.of(
                new ProbeResult("Google", "u", false, -1, "x"),
                new ProbeResult("X", "u", false, -1, "x"));

        assertThat(ServiceReachabilityChecker.allUnreachable(results)).isTrue();
    }

    @Test
    void allUnreachable_oneReachable_isFalse() {
        List<ProbeResult> results = List.of(
                new ProbeResult("Google", "u", true, 10, "HTTP 204"),
                new ProbeResult("X", "u", false, -1, "x"));

        assertThat(ServiceReachabilityChecker.allUnreachable(results)).isFalse();
    }

    @Test
    void allUnreachable_emptyOrNull_isFalse() {
        assertThat(ServiceReachabilityChecker.allUnreachable(List.of())).isFalse();
        assertThat(ServiceReachabilityChecker.allUnreachable(null)).isFalse();
    }

    @Test
    void realProbe_proxyRefused_marksUnreachable() throws Exception {
        // No stub — exercises the real HTTP-through-proxy path. Pointing at a
        // closed loopback port makes the proxy connection fail fast and
        // deterministically, so the probe must report the service unreachable.
        checker = new ServiceReachabilityChecker();

        List<ProbeResult> results = checker
                .checkAll(List.of(new HealthCheckTarget("Google", "https://www.google.com/generate_204")),
                        closedLoopbackPort())
                .get(20, TimeUnit.SECONDS);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).reachable()).isFalse();
        assertThat(results.get(0).latencyMs()).isEqualTo(-1);
    }

    // ===== IP / host:port parsing =====

    @Test
    void parseHostPort_bareIp_defaultsTo443() {
        HostPort hp = ServiceReachabilityChecker.parseHostPort("1.1.1.1");
        assertThat(hp).isNotNull();
        assertThat(hp.host()).isEqualTo("1.1.1.1");
        assertThat(hp.port()).isEqualTo(443);
    }

    @Test
    void parseHostPort_ipWithPort() {
        HostPort hp = ServiceReachabilityChecker.parseHostPort("8.8.8.8:53");
        assertThat(hp.host()).isEqualTo("8.8.8.8");
        assertThat(hp.port()).isEqualTo(53);
    }

    @Test
    void parseHostPort_hostname() {
        HostPort hp = ServiceReachabilityChecker.parseHostPort("example.com:8443");
        assertThat(hp.host()).isEqualTo("example.com");
        assertThat(hp.port()).isEqualTo(8443);
    }

    @Test
    void parseHostPort_bracketedIpv6WithPort() {
        HostPort hp = ServiceReachabilityChecker.parseHostPort("[2606:4700:4700::1111]:53");
        assertThat(hp.host()).isEqualTo("2606:4700:4700::1111");
        assertThat(hp.port()).isEqualTo(53);
    }

    @Test
    void parseHostPort_bareIpv6DefaultsPort() {
        HostPort hp = ServiceReachabilityChecker.parseHostPort("2606:4700:4700::1111");
        assertThat(hp.host()).isEqualTo("2606:4700:4700::1111");
        assertThat(hp.port()).isEqualTo(443);
    }

    @Test
    void parseHostPort_rejectsHttpUrlAndGarbage() {
        assertThat(ServiceReachabilityChecker.parseHostPort("https://example.com")).isNull();
        assertThat(ServiceReachabilityChecker.parseHostPort("http://1.1.1.1")).isNull();
        assertThat(ServiceReachabilityChecker.parseHostPort("1.1.1.1:99999")).isNull();
        assertThat(ServiceReachabilityChecker.parseHostPort("1.1.1.1:0")).isNull();
        assertThat(ServiceReachabilityChecker.parseHostPort("  ")).isNull();
        assertThat(ServiceReachabilityChecker.parseHostPort(null)).isNull();
    }

    // ===== TCP-connect probe through a fake proxy =====

    @Test
    void tcpProbe_proxyEstablishesConnection_marksReachable() throws Exception {
        AtomicReference<String> seenConnect = new AtomicReference<>();
        try (FakeProxy proxy = new FakeProxy("HTTP/1.1 200 Connection established", seenConnect)) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("DNS", "1.1.1.1:853")), proxy.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).reachable()).isTrue();
            assertThat(results.get(0).latencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(seenConnect.get()).isEqualTo("CONNECT 1.1.1.1:853 HTTP/1.1");
        }
    }

    @Test
    void tcpProbe_proxyRefusesTarget_marksUnreachable() throws Exception {
        try (FakeProxy proxy = new FakeProxy("HTTP/1.1 502 Bad Gateway", new AtomicReference<>())) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("Host", "10.0.0.1")), proxy.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isFalse();
            assertThat(results.get(0).latencyMs()).isEqualTo(-1);
        }
    }

    /** Minimal HTTP-CONNECT proxy: reads the request line, replies once. */
    private static final class FakeProxy implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;

        FakeProxy(String statusLine, AtomicReference<String> firstLineSink) throws IOException {
            server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(
                                client.getInputStream(), StandardCharsets.US_ASCII));
                        String requestLine = in.readLine();
                        firstLineSink.compareAndSet(null, requestLine);
                        OutputStream out = client.getOutputStream();
                        out.write((statusLine + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException e) {
                        return;   // server closed
                    }
                }
            }, "fake-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static ProbeResult reachable(HealthCheckTarget t) {
        boolean ok = "ok".equals(t.getUrl());
        return new ProbeResult(t.getName(), t.getUrl(), ok, ok ? 42 : -1, ok ? "HTTP 204" : "refused");
    }

    // Reserves a loopback port then frees it: connecting to it is refused fast
    // and deterministically (mirrors LatencyTesterTest).
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * Through a group, a URL target is fetched by the core through it; a
     * host:port one, which is no URL the group can fetch, still goes through
     * the inbound.
     */
    @Test
    void throughAGroupOnlyUrlTargetsGoToTheGroup() throws Exception {
        List<String> viaInbound = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> viaGroup = new java.util.concurrent.CopyOnWriteArrayList<>();
        ServiceReachabilityChecker checker = new ServiceReachabilityChecker(
                (target, port) -> {
                    viaInbound.add(target.getUrl() + "@" + port);
                    return new ProbeResult(target.getName(), target.getUrl(), true, 1, "tcp");
                },
                (target, route) -> {
                    viaGroup.add(target.getUrl() + "@" + route.group());
                    return new ProbeResult(target.getName(), target.getUrl(), true, 1, "group");
                });
        try {
            List<ProbeResult> results = checker.checkAllThroughGroup(List.of(
                            new HealthCheckTarget("Google", "https://www.google.com/generate_204"),
                            new HealthCheckTarget("Box", "203.0.113.7:22")),
                    new ServiceReachabilityChecker.GroupRoute(1082, 9099, "s", "proxy"))
                    .get(5, TimeUnit.SECONDS);

            assertThat(results).extracting(ProbeResult::detail).containsExactly("group", "tcp");
            assertThat(viaGroup).containsExactly("https://www.google.com/generate_204@proxy");
            assertThat(viaInbound).containsExactly("203.0.113.7:22@1082");
        } finally {
            checker.shutdown();
        }
    }
}
