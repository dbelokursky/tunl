package com.vlessclient.service.mcp.tools;

import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code refresh_subscription} — triggers a refresh of one subscription so its
 * server list is re-fetched. Mutating.
 */
public class RefreshSubscriptionTool implements McpTool {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final AppControlService control;

    public RefreshSubscriptionTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "refresh_subscription";
    }

    @Override
    public String description() {
        return "Refresh a subscription's server list. Requires 'subscriptionId'.";
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
        props.set("subscriptionId", MAPPER.createObjectNode().put("type", "string"));
        schema.set("properties", props);
        schema.putArray("required").add("subscriptionId");
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) throws McpToolException {
        JsonNode node = arguments.get("subscriptionId");
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new McpToolException("'subscriptionId' is required.");
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("message", control.refreshSubscription(node.asString()));
        return result;
    }
}
