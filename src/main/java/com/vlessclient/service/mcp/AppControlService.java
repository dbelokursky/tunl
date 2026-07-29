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
}
