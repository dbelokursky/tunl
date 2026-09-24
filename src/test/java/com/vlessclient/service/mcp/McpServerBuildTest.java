package com.vlessclient.service.mcp;

import com.vlessclient.service.ConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@link McpServerService#buildServer()} wiring — every tool
 * and resource, with the actual schemas — against a fake control facade.
 */
class McpServerBuildTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    private FakeAppControlService control;
    private McpServerService service;

    @BeforeEach
    void setUp() {
        control = new FakeAppControlService();
        ConfigStore store = new ConfigStore(tempDir);
        // These tests are about the tools, so they turn on what a new install leaves off.
        store.getSettings().setMcpAllowMutations(true);
        service = new McpServerService(store, control);
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

    private JsonNode toolCall(McpServer server, String name, ObjectNode args) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", args != null ? args : MAPPER.createObjectNode());
        return call(server, "tools/call", params);
    }

    @Test
    void buildServer_advertisesReadAndMutationTools() {
        JsonNode tools = call(service.buildServer(), "tools/list", null)
                .path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).contains(
                "get_status", "get_traffic", "list_servers", "get_logs", "list_subscriptions",
                "get_routing", "get_settings",
                "connect", "disconnect", "select_server", "measure_latency", "refresh_subscription",
                "add_server", "update_server", "delete_server", "set_proxy_mode", "set_setting",
                "add_routing_rule", "remove_routing_rule");
    }

    @Test
    void buildServer_hidesMutationToolsWhenDisabled() {
        control.settings = new SettingsInfo("system", "en", false, "system_proxy",
                1080, 1081, 9090, "d", "d", "prefer_ipv4", "utun99", "info",
                true, true, 55555, false, "single", true, "172.19.0.1/30", false);
        // The allowMutations supplier reads ConfigStore settings, so flip that store's flag.
        ConfigStore store = new ConfigStore(tempDir);
        store.getSettings().setMcpAllowMutations(false);
        McpServerService disabled = new McpServerService(store, control);

        JsonNode tools = call(disabled.buildServer(), "tools/list", null)
                .path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).contains("get_status").doesNotContain("connect", "add_server",
                "delete_server");
    }

    /**
     * An agent can be steered by what it reads, and the change tools let it add
     * a server and send traffic through it, in TUN mode too: the confirm it has
     * to pass is one it supplies itself. A new install therefore offers only the
     * read tools until the user allows configuration changes.
     */
    @Test
    void buildServer_offersOnlyReadToolsUntilTheUserAllowsChanges() {
        McpServerService fresh = new McpServerService(
                new ConfigStore(tempDir.resolve("fresh-install")), control);

        JsonNode tools = call(fresh.buildServer(), "tools/list", null)
                .path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).contains("get_status", "list_servers")
                .doesNotContain("connect", "add_server", "set_setting", "delete_server");
    }

    @Test
    void addServer_requiresShareLink_schemaMarksRequired() {
        JsonNode tools = call(service.buildServer(), "tools/list", null)
                .path("result").path("tools");
        JsonNode addServer = null;
        for (JsonNode t : tools) {
            if (t.path("name").asString().equals("add_server")) {
                addServer = t;
            }
        }
        assertThat(addServer).isNotNull();
        assertThat(addServer.path("inputSchema").path("required").toString()).contains("shareLink");
    }

    @Test
    void deleteServer_withoutConfirm_isError() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "srv-1");
        JsonNode result = toolCall(service.buildServer(), "delete_server", args).path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void addRoutingRule_passesThrough() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("type", "domain");
        args.put("value", "example.org");
        args.put("action", "proxy");
        JsonNode result = toolCall(service.buildServer(), "add_routing_rule", args).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(control.lastAddedRule.value()).isEqualTo("example.org");
    }

    @Test
    void initialize_advertisesLoggingAndResources() {
        JsonNode caps = call(service.buildServer(), "initialize", null)
                .path("result").path("capabilities");
        assertThat(caps.has("logging")).isTrue();
        assertThat(caps.has("resources")).isTrue();
        assertThat(caps.has("tools")).isTrue();
    }

    @Test
    void resourcesList_hasAllResources() {
        JsonNode resources = call(service.buildServer(), "resources/list", null)
                .path("result").path("resources");
        List<String> uris = new ArrayList<>();
        resources.forEach(r -> uris.add(r.path("uri").asString()));
        assertThat(uris).contains("vless://status", "vless://traffic", "vless://servers",
                "vless://routing", "vless://settings", "vless://logs/recent");
    }
}
