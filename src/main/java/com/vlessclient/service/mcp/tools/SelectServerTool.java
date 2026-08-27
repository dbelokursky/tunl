package com.vlessclient.service.mcp.tools;

import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code select_server} — marks a server active for the next connect. Mutating.
 */
public class SelectServerTool implements McpTool {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final AppControlService control;

    public SelectServerTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "select_server";
    }

    @Override
    public String description() {
        return "Mark a server active (used on the next connect). Requires 'serverId'.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        props.set("serverId", MAPPER.createObjectNode().put("type", "string"));
        schema.set("properties", props);
        schema.putArray("required").add("serverId");
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) throws McpToolException {
        JsonNode node = arguments.get("serverId");
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new McpToolException("'serverId' is required.");
        }
        return control.selectServer(node.asString());
    }
}
