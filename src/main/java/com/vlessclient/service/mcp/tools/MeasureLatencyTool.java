package com.vlessclient.service.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;

/**
 * {@code measure_latency} — pings one server, or all servers when no id is
 * given, and returns the round-trip times. Mutating (it performs network I/O
 * and is a deliberate action rather than a passive read).
 */
public class MeasureLatencyTool implements McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppControlService control;

    public MeasureLatencyTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "measure_latency";
    }

    @Override
    public String description() {
        return "Measure latency to one server ('serverId') or all servers if omitted.";
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
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) throws McpToolException {
        JsonNode node = arguments.get("serverId");
        String serverId = node != null && node.isTextual() ? node.asText() : null;
        ObjectNode result = MAPPER.createObjectNode();
        result.set("results", MAPPER.valueToTree(control.measureLatency(serverId)));
        return result;
    }
}
