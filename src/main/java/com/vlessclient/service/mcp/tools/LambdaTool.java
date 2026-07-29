package com.vlessclient.service.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;

/**
 * A tool defined inline from a name, description, schema, mutating flag and a
 * handler lambda. Keeps the many small config-mutation tools compact without a
 * class file each.
 */
public class LambdaTool implements McpTool {

    /** The tool body. */
    public interface Handler {
        Object call(ObjectNode arguments) throws McpToolException;
    }

    private final String name;
    private final String description;
    private final boolean mutating;
    private final ObjectNode inputSchema;
    private final Handler handler;

    /**
     * Creates an inline tool from its metadata and handler.
     *
     * @param name the tool name
     * @param description the human-readable description
     * @param mutating whether the tool changes state
     * @param inputSchema the JSON schema for the tool arguments
     * @param handler the lambda invoked when the tool is called
     */
    public LambdaTool(String name, String description, boolean mutating,
                      ObjectNode inputSchema, Handler handler) {
        this.name = name;
        this.description = description;
        this.mutating = mutating;
        this.inputSchema = inputSchema;
        this.handler = handler;
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
    public boolean mutating() {
        return mutating;
    }

    @Override
    public ObjectNode inputSchema() {
        return inputSchema;
    }

    @Override
    public Object call(ObjectNode arguments) throws McpToolException {
        return handler.call(arguments);
    }
}
