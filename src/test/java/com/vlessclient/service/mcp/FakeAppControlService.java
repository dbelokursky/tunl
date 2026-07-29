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

    // ----- config mutation -----

    String lastAddedShareLink;
    String lastDeletedServerId;
    String lastSetSettingKey;
    RoutingInfo.RuleInfo lastAddedRule;

    @Override
    public ServerSummary addServer(String shareLink, String name) throws McpToolException {
        if (shareLink == null || shareLink.isBlank()) {
            throw new McpToolException("'shareLink' is required.");
        }
        lastAddedShareLink = shareLink;
        ServerSummary added = new ServerSummary("srv-new",
                name != null ? name : "Imported", "vless", "host", 443, false);
        servers.add(added);
        return added;
    }

    @Override
    public ServerSummary updateServer(String id, String name, String shareLink)
            throws McpToolException {
        ServerSummary existing = servers.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new McpToolException("No server with id: " + id));
        return new ServerSummary(existing.id(),
                name != null ? name : existing.name(), existing.protocol(),
                existing.address(), existing.port(), existing.active());
    }

    @Override
    public String deleteServer(String id, boolean confirm) throws McpToolException {
        if (!confirm) {
            throw new McpToolException("Deleting a server is irreversible; pass confirm:true.");
        }
        if (servers.stream().noneMatch(s -> s.id().equals(id))) {
            throw new McpToolException("No server with id: " + id);
        }
        lastDeletedServerId = id;
        servers.removeIf(s -> s.id().equals(id));
        return "Deleted.";
    }

    @Override
    public SettingsInfo setProxyMode(String mode) throws McpToolException {
        if (!"tun".equals(mode) && !"system_proxy".equals(mode)) {
            throw new McpToolException("Unknown mode.");
        }
        return settings;
    }

    @Override
    public SettingsInfo setSetting(String key, com.fasterxml.jackson.databind.JsonNode value)
            throws McpToolException {
        if (key == null || value == null) {
            throw new McpToolException("Both 'key' and 'value' are required.");
        }
        lastSetSettingKey = key;
        return settings;
    }

    @Override
    public RoutingInfo addRoutingRule(String type, String value, String action)
            throws McpToolException {
        if (value == null || value.isBlank()) {
            throw new McpToolException("Rule 'value' is required.");
        }
        lastAddedRule = new RoutingInfo.RuleInfo("r-new", type, value, action);
        return routing;
    }

    @Override
    public RoutingInfo removeRoutingRule(String ruleId) throws McpToolException {
        if (routing.rules().stream().noneMatch(r -> r.id().equals(ruleId))) {
            throw new McpToolException("No routing rule with id: " + ruleId);
        }
        return routing;
    }

    @Override
    public RoutingInfo setRoutingPreset(String preset) throws McpToolException {
        if (preset == null || preset.isBlank()) {
            throw new McpToolException("'preset' is required.");
        }
        return routing;
    }
}
