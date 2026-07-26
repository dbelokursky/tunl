package com.vlessclient.service.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.ServerConfig;

/**
 * Builds the sing-box Trojan proxy outbound: password auth (stored in the
 * ServerConfig uuid field), plus the shared TLS and transport blocks.
 */
public final class TrojanOutboundBuilder extends OutboundBuilder {

    public TrojanOutboundBuilder(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "trojan");
        outbound.put("tag", tag);
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("password", server.getUuid());

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }
}
