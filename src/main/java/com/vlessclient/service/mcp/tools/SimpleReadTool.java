package com.vlessclient.service.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.McpTool;
import java.util.function.Supplier;

/**
 * A read-only MCP tool that takes no arguments and returns whatever a
 * {@link Supplier} produces. Used for the simple observability tools
 * ({@code get_traffic}, {@code list_servers}, {@code list_subscriptions},
 * {@code get_routing}, {@code get_settings}).
 */
public class SimpleReadTool implements McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final Supplier<Object> supplier;

    /**
     * Creates a read-only tool backed by the given supplier.
     *
     * @param name the tool name
     * @param description the human-readable description
     * @param supplier the source of the tool's result
     */
    public SimpleReadTool(String name, String description, Supplier<Object> supplier) {
        this.name = name;
        this.description = description;
        this.supplier = supplier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
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
        return supplier.get();
    }
}
