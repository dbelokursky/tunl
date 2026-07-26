package com.vlessclient.service.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.ServerConfig;

/**
 * Builds the sing-box Hysteria2 proxy outbound: password auth, optional
 * salamander obfuscation (obfs password stored in the ServerConfig flow
 * field), plus the shared TLS block. Hysteria2 runs over QUIC, so there is
 * no transport block.
 */
public final class Hysteria2OutboundBuilder extends OutboundBuilder {

    public Hysteria2OutboundBuilder(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "hysteria2");
        outbound.put("tag", tag);
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("password", server.getUuid());

        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            ObjectNode obfs = mapper.createObjectNode();
            obfs.put("type", "salamander");
            obfs.put("password", server.getFlow());
            outbound.set("obfs", obfs);
        }

        addTlsIfEnabled(outbound, server.getTls());

        return outbound;
    }
}
