package com.vlessclient.service;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.service.ServiceReachabilityChecker.HostPort;
import com.vlessclient.service.ServiceReachabilityChecker.ProbeResult;
import com.vlessclient.testing.SelfSignedTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    // ===== through the probe inbound's SOCKS5 side =====

    /**
     * A host:port target is reachable once the core says that the tunnel
     * carried the connection. It used to be asked of the HTTP side with a
     * CONNECT, which sing-box answers with 200 before it dials: the target
     * read as reachable through a tunnel that carried nothing.
     */
    @Test
    void aTcpTargetIsReachableWhenTheTunnelCarriedTheConnection() throws Exception {
        try (FakeProbeInbound inbound = new FakeProbeInbound(FakeProbeInbound.CARRIED, null)) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("DNS", "1.1.1.1:853")), inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.reachable()).isTrue();
                assertThat(result.latencyMs()).isNotNegative();
                assertThat(result.detail()).isEqualTo("TCP 853");
            });
            assertThat(inbound.connects).containsExactly("1 1.1.1.1:853");
        }
    }

    @Test
    void aTcpTargetTheTunnelDidNotCarryIsUnreachable() throws Exception {
        try (FakeProbeInbound inbound = new FakeProbeInbound(FakeProbeInbound.REFUSED, null)) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("Host", "10.0.0.1")), inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isFalse();
            assertThat(results.get(0).latencyMs()).isEqualTo(-1);
            assertThat(inbound.connects).as("one per attempt")
                    .containsExactly("1 10.0.0.1:443", "1 10.0.0.1:443");
        }
    }

    @Test
    void anIpv6TargetGoesAsAnAddress() throws Exception {
        try (FakeProbeInbound inbound = new FakeProbeInbound(FakeProbeInbound.CARRIED, null)) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("DNS", "[2606:4700:4700::1111]:53")),
                            inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isTrue();
            assertThat(inbound.connects).containsExactly("4 2606:4700:4700:0:0:0:0:1111:53");
        }
    }

    /**
     * A plain http target is asked over a connection the tunnel carried, and
     * its name goes to the far end as a name. Through the HTTP side, a request
     * the core could not dial came back as the core's own 502, which counted
     * as the site's answer.
     */
    @Test
    void aPlainHttpTargetAnswersOverTheTunnel() throws Exception {
        try (FakeProbeInbound inbound = new FakeProbeInbound(
                FakeProbeInbound.CARRIED, "HTTP/1.1 204 No Content")) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("Cloudflare",
                            "http://cp.cloudflare.com/generate_204?from=tunl")), inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isTrue();
            assertThat(results.get(0).detail()).isEqualTo("HTTP 204");
            assertThat(inbound.connects).containsExactly("3 cp.cloudflare.com:80");
            assertThat(inbound.requests).singleElement().asString()
                    .startsWith("HEAD /generate_204?from=tunl HTTP/1.1\n")
                    .contains("Host: cp.cloudflare.com\n");
        }
    }

    @Test
    void aPlainHttpTargetTheTunnelDidNotCarryIsUnreachable() throws Exception {
        try (FakeProbeInbound inbound = new FakeProbeInbound(
                FakeProbeInbound.REFUSED, "HTTP/1.1 204 No Content")) {
            checker = new ServiceReachabilityChecker();

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("Site", "http://site.example:8080/")),
                            inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isFalse();
            assertThat(inbound.requests).as("nothing to ask once the tunnel failed").isEmpty();
        }
    }

    // ===== https: a connection of its own every time =====

    /**
     * Every round opens a connection of its own through the tunnel. The
     * checks kept one HTTP/2 connection between rounds, and it went on
     * answering while the tunnel could no longer open a new one: for ten
     * minutes the services read reachable, and were not.
     */
    @Test
    void everyRoundOpensAConnectionOfItsOwn(@TempDir Path dir) throws Exception {
        SelfSignedTls tls = SelfSignedTls.in(dir);
        HttpsServer site = siteOn(tls);
        try (FakeProbeInbound inbound = FakeProbeInbound.relayingTo(site.getAddress().getPort())) {
            checker = new ServiceReachabilityChecker(null, tls.client());
            List<HealthCheckTarget> targets = List.of(new HealthCheckTarget("Site",
                    "https://localhost:" + site.getAddress().getPort() + "/generate_204"));

            for (int round = 1; round <= 3; round++) {
                List<ProbeResult> results =
                        checker.checkAll(targets, inbound.port()).get(20, TimeUnit.SECONDS);

                assertThat(results.get(0).detail()).as("round %d", round).isEqualTo("HTTP 204");
                assertThat(inbound.connects)
                        .as("connections through the tunnel after round %d", round)
                        .hasSize(round);
            }
        } finally {
            site.stop(0);
        }
    }

    /**
     * The certificate has to name the host, as it has for a browser: a portal
     * or a filter that answers in the site's place, with a certificate of its
     * own, does not pass for the site.
     */
    @Test
    void aSiteWhoseCertificateNamesAnotherHostIsUnreachable(@TempDir Path dir) throws Exception {
        SelfSignedTls tls = SelfSignedTls.in(dir);
        HttpsServer site = siteOn(tls);
        try (FakeProbeInbound inbound = FakeProbeInbound.relayingTo(site.getAddress().getPort())) {
            checker = new ServiceReachabilityChecker(null, tls.client());

            List<ProbeResult> results = checker
                    .checkAll(List.of(new HealthCheckTarget("Site",
                            "https://site.example/generate_204")), inbound.port())
                    .get(20, TimeUnit.SECONDS);

            assertThat(results.get(0).reachable()).isFalse();
            assertThat(inbound.connects).as("the tunnel carried it").isNotEmpty();
        } finally {
            site.stop(0);
        }
    }

    /** An https site on the loopback, answering 204 at /generate_204. */
    private static HttpsServer siteOn(SelfSignedTls tls) throws Exception {
        HttpsServer site = HttpsServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
        site.createContext("/generate_204", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        site.start();
        return site;
    }

    /** A target of a scheme the checks do not speak fails alone, not the whole round. */
    @Test
    void aUrlOfAnotherSchemeIsUnreachableRatherThanAFailedRound() throws Exception {
        checker = new ServiceReachabilityChecker();

        List<ProbeResult> results = checker
                .checkAll(List.of(new HealthCheckTarget("Files", "ftp://files.example/")),
                        closedLoopbackPort())
                .get(20, TimeUnit.SECONDS);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.reachable()).isFalse();
            assertThat(result.detail()).isEqualTo("invalid url");
        });
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
}
