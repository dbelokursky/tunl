package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyTesterTest {

    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    private LatencyTester tester;

    @BeforeEach
    void setUp() {
        tester = new LatencyTester();
    }

    @AfterEach
    void tearDown() {
        tester.shutdown();
    }

    @Test
    void measureTcp_unreachableHost_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress(LOOPBACK);
        server.setPort(closedLoopbackPort());

        long result = tester.measureTcp(server).get(10, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testAll_emptyList_returnsEmptyMap() throws Exception {
        Map<String, LatencyTester.Result> results = tester.testAll(List.of()).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void testAll_nullList_returnsEmptyMap() throws Exception {
        Map<String, LatencyTester.Result> results = tester.testAll(null).get(5, TimeUnit.SECONDS);

        assertThat(results).isEmpty();
    }

    @Test
    void measureTcp_nullAddress_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress(null);
        server.setPort(443);

        long result = tester.measureTcp(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void measureTcp_blankAddress_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("   ");
        server.setPort(443);

        long result = tester.measureTcp(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void measureTcp_invalidPortZero_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("example.com");
        server.setPort(0);

        long result = tester.measureTcp(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void measureTcp_invalidPortTooLarge_returnsMinusOne() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setAddress("example.com");
        server.setPort(70000);

        long result = tester.measureTcp(server).get(5, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testAll_multipleServers_returnsResultForEach() throws Exception {
        ServerConfig server1 = new ServerConfig();
        server1.setAddress(LOOPBACK);
        server1.setPort(closedLoopbackPort());

        ServerConfig server2 = new ServerConfig();
        server2.setAddress(LOOPBACK);
        server2.setPort(closedLoopbackPort());

        Map<String, LatencyTester.Result> results = tester.testAll(List.of(server1, server2))
                .get(15, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        assertThat(results).containsKey(server1.getId());
        assertThat(results).containsKey(server2.getId());
        assertThat(results.get(server1.getId()).reachable()).isFalse();
        assertThat(results.get(server2.getId()).reachable()).isFalse();
    }

    // Reserves a loopback port then frees it: connecting to it is refused fast and
    // deterministically, regardless of host routing (e.g. a VPN 10.0.0.0/8 route).
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** A TCP connect to a UDP server fails whatever the server does: nothing is measured. */
    @Test
    void aUdpServerIsNotMeasuredByATcpConnect() throws Exception {
        ServerConfig server = new ServerConfig();
        server.setId("hy2");
        server.setProtocol(com.vlessclient.model.Protocol.HYSTERIA2);
        server.setAddress(LOOPBACK);
        server.setPort(closedLoopbackPort());

        LatencyTester.Result result = tester.measure(server).get(10, TimeUnit.SECONDS);

        assertThat(result.measured()).isFalse();
        assertThat(tester.lastLatency("hy2")).as("not ranked as dead").isEmpty();
    }

    /**
     * A plain TCP connect says the address answers, not that the proxy works;
     * a quick one outranked a server a real request went through.
     */
    @Test
    void aTcpConnectRanksAfterAMeasurementThroughTheProxy() throws Exception {
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1,
                InetAddress.getLoopbackAddress())) {
            ServerConfig server = new ServerConfig();
            server.setId("tcp");
            server.setProtocol(com.vlessclient.model.Protocol.VLESS);
            server.setAddress(LOOPBACK);
            server.setPort(listener.getLocalPort());

            LatencyTester.Result result = tester.measure(server).get(10, TimeUnit.SECONDS);

            assertThat(result.reachable()).isTrue();
            assertThat(tester.lastLatency("tcp").getAsLong())
                    .isGreaterThanOrEqualTo(LatencyTester.TCP_ONLY_RANK_OFFSET);
        }
    }
}
