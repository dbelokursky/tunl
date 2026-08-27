package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box VMess proxy outbound: UUID auth with {@code alter_id} 0
 * and {@code auto} security, plus the shared TLS and transport blocks.
 */
public final class VmessOutboundBuilder extends OutboundBuilder {

    public VmessOutboundBuilder(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "vmess");
        outbound.put("tag", tag);
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("uuid", server.getUuid());
        outbound.put("alter_id", 0);
        outbound.put("security", "auto");

        addTlsIfEnabled(outbound, server.getTls());
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }
}
