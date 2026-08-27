package com.vlessclient.service.mcp;

import java.util.List;

/**
 * Facade the MCP tools call into. Every capability exposed over MCP is a method
 * here, delegating to the concrete application services
 * ({@code SingBoxEngine}, {@code ConfigStore}, {@code TrafficMonitor}, ...).
 *
 * <p>The indirection keeps the tool layer trivially unit-testable: tests drive
 * the tools against a fake {@code AppControlService} without booting JavaFX or
 * sing-box, mirroring the project's existing service-test style.</p>
 */
public interface AppControlService {

    /**
     * Returns the current connection status.
     *
     * @return a live snapshot of the current connection status
     */
    StatusInfo getStatus();

    /**
     * Returns live traffic metrics.
     *
     * @return live upload/download rates and session totals
     */
    TrafficInfo getTraffic();

    /**
     * Returns the configured servers.
     *
     * @return all configured servers, in list order
     */
    List<ServerSummary> listServers();

    /**
     * Returns recent sing-box log lines, newest last.
     *
     * @param limit  maximum number of lines to return (most recent)
     * @param filter optional case-insensitive substring; only matching lines are
     *               returned when non-blank
     * @return the matching tail of the log buffer
     */
    List<String> getLogs(int limit, String filter);

    /**
     * Returns the configured subscriptions.
     *
     * @return all configured subscriptions
     */
    List<SubscriptionSummary> listSubscriptions();

    /**
     * Returns the current routing configuration.
     *
     * @return the current routing configuration
     */
    RoutingInfo getRouting();

    /**
     * Returns a safe view of application settings.
     *
     * @return a safe view of application settings (no secrets)
     */
    SettingsInfo getSettings();

    // ----- actions (mutating) -----

    /**
     * Connects the tunnel.
     *
     * @param serverId optional server to select and connect; when {@code null}
     *                 the currently active server is used
     * @param mode     optional proxy mode ({@code system_proxy}/{@code tun});
     *                 when {@code null} the configured mode is used
     * @param confirm  must be {@code true} to connect in TUN mode (which shows a
     *                 macOS admin password prompt)
     * @return the status after initiating the connection
     * @throws McpToolException if there is no active server, the binary is
     *                          missing, TUN was requested without confirmation,
     *                          or sing-box fails to start
     */
    StatusInfo connect(String serverId, String mode, boolean confirm) throws McpToolException;

    /**
     * Disconnects the tunnel.
     *
     * @return the status after disconnecting
     */
    StatusInfo disconnect();

    /**
     * Marks the given server active (used on the next connect).
     *
     * @param serverId the server to activate
     * @return the now-active server
     * @throws McpToolException if no server has that id
     */
    ServerSummary selectServer(String serverId) throws McpToolException;

    /**
     * Measures latency to one server, or all servers when {@code serverId} is
     * {@code null}.
     *
     * @param serverId optional single server id
     * @return per-server latencies
     * @throws McpToolException if the given server id is unknown or there are no
     *                          servers to test
     */
    java.util.List<LatencyResult> measureLatency(String serverId) throws McpToolException;

    /**
     * Triggers a refresh of one subscription.
     *
     * @param subscriptionId the subscription to refresh
     * @return a short status message
     * @throws McpToolException if no subscription has that id
     */
    String refreshSubscription(String subscriptionId) throws McpToolException;

    // ----- config mutation -----

    /**
     * Adds a server parsed from a share link (vless/vmess/trojan/ss/hysteria2).
     *
     * @param shareLink the share URI
     * @param name      optional display-name override
     * @return the added server
     * @throws McpToolException if the link cannot be parsed
     */
    ServerSummary addServer(String shareLink, String name) throws McpToolException;

    /**
     * Updates a server: renames it and/or replaces its config from a new share
     * link (keeping the same id and active flag).
     *
     * @param id        the server to update
     * @param name      optional new name
     * @param shareLink optional new share link to reparse from
     * @return the updated server
     * @throws McpToolException if the id is unknown or the link is invalid
     */
    ServerSummary updateServer(String id, String name, String shareLink) throws McpToolException;

    /**
     * Deletes a server.
     *
     * @param id      the server to delete
     * @param confirm must be {@code true}
     * @return a short status message
     * @throws McpToolException if the id is unknown or confirm is false
     */
    String deleteServer(String id, boolean confirm) throws McpToolException;

    /**
     * Sets the proxy mode.
     *
     * @param mode {@code system_proxy} or {@code tun}
     * @return the updated settings
     * @throws McpToolException if the mode is unknown
     */
    SettingsInfo setProxyMode(String mode) throws McpToolException;

    /**
     * Sets a single whitelisted setting by key.
     *
     * @param key   the setting key (see implementation for the allowed set)
     * @param value the new value
     * @return the updated settings
     * @throws McpToolException if the key is not settable or the value is invalid
     */
    SettingsInfo setSetting(String key, tools.jackson.databind.JsonNode value)
            throws McpToolException;

    /**
     * Appends a routing rule.
     *
     * @param type   rule type (domain, domain_suffix, geosite, geoip, ip_cidr, ...)
     * @param value  the value to match
     * @param action proxy | direct | block
     * @return the updated routing configuration
     * @throws McpToolException if any field is invalid
     */
    RoutingInfo addRoutingRule(String type, String value, String action) throws McpToolException;

    /**
     * Removes a routing rule by id.
     *
     * @param ruleId the rule to remove
     * @return the updated routing configuration
     * @throws McpToolException if the rule id is unknown
     */
    RoutingInfo removeRoutingRule(String ruleId) throws McpToolException;
}
