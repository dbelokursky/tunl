package com.vlessclient.service.mcp;

/**
 * Safe, agent-facing view of application settings, returned by
 * {@code get_settings}. Deliberately excludes the MCP bearer token.
 *
 * @param theme            UI theme ({@code system}/{@code light}/{@code dark})
 * @param language         UI language code
 * @param autoConnect      connect automatically on launch
 * @param proxyMode        {@code system_proxy} or {@code tun}
 * @param socksPort        local SOCKS5 port
 * @param httpPort         local HTTP proxy port
 * @param clashApiPort     Clash API port
 * @param proxyDns         DNS used for proxied traffic
 * @param directDns        DNS used for direct traffic
 * @param dnsStrategy      DNS resolution strategy
 * @param tunInterfaceName TUN interface name
 * @param healthCheck      whether periodic reachability checks are enabled
 * @param mcpEnabled       whether the MCP server is enabled
 * @param mcpPort          MCP server port
 * @param mcpAllowMutations whether mutating MCP tools are permitted
 */
public record SettingsInfo(
        String theme,
        String language,
        boolean autoConnect,
        String proxyMode,
        int socksPort,
        int httpPort,
        int clashApiPort,
        String proxyDns,
        String directDns,
        String dnsStrategy,
        String tunInterfaceName,
        boolean healthCheck,
        boolean mcpEnabled,
        int mcpPort,
        boolean mcpAllowMutations) {
}
