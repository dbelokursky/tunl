package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The group monitor against a loopback stand-in for the core's Clash API.
 * What matters is the contract with the dashboard: a definite pick comes
 * through with the token attached, anything less is "unknown", and a stop
 * clears what was published.
 */
class ProxyGroupMonitorTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final ProxyGroupMonitor monitor = new ProxyGroupMonitor();

    @AfterEach
    void tearDown() {
        monitor.stop();
        if (server != null) {
            server.stop(0);
        }
    }

    private int serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxies/proxy", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void reportsTheMemberTheGroupCurrentlyUses() throws IOException {
        int port = serve(200, "{\"type\":\"URLTest\",\"now\":\"srv-abc\","
                + "\"all\":[\"srv-abc\",\"srv-def\"]}");

        assertThat(monitor.currentMember(port, "s3cret", "proxy")).contains("srv-abc");
        assertThat(authorization.get()).isEqualTo("Bearer s3cret");
    }

    @Test
    void anAnswerWithoutAPickIsUnknown() throws IOException {
        int port = serve(200, "{\"type\":\"Selector\",\"all\":[]}");

        assertThat(monitor.currentMember(port, "", "proxy")).isEmpty();
        assertThat(authorization.get()).as("no token, no header").isNull();
    }

    @Test
    void anErrorStatusIsUnknown() throws IOException {
        int port = serve(404, "{\"message\":\"proxy not found\"}");

        assertThat(monitor.currentMember(port, "", "proxy")).isEmpty();
    }

    @Test
    void aCoreThatIsNotListeningIsUnknown() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        assertThat(monitor.currentMember(port, "", "proxy")).isEmpty();
        assertThat(monitor.currentMember(0, "", "proxy")).isEmpty();
        assertThat(monitor.currentMember(port, "", " ")).isEmpty();
    }

    @Test
    void pollingPublishesThePickAndStopClearsIt() throws Exception {
        int port = serve(200, "{\"now\":\"srv-abc\"}");

        monitor.start(port, "");
        awaitTag("srv-abc");
        assertThat(monitor.currentMemberTagProperty().get()).isEqualTo("srv-abc");

        monitor.stop();
        awaitTag(null);
        assertThat(monitor.currentMemberTagProperty().get()).isNull();
    }

    /** The property lands on the FX thread when a toolkit is up, so wait for it. */
    private void awaitTag(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String current = monitor.currentMemberTagProperty().get();
            if (expected == null ? current == null : expected.equals(current)) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
