package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box WireGuard endpoint. Unlike the other protocols,
 * WireGuard is not an outbound: sing-box 1.13 removed the legacy
 * {@code wireguard} outbound (deprecated since 1.11) in favor of a top-level
 * {@code endpoints} entry, so this builder produces that entry instead.
 */
public final class WireguardEndpointBuilder {

    private static final Logger log = LoggerFactory.getLogger(WireguardEndpointBuilder.class);

    private final ObjectMapper mapper;

    public WireguardEndpointBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Builds a WireGuard endpoint (sing-box 1.11+ schema; the legacy
     * {@code wireguard} outbound was removed in 1.13). Field sources keep the
     * ServerConfig mapping the legacy outbound used: uuid → private_key,
     * encryption → peer public_key, flow → interface address,
     * tls.serverName → reserved bytes.
     *
     * @param server the server to build the endpoint from
     * @return the endpoint as a JSON object node
     */
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("type", "wireguard");
        endpoint.put("tag", tag);

        if (server.getFlow() != null && !server.getFlow().isBlank()) {
            ArrayNode address = mapper.createArrayNode();
            address.add(server.getFlow().trim());
            endpoint.set("address", address);
        }

        endpoint.put("private_key", server.getUuid());

        ObjectNode peer = mapper.createObjectNode();
        peer.put("address", server.getAddress());
        peer.put("port", server.getPort());

        if (server.getEncryption() != null && !server.getEncryption().isEmpty()
                && !"none".equals(server.getEncryption())) {
            peer.put("public_key", server.getEncryption());
        }

        // The legacy single-peer outbound implicitly routed everything through
        // the peer; the endpoint schema makes allowed_ips explicit.
        ArrayNode allowedIps = mapper.createArrayNode();
        allowedIps.add("0.0.0.0/0");
        allowedIps.add("::/0");
        peer.set("allowed_ips", allowedIps);

        ArrayNode reserved = parseReservedBytes(server);
        if (reserved != null) {
            peer.set("reserved", reserved);
        }

        ArrayNode peers = mapper.createArrayNode();
        peers.add(peer);
        endpoint.set("peers", peers);

        return endpoint;
    }

    /**
     * Parses the comma-separated reserved-bytes list (stored in
     * tls.serverName). sing-box fatally rejects a reserved array that is not
     * exactly 3 values, and JSON-decodes each value as uint8 — so anything
     * other than exactly three ints in 0..255 must be dropped entirely,
     * or the config won't start at all.
     */
    private ArrayNode parseReservedBytes(ServerConfig server) {
        if (server.getTls() == null || server.getTls().getServerName() == null
                || server.getTls().getServerName().isBlank()) {
            return null;
        }
        ArrayNode reserved = mapper.createArrayNode();
        for (String part : server.getTls().getServerName().split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value < 0 || value > 255) {
                    log.warn("Skipping out-of-range reserved byte value '{}' "
                            + "for WireGuard server {}", trimmed, server.getAddress());
                    continue;
                }
                reserved.add(value);
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid reserved byte value '{}' for WireGuard server {}",
                        trimmed, server.getAddress());
            }
        }
        if (reserved.size() == 0) {
            return null;
        }
        if (reserved.size() != 3) {
            log.warn("Ignoring WireGuard reserved bytes for server {}: "
                    + "need exactly 3 values, got {}", server.getAddress(), reserved.size());
            return null;
        }
        return reserved;
    }
}
