package com.vlessclient.service.mcp;

import com.vlessclient.app.I18n;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.RoutingRule;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.FxExecutor;
import com.vlessclient.service.ProxyGroupMonitor;
import com.vlessclient.service.Redact;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.TunnelHealthState;
import com.vlessclient.service.outbound.OutboundTags;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * Production {@link AppControlService} backed by the real application services.
 *
 * <p>Dependencies are injected rather than pulled from the global
 * {@code ServiceLocator} so the class stays unit-testable and so a freshly
 * downloaded {@code SingBoxEngine} can be swapped in without rebuilding the MCP
 * layer (see {@link #setEngine}).</p>
 *
 * <p>Reads that touch JavaFX observable state (connection state, log buffer,
 * traffic counters, server list) are marshalled onto the FX thread via
 * {@link FxExecutor} to avoid torn reads and concurrent modification.</p>
 */
public class DefaultAppControlService implements AppControlService {

    private static final long LATENCY_TIMEOUT_SECONDS = 15;

    private final ConfigStore configStore;
    private final TrafficMonitor trafficMonitor;
    private final SubscriptionService subscriptionService;
    private final RoutingService routingService;
    private final ConnectionService connectionService;
    private final com.vlessclient.service.LatencyTester latencyTester;
    private final ShareLinkParser shareLinkParser;
    private volatile SingBoxEngine engine;
    private final TunnelHealthState healthState;
    private final ProxyGroupMonitor groupMonitor;

    /**
     * Creates the service wired to the concrete application services.
     *
     * @param configStore the configuration store
     * @param trafficMonitor the live traffic monitor
     * @param subscriptionService the subscription service
     * @param routingService the routing service
     * @param connectionService the owner of the connect/disconnect flow
     * @param latencyTester the latency tester
     * @param shareLinkParser the share-link parser
     * @param engine the sing-box engine, or {@code null} if not yet available
     */
    public DefaultAppControlService(ConfigStore configStore,
                                    TrafficMonitor trafficMonitor,
                                    SubscriptionService subscriptionService,
                                    RoutingService routingService,
                                    ConnectionService connectionService,
                                    com.vlessclient.service.LatencyTester latencyTester,
                                    ShareLinkParser shareLinkParser,
                                    SingBoxEngine engine) {
        this(configStore, trafficMonitor, subscriptionService, routingService,
                connectionService, latencyTester, shareLinkParser, engine, null, null);
    }

    /** Creates the facade with the shared health verdict and the core's reported server pick. */
    public DefaultAppControlService(ConfigStore configStore,
                                    TrafficMonitor trafficMonitor,
                                    SubscriptionService subscriptionService,
                                    RoutingService routingService,
                                    ConnectionService connectionService,
                                    com.vlessclient.service.LatencyTester latencyTester,
                                    ShareLinkParser shareLinkParser,
                                    SingBoxEngine engine,
                                    TunnelHealthState healthState,
                                    ProxyGroupMonitor groupMonitor) {
        this.healthState = healthState;
        this.groupMonitor = groupMonitor;
        this.configStore = configStore;
        this.trafficMonitor = trafficMonitor;
        this.subscriptionService = subscriptionService;
        this.routingService = routingService;
        this.connectionService = connectionService;
        this.latencyTester = latencyTester;
        this.shareLinkParser = shareLinkParser;
        this.engine = engine;
    }

    /**
     * Replaces the engine reference — used when the sing-box binary is
     * downloaded after startup and a new engine instance is registered.
     */
    public void setEngine(SingBoxEngine engine) {
        this.engine = engine;
    }

    @Override
    public String retrySaving() throws McpToolException {
        configStore.getPersistenceState().retry();
        ensureSaved();
        return I18n.get("persistence.saved");
    }

    private void ensureSaved() throws McpToolException {
        List<String> failed = toolFiles(configStore.getPersistenceState().failedFiles());
        if (!failed.isEmpty()) {
            throw new McpToolException(
                    I18n.get("persistence.mcp.failed", String.join(", ", failed)));
        }
        // A file that could not be opened at startup is never saved over, so
        // a change to it lives only as long as this run: say so, as for a
        // failed save, rather than report it done.
        List<String> held = toolFiles(configStore.getPersistenceState().heldReasons().keySet());
        if (!held.isEmpty()) {
            throw new McpToolException(
                    I18n.get("persistence.mcp.held", String.join(", ", held)));
        }
    }

    /**
     * The files a tool's change can be in: all but the traffic history, which
     * no tool writes. Its failures are the window's to show; blaming them on
     * an added server told the agent a change that was saved had not been.
     */
    private static List<String> toolFiles(java.util.Collection<String> files) {
        return files.stream()
                .filter(file -> !TrafficHistoryStore.HISTORY_FILE.equals(file))
                .toList();
    }

    @Override
    public StatusInfo getStatus() {
        // One FX snapshot: the core state, verdict and routed member must not
        // come from different moments of a reconnect.
        return FxExecutor.get(() -> {
            AppSettings settings = configStore.getSettings();
            SingBoxEngine current = engine;
            ConnectionState state = current != null
                    ? current.connectionStateProperty().get() : ConnectionState.DISCONNECTED;
            // The core's own line: it says why exactly, and in the same words
            // on every machine; the window's sentence is in the UI's language.
            String error = current != null ? current.errorDetailProperty().get() : "";
            if ((error == null || error.isBlank()) && current != null) {
                error = current.errorMessageProperty().get();
            }
            ServerConfig active = configStore.getServers().stream()
                    .filter(ServerConfig::isActive).findFirst().orElse(null);
            TunnelHealth health = state == ConnectionState.CONNECTED && healthState != null
                    ? healthState.get() : TunnelHealth.UNMONITORED;
            String tag = state == ConnectionState.CONNECTED && groupMonitor != null
                    ? groupMonitor.currentMemberTagProperty().get() : null;
            ServerConfig routed = tag == null ? null : configStore.getServers().stream()
                    .filter(server -> tag.equals(OutboundTags.server(server)))
                    .findFirst().orElse(null);
            // The settings' mode is what the next start uses; a mode changed
            // there does not change the running core's, and get_status said
            // the new one as if it were running.
            String runningMode = connectionService == null ? null
                    : connectionService.runningMode().map(ProxyMode::getValue).orElse(null);
            return new StatusInfo(
                    state.name(), state == ConnectionState.CONNECTED,
                    active != null ? active.getId() : null,
                    active != null ? active.getName() : null,
                    settings.getProxyMode().getValue(), settings.listenSocksPort(),
                    settings.listenHttpPort(), settings.listenClashApiPort(),
                    error != null ? error : "", health.name(),
                    TunnelStatus.of(state, health).name(),
                    routed != null ? routed.getId() : null,
                    routed != null ? routed.getName() : null,
                    runningMode);
        });
    }

    @Override
    public TrafficInfo getTraffic() {
        long up = FxExecutor.get(() -> trafficMonitor.uploadSpeedProperty().get());
        long down = FxExecutor.get(() -> trafficMonitor.downloadSpeedProperty().get());
        long totalUp = FxExecutor.get(() -> trafficMonitor.totalUploadProperty().get());
        long totalDown = FxExecutor.get(() -> trafficMonitor.totalDownloadProperty().get());
        return new TrafficInfo(
                up, down, totalUp, totalDown,
                TrafficMonitor.formatSpeed(up),
                TrafficMonitor.formatSpeed(down),
                TrafficMonitor.formatBytes(totalUp),
                TrafficMonitor.formatBytes(totalDown));
    }

    @Override
    public List<ServerSummary> listServers() {
        return FxExecutor.get(() -> configStore.getServers().stream()
                .map(s -> new ServerSummary(
                        s.getId(), s.getName(),
                        s.getProtocol() != null ? s.getProtocol().getValue() : null,
                        s.getAddress(), s.getPort(), s.isActive()))
                .toList());
    }

    @Override
    public List<String> getLogs(int limit, String filter) {
        SingBoxEngine current = engine;
        if (current == null) {
            return List.of();
        }
        List<String> snapshot = FxExecutor.get(() -> new ArrayList<>(current.getLogLines()));
        if (filter != null && !filter.isBlank()) {
            String needle = filter.toLowerCase();
            snapshot = snapshot.stream()
                    .filter(line -> line.toLowerCase().contains(needle))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        int effectiveLimit = limit > 0 ? limit : snapshot.size();
        if (snapshot.size() > effectiveLimit) {
            snapshot = new ArrayList<>(
                    snapshot.subList(snapshot.size() - effectiveLimit, snapshot.size()));
        }
        // Same treatment as the diagnostics bundle and the SSE stream: a line
        // can quote a subscription URL, and the token in it is not something
        // every holder of the MCP token should get.
        snapshot.replaceAll(Redact::urlsIn);
        return snapshot;
    }

    @Override
    public List<SubscriptionSummary> listSubscriptions() {
        return FxExecutor.get(() -> subscriptionService.getSubscriptions().stream()
                .map(this::toSummary)
                .toList());
    }

    private SubscriptionSummary toSummary(Subscription sub) {
        int count = sub.getServerIds() != null ? sub.getServerIds().size() : 0;
        // Scheme and host only, as the Subscriptions tab shows it: the path or
        // query holds the account token, which get_logs and the event stream
        // already keep from agents, and this read tool works with changes off.
        return new SubscriptionSummary(sub.getId(), sub.getName(), Redact.url(sub.getUrl()),
                count, sub.getLastRefreshedAt());
    }

    @Override
    public RoutingInfo getRouting() {
        RoutingConfig config = routingService.getConfig();
        List<RoutingInfo.RuleInfo> rules = new ArrayList<>();
        if (config.getRules() != null) {
            config.getRules().forEach(r -> rules.add(new RoutingInfo.RuleInfo(
                    r.getId(),
                    r.getType() != null ? r.getType().getValue() : null,
                    r.getValue(),
                    r.getAction() != null ? r.getAction().getValue() : null)));
        }
        List<String> bypass = config.getBypassList() != null
                ? new ArrayList<>(config.getBypassList()) : List.of();
        return new RoutingInfo(config.getLegacyPreset(), bypass, rules);
    }

    @Override
    public SettingsInfo getSettings() {
        AppSettings s = configStore.getSettings();
        return new SettingsInfo(
                s.getTheme(), s.getLanguage(), s.isAutoConnect(),
                s.getProxyMode().getValue(),
                s.getSocksPort(), s.getHttpPort(), s.getClashApiPort(),
                s.getProxyDns(), s.getDirectDns(), s.getDnsStrategy(),
                s.getTunInterfaceName(), s.getCoreLogLevel().getValue(),
                s.isHealthCheckEnabled(),
                s.isMcpEnabled(), s.getMcpPort(), s.isMcpAllowMutations(),
                s.getServerSelection().getValue(), s.isSystemProxyAutoConfig(),
                s.getTunIpv4Address(), s.isTunIpv6Enabled());
    }

    @Override
    public StatusInfo connect(String serverId, String mode, boolean confirm)
            throws McpToolException {
        SingBoxEngine current = engine;
        if (current == null) {
            throw new McpToolException("sing-box binary is not available; cannot connect.");
        }

        // Resolve and gate the proxy mode before doing any work: TUN triggers a
        // macOS admin password prompt and captures all traffic, so it needs an
        // explicit confirm.
        AppSettings settings = configStore.getSettings();
        ProxyMode effectiveMode = mode != null && !mode.isBlank()
                ? parseMode(mode) : settings.getProxyMode();
        if (effectiveMode == ProxyMode.TUN && !confirm) {
            throw new McpToolException("Connecting in TUN mode shows a macOS admin password "
                    + "prompt and routes all traffic. Pass confirm:true to proceed.");
        }

        if (serverId != null && !serverId.isBlank()) {
            selectServer(serverId);
        }

        if (mode != null && !mode.isBlank() && effectiveMode != settings.getProxyMode()) {
            // On the FX thread, as set_proxy_mode does: this runs on an HTTP
            // worker, and the dashboard reads the same instance from FX.
            FxExecutor.run(() -> {
                AppSettings live = configStore.getSettings();
                live.setProxyMode(effectiveMode);
                configStore.saveSettings(live);
            });
        }

        // The connect flow itself belongs to ConnectionService — including
        // passing every candidate to the generator, which is what keeps
        // automatic selection working on this path.
        ensureSaved();
        ConnectionService.ConnectAttempt attempt;
        try {
            attempt = connectionService.connect(effectiveMode);
        } catch (IOException e) {
            throw new McpToolException("Failed to start sing-box: " + e.getMessage(), e);
        }
        if (!attempt.started()) {
            // Anything but a start is an error for a tool call — including a
            // future outcome this facade has not been taught to report, which
            // must not read as a silent "connected".
            String reason = switch (attempt.outcome()) {
                case NO_ENGINE -> "sing-box binary is not available; cannot connect.";
                case NO_ACTIVE_SERVER ->
                        "No active server. Pass serverId or select one with select_server.";
                case ALREADY_RUNNING -> "sing-box is already running.";
                case CANCELLED -> "The connect was cancelled: the user dismissed the "
                        + "administrator prompt, or a newer request superseded it.";
                default -> "Unexpected connect outcome: " + attempt.outcome();
            };
            throw new McpToolException(reason);
        }
        return getStatus();
    }

    @Override
    public StatusInfo disconnect() {
        connectionService.disconnect();
        return getStatus();
    }

    @Override
    public ServerSummary selectServer(String serverId) throws McpToolException {
        ServerConfig s = configStore.getServerById(serverId)
                .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
        configStore.setActiveServer(serverId);
        ensureSaved();
        return new ServerSummary(s.getId(), s.getName(),
                s.getProtocol() != null ? s.getProtocol().getValue() : null,
                s.getAddress(), s.getPort(), true);
    }

    @Override
    public List<LatencyResult> measureLatency(String serverId) throws McpToolException {
        List<ServerConfig> servers;
        if (serverId != null && !serverId.isBlank()) {
            ServerConfig one = configStore.getServerById(serverId)
                    .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
            servers = List.of(one);
        } else {
            servers = new ArrayList<>(
                    FxExecutor.get(() -> new ArrayList<>(configStore.getServers())));
        }
        if (servers.isEmpty()) {
            throw new McpToolException("No servers configured to test.");
        }
        try {
            Map<String, com.vlessclient.service.LatencyTester.Result> results =
                    latencyTester.testAll(servers).get(LATENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<LatencyResult> out = new ArrayList<>();
            for (ServerConfig s : servers) {
                com.vlessclient.service.LatencyTester.Result r = results.get(s.getId());
                if (r == null || !r.measured()) {
                    out.add(new LatencyResult(s.getId(), s.getName(), -1,
                            LatencyResult.NOT_MEASURED));
                } else if (r.reachable()) {
                    out.add(new LatencyResult(s.getId(), s.getName(), r.millis(),
                            LatencyResult.MEASURED));
                } else {
                    out.add(new LatencyResult(s.getId(), s.getName(), -1,
                            LatencyResult.UNREACHABLE));
                }
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Latency test interrupted.");
        } catch (Exception e) {
            throw new McpToolException("Latency test failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String refreshSubscription(String subscriptionId) throws McpToolException {
        Optional<Subscription> sub = subscriptionService.getSubscriptions().stream()
                .filter(s -> s.getId().equals(subscriptionId)).findFirst();
        if (sub.isEmpty()) {
            throw new McpToolException("No subscription with id: " + subscriptionId);
        }
        subscriptionService.refreshSubscription(subscriptionId);
        ensureSaved();
        // The refresh has run by now, and records its failure on the
        // subscription rather than throwing: the tool said "Refresh triggered"
        // for a dead URL or an expired token alike.
        Subscription refreshed = subscriptionService.getSubscriptions().stream()
                .filter(s -> s.getId().equals(subscriptionId)).findFirst().orElse(sub.get());
        if (refreshed.hasLastError()) {
            throw new McpToolException("Refreshing subscription '" + refreshed.getName()
                    + "' failed: " + SubscriptionService.failureText(refreshed));
        }
        return "Refreshed subscription '" + refreshed.getName() + "': "
                + refreshed.getServerIds().size() + " servers.";
    }

    @Override
    public ServerSummary addServer(String shareLink, String name) throws McpToolException {
        ServerConfig config = parseShareLink(shareLink);
        if (name != null && !name.isBlank()) {
            config.setName(name);
        }
        config.setActive(false);
        configStore.addServer(config);
        ensureSaved();
        return summaryOf(config);
    }

    @Override
    public ServerSummary updateServer(String id, String name, String shareLink)
            throws McpToolException {
        ServerConfig existing = configStore.getServerById(id)
                .orElseThrow(() -> new McpToolException("No server with id: " + id));
        ServerConfig updated;
        if (shareLink != null && !shareLink.isBlank()) {
            updated = parseShareLink(shareLink);
            updated.setId(existing.getId());
            updated.setActive(existing.isActive());
            updated.setName(name != null && !name.isBlank() ? name : existing.getName());
        } else {
            updated = existing;
            if (name != null && !name.isBlank()) {
                updated.setName(name);
            }
        }
        configStore.updateServer(updated);
        ensureSaved();
        return summaryOf(updated);
    }

    @Override
    public String deleteServer(String id, boolean confirm) throws McpToolException {
        if (!confirm) {
            throw new McpToolException("Deleting a server is irreversible; pass confirm:true.");
        }
        ServerConfig existing = configStore.getServerById(id)
                .orElseThrow(() -> new McpToolException("No server with id: " + id));
        configStore.removeServer(id);
        ensureSaved();
        return "Deleted server '" + existing.getName() + "'.";
    }

    @Override
    public SettingsInfo setProxyMode(String mode) throws McpToolException {
        ProxyMode parsed = parseMode(mode);
        // Settings are mutated on the FX thread only, like the Settings view
        // does: this runs on an HTTP worker, and the dashboard reads the same
        // instance from the FX thread with nothing in between.
        FxExecutor.run(() -> {
            AppSettings settings = configStore.getSettings();
            settings.setProxyMode(parsed);
            configStore.saveSettings(settings);
        });
        ensureSaved();
        return getSettings();
    }

    @Override
    public SettingsInfo setSetting(String key, JsonNode value) throws McpToolException {
        if (key == null || value == null) {
            throw new McpToolException("Both 'key' and 'value' are required.");
        }
        McpToolException[] rejected = new McpToolException[1];
        FxExecutor.run(() -> {
            AppSettings s = configStore.getSettings();
            try {
                McpSettings.apply(s, key, value);
            } catch (McpToolException e) {
                rejected[0] = e;
                return;
            }
            configStore.saveSettings(s);
        });
        if (rejected[0] != null) {
            throw rejected[0];
        }
        ensureSaved();
        return getSettings();
    }

    @Override
    public RoutingInfo addRoutingRule(String type, String value, String action)
            throws McpToolException {
        if (value == null || value.isBlank()) {
            throw new McpToolException("Rule 'value' is required.");
        }
        RoutingRule rule = new RoutingRule(parseRuleType(type), value.strip(),
                parseRuleAction(action));
        try {
            routingService.addRule(rule);
        } catch (IllegalArgumentException refused) {
            throw new McpToolException(refused.getMessage());
        }
        ensureSaved();
        return getRouting();
    }

    @Override
    public RoutingInfo removeRoutingRule(String ruleId) throws McpToolException {
        boolean present = routingService.getConfig().getRules().stream()
                .anyMatch(r -> r.getId().equals(ruleId));
        if (!present) {
            throw new McpToolException("No routing rule with id: " + ruleId);
        }
        routingService.removeRule(ruleId);
        ensureSaved();
        return getRouting();
    }

    private ServerConfig parseShareLink(String shareLink) throws McpToolException {
        if (shareLink == null || shareLink.isBlank()) {
            throw new McpToolException("'shareLink' is required.");
        }
        try {
            ServerConfig config = shareLinkParser.parseForImport(shareLink.trim());
            if (config == null) {
                throw new McpToolException("Could not parse share link.");
            }
            return config;
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Invalid share link: " + e.getMessage(), e);
        }
    }

    private ServerSummary summaryOf(ServerConfig s) {
        return new ServerSummary(s.getId(), s.getName(),
                s.getProtocol() != null ? s.getProtocol().getValue() : null,
                s.getAddress(), s.getPort(), s.isActive());
    }




    private RoutingRule.RuleType parseRuleType(String type) throws McpToolException {
        if (type == null) {
            throw new McpToolException("Rule 'type' is required.");
        }
        for (RoutingRule.RuleType t : RoutingRule.RuleType.values()) {
            if (t.getValue().equalsIgnoreCase(type) || t.name().equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new McpToolException("Unknown rule type: " + type);
    }

    private RoutingRule.RuleAction parseRuleAction(String action) throws McpToolException {
        if (action == null) {
            throw new McpToolException("Rule 'action' is required.");
        }
        for (RoutingRule.RuleAction a : RoutingRule.RuleAction.values()) {
            if (a.getValue().equalsIgnoreCase(action) || a.name().equalsIgnoreCase(action)) {
                return a;
            }
        }
        throw new McpToolException("Unknown rule action: " + action + " (proxy|direct|block).");
    }

    private ProxyMode parseMode(String mode) throws McpToolException {
        return switch (mode.toLowerCase()) {
            case "tun" -> ProxyMode.TUN;
            case "system", "system_proxy" -> ProxyMode.SYSTEM_PROXY;
            default -> throw new McpToolException(
                    "Unknown mode '" + mode + "'. Use 'system_proxy' or 'tun'.");
        };
    }

}
