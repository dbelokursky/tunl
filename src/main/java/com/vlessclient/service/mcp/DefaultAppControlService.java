package com.vlessclient.service.mcp;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficMonitor;

import java.util.ArrayList;
import java.util.List;

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

    private final ConfigStore configStore;
    private final TrafficMonitor trafficMonitor;
    private final SubscriptionService subscriptionService;
    private final RoutingService routingService;
    private volatile SingBoxEngine engine;

    public DefaultAppControlService(ConfigStore configStore,
                                    TrafficMonitor trafficMonitor,
                                    SubscriptionService subscriptionService,
                                    RoutingService routingService,
                                    SingBoxEngine engine) {
        this.configStore = configStore;
        this.trafficMonitor = trafficMonitor;
        this.subscriptionService = subscriptionService;
        this.routingService = routingService;
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
    public StatusInfo getStatus() {
        AppSettings settings = configStore.getSettings();
        SingBoxEngine current = engine;

        ConnectionState state = FxExecutor.get(() -> current != null
                ? current.connectionStateProperty().get()
                : ConnectionState.DISCONNECTED);
        String error = FxExecutor.get(() ->
                current != null ? current.errorMessageProperty().get() : "");

        ServerConfig active = FxExecutor.get(() -> configStore.getServers().stream()
                .filter(ServerConfig::isActive)
                .findFirst()
                .orElse(null));

        return new StatusInfo(
                state.name(),
                state == ConnectionState.CONNECTED,
                active != null ? active.getId() : null,
                active != null ? active.getName() : null,
                settings.getProxyMode().getValue(),
                settings.getSocksPort(),
                settings.getHttpPort(),
                settings.getClashApiPort(),
                error != null ? error : "");
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
            return new ArrayList<>(snapshot.subList(snapshot.size() - effectiveLimit, snapshot.size()));
        }
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
        return new SubscriptionSummary(sub.getId(), sub.getName(), sub.getUrl(),
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
        return new RoutingInfo(config.getPreset(), bypass, rules);
    }

    @Override
    public SettingsInfo getSettings() {
        AppSettings s = configStore.getSettings();
        return new SettingsInfo(
                s.getTheme(), s.getLanguage(), s.isAutoConnect(),
                s.getProxyMode().getValue(),
                s.getSocksPort(), s.getHttpPort(), s.getClashApiPort(),
                s.getProxyDns(), s.getDirectDns(), s.getDnsStrategy(),
                s.getTunInterfaceName(), s.isHealthCheckEnabled(),
                s.isMcpEnabled(), s.getMcpPort(), s.isMcpAllowMutations());
    }
}
