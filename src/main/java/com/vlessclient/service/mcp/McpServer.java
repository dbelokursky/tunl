package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.Redact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Tool argument names whose value is a credential and is dropped whole
     * from the audit log. {@code shareLink} is add_server/update_server's
     * payload; {@code url} is here for the subscription tools this list will
     * outlive.
     */
    private static final Set<String> SECRET_ARG_KEYS = Set.of("shareLink", "url");

    private final String serverName;
    private final String serverVersion;
    private final ObjectMapper mapper;
    private final BooleanSupplier allowMutations;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    private McpAuditLog auditLog = McpAuditLog.NOOP;
    private boolean loggingCapability;

    /**
     * Creates a server advertising the given identity and mutation policy.
     *
     * @param serverName the server name reported during {@code initialize}
     * @param serverVersion the server version reported during {@code initialize}
     * @param mapper the JSON mapper used to build responses
     * @param allowMutations supplies whether mutating tool calls are currently allowed
     */
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

    /** Registers a resource. Registration order is preserved in {@code resources/list}. */
    public void addResource(McpResource resource) {
        resources.put(resource.uri(), resource);
    }

    /** Sets the audit sink that records mutating tool calls. */
    public void setAuditLog(McpAuditLog auditLog) {
        this.auditLog = auditLog != null ? auditLog : McpAuditLog.NOOP;
    }

    /** Advertises the {@code logging} capability (server pushes log notifications over SSE). */
    public void setLoggingCapability(boolean enabled) {
        this.loggingCapability = enabled;
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
        ObjectNode params = message.get("params") instanceof ObjectNode p
                ? p : mapper.createObjectNode();

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
                    return result(idNode, resourcesList());
                }
                case "resources/read" -> {
                    return result(idNode, readResource(params));
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
        if (!resources.isEmpty()) {
            ObjectNode resourceCaps = mapper.createObjectNode();
            resourceCaps.put("subscribe", false);
            resourceCaps.put("listChanged", false);
            capabilities.set("resources", resourceCaps);
        }
        if (loggingCapability) {
            capabilities.set("logging", mapper.createObjectNode());
        }
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

    private ObjectNode resourcesList() {
        ObjectNode res = mapper.createObjectNode();
        ArrayNode arr = res.putArray("resources");
        for (McpResource resource : resources.values()) {
            ObjectNode r = mapper.createObjectNode();
            r.put("uri", resource.uri());
            r.put("name", resource.name());
            r.put("description", resource.description());
            r.put("mimeType", resource.mimeType());
            arr.add(r);
        }
        return res;
    }

    private ObjectNode readResource(ObjectNode params) {
        JsonNode uriNode = params.get("uri");
        if (uriNode == null || !uriNode.isTextual()) {
            throw new IllegalArgumentException("resources/read requires a 'uri'");
        }
        String uri = uriNode.asText();
        McpResource resource = resources.get(uri);
        if (resource == null) {
            throw new IllegalArgumentException("Unknown resource: " + uri);
        }
        JsonNode tree = mapper.valueToTree(resource.read());
        String text;
        try {
            text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception e) {
            text = String.valueOf(tree);
        }
        ObjectNode res = mapper.createObjectNode();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("uri", uri);
        entry.put("mimeType", resource.mimeType());
        entry.put("text", text);
        ArrayNode contents = res.putArray("contents");
        contents.add(entry);
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
            if (tool.mutating()) {
                auditLog.record(toolName, auditArgs(arguments), false, "");
            }
            return toolSuccess(value);
        } catch (McpToolException e) {
            if (tool.mutating()) {
                auditLog.record(toolName, auditArgs(arguments), true, e.getMessage());
            }
            return toolError(e.getMessage());
        }
    }

    /**
     * The audit-log view of a tool's arguments. add_server and update_server
     * take a whole share link, so the raw arguments carried the very
     * credential ConfigStore seals into the keychain — {@code vless://<uuid>@…}
     * — into a plaintext rotating log.
     *
     * <p>Redaction is by key <em>and</em> by value shape. The key list covers
     * today's tools; the value pass catches a credential that arrives under a
     * name nobody thought to add here, which is the failure mode a pure
     * denylist has.</p>
     */
    private String auditArgs(ObjectNode arguments) {
        ObjectNode copy = arguments.deepCopy();
        // Snapshot the names: mutating while iterating fieldNames() reads as a
        // bug even where LinkedHashMap tolerates a same-key replace.
        List<String> fields = new ArrayList<>();
        copy.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode value = copy.get(field);
            if (value == null || !value.isTextual()) {
                continue;
            }
            copy.put(field, SECRET_ARG_KEYS.contains(field)
                    ? Redact.REDACTED
                    : Redact.urlsIn(value.asText()));
        }
        return copy.toString();
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
