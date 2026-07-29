package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single MCP tool the agent can invoke.
 *
 * <p>Implementations are thin adapters over {@link AppControlService}. The
 * {@link McpServer} takes care of JSON-RPC framing, wrapping the returned value
 * into MCP {@code content}, and translating {@link McpToolException} into an
 * {@code isError} result — so a tool only has to describe itself and do its
 * work.</p>
 */
public interface McpTool {

    /** Unique tool name, e.g. {@code get_status}. Snake-case by convention. */
    String name();

    /** One-line human/agent-facing description of what the tool does. */
    String description();

    /**
     * The JSON Schema for the tool's {@code arguments} object. Must be a schema
     * of {@code "type": "object"}; return an empty-properties object for tools
     * that take no arguments.
     */
    ObjectNode inputSchema();

    /**
     * Whether invoking this tool mutates application state (adds/edits servers,
     * changes routing, connects, etc.). Read-only tools return {@code false} and
     * are always available; mutating tools are gated behind the
     * {@code mcp_allow_mutations} setting.
     */
    default boolean mutating() {
        return false;
    }

    /**
     * Executes the tool.
     *
     * @param arguments the caller-supplied arguments object (never {@code null};
     *                  may be empty)
     * @return a JSON-serializable value returned to the caller both as pretty
     *         text content and as structured content
     * @throws McpToolException if the arguments are invalid or the action fails;
     *                          the message is surfaced to the caller as an error
     */
    Object call(ObjectNode arguments) throws McpToolException;
}
