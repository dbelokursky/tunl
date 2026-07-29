package com.vlessclient.service.mcp;

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
}
