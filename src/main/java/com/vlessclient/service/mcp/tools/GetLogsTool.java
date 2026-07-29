package com.vlessclient.service.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;

import java.util.List;

/**
 * {@code get_logs} — returns recent sing-box log lines (newest last), optionally
 * limited and filtered by a case-insensitive substring. Read-only.
 */
public class GetLogsTool implements McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 100;

    private final AppControlService control;

    public GetLogsTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "get_logs";
    }

    @Override
    public String description() {
        return "Get recent sing-box log lines (newest last). Optional 'limit' "
                + "(default 100) and 'filter' (case-insensitive substring).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode limit = MAPPER.createObjectNode();
        limit.put("type", "integer");
        limit.put("description", "Max number of most-recent lines to return.");
        props.set("limit", limit);
        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("type", "string");
        filter.put("description", "Only return lines containing this substring.");
        props.set("filter", filter);
        schema.set("properties", props);
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) {
        JsonNode limitNode = arguments.get("limit");
        int limit = limitNode != null && limitNode.isInt() ? limitNode.asInt() : DEFAULT_LIMIT;
        JsonNode filterNode = arguments.get("filter");
        String filter = filterNode != null && filterNode.isTextual() ? filterNode.asText() : null;

        List<String> lines = control.getLogs(limit, filter);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("count", lines.size());
        result.set("lines", MAPPER.valueToTree(lines));
        return result;
    }
}
