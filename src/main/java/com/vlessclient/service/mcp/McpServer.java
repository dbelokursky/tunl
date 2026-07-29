package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Transport-agnostic MCP server: the JSON-RPC 2.0 protocol engine.
 *
 * <p>Given a single parsed JSON-RPC message (or a batch array), it dispatches
 * the MCP methods this client needs — {@code initialize}, {@code ping},
 * {@code tools/list}, {@code tools/call}, plus empty {@code resources/list} and
 * {@code prompts/list} so probing clients don't choke — and returns the response
 * node, or {@code null} for notification-only input (which the transport answers
 * with {@code 202 Accepted}).</p>
 *
 * <p>Kept free of any HTTP concern so it can be unit-tested by feeding JSON
 * straight to {@link #handle(JsonNode)}.</p>
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** MCP protocol revision this server implements. */
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String JSONRPC = "2.0";

    // JSON-RPC standard error codes.
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final String serverName;
    private final String serverVersion;
    private final ObjectMapper mapper;
    private final BooleanSupplier allowMutations;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpServer(String serverName, String serverVersion, ObjectMapper mapper,
                     BooleanSupplier allowMutations) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.mapper = mapper;
        this.allowMutations = allowMutations;
    }

    /** Registers a tool. Registration order is preserved in {@code tools/list}. */
    public void addTool(McpTool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Handles one JSON-RPC message or a batch.
     *
     * @param message the parsed request (object) or batch (array)
     * @return the response node to serialize, or {@code null} if the input was a
     *         notification / batch of notifications with nothing to answer
     */
    public JsonNode handle(JsonNode message) {
        if (message.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode element : message) {
                JsonNode response = handleSingle(element);
                if (response != null) {
                    out.add(response);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return handleSingle(message);
    }

    private JsonNode handleSingle(JsonNode message) {
        if (!message.isObject()) {
            return error(null, INVALID_REQUEST, "Message must be a JSON object");
        }
        JsonNode idNode = message.get("id");
        boolean isNotification = idNode == null || idNode.isNull();

        JsonNode methodNode = message.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            // No method → this is a response echoed to us; nothing to do.
            return isNotification ? null : error(idNode, INVALID_REQUEST, "Missing method");
        }
        String method = methodNode.asText();
        ObjectNode params = message.get("params") instanceof ObjectNode p ? p : mapper.createObjectNode();

        try {
            switch (method) {
                case "initialize" -> {
                    return result(idNode, initializeResult(params));
                }
                case "ping" -> {
                    return result(idNode, mapper.createObjectNode());
                }
                case "tools/list" -> {
                    return result(idNode, toolsList());
                }
                case "tools/call" -> {
                    return result(idNode, callTool(params));
                }
                case "resources/list" -> {
                    return result(idNode, arrayResult("resources"));
                }
                case "resources/templates/list" -> {
                    return result(idNode, arrayResult("resourceTemplates"));
                }
                case "prompts/list" -> {
                    return result(idNode, arrayResult("prompts"));
                }
                default -> {
                    if (method.startsWith("notifications/")) {
                        // Client notifications (initialized, cancelled, ...): no reply.
                        return null;
                    }
                    return isNotification ? null
                            : error(idNode, METHOD_NOT_FOUND, "Unknown method: " + method);
                }
            }
        } catch (IllegalArgumentException e) {
            return error(idNode, INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.warn("MCP method {} failed", method, e);
            return error(idNode, INTERNAL_ERROR, e.getMessage());
        }
    }

    private ObjectNode initializeResult(ObjectNode params) {
        ObjectNode res = mapper.createObjectNode();
        JsonNode requested = params.get("protocolVersion");
        res.put("protocolVersion",
                requested != null && requested.isTextual() ? requested.asText() : PROTOCOL_VERSION);

        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", mapper.createObjectNode().put("listChanged", false));
        res.set("capabilities", capabilities);

        ObjectNode info = mapper.createObjectNode();
        info.put("name", serverName);
        info.put("version", serverVersion);
        res.set("serverInfo", info);
        return res;
    }

    private ObjectNode toolsList() {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode arr = res.putArray("tools");
        boolean mutationsOn = allowMutations.getAsBoolean();
        for (McpTool tool : tools.values()) {
            if (tool.mutating() && !mutationsOn) {
                continue;
            }
            ObjectNode t = mapper.createObjectNode();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", tool.inputSchema());
            arr.add(t);
        }
        return res;
    }

    private ObjectNode callTool(ObjectNode params) {
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            throw new IllegalArgumentException("tools/call requires a 'name'");
        }
        String toolName = nameNode.asText();
        McpTool tool = tools.get(toolName);
        if (tool == null) {
            return toolError("Unknown tool: " + toolName);
        }
        if (tool.mutating() && !allowMutations.getAsBoolean()) {
            return toolError("Tool '" + toolName + "' is disabled: mutations are turned off "
                    + "in settings (mcp_allow_mutations = false).");
        }
        ObjectNode arguments = params.get("arguments") instanceof ObjectNode a
                ? a : mapper.createObjectNode();
        try {
            Object value = tool.call(arguments);
            return toolSuccess(value);
        } catch (McpToolException e) {
            return toolError(e.getMessage());
        }
    }

    private ObjectNode toolSuccess(Object value) {
        JsonNode tree = mapper.valueToTree(value);
        ObjectNode res = mapper.createObjectNode();
        ArrayNode content = res.putArray("content");
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        try {
            text.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
        } catch (Exception e) {
            text.put("text", String.valueOf(value));
        }
        content.add(text);
        if (tree != null && tree.isObject()) {
            res.set("structuredContent", tree);
        }
        res.put("isError", false);
        return res;
    }

    private ObjectNode toolError(String message) {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode content = res.putArray("content");
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", "Error: " + message);
        content.add(text);
        res.put("isError", true);
        return res;
    }

    private ObjectNode arrayResult(String key) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray(key);
        return res;
    }

    private ObjectNode result(JsonNode id, JsonNode resultValue) {
        ObjectNode res = mapper.createObjectNode();
        res.put("jsonrpc", JSONRPC);
        res.set("id", id == null ? mapper.nullNode() : id);
        res.set("result", resultValue);
        return res;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode res = mapper.createObjectNode();
        res.put("jsonrpc", JSONRPC);
        res.set("id", id == null ? mapper.nullNode() : id);
        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        res.set("error", err);
        return res;
    }
}
