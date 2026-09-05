package com.vlessclient.service.mcp;

/**
 * Immutable snapshot of the client's connection state, returned by the
 * {@code get_status} MCP tool. Field names are serialized verbatim by Jackson,
 * so they double as the tool's structured-output schema.
 *
 * @param state          the raw connection state ({@code DISCONNECTED},
 *                       {@code CONNECTING}, {@code CONNECTED}, {@code ERROR})
 * @param connected      whether the core is connected; does not prove traffic is reachable
 * @param activeServerId id of the server marked active, or {@code null}
 * @param activeServer   display name of the active server, or {@code null}
 * @param proxyMode      configured proxy mode ({@code system_proxy} / {@code tun})
 * @param socksPort      local SOCKS5 port
 * @param httpPort       local HTTP proxy port
 * @param clashApiPort   Clash API port used for live traffic stats
 * @param error          last error message, or empty string when none
 * @param health         reachability verdict, independent of the core state
 * @param tunnelStatus   combined status shared with the dashboard and tray
 * @param currentServerId core-reported routed server id, null when unknown or disconnected
 * @param currentServer  core-reported routed server name, null when unknown or disconnected
 */
public record StatusInfo(
        String state,
        boolean connected,
        String activeServerId,
        String activeServer,
        String proxyMode,
        int socksPort,
        int httpPort,
        int clashApiPort,
        String error,
        String health,
        String tunnelStatus,
        String currentServerId,
        String currentServer) {

    /** Creates a legacy snapshot with no measured health or core-reported server. */
    public StatusInfo(String state, boolean connected, String activeServerId, String activeServer,
                      String proxyMode, int socksPort, int httpPort, int clashApiPort,
                      String error) {
        this(state, connected, activeServerId, activeServer, proxyMode, socksPort, httpPort,
                clashApiPort, error, "UNMONITORED", state, null, null);
    }
}
