package com.vlessclient.service.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.ServerConfig;

/**
 * Builds the sing-box VLESS proxy outbound: UUID auth, optional flow control
 * (e.g. {@code xtls-rprx-vision}), plus the shared TLS/Reality and transport
 * blocks.
 */
public final class VlessOutboundBuilder extends OutboundBuilder {

    public VlessOutboundBuilder(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "vless");
        outbound.put("tag", tag);
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("uuid", server.getUuid());

        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            outbound.put("flow", server.getFlow());
        }

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }
}
