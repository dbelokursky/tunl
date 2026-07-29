package com.vlessclient.service.mcp;

/**
 * A readable MCP resource — a named, addressable snapshot the agent can fetch by
 * URI (e.g. {@code vless://status}). Resources complement tools: the same live
 * data is browsable without invoking an action.
 */
public interface McpResource {

    /** Stable resource URI, e.g. {@code vless://status}. */
    String uri();

    /** Short human/agent-facing name. */
    String name();

    /** One-line description. */
    String description();

    /** MIME type of the rendered content. */
    default String mimeType() {
        return "application/json";
    }

    /**
     * Reads the resource content.
     *
     * @return the JSON-serializable content produced when the resource is read
     */
    Object read();
}
