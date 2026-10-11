package com.vlessclient.service.mcp;

import com.vlessclient.service.mcp.tools.GetStatusTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
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
    void mutatingTool_blockedWhenMutationsDisabled() {
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

        // Blocked on call.
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "do_mutate");
        JsonNode call = server.handle(request("tools/call", params));
        assertThat(call.path("result").path("isError").asBoolean()).isTrue();
        assertThat(call.path("result").path("content").get(0).path("text").asString())
                .contains("mcp_allow_mutations");
        assertThat(called).isFalse();
    }

    /**
     * The list said it never changes, yet hid the change tools while changes
     * were off and sent no notice when they came on: an agent that listed the
     * tools first had no connect until its session restarted. The list is the
     * same either way now, and a call says when changes are off.
     */
    @Test
    void mutatingTools_areListedWhetherOrNotChangesAreAllowed() {
        AtomicBoolean allowed = new AtomicBoolean(false);
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, allowed::get);
        server.addTool(tool("do_mutate", true, args -> "done"));

        JsonNode before = server.handle(request("tools/list", null));
        allowed.set(true);
        JsonNode after = server.handle(request("tools/list", null));

        assertThat(before.path("result").path("tools")).hasSize(1);
        assertThat(before.path("result")).isEqualTo(after.path("result"));
        assertThat(server.handle(request("initialize", null)).path("result")
                .path("capabilities").path("tools").path("listChanged").asBoolean(true))
                .isFalse();
    }

    /** A tool body, which may throw what a real tool body can. */
    private interface Body {
        Object call(ObjectNode arguments) throws McpToolException;
    }

    private static McpTool tool(String name, boolean mutating, Body body) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
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
                return mutating;
            }

            @Override
            public Object call(ObjectNode arguments) throws McpToolException {
                return body.call(arguments);
            }
        };
    }

    private JsonNode callTool(McpServer server, String name) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.createObjectNode().put("url",
                "https://provider.example/sub?token=SECRET"));
        return server.handle(request("tools/call", params));
    }

    /**
     * A change tool that ended in an unchecked exception, such as an interrupt
     * while it waited for the UI thread after the core had started, left no
     * line in the audit log and came back as a JSON-RPC protocol error.
     */
    @Test
    void aToolThatThrowsUnchecked_isAuditedAndReturnedAsAToolError() {
        List<String> audited = new ArrayList<>();
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.setAuditLog((tool, args, error, message) ->
                audited.add(tool + ":" + error + ":" + message));
        server.addTool(tool("connect", true, args -> {
            throw new IllegalStateException("Interrupted waiting for the UI thread");
        }));
        server.addTool(tool("set_setting", true, args -> {
            throw new IllegalArgumentException(
                    "bad value for https://provider.example/sub?token=SECRET");
        }));
        server.addTool(tool("get_status", false, args -> {
            throw new NullPointerException();
        }));

        JsonNode connect = callTool(server, "connect");
        JsonNode setSetting = callTool(server, "set_setting");
        JsonNode status = callTool(server, "get_status");

        for (JsonNode response : List.of(connect, setSetting, status)) {
            assertThat(response.has("error")).as(response.toString()).isFalse();
            assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        }
        assertThat(connect.path("result").path("content").get(0).path("text").asString())
                .contains("Interrupted waiting for the UI thread");
        assertThat(status.path("result").path("content").get(0).path("text").asString())
                .contains("NullPointerException");
        assertThat(setSetting.toString()).doesNotContain("SECRET");
        assertThat(audited).containsExactly(
                "connect:true:Interrupted waiting for the UI thread",
                "set_setting:true:bad value for https://provider.example/…");
    }

    /**
     * initialize echoed whatever version the client named, including ones
     * this server never implemented; the lifecycle says to answer an
     * unsupported request with a version the server does support.
     */
    @Test
    void initialize_answersAnUnsupportedVersionWithItsOwn() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "1999-01-01");

        JsonNode response = serverWithMutations(true).handle(request("initialize", params));

        assertThat(response.path("result").path("protocolVersion").asString())
                .isEqualTo(McpServer.PROTOCOL_VERSION);
    }

    @Test
    void initialize_echoesEverySupportedVersion() {
        for (String version : List.of("2024-11-05", "2025-03-26", "2025-06-18")) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", version);

            JsonNode response = serverWithMutations(true).handle(request("initialize", params));

            assertThat(response.path("result").path("protocolVersion").asString())
                    .isEqualTo(version);
        }
    }

    /**
     * A request without an id is a notification, which JSON-RPC never
     * answers; a tools/call sent as one was answered with "id": null.
     */
    @Test
    void aNotificationOfAnyMethodIsRunButNotAnswered() {
        AtomicBoolean ran = new AtomicBoolean();
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.addTool(tool("disconnect", true, args -> {
            ran.set(true);
            return "done";
        }));
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "disconnect");
        ObjectNode call = MAPPER.createObjectNode();
        call.put("jsonrpc", "2.0");
        call.put("method", "tools/call");
        call.set("params", params);
        ObjectNode badParams = MAPPER.createObjectNode();
        badParams.put("jsonrpc", "2.0");
        badParams.put("method", "tools/call");

        assertThat(server.handle(call)).isNull();
        assertThat(server.handle(badParams)).as("nor a failed one").isNull();
        assertThat(ran).isTrue();
    }

    @Test
    void aBatchAnswersItsRequestsAndNotItsNotifications() {
        ArrayNode batch = MAPPER.createArrayNode();
        batch.add(request("ping", null));
        ObjectNode initialized = MAPPER.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        batch.add(initialized);
        ObjectNode status = MAPPER.createObjectNode();
        status.put("jsonrpc", "2.0");
        status.put("method", "tools/call");
        status.set("params", MAPPER.createObjectNode().put("name", "get_status"));
        batch.add(status);

        JsonNode response = serverWithMutations(true).handle(batch);

        assertThat(response.isArray()).isTrue();
        assertThat(response).singleElement()
                .satisfies(only -> assertThat(only.path("id").asInt()).isEqualTo(1));
    }

    @Test
    void aBatchOfNotificationsOnlyIsNotAnswered() {
        ArrayNode batch = MAPPER.createArrayNode();
        ObjectNode initialized = MAPPER.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        batch.add(initialized);

        assertThat(serverWithMutations(true).handle(batch)).isNull();
    }

    /**
     * The logging capability was advertised, but logging/setLevel, which a
     * client that sees it may call, was an unknown method.
     */
    @Test
    void loggingSetLevel_isTakenWhereLoggingIsAdvertised() {
        List<String> levels = new ArrayList<>();
        McpServer server = serverWithMutations(true);
        server.setLoggingCapability(true);
        server.setLogLevelListener(levels::add);
        ObjectNode params = MAPPER.createObjectNode().put("level", "warning");

        JsonNode response = server.handle(request("logging/setLevel", params));
        JsonNode unknown = server.handle(request("logging/setLevel",
                MAPPER.createObjectNode().put("level", "verbose")));

        assertThat(response.path("result").isObject()).as(response.toString()).isTrue();
        assertThat(levels).containsExactly("warning");
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void loggingSetLevel_isUnknownWhereLoggingIsNotAdvertised() {
        JsonNode response = serverWithMutations(true).handle(request("logging/setLevel",
                MAPPER.createObjectNode().put("level", "info")));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }
}
