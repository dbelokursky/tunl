package com.vlessclient.service.mcp.tools;

import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code select_server} — makes a server the active one, which a connected
 * tunnel switches to at once and the next connect uses. Mutating.
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
        // It said "used on the next connect", but a connected tunnel switches
        // to the server at once, as it does for a pick in the app's list.
        return "Make a server the active one. A connected tunnel switches to it at once; "
                + "otherwise the next connect uses it. Requires 'serverId'.";
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
