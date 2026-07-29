package com.vlessclient.service.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;

/**
 * {@code disconnect} — stops the tunnel. Mutating.
 */
public class DisconnectTool implements McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppControlService control;

    public DisconnectTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "disconnect";
    }

    @Override
    public String description() {
        return "Disconnect the VPN.";
    }

    @Override
    public boolean mutating() {
        return true;
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
        return control.disconnect();
    }
}
