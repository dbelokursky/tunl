package com.vlessclient.service.mcp.tools;

import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code get_status} — returns a live snapshot of the connection: state, active
 * server, proxy mode and local ports. Read-only.
 */
public class GetStatusTool implements McpTool {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final AppControlService control;

    public GetStatusTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "get_status";
    }

    @Override
    public String description() {
        // The server the core runs could differ from the active one with no
        // way to tell: one a refresh withdrew read as no server at all.
        return "Get the current VPN connection status: state, active server, "
                + "proxy mode and local proxy ports. 'proxyMode' is the configured mode; "
                + "'runningProxyMode' is the one the running core was started with, "
                + "and differs until a reconnect applies a changed mode. 'currentServerId' "
                + "is the server the tunnel routes through, which can differ from "
                + "'activeServerId': with server_selection auto_best, and when a refresh "
                + "withdrew the server the tunnel runs on (select_server of the active "
                + "server, or a reconnect, moves it).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) {
        return control.getStatus();
    }
}
