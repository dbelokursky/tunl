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
        return "Get the current VPN connection status: state, active server, "
                + "proxy mode and local proxy ports.";
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
