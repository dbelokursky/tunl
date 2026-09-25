package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box Hysteria2 proxy outbound: password auth, port hopping
 * ({@code server_ports}), optional salamander or gecko obfuscation (obfs
 * password stored in the ServerConfig flow field, its type in encryption),
 * plus the shared TLS block. Hysteria2 runs over QUIC, so there is no
 * transport block.
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
        String hopping = server.getServerPorts();
        if (hopping != null && !hopping.isBlank()) {
            // As stored when malformed: the core refuses it as CoreSettings
            // does, rather than connecting somewhere the server never named.
            ArrayNode ports = outbound.putArray("server_ports");
            (HysteriaPorts.isValid(hopping) ? HysteriaPorts.forCore(hopping)
                    : List.of(hopping.split(","))).forEach(ports::add);
        }
        outbound.put("password", server.getUuid());

        // A password with no type, or with encryption's default "none", is
        // salamander, as it always was.
        String obfsType = server.getEncryption() == null ? ""
                : server.getEncryption().strip().toLowerCase(Locale.ROOT);
        if (obfsType.isEmpty() || "none".equals(obfsType)) {
            obfsType = "salamander";
        }
        if (server.getFlow() != null && !server.getFlow().isEmpty()) {
            ObjectNode obfs = mapper.createObjectNode();
            // The link's obfs type sits in encryption; gecko was written as
            // salamander, and that server never connected.
            obfs.put("type", obfsType);
            obfs.put("password", server.getFlow());
            outbound.set("obfs", obfs);
        }

        addTlsIfEnabled(outbound, server);

        return outbound;
    }
}
