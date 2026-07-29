package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.service.mcp.tools.GetStatusTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    @Test
    void initialize_withToken_returnsServerInfo() throws Exception {
        HttpResponse<String> response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.body());
        assertThat(json.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("vless-client");
    }

    @Test
    void toolsCall_getStatus_returnsLiveStatus() throws Exception {
        HttpResponse<String> response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_status\",\"arguments\":{}}}", TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.body());
        assertThat(json.path("result").path("structuredContent").path("state").asText())
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
