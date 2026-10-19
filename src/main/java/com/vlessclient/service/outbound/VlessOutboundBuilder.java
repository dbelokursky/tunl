package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

        // Without the spaces a link or an edit can leave around it, as
        // CoreSettings checks it: written with them, a server stored as Vision
        // was refused by the core ("unsupported flow").
        String flow = server.getFlow() == null ? "" : server.getFlow().strip();
        if (!flow.isEmpty()) {
            outbound.put("flow", flow);
        }

        addTlsIfEnabled(outbound, server);
        addTransportIfNeeded(outbound, server.getTransport());

        return outbound;
    }
}
