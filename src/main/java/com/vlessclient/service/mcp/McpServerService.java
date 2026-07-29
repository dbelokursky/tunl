package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.app.AppVersion;
import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.mcp.tools.ConnectTool;
import com.vlessclient.service.mcp.tools.DisconnectTool;
import com.vlessclient.service.mcp.tools.GetLogsTool;
import com.vlessclient.service.mcp.tools.GetStatusTool;
import com.vlessclient.service.mcp.tools.LambdaTool;
import com.vlessclient.service.mcp.tools.MeasureLatencyTool;
import com.vlessclient.service.mcp.tools.RefreshSubscriptionTool;
import com.vlessclient.service.mcp.tools.SelectServerTool;
import com.vlessclient.service.mcp.tools.SimpleReadTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of the local MCP control server and keeps it in sync with
 * {@link AppSettings}. Reconciling logic lives in {@link #apply()}: it starts
 * the server when {@code mcp_enabled} turns on and stops it when it turns off,
 * restarting cleanly when the port changes.
 *
 * <p>Registered as a singleton in the {@code ServiceLocator}; {@link #apply()}
 * is called once at startup and again whenever settings are saved.</p>
 */
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);
    private static final String SERVER_NAME = "vless-client";

    private final ConfigStore configStore;
    private final AppControlService control;
    private final McpTokenStore tokenStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private McpHttpServer httpServer;
    private int runningPort = -1;

    public McpServerService(ConfigStore configStore, AppControlService control) {
        this.configStore = configStore;
        this.control = control;
        this.tokenStore = new McpTokenStore(configStore.getDataDir());
    }

    /**
     * Reconciles the running server with current settings. Safe to call
     * repeatedly (e.g. after every settings save).
     */
    public synchronized void apply() {
        AppSettings settings = configStore.getSettings();
        boolean shouldRun = settings.isMcpEnabled();
        int desiredPort = settings.getMcpPort();

        if (!shouldRun) {
            stop();
            return;
        }
        if (httpServer != null && runningPort == desiredPort) {
            return; // already running on the right port
        }
        // (Re)start on the desired port.
        stop();
        try {
            String token = tokenStore.getOrCreate();
            McpServer server = buildServer();
            httpServer = new McpHttpServer(desiredPort, token, server, mapper);
            httpServer.start();
            runningPort = desiredPort;
        } catch (Exception e) {
            log.error("Failed to start MCP server on port {}", desiredPort, e);
            httpServer = null;
            runningPort = -1;
        }
    }

    /** Stops the server if it is running. */
    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            runningPort = -1;
        }
    }

    public synchronized boolean isRunning() {
        return httpServer != null && httpServer.isRunning();
    }

    /** @return the loopback endpoint URL agents should connect to. */
    public String endpointUrl() {
        return "http://127.0.0.1:" + configStore.getSettings().getMcpPort() + "/mcp";
    }

    /** @return the current bearer token, creating one if needed (for the UI). */
    public String token() {
        try {
            return tokenStore.getOrCreate();
        } catch (Exception e) {
            log.error("Failed to read MCP token", e);
            return "";
        }
    }

    /** Builds the fully-wired MCP server (tools + resources + audit). Visible for tests. */
    McpServer buildServer() {
        McpServer server = new McpServer(SERVER_NAME, AppVersion.VERSION, mapper,
                () -> configStore.getSettings().isMcpAllowMutations());

        // Read / observability tools.
        server.addTool(new GetStatusTool(control));
        server.addTool(new SimpleReadTool("get_traffic",
                "Get live upload/download speeds and session totals.",
                control::getTraffic));
        server.addTool(new SimpleReadTool("list_servers",
                "List all configured servers.", control::listServers));
        server.addTool(new GetLogsTool(control));
        server.addTool(new SimpleReadTool("list_subscriptions",
                "List all configured subscriptions.", control::listSubscriptions));
        server.addTool(new SimpleReadTool("get_routing",
                "Get the current routing configuration (preset, rules, bypass list).",
                control::getRouting));
        server.addTool(new SimpleReadTool("get_settings",
                "Get application settings (no secrets).", control::getSettings));

        // Action tools (mutating; hidden/blocked when mutations are off).
        server.addTool(new ConnectTool(control));
        server.addTool(new DisconnectTool(control));
        server.addTool(new SelectServerTool(control));
        server.addTool(new MeasureLatencyTool(control));
        server.addTool(new RefreshSubscriptionTool(control));

        // Config-mutation tools.
        addMutationTools(server);

        // Audit every mutating call to logs/mcp-audit.log.
        server.setAuditLog(new FileAuditLog(configStore.getDataDir()));

        // Browsable resources mirroring the read tools.
        server.addResource(new JsonResource("vless://status",
                "Connection status", "Current connection state and active server.",
                control::getStatus));
        server.addResource(new JsonResource("vless://traffic",
                "Traffic", "Live traffic speeds and session totals.", control::getTraffic));
        server.addResource(new JsonResource("vless://servers",
                "Servers", "All configured servers.", control::listServers));
        server.addResource(new JsonResource("vless://routing",
                "Routing", "Current routing configuration.", control::getRouting));
        server.addResource(new JsonResource("vless://settings",
                "Settings", "Application settings (no secrets).", control::getSettings));
        server.addResource(new JsonResource("vless://logs/recent",
                "Recent logs", "The most recent sing-box log lines.",
                () -> control.getLogs(200, null)));

        return server;
    }

    private void addMutationTools(McpServer server) {
        server.addTool(new LambdaTool("add_server",
                "Add a server from a share link (vless/vmess/trojan/ss/hysteria2). "
                        + "Optional 'name' override. Requires 'shareLink'.",
                true, schema(prop("shareLink", "string"), prop("name", "string"), req("shareLink")),
                args -> control.addServer(str(args, "shareLink"), str(args, "name"))));

        server.addTool(new LambdaTool("update_server",
                "Update a server: rename ('name') and/or replace from a new 'shareLink'. "
                        + "Requires 'id'.",
                true, schema(prop("id", "string"), prop("name", "string"),
                        prop("shareLink", "string"), req("id")),
                args -> control.updateServer(str(args, "id"), str(args, "name"),
                        str(args, "shareLink"))));

        server.addTool(new LambdaTool("delete_server",
                "Delete a server. Requires 'id' and 'confirm':true.",
                true, schema(prop("id", "string"), prop("confirm", "boolean"), req("id")),
                args -> control.deleteServer(str(args, "id"), bool(args, "confirm"))));

        server.addTool(new LambdaTool("set_proxy_mode",
                "Set the proxy mode: 'system_proxy' or 'tun'.",
                true, schema(prop("mode", "string"), req("mode")),
                args -> control.setProxyMode(str(args, "mode"))));

        server.addTool(new LambdaTool("set_setting",
                "Set one setting by 'key' and 'value' (e.g. theme, language, socks_port, "
                        + "proxy_dns, health_check_enabled).",
                true, schema(prop("key", "string"), prop("value", null), req("key", "value")),
                args -> control.setSetting(str(args, "key"), args.get("value"))));

        server.addTool(new LambdaTool("add_routing_rule",
                "Append a routing rule. 'type' (domain, domain_suffix, geosite, geoip, "
                        + "ip_cidr, ...), 'value', 'action' (proxy|direct|block).",
                true, schema(prop("type", "string"), prop("value", "string"),
                        prop("action", "string"), req("type", "value", "action")),
                args -> control.addRoutingRule(str(args, "type"), str(args, "value"),
                        str(args, "action"))));

        server.addTool(new LambdaTool("remove_routing_rule",
                "Remove a routing rule by 'ruleId'.",
                true, schema(prop("ruleId", "string"), req("ruleId")),
                args -> control.removeRoutingRule(str(args, "ruleId"))));

        server.addTool(new LambdaTool("set_routing_preset",
                "Set the routing preset by 'preset' name.",
                true, schema(prop("preset", "string"), req("preset")),
                args -> control.setRoutingPreset(str(args, "preset"))));
    }

    // ----- small schema/arg helpers -----

    /** A single named property with an optional JSON-schema type (null = any). */
    private record Prop(String name, String type) { }

    private Prop prop(String name, String type) {
        return new Prop(name, type);
    }

    /** Marker carrying the list of required property names. */
    private record Required(String[] names) { }

    private Required req(String... names) {
        return new Required(names);
    }

    private ObjectNode schema(Object... parts) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode required = mapper.createArrayNode();
        for (Object part : parts) {
            if (part instanceof Prop p) {
                ObjectNode node = mapper.createObjectNode();
                if (p.type() != null) {
                    node.put("type", p.type());
                }
                props.set(p.name(), node);
            } else if (part instanceof Required r) {
                for (String name : r.names()) {
                    required.add(name);
                }
            }
        }
        schema.set("properties", props);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private static String str(ObjectNode args, String field) {
        JsonNode node = args.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean bool(ObjectNode args, String field) {
        JsonNode node = args.get(field);
        return node != null && node.asBoolean(false);
    }
}
