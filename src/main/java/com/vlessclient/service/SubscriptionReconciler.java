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

    /**
     * Matches what a refresh fetched against what the subscription has.
     *
     * @param existing      the subscription's stored servers
     * @param strays        stored servers of a subscription that no longer
     *                      exists, which a fetched server may take back but
     *                      which are never removed
     * @param fetched       the servers the provider sent
     * @param allowRemovals whether a server of {@code existing} the provider
     *                      no longer sends is removed
     * @return the servers to store, the ids to remove, and the subscription's
     *     ids after it
     */
    static Batch reconcile(List<ServerConfig> existing, List<ServerConfig> strays,
                           List<ServerConfig> fetched, boolean allowRemovals) {
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

        matchAll(unmatchedOld, unmatchedNew);
        // Then, for what the subscription's own servers did not account for,
        // a server a deleted subscription left behind: a restored backup
        // keeps each server's subscription stamp but not the subscription,
        // and the subscription added again matched none of them, so every
        // server was added a second time and the restored copies belonged to
        // nothing. After its own, so a stray cannot take a match from them.
        matchAll(new ArrayList<>(strays), unmatchedNew);

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

    private static void matchAll(List<ServerConfig> unmatchedOld,
                                 List<ServerConfig> unmatchedNew) {
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
            // The id only: whether it is picked is the store's to say when the
            // batch lands (ConfigStore.applyServerBatch), not what it said
            // when the members were read, before the fetch.
            incoming.setId(previous.getId());
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
        // The subscription stamp is local state too: a stored server carries
        // it and a fetched one does not yet.
        node.remove(List.of("id", "name", "active", "subscription_id"));
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
