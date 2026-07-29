package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.tools.ConnectTool;
import com.vlessclient.service.mcp.tools.DisconnectTool;
import com.vlessclient.service.mcp.tools.MeasureLatencyTool;
import com.vlessclient.service.mcp.tools.RefreshSubscriptionTool;
import com.vlessclient.service.mcp.tools.SelectServerTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpActionToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeAppControlService control = new FakeAppControlService();

    private McpServer buildServer(boolean allowMutations) {
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> allowMutations);
        server.addTool(new ConnectTool(control));
        server.addTool(new DisconnectTool(control));
        server.addTool(new SelectServerTool(control));
        server.addTool(new MeasureLatencyTool(control));
        server.addTool(new RefreshSubscriptionTool(control));
        return server;
    }

    private JsonNode callTool(McpServer server, String name, ObjectNode args) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", args != null ? args : MAPPER.createObjectNode());
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 1);
        msg.put("method", "tools/call");
        msg.set("params", params);
        return server.handle(msg);
    }

    @Test
    void connect_passesArgumentsThrough() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("serverId", "srv-2");
        args.put("mode", "tun");
        args.put("confirm", true);

        JsonNode result = callTool(buildServer(true), "connect", args).path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(control.lastConnectServerId).isEqualTo("srv-2");
        assertThat(control.lastConnectMode).isEqualTo("tun");
        assertThat(control.lastConnectConfirm).isTrue();
    }

    @Test
    void disconnect_invokesControl() {
        JsonNode result = callTool(buildServer(true), "disconnect", null).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(control.disconnectCalled).isTrue();
    }

    @Test
    void selectServer_requiresServerId() {
        JsonNode result = callTool(buildServer(true), "select_server", MAPPER.createObjectNode())
                .path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(control.lastSelectedServerId).isNull();
    }

    @Test
    void selectServer_activatesServer() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("serverId", "srv-2");
        JsonNode result = callTool(buildServer(true), "select_server", args).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(control.lastSelectedServerId).isEqualTo("srv-2");
    }

    @Test
    void measureLatency_returnsResults() {
        JsonNode result = callTool(buildServer(true), "measure_latency", null).path("result");
        assertThat(result.path("structuredContent").path("results").get(0).path("latencyMs").asLong())
                .isEqualTo(42);
    }

    @Test
    void refreshSubscription_unknownId_isError() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("subscriptionId", "nope");
        JsonNode result = callTool(buildServer(true), "refresh_subscription", args).path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void actionTools_hiddenWhenMutationsDisabled() {
        JsonNode tools = callTool(buildServer(false), "connect", null); // still blocked below
        // tools/list should not advertise any mutating tool.
        ObjectNode listMsg = MAPPER.createObjectNode();
        listMsg.put("jsonrpc", "2.0");
        listMsg.put("id", 2);
        listMsg.put("method", "tools/list");
        JsonNode list = buildServer(false).handle(listMsg).path("result").path("tools");
        assertThat(list).isEmpty();
        // And the direct call is refused.
        assertThat(tools.path("result").path("isError").asBoolean()).isTrue();
        assertThat(control.disconnectCalled).isFalse();
    }
}
