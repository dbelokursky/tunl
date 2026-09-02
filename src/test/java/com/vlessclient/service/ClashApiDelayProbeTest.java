package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code measure} call, against a real local HTTP server.
 *
 * <p>The only test that touched this class before overrode {@code measure}
 * outright ({@code LatencyTesterProbeTest.StubProbe}), so the seam declared in
 * the package-private constructor was never used and the method itself — URL
 * shape, auth header, status handling, JSON reading — ran nowhere. Every proxy
 * latency number the dashboard shows comes through here.</p>
 */
class ClashApiDelayProbeTest {

    private HttpServer server;
    private int port;
    private int status = 200;
    private String body = "{\"delay\":42}";
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth == null ? "" : auth);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClashApiDelayProbe probe() {
        return new ClashApiDelayProbe(HttpClient.newHttpClient());
    }

    @Test
    @DisplayName("a delay is read out of the response")
    void aDelayIsReturned() {
        assertThat(probe().measure(port, "", "proxy-tokyo")).contains(42L);
        assertThat(paths).singleElement().asString()
                .as("the tag is URL-encoded into the path")
                .startsWith("/proxies/proxy-tokyo/delay")
                .contains("timeout=");
    }

    @Test
    @DisplayName("a secret is sent as a bearer token, a blank one is not")
    void theSecretBecomesAnAuthorizationHeader() {
        probe().measure(port, "s3cr3t", "proxy-tokyo");
        assertThat(authHeaders).singleElement().isEqualTo("Bearer s3cr3t");

        authHeaders.clear();
        probe().measure(port, "  ", "proxy-tokyo");
        assertThat(authHeaders).singleElement().isEqualTo("");
    }

    @Test
    @DisplayName("a non-200 is a result, not an error")
    void aTimingOutProxyYieldsEmpty() {
        status = 504;
        body = "{\"message\":\"An error occurred in the delay test\"}";

        assertThat(probe().measure(port, "", "proxy-tokyo")).isEmpty();
    }

    @Test
    @DisplayName("a negative or missing delay is empty, not a bogus number")
    void anUnusableDelayYieldsEmpty() {
        body = "{\"delay\":-1}";
        assertThat(probe().measure(port, "", "proxy-tokyo")).isEmpty();

        body = "{}";
        assertThat(probe().measure(port, "", "proxy-tokyo")).isEmpty();
    }

    @Test
    @DisplayName("a body that is not JSON is swallowed, not thrown")
    void garbageYieldsEmpty() {
        body = "<html>not json</html>";

        assertThat(probe().measure(port, "", "proxy-tokyo")).isEmpty();
    }

    @Test
    @DisplayName("an unusable request never reaches the network")
    void badArgumentsShortCircuit() {
        assertThat(probe().measure(port, "", null)).isEmpty();
        assertThat(probe().measure(port, "", "  ")).isEmpty();
        assertThat(probe().measure(0, "", "proxy-tokyo")).isEmpty();

        assertThat(paths).as("none of these should have been sent").isEmpty();
    }

    @Test
    @DisplayName("a dead core is empty rather than an exception")
    void aClosedPortYieldsEmpty() {
        server.stop(0);
        Optional<Long> result = probe().measure(port, "", "proxy-tokyo");
        server = null;

        assertThat(result).isEmpty();
    }
}
