package com.vlessclient.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        assertThat(probe().measure(port, "", "proxy-tokyo"))
                .isEqualTo(new ClashApiDelayProbe.Answer.Delay(42));
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

    /**
     * sing-box answers 504 when the test times out and 503 when it fails
     * (experimental/clashapi/proxies.go): the proxy was tried and does not
     * work, which is an answer, not the lack of one.
     */
    @Test
    @DisplayName("a proxy that timed out or failed is reported as failed")
    void aTimingOutOrFailingProxyIsFailed() {
        body = "{\"message\":\"An error occurred in the delay test\"}";
        for (int code : new int[] {503, 504}) {
            status = code;
            assertThat(probe().measure(port, "", "proxy-tokyo")).as("HTTP %d", code)
                    .isInstanceOf(ClashApiDelayProbe.Answer.Failed.class);
        }
    }

    @Test
    @DisplayName("a tag the core does not know is no answer, not a failure")
    void anUnknownTagIsNoAnswer() {
        status = 404;
        body = "{\"message\":\"Resource not found\"}";

        assertThat(probe().measure(port, "", "proxy-tokyo"))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
    }

    @Test
    @DisplayName("a negative or missing delay is empty, not a bogus number")
    void anUnusableDelayYieldsEmpty() {
        body = "{\"delay\":-1}";
        assertThat(probe().measure(port, "", "proxy-tokyo"))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);

        body = "{}";
        assertThat(probe().measure(port, "", "proxy-tokyo"))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
    }

    @Test
    @DisplayName("a body that is not JSON is swallowed, not thrown")
    void garbageYieldsEmpty() {
        body = "<html>not json</html>";

        assertThat(probe().measure(port, "", "proxy-tokyo"))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
    }

    @Test
    @DisplayName("an unusable request never reaches the network")
    void badArgumentsShortCircuit() {
        assertThat(probe().measure(port, "", null))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
        assertThat(probe().measure(port, "", "  "))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
        assertThat(probe().measure(0, "", "proxy-tokyo"))
                .isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);

        assertThat(paths).as("none of these should have been sent").isEmpty();
    }

    @Test
    @DisplayName("a dead core is empty rather than an exception")
    void aClosedPortYieldsEmpty() {
        server.stop(0);
        ClashApiDelayProbe.Answer result = probe().measure(port, "", "proxy-tokyo");
        server = null;

        assertThat(result).isInstanceOf(ClashApiDelayProbe.Answer.NoAnswer.class);
    }

    /** A health target is measured as itself, through the group, not as the stock probe URL. */
    @Test
    @DisplayName("a given target is what the core is asked to fetch")
    void aGivenTargetIsWhatTheCoreFetches() {
        probe().measure(port, "", "proxy", "https://www.google.com/generate_204");

        assertThat(paths).singleElement().asString()
                .startsWith("/proxies/proxy/delay")
                .contains("url=https%3A%2F%2Fwww.google.com%2Fgenerate_204");
    }
}
