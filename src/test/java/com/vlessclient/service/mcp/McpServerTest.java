package com.vlessclient.service.mcp;

import com.vlessclient.service.mcp.tools.GetStatusTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final FakeAppControlService control = new FakeAppControlService();

    private McpServer serverWithMutations(boolean allowMutations) {
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> allowMutations);
        server.addTool(new GetStatusTool(control));
        return server;
    }

    private JsonNode request(String method, ObjectNode params) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 1);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        return msg;
    }

    @Test
    void initialize_returnsServerInfoAndProtocol() {
        JsonNode response = serverWithMutations(true).handle(request("initialize", null));

        assertThat(response.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("vless-client");
        assertThat(response.path("result").path("protocolVersion").asString()).isNotEmpty();
        assertThat(response.path("result").path("capabilities").has("tools")).isTrue();
    }

    @Test
    void initialize_echoesRequestedProtocolVersion() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");

        JsonNode response = serverWithMutations(true).handle(request("initialize", params));

        assertThat(response.path("result").path("protocolVersion").asString()).isEqualTo("2024-11-05");
    }

    @Test
    void toolsList_includesGetStatus() {
        JsonNode response = serverWithMutations(true).handle(request("tools/list", null));

        JsonNode tools = response.path("result").path("tools");
        assertThat(tools).isNotEmpty();
        assertThat(tools.get(0).path("name").asString()).isEqualTo("get_status");
        assertThat(tools.get(0).path("inputSchema").path("type").asString()).isEqualTo("object");
    }

    @Test
    void toolsCall_getStatus_returnsStructuredContent() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "get_status");
        params.set("arguments", MAPPER.createObjectNode());

        JsonNode response = serverWithMutations(true).handle(request("tools/call", params));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("state").asString()).isEqualTo("CONNECTED");
        assertThat(result.path("structuredContent").path("activeServer").asString()).isEqualTo("Tokyo");
        assertThat(result.path("content").get(0).path("type").asString()).isEqualTo("text");
    }

    @Test
    void toolsCall_unknownTool_returnsIsError() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "does_not_exist");

        JsonNode response = serverWithMutations(true).handle(request("tools/call", params));

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
    }

    @Test
    void unknownMethod_returnsMethodNotFoundError() {
        JsonNode response = serverWithMutations(true).handle(request("no/such/method", null));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void notification_returnsNoResponse() {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", "notifications/initialized");

        assertThat(serverWithMutations(true).handle(msg)).isNull();
    }

    @Test
    void ping_returnsEmptyResult() {
        JsonNode response = serverWithMutations(true).handle(request("ping", null));

        assertThat(response.path("result").isObject()).isTrue();
        assertThat(response.path("error").isMissingNode()).isTrue();
    }

    @Test
    void mutatingTool_hiddenAndBlockedWhenMutationsDisabled() {
        AtomicBoolean called = new AtomicBoolean(false);
        McpTool mutating = new McpTool() {
            @Override
            public String name() {
                return "do_mutate";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public ObjectNode inputSchema() {
                return MAPPER.createObjectNode().put("type", "object");
            }

            @Override
            public boolean mutating() {
                return true;
            }

            @Override
            public Object call(ObjectNode arguments) {
                called.set(true);
                return "done";
            }
        };

        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> false);
        server.addTool(mutating);

        // Not advertised in tools/list.
        JsonNode list = server.handle(request("tools/list", null));
        assertThat(list.path("result").path("tools")).isEmpty();

        // And blocked on call.
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "do_mutate");
        JsonNode call = server.handle(request("tools/call", params));
        assertThat(call.path("result").path("isError").asBoolean()).isTrue();
        assertThat(called).isFalse();
    }
}
