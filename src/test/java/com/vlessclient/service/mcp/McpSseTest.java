package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.service.mcp.tools.GetStatusTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class McpSseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "sse-token";

    private final FakeAppControlService control = new FakeAppControlService();

    private McpNotifier notifier;
    private McpHttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        McpServer mcp = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        mcp.addTool(new GetStatusTool(control));
        mcp.setLoggingCapability(true);
        notifier = new McpNotifier(MAPPER);
        server = new McpHttpServer(0, TOKEN, mcp, MAPPER, notifier);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpRequest sseRequest(String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.boundPort() + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    @Test
    void sse_withoutToken_returns401() throws Exception {
        HttpResponse<String> resp = client.send(sseRequest(null), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void sse_streamsBroadcastLogLine() throws Exception {
        CompletableFuture<HttpResponse<Stream<String>>> future =
                client.sendAsync(sseRequest(TOKEN), HttpResponse.BodyHandlers.ofLines());
        HttpResponse<Stream<String>> response = future.get(3, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);

        // Wait until the server-side subscription exists, then push a line.
        for (int i = 0; i < 100 && notifier.subscriberCount() == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(notifier.subscriberCount()).isGreaterThan(0);
        notifier.broadcastLog("hello from sing-box");

        String dataLine = response.body()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .orElse("");
        assertThat(dataLine).contains("notifications/message").contains("hello from sing-box");
    }

    @Test
    void sse_capsConcurrentStreams() throws Exception {
        List<InputStream> held = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                HttpResponse<InputStream> resp = client.send(sseRequest(TOKEN),
                        HttpResponse.BodyHandlers.ofInputStream());
                held.add(resp.body());
            }
            for (int i = 0; i < 100 && notifier.subscriberCount() < 8; i++) {
                Thread.sleep(20);
            }
            assertThat(notifier.subscriberCount()).isEqualTo(8);

            HttpResponse<String> overflow = client.send(sseRequest(TOKEN),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(overflow.statusCode()).isEqualTo(503);
        } finally {
            for (InputStream in : held) {
                in.close();
            }
        }
    }
}
