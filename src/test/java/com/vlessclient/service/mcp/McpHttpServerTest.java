package com.vlessclient.service.mcp;

import com.vlessclient.service.mcp.tools.GetStatusTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpServerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String TOKEN = "test-secret-token";

    private final FakeAppControlService control = new FakeAppControlService();

    private McpHttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        McpServer mcp = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        mcp.addTool(new GetStatusTool(control));
        server = new McpHttpServer(0, TOKEN, mcp, MAPPER);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> post(String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.boundPort() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingToken_returns401() throws Exception {
        HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void wrongToken_returns401() throws Exception {
        HttpResponse<String> response =
                post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "nope");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void getRequest_returns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.boundPort() + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    /**
     * A raw request, so the test controls the Host header the JDK client
     * would otherwise set for it.
     *
     * @return the response's status line
     */
    private String rawStatusLine(String... headerLines) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        StringBuilder request = new StringBuilder("POST /mcp HTTP/1.1\r\n");
        for (String header : headerLines) {
            request.append(header).append("\r\n");
        }
        request.append("Authorization: Bearer ").append(TOKEN).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length()).append("\r\n")
                .append("Connection: close\r\n\r\n")
                .append(body);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.boundPort())) {
            socket.getOutputStream().write(
                    request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return response.split("\r\n", 2)[0];
        }
    }

    /**
     * DNS rebinding: a hostile page resolves its own name to 127.0.0.1 and
     * the browser sends the request with that name as Host. The token would
     * still fail it; the header check fails it a class earlier.
     */
    @Test
    void foreignHostHeader_returns403() throws Exception {
        assertThat(rawStatusLine("Host: evil.example:" + server.boundPort())).contains("403");
    }

    @Test
    void foreignOrigin_returns403() throws Exception {
        assertThat(rawStatusLine("Host: 127.0.0.1:" + server.boundPort(),
                "Origin: http://evil.example")).contains("403");
        assertThat(rawStatusLine("Host: 127.0.0.1:" + server.boundPort(),
                "Origin: null")).contains("403");
    }

    @Test
    void loopbackHostAndOrigin_areAccepted() throws Exception {
        assertThat(rawStatusLine("Host: localhost:" + server.boundPort(),
                "Origin: http://127.0.0.1:" + server.boundPort())).contains("200");
        assertThat(rawStatusLine("Host: [::1]:" + server.boundPort())).contains("200");
    }

    @Test
    void isLocalOrigin_decidesOnHostAndOrigin() {
        assertThat(McpHttpServer.isLocalOrigin("127.0.0.1:5555", null)).isTrue();
        assertThat(McpHttpServer.isLocalOrigin("localhost", "")).isTrue();
        assertThat(McpHttpServer.isLocalOrigin("[::1]:5555", "http://localhost:5555")).isTrue();
        assertThat(McpHttpServer.isLocalOrigin("evil.example", null)).isFalse();
        assertThat(McpHttpServer.isLocalOrigin("127.0.0.1", "http://evil.example")).isFalse();
        assertThat(McpHttpServer.isLocalOrigin(null, null)).isFalse();
        assertThat(McpHttpServer.isLocalOrigin("127.0.0.1", "not a url ://")).isFalse();
    }

    @Test
    void initialize_withToken_returnsServerInfo() throws Exception {
        HttpResponse<String> response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.body());
        assertThat(json.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("vless-client");
    }

    @Test
    void toolsCall_getStatus_returnsLiveStatus() throws Exception {
        HttpResponse<String> response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_status\",\"arguments\":{}}}", TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.body());
        assertThat(json.path("result").path("structuredContent").path("state").asString())
                .isEqualTo("CONNECTED");
        assertThat(json.path("result").path("structuredContent").path("socksPort").asInt())
                .isEqualTo(1080);
    }

    @Test
    void notification_returns202() throws Exception {
        HttpResponse<String> response =
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN);
        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    void malformedJson_returns400() throws Exception {
        HttpResponse<String> response = post("{not json", TOKEN);
        assertThat(response.statusCode()).isEqualTo(400);
    }
}
