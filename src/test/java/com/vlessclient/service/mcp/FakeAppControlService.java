package com.vlessclient.service.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory {@link AppControlService} for tests — no JavaFX, no
 * sing-box, no network. Fields are public so individual tests can tweak the
 * returned values.
 */
class FakeAppControlService implements AppControlService {

    StatusInfo status =
            new StatusInfo("CONNECTED", true, "srv-1", "Tokyo", "tun", 1080, 1081, 9090, "");
    TrafficInfo traffic =
            new TrafficInfo(2048, 4096, 1_000_000, 2_000_000, "2 KB/s", "4 KB/s", "1 MB", "2 MB");
    List<ServerSummary> servers = new ArrayList<>(List.of(
            new ServerSummary("srv-1", "Tokyo", "vless", "jp.example.com", 443, true),
            new ServerSummary("srv-2", "Berlin", "trojan", "de.example.com", 8443, false)));
    List<String> logs = new ArrayList<>(List.of(
            "line one INFO started", "line two WARN retry", "line three INFO ok"));
    List<SubscriptionSummary> subscriptions = new ArrayList<>(List.of(
            new SubscriptionSummary("sub-1", "Main", "https://example.com/sub", 5, 1700000000000L)));
    RoutingInfo routing = new RoutingInfo("route_all", List.of("*.local"),
            List.of(new RoutingInfo.RuleInfo("r1", "domain", "example.com", "proxy")));
    SettingsInfo settings = new SettingsInfo("system", "en", false, "tun",
            1080, 1081, 9090, "https://1.1.1.1/dns-query", "https://223.5.5.5/dns-query",
            "prefer_ipv4", "utun99", true, true, 55555, true);

    @Override
    public StatusInfo getStatus() {
        return status;
    }

    @Override
    public TrafficInfo getTraffic() {
        return traffic;
    }

    @Override
    public List<ServerSummary> listServers() {
        return servers;
    }

    @Override
    public List<String> getLogs(int limit, String filter) {
        List<String> result = new ArrayList<>();
        for (String line : logs) {
            if (filter == null || filter.isBlank()
                    || line.toLowerCase().contains(filter.toLowerCase())) {
                result.add(line);
            }
        }
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    @Override
    public List<SubscriptionSummary> listSubscriptions() {
        return subscriptions;
    }

    @Override
    public RoutingInfo getRouting() {
        return routing;
    }

    @Override
    public SettingsInfo getSettings() {
        return settings;
    }

    // ----- actions: record calls so tests can assert behavior -----

    String lastConnectServerId;
    String lastConnectMode;
    boolean lastConnectConfirm;
    boolean disconnectCalled;
    String lastSelectedServerId;
    String lastLatencyServerId;
    String lastRefreshedSubscriptionId;
    McpToolException connectError;

    @Override
    public StatusInfo connect(String serverId, String mode, boolean confirm) throws McpToolException {
        if (connectError != null) {
            throw connectError;
        }
        lastConnectServerId = serverId;
        lastConnectMode = mode;
        lastConnectConfirm = confirm;
        status = new StatusInfo("CONNECTED", true,
                serverId != null ? serverId : "srv-1", "Tokyo",
                mode != null ? mode : "system_proxy", 1080, 1081, 9090, "");
        return status;
    }

    @Override
    public StatusInfo disconnect() {
        disconnectCalled = true;
        status = new StatusInfo("DISCONNECTED", false, null, null,
                "system_proxy", 1080, 1081, 9090, "");
        return status;
    }

    @Override
    public ServerSummary selectServer(String serverId) throws McpToolException {
        lastSelectedServerId = serverId;
        return servers.stream().filter(s -> s.id().equals(serverId)).findFirst()
                .orElseThrow(() -> new McpToolException("No server with id: " + serverId));
    }

    @Override
    public List<LatencyResult> measureLatency(String serverId) {
        lastLatencyServerId = serverId;
        return List.of(new LatencyResult("srv-1", "Tokyo", 42));
    }

    @Override
    public String refreshSubscription(String subscriptionId) throws McpToolException {
        if (subscriptions.stream().noneMatch(s -> s.id().equals(subscriptionId))) {
            throw new McpToolException("No subscription with id: " + subscriptionId);
        }
        lastRefreshedSubscriptionId = subscriptionId;
        return "Refresh triggered.";
    }
}
