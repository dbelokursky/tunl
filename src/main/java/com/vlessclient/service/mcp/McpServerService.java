package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlessclient.app.AppVersion;
import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.mcp.tools.ConnectTool;
import com.vlessclient.service.mcp.tools.DisconnectTool;
import com.vlessclient.service.mcp.tools.GetLogsTool;
import com.vlessclient.service.mcp.tools.GetStatusTool;
import com.vlessclient.service.mcp.tools.MeasureLatencyTool;
import com.vlessclient.service.mcp.tools.RefreshSubscriptionTool;
import com.vlessclient.service.mcp.tools.SelectServerTool;
import com.vlessclient.service.mcp.tools.SimpleReadTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of the local MCP control server and keeps it in sync with
 * {@link AppSettings}. Reconciling logic lives in {@link #apply()}: it starts
 * the server when {@code mcp_enabled} turns on and stops it when it turns off,
 * restarting cleanly when the port changes.
 *
 * <p>Registered as a singleton in the {@code ServiceLocator}; {@link #apply()}
 * is called once at startup and again whenever settings are saved.</p>
 */
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);
    private static final String SERVER_NAME = "vless-client";

    private final ConfigStore configStore;
    private final AppControlService control;
    private final McpTokenStore tokenStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private McpHttpServer httpServer;
    private int runningPort = -1;

    public McpServerService(ConfigStore configStore, AppControlService control) {
        this.configStore = configStore;
        this.control = control;
        this.tokenStore = new McpTokenStore(configStore.getDataDir());
    }

    /**
     * Reconciles the running server with current settings. Safe to call
     * repeatedly (e.g. after every settings save).
     */
    public synchronized void apply() {
        AppSettings settings = configStore.getSettings();
        boolean shouldRun = settings.isMcpEnabled();
        int desiredPort = settings.getMcpPort();

        if (!shouldRun) {
            stop();
            return;
        }
        if (httpServer != null && runningPort == desiredPort) {
            return; // already running on the right port
        }
        // (Re)start on the desired port.
        stop();
        try {
            String token = tokenStore.getOrCreate();
            McpServer server = buildServer();
            httpServer = new McpHttpServer(desiredPort, token, server, mapper);
            httpServer.start();
            runningPort = desiredPort;
        } catch (Exception e) {
            log.error("Failed to start MCP server on port {}", desiredPort, e);
            httpServer = null;
            runningPort = -1;
        }
    }

    /** Stops the server if it is running. */
    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            runningPort = -1;
        }
    }

    public synchronized boolean isRunning() {
        return httpServer != null && httpServer.isRunning();
    }

    /** @return the loopback endpoint URL agents should connect to. */
    public String endpointUrl() {
        return "http://127.0.0.1:" + configStore.getSettings().getMcpPort() + "/mcp";
    }

    /** @return the current bearer token, creating one if needed (for the UI). */
    public String token() {
        try {
            return tokenStore.getOrCreate();
        } catch (Exception e) {
            log.error("Failed to read MCP token", e);
            return "";
        }
    }

    private McpServer buildServer() {
        McpServer server = new McpServer(SERVER_NAME, AppVersion.VERSION, mapper,
                () -> configStore.getSettings().isMcpAllowMutations());

        // Read / observability tools.
        server.addTool(new GetStatusTool(control));
        server.addTool(new SimpleReadTool("get_traffic",
                "Get live upload/download speeds and session totals.",
                control::getTraffic));
        server.addTool(new SimpleReadTool("list_servers",
                "List all configured servers.", control::listServers));
        server.addTool(new GetLogsTool(control));
        server.addTool(new SimpleReadTool("list_subscriptions",
                "List all configured subscriptions.", control::listSubscriptions));
        server.addTool(new SimpleReadTool("get_routing",
                "Get the current routing configuration (preset, rules, bypass list).",
                control::getRouting));
        server.addTool(new SimpleReadTool("get_settings",
                "Get application settings (no secrets).", control::getSettings));

        // Action tools (mutating; hidden/blocked when mutations are off).
        server.addTool(new ConnectTool(control));
        server.addTool(new DisconnectTool(control));
        server.addTool(new SelectServerTool(control));
        server.addTool(new MeasureLatencyTool(control));
        server.addTool(new RefreshSubscriptionTool(control));

        // Audit every mutating call to logs/mcp-audit.log.
        server.setAuditLog(new FileAuditLog(configStore.getDataDir()));

        // Browsable resources mirroring the read tools.
        server.addResource(new JsonResource("vless://status",
                "Connection status", "Current connection state and active server.",
                control::getStatus));
        server.addResource(new JsonResource("vless://traffic",
                "Traffic", "Live traffic speeds and session totals.", control::getTraffic));
        server.addResource(new JsonResource("vless://servers",
                "Servers", "All configured servers.", control::listServers));
        server.addResource(new JsonResource("vless://routing",
                "Routing", "Current routing configuration.", control::getRouting));
        server.addResource(new JsonResource("vless://settings",
                "Settings", "Application settings (no secrets).", control::getSettings));
        server.addResource(new JsonResource("vless://logs/recent",
                "Recent logs", "The most recent sing-box log lines.",
                () -> control.getLogs(200, null)));

        return server;
    }
}
