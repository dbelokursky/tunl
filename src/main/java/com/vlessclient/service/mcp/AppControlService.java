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
     * @return a live snapshot of the current connection status.
     */
    StatusInfo getStatus();

    /**
     * @return live upload/download rates and session totals.
     */
    TrafficInfo getTraffic();

    /**
     * @return all configured servers, in list order.
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
     * @return all configured subscriptions.
     */
    List<SubscriptionSummary> listSubscriptions();

    /**
     * @return the current routing configuration.
     */
    RoutingInfo getRouting();

    /**
     * @return a safe view of application settings (no secrets).
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
}
