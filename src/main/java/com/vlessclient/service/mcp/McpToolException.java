package com.vlessclient.service.mcp;

/**
 * Thrown by an {@link McpTool} when a call cannot be completed — invalid
 * arguments, a missing server, a failed connect, and so on. The message is
 * returned to the MCP caller as a tool-level error result ({@code isError:true})
 * rather than a transport error, which is how the MCP spec expects tools to
 * report failures.
 */
public class McpToolException extends Exception {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
