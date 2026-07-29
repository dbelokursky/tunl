package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.tools.GetLogsTool;
import com.vlessclient.service.mcp.tools.GetStatusTool;
import com.vlessclient.service.mcp.tools.SimpleReadTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpReadToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeAppControlService control = new FakeAppControlService();

    private McpServer buildServer() {
        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.addTool(new GetStatusTool(control));
        server.addTool(new SimpleReadTool("get_traffic", "traffic", control::getTraffic));
        server.addTool(new SimpleReadTool("list_servers", "servers", control::listServers));
        server.addTool(new GetLogsTool(control));
        server.addTool(new SimpleReadTool("list_subscriptions", "subs", control::listSubscriptions));
        server.addTool(new SimpleReadTool("get_routing", "routing", control::getRouting));
        server.addTool(new SimpleReadTool("get_settings", "settings", control::getSettings));
        server.addResource(new JsonResource("vless://status", "Status", "s", control::getStatus));
        server.addResource(new JsonResource("vless://logs/recent", "Logs", "l",
                () -> control.getLogs(200, null)));
        return server;
    }

    private JsonNode call(McpServer server, String method, ObjectNode params) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 1);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        return server.handle(msg);
    }

    private ObjectNode toolCall(String name, ObjectNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null ? arguments : MAPPER.createObjectNode());
        return params;
    }

    @Test
    void toolsList_hasAllSevenReadTools() {
        JsonNode tools = call(buildServer(), "tools/list", null).path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrder("get_status", "get_traffic", "list_servers",
                "get_logs", "list_subscriptions", "get_routing", "get_settings");
    }

    @Test
    void getTraffic_returnsFormattedAndRaw() {
        JsonNode result = call(buildServer(), "tools/call", toolCall("get_traffic", null))
                .path("result").path("structuredContent");
        assertThat(result.path("downloadSpeedBytesPerSec").asLong()).isEqualTo(4096);
        assertThat(result.path("totalDownload").asText()).isEqualTo("2 MB");
    }

    @Test
    void listServers_returnsAllServers() {
        JsonNode result = call(buildServer(), "tools/call", toolCall("list_servers", null))
                .path("result");
        JsonNode servers = result.path("structuredContent");
        // structuredContent is only set for object results; a list comes back as text content.
        JsonNode text = result.path("content").get(0).path("text");
        assertThat(text.asText()).contains("Tokyo").contains("Berlin");
        assertThat(servers.isMissingNode() || servers.isNull() || !servers.isObject()).isTrue();
    }

    @Test
    void getLogs_appliesFilterAndLimit() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("filter", "warn");
        JsonNode result = call(buildServer(), "tools/call", toolCall("get_logs", args))
                .path("result").path("structuredContent");
        assertThat(result.path("count").asInt()).isEqualTo(1);
        assertThat(result.path("lines").get(0).asText()).contains("WARN");
    }

    @Test
    void getRouting_returnsPresetAndRules() {
        JsonNode result = call(buildServer(), "tools/call", toolCall("get_routing", null))
                .path("result").path("content").get(0).path("text");
        assertThat(result.asText()).contains("route_all").contains("example.com");
    }

    @Test
    void getSettings_omitsToken() {
        JsonNode text = call(buildServer(), "tools/call", toolCall("get_settings", null))
                .path("result").path("content").get(0).path("text");
        assertThat(text.asText()).contains("mcpPort").doesNotContain("token");
    }

    @Test
    void resourcesList_advertisesResources() {
        JsonNode resources = call(buildServer(), "resources/list", null)
                .path("result").path("resources");
        List<String> uris = new ArrayList<>();
        resources.forEach(r -> uris.add(r.path("uri").asText()));
        assertThat(uris).contains("vless://status", "vless://logs/recent");
    }

    @Test
    void resourcesRead_returnsContent() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", "vless://status");
        JsonNode contents = call(buildServer(), "resources/read", params)
                .path("result").path("contents").get(0);
        assertThat(contents.path("uri").asText()).isEqualTo("vless://status");
        assertThat(contents.path("mimeType").asText()).isEqualTo("application/json");
        assertThat(contents.path("text").asText()).contains("CONNECTED");
    }

    @Test
    void resourcesRead_unknownUri_returnsError() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", "vless://nope");
        JsonNode response = call(buildServer(), "resources/read", params);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void initialize_advertisesResourcesCapability() {
        JsonNode caps = call(buildServer(), "initialize", null).path("result").path("capabilities");
        assertThat(caps.has("resources")).isTrue();
        assertThat(caps.has("tools")).isTrue();
    }
}
