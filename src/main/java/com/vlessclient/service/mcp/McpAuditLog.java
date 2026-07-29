package com.vlessclient.service.mcp;

/**
 * Sink for an append-only record of every MCP tool invocation, so the user can
 * see exactly what an agent did through the control server. Implementations must
 * be safe to call from multiple request threads.
 */
public interface McpAuditLog {

    /** A no-op audit log, used in tests and when auditing is unavailable. */
    McpAuditLog NOOP = (tool, argsSummary, error, message) -> { };

    /**
     * Records one tool call.
     *
     * @param tool        the tool name
     * @param argsSummary a compact, single-line rendering of the arguments
     * @param error       whether the call resulted in a tool error
     * @param message     short outcome/error message (may be empty)
     */
    void record(String tool, String argsSummary, boolean error, String message);
}
