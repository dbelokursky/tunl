package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Matches profiles without collapsing different users or transports on one endpoint. */
final class SubscriptionReconciler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SubscriptionReconciler() {
    }

    record Batch(List<ServerConfig> upserts, List<String> removals, List<String> serverIds) {
    }

    private record Endpoint(String address, int port, Protocol protocol) {
    }

    private record NamedEndpoint(Endpoint endpoint, String name) {
    }

    static Batch reconcile(List<ServerConfig> existing, List<ServerConfig> fetched,
                           boolean allowRemovals) {
        // Identity contains every connection field, including credentials, but
        // not local state or display names. JsonNode equality also ignores map
        // ordering (for example HTTP headers). Never log these keys: they contain secrets.
        Map<JsonNode, ServerConfig> distinct = new LinkedHashMap<>();
        for (ServerConfig server : fetched) {
            distinct.putIfAbsent(identity(server), server);
        }
        List<ServerConfig> upserts = new ArrayList<>(distinct.values());
        List<ServerConfig> unmatchedOld = new ArrayList<>(existing);
        List<ServerConfig> unmatchedNew = new ArrayList<>(upserts);

        matchUnique(unmatchedOld, unmatchedNew, SubscriptionReconciler::identity);
        // Credential rotation can change the exact identity. A unique provider
        // label on the same endpoint is the next strongest correspondence.
        matchUnique(unmatchedOld, unmatchedNew, server ->
                server.getName() == null || server.getName().isBlank() ? null
                        : new NamedEndpoint(endpoint(server), server.getName()));
        matchUnique(unmatchedOld, unmatchedNew, SubscriptionReconciler::withoutCredentials);
        // Preserve the legacy single-profile rename/reconfiguration behavior,
        // but never choose arbitrarily among several variants on one endpoint.
        matchUnique(unmatchedOld, unmatchedNew, SubscriptionReconciler::endpoint);

        List<String> ids = new ArrayList<>(upserts.stream().map(ServerConfig::getId).toList());
        List<String> removals = new ArrayList<>();
        for (ServerConfig old : unmatchedOld) {
            if (allowRemovals) {
                removals.add(old.getId());
            } else {
                ids.add(old.getId());
            }
        }
        return new Batch(upserts, removals, ids);
    }

    private static <K> void matchUnique(List<ServerConfig> oldServers,
                                       List<ServerConfig> newServers,
                                       Function<ServerConfig, K> key) {
        Map<K, List<ServerConfig>> oldGroups = group(oldServers, key);
        Map<K, List<ServerConfig>> newGroups = group(newServers, key);
        for (Map.Entry<K, List<ServerConfig>> entry : newGroups.entrySet()) {
            List<ServerConfig> old = oldGroups.get(entry.getKey());
            if (old == null || old.size() != 1 || entry.getValue().size() != 1) {
                continue;
            }
            ServerConfig incoming = entry.getValue().getFirst();
            ServerConfig previous = old.getFirst();
            incoming.setId(previous.getId());
            incoming.setActive(previous.isActive());
            oldServers.remove(previous);
            newServers.remove(incoming);
        }
    }

    private static <K> Map<K, List<ServerConfig>> group(List<ServerConfig> servers,
                                                       Function<ServerConfig, K> key) {
        Map<K, List<ServerConfig>> groups = new LinkedHashMap<>();
        for (ServerConfig server : servers) {
            K value = key.apply(server);
            if (value != null) {
                groups.computeIfAbsent(value, ignored -> new ArrayList<>()).add(server);
            }
        }
        return groups;
    }

    private static ObjectNode identity(ServerConfig server) {
        ObjectNode node = MAPPER.valueToTree(server);
        node.remove(List.of("id", "name", "active"));
        return node;
    }

    private static JsonNode withoutCredentials(ServerConfig server) {
        ObjectNode node = identity(server);
        node.remove("uuid");
        if (server.getProtocol() == Protocol.HYSTERIA2) {
            node.remove("flow");
        }
        return node;
    }

    private static Endpoint endpoint(ServerConfig server) {
        return new Endpoint(server.getAddress(), server.getPort(), server.getProtocol());
    }
}
