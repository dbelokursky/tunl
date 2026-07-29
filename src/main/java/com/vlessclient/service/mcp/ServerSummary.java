package com.vlessclient.service.mcp;

/**
 * Compact view of a configured server, returned by {@code list_servers}.
 *
 * @param id       stable server id
 * @param name     display name
 * @param protocol protocol value (e.g. {@code vless})
 * @param address  server host
 * @param port     server port
 * @param active   whether this is the currently selected server
 */
public record ServerSummary(
        String id,
        String name,
        String protocol,
        String address,
        int port,
        boolean active) {
}
